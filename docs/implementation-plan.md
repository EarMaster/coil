# Coil — Remote for Phoniebox
## Implementation plan for the native Android client

`coilforphoniebox.app` · Application ID `app.coilforphoniebox`

---

## 1. Scope

A native Android app that acts as a remote control for Phoniebox v3 (`future3/main`) on the local network.

**In scope for the first release:**
- Player: title, cover, progress, play/pause/next/prev/seek, volume, shuffle/repeat
- Content selection: folder browser and album view
- System-wide media notification and lock screen controls that appear automatically when the box starts playing — **switchable off**
- Favourites, including launcher shortcuts for direct start
- **Multiple boxes** with per-box settings and a switcher
- Five languages at launch

**Explicitly out of scope:**
- Card management (RFID)
- Phoniebox system settings, timers, shutdown/reboot, auto-hotspot
- Streaming services, file upload
- Any compatibility layer for Phoniebox v2 or earlier

### Target version

Development targets **`future3/main`** and nothing else. There is no version negotiation and no fallback path. `core.version` is read on connect and included in bug reports, which costs nothing and makes incompatibilities diagnosable — but the app does not branch on it. If a future Phoniebox release breaks something, that is dealt with at the time.

---

## 2. Technology decisions

| Area | Choice | Rationale |
|---|---|---|
| Language / UI | Kotlin + Jetpack Compose | Material 3, fast iteration |
| Architecture | MVVM, unidirectional state flow | State arrives asynchronously over PubSub, which maps well onto `StateFlow` |
| Transport | **JeroMQ** (`org.zeromq:jeromq`) | Pure Java, no NDK. **Important:** JeroMQ has no `ws://` transport, so use TCP ports 5555/5558 rather than WebSocket ports 5556/5557 |
| Media | **androidx.media3** (`media3-session`) | `SimpleBasePlayer` is built precisely for remote playback (the Cast scenario) |
| JSON | kotlinx.serialization | |
| Images | Coil (the image loading library) | Cover art is fetched over HTTP from the cover cache |
| DI | Hilt | |
| Persistence | DataStore (settings) + Room (library cache, favourites, boxes) | |
| Typography | **Material 3 default** | No custom fonts — see section 10.4 |
| Min SDK | 26, target 35 | media3 plus foreground service rules |

> **Naming collision worth knowing about:** this app is called Coil and the image loading library is also called Coil. Alias the library import or qualify it in review comments to keep discussions unambiguous.

**Dependency constraint:** every library above is open source and free of Google Play Services. This is deliberate and must be preserved — see section 13.3.

---

## 3. Module layout

```
:app               UI (Compose), navigation, screens
:core-transport    ZMQ client, serialisation, reconnect
:core-domain       Models, repository interfaces, use cases
:core-data         Repository implementations, Room, DataStore
:feature-media     MediaSessionService, PhonieboxPlayer, notification
:feature-shortcuts Favourites to dynamic and pinned shortcuts
```

---

## 4. Transport layer (`:core-transport`)

The most critical component. Two separate sockets, each on its own thread or coroutine dispatcher — JeroMQ sockets are **not** thread-safe.

### 4.1 RPC (commands) — port 5555

The web UI uses `REQ`. The repository code carries an explicit comment that concurrent requests break it: the first response closes the channel. **Recommendation: use `DEALER` instead.** A DEALER allows several outstanding requests, and correlation happens through the `id` field anyway.

Outbound payload:
```json
{ "id": "<uuid>", "package": "player", "plugin": "ctrl",
  "method": "play_folder", "kwargs": { "folder": "Audiobooks/Bibi" } }
```
Inbound: `{"id": ..., "result": ...}` or `{"id": ..., "error": {...}}`

Implementation: an `RpcClient` exposing `suspend fun <T> call(cmd: Command): Result<T>`, backed internally by a `ConcurrentHashMap<String, CompletableDeferred>` keyed on `id`, plus a timeout (3 s) and automatic retry after reconnect.

> **DEALER caveat:** with `REQ`/`REP`, ZMQ inserts an empty delimiter frame that a `DEALER` must prepend itself. So `sendMore("")` before the actual payload frame — otherwise the server never replies. This is the classic pitfall and belongs in the very first spike.

### 4.2 PubSub (status) — port 5558

A `SUB` socket. Subscribe to at least:

- `playerstatus` — the core topic (state, title, position, playlist)
- `volume.level`
- `core.version`, `core.started_at` — useful as a connection check
- optionally `rfid.card_id`, `host.temperature.cpu`, `batt_status`

Messages arrive as two frames: topic (text) and payload (JSON).

`playerstatus` is essentially the MPD status enriched with song data. Expected fields: `state` (`play`/`pause`/`stop`), `elapsed`, `duration`, `songid`, `pos`, `playlistlength`, `file`, `artist`, `album`, `title`, `repeat`, `random`, `single`.

> **Robustness rule:** treat every field as optional and parse leniently. MPD returns some numbers as strings, and depending on content — web radio, for instance — `duration` and `album` are missing entirely. `ignoreUnknownKeys = true` plus custom converters will save a lot of pain later.

### 4.2.1 No polling required

The box polls MPD itself every 250 ms (`mpd_status_poll_interval = 0.25`) and publishes the result. The client therefore receives four updates per second without asking. Periodic `playerstatus` RPC calls are redundant and would only load the shared RPC socket.

### 4.2.2 Last value cache — no initial request

The publishing server maintains a last value cache. When a new subscriber connects, the complete current state is resent automatically. Consequences:

- **No initial `playerstatus` RPC on connect.** Subscribing is enough; state arrives on its own.
- A `resend` command exists to force the state after an uncertain reconnect.
- Connection setup reduces to: connect SUB, wait, state is there.

### 4.2.3 Parse throttling (important for long-running operation)

The publish call in the poller runs **unconditionally** — even when the box is stopped. The docstring in the repository claims "published only on change"; the code does not. For a continuously running service that amounts to roughly 345,000 messages per day.

The data volume is irrelevant, the CPU time for JSON parsing is not. Mitigation:

1. Compare the raw string against the previous raw string — identical? discard without parsing
2. Only then parse and write into the `StateFlow`
3. On the UI side, additionally reduce via `distinctUntilChanged` on the fields actually displayed

At rest the parsing cost drops to almost nothing, because the status does not change.

### 4.3 Connection management

- State machine: `Disconnected → Connecting → Connected → Degraded → Disconnected`
- Watchdog: if no PubSub message arrives for over 20 s, ping `core.version` over RPC. No answer means reconnect with exponential backoff (1 s to a 30 s ceiling).
- ZMQ does reconnect TCP by itself but never reports it upwards. The watchdog is therefore mandatory, not optional.
- Set TCP keepalive on the socket (`setTCPKeepAlive(1)`) so dead connections surface faster under Doze.

### 4.4 Host discovery

- Primary: manual entry of hostname or IP
- Convenience: `NsdManager` scans for candidates on the local network and resolves `<hostname>.local` (Avahi runs on Raspberry Pi OS by default). Discovered hosts are offered as a list when adding a box
- Test the connection with `core.version` and show a clear error on timeout

---

## 5. Command mapping

Can be lifted directly from `src/webapp/src/commands/index.js`. Relevant commands:

**Player** (`player.ctrl.*`): `play`, `pause`, `toggle`, `prev`, `next`, `seek`, `shuffle(option)`, `repeat(option)`, `playerstatus`

**Content** (`player.ctrl.*`): `get_folder_content`, `list_albums`, `list_songs_by_artist_and_album(albumartist, album)`, `get_single_coverart`, `get_album_coverart`, `play_folder(folder)`, `play_album(albumartist, album)`, `play_single(song_url)`, `update`

**Volume** (`volume.ctrl.*`): `get_volume`, `set_volume(volume)`, `change_volume(step)`, `mute`, `get_soft_max_volume`

> Deliberately avoid `list_all_dirs` — the repository carries an explicit note that it consumes a lot of memory on large libraries. Use `get_folder_content` lazily, one level at a time, instead.

**Cover art:** `get_single_coverart` returns a filename in the cover cache. The image is fetched over HTTP from the box's web server (`http://<host>/cover-cache/<file>`), not over ZMQ. Give the image loader a custom keyer so the cache keys on song ID **and** box ID.

---

## 6. Data and cache layer

The RPC socket is shared with `inproc` — internal calls from GPIO and the RFID reader run over the same strictly sequential socket. A blocking `list_albums` therefore delays **card detection on the box itself**. Library data must never be fetched periodically.

### 6.1 Room as the single source of truth

Repositories always return a `Flow` from the database, never straight from RPC. A refresh writes to the database and the UI updates itself as a result.

Side benefit: the library stays browsable when the box is off. Favourites and folder structure are displayed; only playback fails, with a clear message.

### 6.2 Three classes of data

| Data | Source | Strategy |
|---|---|---|
| `playerstatus`, `volume.level` | PubSub | Never persisted, always live |
| Albums, folder contents | RPC | Room, explicit refresh |
| Cover art | HTTP | Image loader disk cache, effectively immutable |

**Load folders lazily:** `get_folder_content` per level, each level cached on first visit. Never the whole library at once — `list_all_dirs` stays off limits.

**Slow calls** should carry `as_thread: true` so the box's RPC loop is not blocked.

### 6.3 Schema

```
Box(id PK, displayName, host, rpcPort, pubPort, addedAt,
    autoSessionEnabled, networkSsid?, lastSeenAt, sortIndex)

LibraryFolder(boxId FK, path, parentPath, displayName, hasChildren, cachedAt)
    PK (boxId, path)
LibraryTrack(boxId FK, url, parentPath, title, artist, album, trackNo, duration)
    PK (boxId, url)
LibraryAlbum(boxId FK, albumartist, album, coverFile, cachedAt)
    PK (boxId, albumartist, album)

Favorite(id PK, boxId FK, label, type, folder?, albumartist?, album?,
         coverFile?, sortIndex, launchCount, shortcutPinned)
```

`type` is one of `FOLDER | ALBUM`. Every table carries `boxId` with `ON DELETE CASCADE`, so removing a box cleans up its cache and favourites in one step.

### 6.4 Refresh triggers

1. **Pull to refresh** in the library view — refreshes only the visible level
2. **"Rescan library"** in box settings: fire `player.ctrl.update` to start the MPD database scan, then reload after a short delay. `update_wait` blocks until completion and should be avoided
3. **Opportunistically at app start**, but only when both conditions hold: cache older than roughly 7 days **and** `playerstatus.state == "stop"`. A blocking call bothers nobody when nothing is playing

Pair this with a discreet freshness hint in the UI ("Updated 3 days ago") so it is obvious why a newly added file has not shown up yet.

### 6.5 Shortcuts do not need the cache

A favourite stores the folder path, and `play_folder` takes exactly that. Tapping the home screen icon is therefore precisely one RPC call with no prior lookup — the fastest possible start, independent of cache state and working even on first launch after a reinstall.

---

## 7. Multiple boxes

Households with more than one Phoniebox are not an edge case — one per child's room is the common pattern. Multi-box is therefore part of the design rather than a later retrofit.

### 7.1 One active box at a time

The app has exactly one **active box**. Switching is explicit and instant; there is no simultaneous control of two boxes. This matches how the device is actually used (you are in one room) and avoids a class of ambiguity: which box does the play button target, which box does the media notification represent, which box does the volume rocker change.

Consequence for the connection layer: `ConnectionManager` holds one RPC socket pair and one SUB socket, both bound to the active box. Switching tears down and rebuilds them. Because the last value cache resends full state on subscribe, a switch feels immediate — no request round trip needed to populate the player.

### 7.2 What is per box and what is global

| Per box | Global |
|---|---|
| Host, ports | Theme, dynamic colour |
| Display name | Language |
| Automatic session on/off | Notification permission |
| Network SSID for the automatic mode | |
| Library cache | |
| Favourites | |

Favourites are scoped per box on purpose: a folder path that exists on one box need not exist on another, and a favourite that silently fails is worse than one that is simply not there.

### 7.3 Shortcuts carry the box

Deep link format: `coil://play?box=<boxId>&type=folder&path=...`

A home screen shortcut must start the box it was created for, **regardless of which box is currently active**. `PlayShortcutActivity` therefore reads `boxId` from the intent, connects to that box ad hoc, fires `play_folder`, and switches the active box to match so that the media notification and the app agree afterwards.

Dynamic shortcuts are drawn from the active box only — a launcher long-press menu mixing boxes would be confusing. Pinned shortcuts can come from any box, since the user placed them deliberately.

### 7.4 Background mode with several boxes

The automatic foreground service monitors the **active box only**. Monitoring all boxes would mean one persistent connection per box, multiplying the battery cost of a feature that is already a compromise.

Per box there is an `autoSessionEnabled` flag, so a box in a guest room can be excluded permanently. If the active box has the flag off, the service does not start at all.

### 7.5 UI

- A box switcher in the top app bar: name plus connection dot, tapping opens a bottom sheet with all boxes and their reachability
- With exactly one box configured, the switcher collapses to a plain connection indicator — no one should pay UI complexity for a feature they do not use
- Adding a box: mDNS scan results plus manual entry, connection test, name it

### 7.6 Migration safety

Even though phase 1 ships with a single-box UI, the schema and the settings storage carry `boxId` from the first commit. Adding the column later would mean a Room migration across every table plus a rewrite of the shortcut deep link format, which is precisely the kind of change that is cheap now and expensive in six months.

---

## 8. Media session — the centrepiece

Goal: as soon as the box plays, the notification and lock screen controls appear automatically.

### 8.1 The player

`PhonieboxPlayer : SimpleBasePlayer`. This media3 class exists precisely for the case where playback happens **somewhere else**.

- `getState()` is built from the most recent `playerstatus`
- Position: at a 4 Hz update rate the published `elapsed` is smooth enough on its own. A `PositionSupplier` for local interpolation is still worth having, but as robustness against brief connection gaps rather than a basic requirement — prioritise accordingly
- Overrides: `handlePlayWhenReadyChanged`, `handleSeek`, `handleSeekToNext/Previous`, `handleSetDeviceVolume`, `handleSetShuffleModeEnabled`, `handleSetRepeatMode`. Each fires the corresponding RPC and returns `Futures.immediateVoidFuture()` right away — optimistic UI, corrected on the next PubSub message

**Nice side effect:** through `COMMAND_SET_DEVICE_VOLUME` the **phone's hardware volume buttons** control the Phoniebox directly while the session is active. Take `deviceVolumeMax` from `get_soft_max_volume`.

With several boxes configured, put the box name in the media metadata subtitle so the lock screen makes clear which device is playing.

### 8.2 The service

`PhonieboxMediaService : MediaSessionService`

- Manifest: `android:foregroundServiceType="mediaPlayback"`, permission `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
- Remote and cast playback fall under the permitted use case for this foreground service type
- `POST_NOTIFICATIONS` as a runtime permission (Android 13+), requested on first launch
- The service holds the PubSub connection even when no activity is running

### 8.3 Automatic appearance (and being honest about it)

For the app to notice that the box has started playing, **something** has to hold the PubSub connection open. Android has no push mechanism from a device on the local network. In practice:

**"Automatic" mode (opt-in):**
- A foreground service runs continuously while the device is on the configured Wi-Fi network
- Started via `BOOT_COMPLETED` plus a `ConnectivityManager.NetworkCallback` on the network
- While the box is not playing: a discreet, low-priority notification ("Connected to Phoniebox") — foreground service rules require a visible notification
- Once `state == play` arrives, the session becomes active and the notification turns into a full media notification with cover art and controls
- When the device leaves the network, the service stops itself

**"Only while the app is open" mode (default):**
- No persistent service; the session exists only while the app is in the foreground
- No battery drain, no permanent notification

**Realistic expectation:** in automatic mode delivery is not completely reliable. Aggressive Doze and vendor-specific app killing (Samsung, Xiaomi, OnePlus) can drop the connection. The watchdog catches this, but it can take seconds to minutes. This belongs in a note in settings, along with a link to the battery optimisation exemption (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

Idle consumption itself is low: one open TCP connection with no traffic. The cost driver is the process being kept alive, not the network.

---

## 9. Favourites and shortcuts

**Creating one:** long press on a folder or album in the browser, or a star icon in the player ("save current content as favourite").

**Launcher integration:**
- `ShortcutManagerCompat.setDynamicShortcuts()` with the top N by usage from the active box — depending on the device, only about four are visible on launcher long press
- Additionally **pinned shortcuts**: place a favourite directly on the home screen, using cover art as the icon (`IconCompat.createWithAdaptiveBitmap`)
- Target intent: `coil://play?box=<boxId>&type=folder&path=...`, handled by a transparent `PlayShortcutActivity`

**Flow on tap:** activity starts, connects to the box named in the intent (or reuses an existing connection), fires `play_folder`, starts the media service, calls `finish()`. The result: tapping the home screen icon starts the audiobook without the app UI ever becoming visible. On failure, show a toast rather than an empty activity.

---

## 10. Brand, colour, typography and icon

### 10.1 Name

**Coil** — store title "Coil — Remote for Phoniebox", domain `coilforphoniebox.app`.

The coil is not a metaphor: every RFID card contains an antenna coil that is energised by the reader's field. The name describes what actually happens when a card is placed on the box.

The "for Phoniebox" suffix carries discoverability — people search for *Phoniebox*, not for *Coil*. It also signals compatibility rather than official affiliation, which matters for store policy.

**Application ID:** `app.coilforphoniebox` — immutable after publication, so lock it down before the first upload.

"Coil" is an ordinary English word: low legal risk, but also almost no trademark protection. In a music context the British band of the same name dominates search results. This is accepted deliberately, because discoverability runs through "Phoniebox" rather than through the name itself.

### 10.2 Logo mark

The "Winding" mark: a solid card body with the coil knocked out. The path is the layout of a real card antenna. Two clear masses rather than thin lines, which is why it stays stable at 40 px.

```
M22 60 V36 H74 V60 H30 V44 H66 V52 H40
```
on a rectangle `x=10 y=24 w=76 h=48 rx=11`, viewBox `0 0 96 96`, stroke width 5.

### 10.3 Brand colours

Fixed, independent of theme — for the logo, store assets and website.

| Token | Hex | Use |
|---|---|---|
| Coil Deep | `#0F6B5C` | Primary colour, icon background |
| Coil Mint | `#6DDBC3` | Primary in dark mode, accent on dark |
| Coil Ink | `#14191B` | Text, lockup on light |
| Coil Bone | `#E4E7E4` | Neutral background |

Contrast checked: Coil Deep on white reaches 6.3:1, Coil Mint on the dark surface 11.2:1. Both meet WCAG AA including body text.

### 10.4 Typography

**Material 3 default type scale. No custom fonts.**

The reasoning is practical rather than aesthetic: shipping five languages means the font has to cover the full Latin-1 range including French accents and Dutch digraphs, and any future language expands that requirement further. A bundled font also costs APK size for a benefit that is invisible to most users. The system font already covers every planned locale and adapts to the user's font size preference, which matters for accessibility.

If a display face is ever wanted, it should be limited to the app title and store assets — never to content.

### 10.5 Material 3 scheme — light

The complete token set lives in `android/theme/Color.kt` in this bundle. Key values:

```kotlin
primary            = Color(0xFF0F6B5C)
onPrimary          = Color(0xFFFFFFFF)
primaryContainer   = Color(0xFFA8F2DF)
onPrimaryContainer = Color(0xFF00201A)

// Warm counterpoint: favourites, currently playing
tertiary            = Color(0xFF7A5900)
tertiaryContainer   = Color(0xFFFFDF9B)
onTertiaryContainer = Color(0xFF261A00)

surface        = Color(0xFFF5FBF7)
onSurface      = Color(0xFF171D1B)
surfaceVariant = Color(0xFFDBE5E0)
outline        = Color(0xFF6F7975)
```

### 10.6 Material 3 scheme — dark

Dark mode is not an accessory here. The app is used at bedtime, often in a darkened child's bedroom. The surface is therefore deliberately very dark and slightly green-tinted rather than neutral grey.

```kotlin
primary            = Color(0xFF6DDBC3)
onPrimary          = Color(0xFF003830)
primaryContainer   = Color(0xFF005046)
onPrimaryContainer = Color(0xFF8AF8DF)

tertiary            = Color(0xFFF0C048)
tertiaryContainer   = Color(0xFF5D4200)
onTertiaryContainer = Color(0xFFFFDF9B)

surface        = Color(0xFF0E1513)
onSurface      = Color(0xFFDDE4E0)
surfaceVariant = Color(0xFF3F4945)
outline        = Color(0xFF899390)
```

### 10.7 How colour is used

- **Primary** carries transport controls: play/pause as a filled circle, the active volume slider
- **Tertiary** (amber) marks favourites and the currently playing row. The warm counterpoint keeps an all-green interface from becoming fatiguing
- **Cover art dominates the player.** The brand colour recedes there and appears only in controls — otherwise it fights every album image
- **Error** is reserved for connection loss and failed commands, never for "the box is off"; that is a normal state, not an error

### 10.8 Android icon: three layers

1. **Background layer** — solid Coil Deep `#0F6B5C`
2. **Foreground layer** — card body and coil in white, inside the safe zone (66 of 108 dp; anything outside can be clipped by round or squircle masks)
3. **Monochrome layer** (Android 13+, themed icons) — **the knockout does not work here.** The system tints the whole layer a single colour, so a knocked-out coil would merge with the card body. Use the outline variant for this layer: card and coil both as strokes

**Notification icon:** white silhouette on transparent, 24 dp. Outline variant as well — at 24 dp the coil is at the limit of resolution, so raise the stroke width and drop the innermost winding.

### 10.9 Dynamic colour

Material You would replace the brand colour with the user's wallpaper palette. **Off by default**, switchable in settings. The brand is new and needs recognition; anyone who wants Material You will find the toggle.

---

## 11. Screens and onboarding

### 11.1 Screens

1. **Player** (start screen) — large cover, title and album, progress with seek, transport controls, volume slider, shuffle/repeat, favourite star, box switcher with connection state
2. **Library** — tabs for "Folders" (drill-down) and "Albums" (grid with covers); tap to play, long press to favourite
3. **Favourites** — grid, drag to reorder, context menu for "Add to home screen"
4. **Settings** — split into global (theme, dynamic colour, language) and per box (host, ports, connection test, automatic session, network, rescan library)

A persistent mini player sits at the bottom across all screens.

### 11.2 Onboarding

The audience already owns a Phoniebox and set it up themselves. The app therefore assumes competence and explains the one thing that is genuinely specific to it: how to point the app at the box.

First launch shows a single screen: an mDNS scan result list, a manual entry field, and one sentence explaining that the box and phone must be on the same network. Nothing else. No feature tour, no permission pre-briefing, no carousel.

One troubleshooting hint is worth including because it is the failure that looks like a bug rather than a configuration issue: **client isolation**, where a router or guest network prevents devices from seeing each other. Surface it in the error state when the connection test times out, not up front.

Everything else stays discoverable in settings rather than being explained ahead of time.

---

## 12. Internationalisation

The project language is **English**: code, comments, commit messages, documentation, and the source locale for all UI strings.

This section sits before the phase plan because retrofitting string extraction into an existing codebase costs far more than doing it from the first commit.

### 12.1 Launch languages

| Locale | Resource directory | Status at launch |
|---|---|---|
| English | `values/` | Source |
| German | `values-de/` | Complete |
| French | `values-fr/` | Complete |
| Spanish | `values-es/` | Complete |
| Dutch | `values-nl/` | Complete |

Adding further languages later is a matter of dropping in a directory — the infrastructure decisions below are what make that true.

### 12.2 Ground rules from commit one

- **No hardcoded user-facing strings.** Everything goes in `res/values/strings.xml`, including toast text, error messages, notification channel names and content descriptions
- Enable the `HardcodedText` lint check as an **error**, not a warning, so it cannot be waved through under time pressure
- **Never concatenate strings.** Word order differs between languages. Use positional placeholders — `"Playing %1$s from %2$s"`, never `getString(R.string.playing) + title`
- **Use `<plurals>` for anything countable.** Note that plural rules genuinely differ across the launch set: English, German, Dutch and Spanish treat 0 as plural, French treats 0 and 1 alike as singular. An `if (count == 1)` is wrong in French
- **Format numbers, dates and durations through the platform**, via `java.time` and `NumberFormat`, never by hand

### 12.3 Layout consequences

German and Dutch strings run roughly 30–35% longer than English, French around 20%. Do not size buttons or labels to fit the English text. The box switcher and the settings rows are the places where this will bite first.

Use `start`/`end` in Compose modifiers rather than `left`/`right`, and keep `supportsRtl="true"` in the manifest. No RTL language is planned, but not breaking it is nearly free whereas fixing it later is not.

Test with **pseudolocales**: `en_XA` inflates every string and exposes truncation, `ar_XB` mirrors the layout. Both catch hardcoded strings instantly, because untranslated text stays unmodified.

### 12.4 What is never translated

Content coming from the Phoniebox is user data: folder names, track titles, artists, album names. These are never touched, never title-cased, and never sorted with a locale-specific collator that would surprise the user. Sorting should follow the box's own ordering wherever possible, so that app and web UI agree.

The app name stays **"Coil — Remote for Phoniebox"** in every locale. "Phoniebox" is a product name.

### 12.5 Translation workflow

Translations are contributed as **pull requests** against `res/values-<locale>/strings.xml`. No translation platform, no additional infrastructure.

What this asks of the source strings, since five files now move together whenever the UI changes:

- Give every string a **name that describes its role**, not its current wording — `error_connection_timeout`, not `error_could_not_reach_box`. Rewording the English text should not orphan four translations
- Add a `<!-- comment -->` above anything ambiguous. A translator seeing `%1$s` with no context cannot know whether it is a folder name or a box name, and word order depends on that
- Never reuse one string in two places because the English happens to match. Other languages will need them to differ

Missing strings fall back to English automatically, so a partial translation is safe to merge and a language can be completed incrementally.

Keep `MissingTranslation` visible in lint output so drift is noticed as the UI evolves.

One caution worth stating in `CONTRIBUTING`: a machine-translated locale that nobody fluent has read is worse than no locale at all, because it looks finished. A rough draft that a speaker then corrects is genuinely useful.

### 12.6 Accessibility belongs here

Content descriptions for TalkBack are strings like any other and need the same treatment. Writing them alongside the visible labels costs almost nothing; adding them in a later pass means revisiting every screen.

---

## 13. Distribution and release

### 13.1 Channels

**Play Store** — the primary channel, where the audience actually looks for apps.

- Distribution format is AAB. A privacy policy page is required even though the app collects nothing; host it on `coilforphoniebox.app`
- The Data Safety form declares no data collected and no data shared. This is accurate: everything stays on the device and the local network
- `BOOT_COMPLETED` and the foreground service type need a plain-language justification in the listing. "Shows playback controls when the connected device starts playing" is both true and sufficient

**GitHub Releases** — a signed APK per release, for people who prefer sideloading or want to test before a store rollout.

**F-Droid** — not at launch. Added if demand appears, and section 13.3 exists to make sure that stays possible.

### 13.2 The signing trap worth knowing before the first upload

Play App Signing is mandatory for new applications. Google holds the app signing key and re-signs uploads made with the developer's upload key.

**Consequence:** the APK built and signed locally for GitHub Releases carries a different signature from the one the Play Store delivers. Android refuses to install an update across different signatures. A user who installed from GitHub cannot update from the Play Store, or vice versa, without uninstalling first — losing app data in the process.

This is not fixable, only documented. Practical handling:

- State the situation plainly in the release notes and the README
- Treat the two channels as separate install lineages
- Since app data here is small (box configuration and favourites, no media), a **settings export and import** feature makes a channel switch survivable. Worth building once the app is stable

### 13.3 Preserving the F-Droid option

F-Droid is out of scope for now, but the door stays open at essentially zero cost provided the project keeps three constraints:

1. **No proprietary dependencies.** No Firebase, no Google Play Services, no closed-source SDKs. The stack in section 2 already satisfies this — the point is to keep it that way when a convenient library appears later
2. **A reproducible build.** Pin dependency versions, avoid build steps that embed timestamps or paths

Constraints 2 and 3 cost nothing to maintain and a great deal to retrofit.

### 13.4 Versioning

`versionName` follows semantic versioning. `versionCode` increases monotonically and is shared across both channels, so a given version number means the same build everywhere.

---

## 14. Phases

### Phase 0 — transport spike (roughly one weekend)
A Kotlin console test against a real box: settle the DEALER handshake, subscribe to `playerstatus`, trigger play/pause. **Nothing else is worth building until this works.** This is where it is decided whether the DEALER approach holds or a serialised `REQ` with a mutex is needed instead.

### Phase 1 — skeleton
Module structure, Hilt, connection state machine, raw status dump on screen. **String resources and lint configuration from section 12 are set up here**, not later. The **`Box` entity and `boxId` foreign keys land here too** even though the UI is single-box — see section 7.6.

### Phase 2 — player UI
Compose player, cover loading, transport controls, volume. The theme from section 10 lands here: `LightColors`/`DarkColors`, Material 3 type scale, adaptive icon in all three layers. Add the icon early — it shapes how build and install feel, and it exposes masking errors while they are still cheap to fix.

### Phase 3 — library
Folder drill-down, album list, playing folders, albums and single tracks. Room cache and the freshness hint.

### Phase 4 — media session
`SimpleBasePlayer`, `MediaSessionService`, notification, lock screen, hardware volume buttons. Foreground only at first.

### Phase 5 — background and automatic mode
Foreground service, boot receiver, network trigger, battery notes, the off switch.

### Phase 6 — favourites and shortcuts
Favourites UI, dynamic and pinned shortcuts, deep link activity with `boxId` handling.

### Phase 7 — multiple boxes
Box switcher, add and remove flow with mDNS scan, per-box settings, the collapsed single-box presentation. The data model already supports it; this phase is UI and connection lifecycle.

### Phase 8 — release preparation
Error and empty states, offline behaviour, tablet layout, onboarding screen, pseudolocale pass, four translations landed, privacy policy page, store listing, settings export and import.

---

## 15. Risks

| Risk | Assessment | Mitigation |
|---|---|---|
| DEALER framing against the Python RPC server | **Medium** (was high) | Code review shows the server is a plain `zmq.REP` socket that manages the envelope itself. DEALER with an empty delimiter frame should work. Residual risk: JeroMQ is an independent reimplementation and may differ, so the spike remains mandatory. Fallback: `REQ` with a global mutex |
| Slow RPCs delay the card reader and GPIO | Medium | Shared socket — see section 6. Caching, `as_thread`, no polling |
| Background connection killed by the OS | Medium | Watchdog, battery optimisation exemption, honest expectation setting in the settings screen |
| `playerstatus` field names change | Medium | Lenient parsing, field names centralised in one place |
| Translation quality in languages the maintainer cannot read | Medium | No unreviewed machine translation; a locale ships only after a human has read it |
| Users stuck between install channels by the signing split | Medium | Documented in release notes and README; settings export and import in phase 8 |
| Connection lifecycle bugs when switching boxes | Low to medium | One active box only, explicit teardown and rebuild, no shared socket state |
| Foreground service policy changes in future Android versions | Low to medium | `mediaPlayback` is intended for remote playback and has been stable |
| Large library means slow browsing | Low | Lazy loading per folder, paging in Compose |

---

## 16. Security

The RPC port is **unauthenticated and unencrypted**. Anyone who can reach it can also shut the box down.

- Design the app for LAN use only
- Do not recommend port forwarding; if remote access is wanted, use a VPN such as WireGuard — mention this in settings
- Do not expose commands beyond playback (`host.shutdown` and friends stay out) — this limits the damage if a port is accidentally exposed

---

## 17. Rejected alternatives

Documented so the question does not resurface in every review.

**MQTT as transport instead of ZMQ.** The box's MQTT component is disabled by default and works as a pure bridge: it subscribes to the same internal publisher and forwards to an external broker. For the app that means identical data with an extra hop of latency, another service that can fail, and an additional dependency on the box. It does not help with the battery problem either — MQTT also needs a persistent TCP connection on Android and is subject to Doze in exactly the same way. Real push would require a cloud broker, which is neither necessary nor desirable for a device on a home network. **Decision: ZMQ directly.** Users should not have to run a broker to use the app.

**The WebSocket ports 5556/5557.** JeroMQ does not support the `ws://` transport. An additional ZMQ-over-WebSocket layer would be custom work with no benefit, since the TCP ports carry the same data.

**Direct MPD access on port 6600.** Bypasses the jukebox logic entirely (volume plugin, card handling, per-folder resume behaviour) and is bound to `localhost` by default anyway. Conceivable only as an emergency fallback.

**A simplified mode for children.** Considered and dropped. Children handle ordinary media player interfaces perfectly well, and the physical cards already are the child-facing interface. A separate mode would add a settings surface, a lock mechanism and a second layout to maintain, for a problem that does not exist.

**Compatibility with Phoniebox v2.** Out of scope. The protocol differs substantially and the v3 branch is where development happens.

**Custom typography.** Dropped in favour of the Material 3 default — see section 10.4.

---

## 18. Remaining unknowns

Small enough not to block a start, but worth settling before the first public release.

1. **Name availability.** Play Store listing name, F-Droid, GitHub organisation. The domain is secured; the rest is unverified.
2. **Whether the settings export and import lands in phase 8 or later.** It is the only mitigation for the channel signing split, which argues for having it at launch rather than after users are already split across channels.

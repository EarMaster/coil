# AGENTS.md

Source of truth for working in this repository. `CLAUDE.md` points here; keep this file
authoritative and update `CLAUDE.md` only if the pointer itself needs to change.

## What this repo is

Coil is a planned native Android remote control for [Phoniebox](https://github.com/MiczFlor/RPi-Jukebox-RFID)
v3 (`future3/main`), talking to the box over its ZeroMQ RPC/PubSub interface on the local
network. See `README.md` for the user-facing pitch and feature list.

**Current state: implemented through the plan's phase 7, with most of phase 8 done.** The app
builds, tests and lints; what it has not had is a run against a real box. Treat
`docs/implementation-plan.md` as the detailed spec for anything not covered below — this file is a
condensed map of it, not a replacement. See "Implementation status" for what is and is not done.

## Repo layout

| Path | Contents |
|---|---|
| `app/` | Compose UI, navigation, screens, string resources for all five locales, adaptive and notification icons, `PlayShortcutActivity`. All translatable strings live here, in one file per locale. |
| `core-domain/` | Plain JVM module: models, repository interfaces, `PlayFavoriteUseCase`. No Android dependency. |
| `core-transport/` | ZeroMQ client: `ZmqRpcClient` (DEALER), `ZmqStatusSubscriber` (SUB), `PhonieboxSession` (watchdog, reconnect), `ConnectionManager` (active box, reference-counted lifetime), `Commands`, the payload parsers, `HostDiscovery` (mDNS), `BoxProbe`. |
| `core-data/` | Room database and DAOs, DataStore settings, repository implementations, settings export/import. |
| `feature-media/` | `PhonieboxPlayer` (`SimpleBasePlayer`), `PhonieboxMediaService` (`MediaSessionService`), notification handling, `AutoSessionStarter`, `BootReceiver`, `MediaSessionBinder`. |
| `feature-shortcuts/` | `ShortcutPublisher` (dynamic and pinned shortcuts), `ShortcutSynchronizer`, `PlayDeepLink` (`coil://play?box=…`). |
| `docs/implementation-plan.md` | Full architecture/design spec — tech stack, module layout, transport design, data model, media session, multi-box design, branding, i18n rules, phased build plan. **Read before implementing anything non-trivial.** |
| `docs/protocol-notes.md` | Condensed Phoniebox v3 ZMQ protocol reference, distilled from the upstream Python source. |
| `docs/pages/` | The GitHub Pages site (Jekyll), deployed by `pages.yml` — landing page and privacy policy, served at `coilforphoniebox.app` via the `CNAME` file in this folder. Kept separate from the planning docs above so the Pages *site* only contains user-facing content — the planning docs are still public in the repo, just not part of the deployed website. |
| `spike/` | Standalone Gradle/Kotlin JVM project validating the transport approach (JeroMQ DEALER client) against a real box, independent of Android. |
| `tools/check_store_metadata.sh` | Validates `fastlane/metadata/android/` against Play's per-locale character limits (title 30, short 80, full 4000, release notes 500). Pass a versionCode to also require release notes in every launch locale. Run by `/release` and by `google-play.yml`. Counts characters as bytes-minus-UTF-8-continuation-bytes rather than using `wc -m`, which silently counts bytes in a non-UTF-8 shell locale. |
| `tools/probe_phoniebox.py` | Python/pyzmq script that does the same validation from the other side (REQ vs DEALER framing, pipelining, PubSub schema dump) — used to sanity-check protocol assumptions against a live box before trusting them in Kotlin. |
| `android/` | Original reference bundle for theme and adaptive icon XML. Now **copied into `app/`** and kept only as the source of those files' provenance; edit the copies under `app/src/main/`, not these. |
| `fastlane/metadata/android/` | All Play Store text, one folder per locale in `fastlane supply` layout: `title.txt`, `short_description.txt`, `full_description.txt`, and release notes at `changelogs/{versionCode}.txt`. Written by `/release`, validated by `tools/check_store_metadata.sh`, consumed by `google-play.yml`. See that folder's `README.md`. |
| `brand/` | Logo mark SVG. |
| `mockup/` | Static HTML UI mockup (`coil-mockup.html`) — visual reference only, not implementation. |
| `CHANGELOG.md` | [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)-format history. **Update the `## [Unreleased]` section as part of any user-facing change** — new feature, fix, or behaviour change. `release.yml` extracts release notes straight from the `## [x.y.z]` heading matching `versionName`, so headings must stay exact and the `Unreleased` section shouldn't go stale. |

## CI/CD

`.github/workflows/` mirrors the setup from a sibling project, adapted for Coil ahead of time:

- `ci.yml` / `codeql.yml` — build/test/lint and CodeQL analysis on PRs to `main` or `develop`. Both
  start with a `detect` job that checks for a root `./gradlew`; that gate is now satisfied, so these
  actually build, test and lint.
- `release.yml` — tags `main` from `app/build.gradle.kts`'s `versionName`, builds a signed
  APK/AAB, and cuts a GitHub Release with notes pulled from `CHANGELOG.md`. Its gate
  (`app/build.gradle.kts` exists) is now satisfied too. Needs `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD` repo secrets before it can actually sign a build. Release notes come
  from `CHANGELOG.md`'s `## [x.y.z]` heading matching `versionName` — see that file's own note.
- `google-play.yml` — reusable workflow (`workflow_call`/manual dispatch) that uploads a tagged
  release's AAB to Google Play, package `app.coilforphoniebox`. Needs a `SERVICE_ACCOUNT_JSON`
  repo secret and an active Play Console listing. Checks out the *tag* (not the default branch)
  so the `versionCode` it reads from `app/build.gradle.kts` matches the AAB, and aborts if that
  file's `versionName` disagrees with the tag. Release notes come from
  `fastlane/metadata/android/{locale}/changelogs/{versionCode}.txt`, gated by
  `tools/check_store_metadata.sh` — a missing or over-limit locale fails the deploy. It does
  **not** upload listing text (title/descriptions) yet; `r0adkll/upload-google-play` handles only
  the AAB, mapping and release notes.
- `pages.yml` — deploys `docs/pages/` via Jekyll to GitHub Pages on push to `main` (path-filtered
  to `docs/pages/**`). GitHub Pages must be enabled in repo settings with source "GitHub Actions",
  and the `coilforphoniebox.app` DNS must point at GitHub Pages, for the `CNAME` file in
  `docs/pages/` to actually resolve.

`main` is branch-protected: PRs required (enforced for admins too, 0 required approvals), plus
`Build`/`Unit Tests`/`Lint`/`Analyze (Kotlin)` as required status checks (these currently pass
by skipping, per the gate above). `ci.yml`/`codeql.yml` also trigger on PRs into `develop`.

## Branching model

`main` represents **the released state of the app** — nothing else. `develop` is where all
ongoing work lands first: commits, feature branches, PRs, all of it. `develop` is intentionally
left unprotected (direct pushes are fine — the maintainer is a solo developer and doesn't want
extra ceremony there). `main` only ever advances by merging `develop` into it at release time,
which is what triggers `release.yml`'s tag/build/publish.

**Never open a pull request targeting `main`.** Base every branch and PR on `develop` instead.
Promoting `develop` to `main` for a release is the maintainer's call to make, not something to
initiate unprompted.

## Slash commands

`.claude/commands/` — repo-specific Claude Code commands:

- `/commit` — stages/syncs, drafts a conventional commit message, updates `CHANGELOG.md`'s
  `Unreleased` section when the change is user-facing, and offers to push (to a branch — `main`
  is protected).
- `/release` — bumps `versionName`/`versionCode` in `app/build.gradle.kts`, stamps the
  `CHANGELOG.md` `Unreleased` section with the new version, and generates release notes at
  `fastlane/metadata/android/{locale}/changelogs/{versionCode}.txt` for all five launch locales,
  then verifies each against Play's 500-character limit via `tools/check_store_metadata.sh`.
  Note the filename is the **versionCode**, not the versionName — that is what fastlane and the
  Play API key release notes by. Gated on `app/build.gradle.kts` existing, which it now does.

## Commands

```bash
./gradlew assembleDebug     # debug APK
./gradlew test              # unit tests (core-domain, core-transport)
./gradlew lint              # lint; HardcodedText is an error, MissingTranslation a warning
./gradlew assembleRelease   # minified APK; unsigned unless KEYSTORE_PATH and friends are set
```

Requires JDK 17 (CI uses Temurin 17) and Android SDK 36. `local.properties` with `sdk.dir` is
needed for a local build and is deliberately not checked in — note that lint rejects an unescaped
Windows drive letter there, so write `sdk.dir=C\:/Android/Sdk`, not `C:/Android/Sdk`.

**Toolchain:** Gradle 8.13, AGP 8.13.2, `compileSdk`/`targetSdk` 36, `minSdk` 26. AGP 8.13 is the
newest 8.x and still takes Gradle 8.13, which is why `targetSdk` 36 needed no Gradle upgrade.
Moving to AGP 9.x would require Gradle 9.5 plus a DSL migration (`kotlinOptions` → `compilerOptions`
among others) and is deliberately a separate exercise.

**Transport spike** (validates the DEALER/empty-delimiter approach against a real box):
```bash
cd spike
gradle run --args="phoniebox.local"   # or your box's hostname/IP
```
No `gradlew` wrapper is checked in yet; use a local Gradle install (JDK 17, matches `spike/build.gradle.kts`).

**Protocol probe** (independent Python check of the same assumptions):
```bash
pip install pyzmq
python3 tools/probe_phoniebox.py phoniebox.local
```

Both tools require a real, reachable Phoniebox v3 box on the same network — there is no mock
server in this repo.

## Protocol essentials (see `docs/protocol-notes.md` for full detail)

- Two sockets: **RPC on TCP 5555** (`REQ`/`REP` pattern from the box's side) and **PubSub on TCP
  5558**. Use the TCP ports, not the WebSocket ones (5556/5557) — JeroMQ has no `ws://` transport.
- The RPC server is a plain `zmq.REP` socket. A client `DEALER` works but **must prepend an empty
  delimiter frame** before the JSON payload, or the server never replies. This is the single most
  important framing detail in the whole transport layer.
- The RPC socket is shared with the box's own internal GPIO/RFID handling via `inproc`, processed
  strictly sequentially. A slow or frequent RPC call (e.g. polling `list_albums`) delays card
  detection on the box itself. Never poll for library data or `playerstatus` — see next point.
- PubSub has a **last-value cache**: subscribing alone yields the full current state, no initial
  RPC needed. The box also publishes `playerstatus` unconditionally at ~4 Hz even when idle —
  compare the raw string to the previous one and discard before parsing, to avoid needless JSON
  parsing over a long-running connection.
- Parse `playerstatus` leniently (`ignoreUnknownKeys = true`, tolerate stringified numbers and
  missing fields like `duration`/`album` on web radio streams). Field names are not a stable
  contract upstream.
- **Never send `as_thread`.** `jukebox/plugs.py` starts a daemon thread and returns the `Thread`
  object instead of the function's result, so any call carrying it answers with something
  unusable — it is fire-and-forget only. The implementation plan §6.2 recommends it for slow
  calls; that advice is wrong for anything whose result you need, which is every call Coil makes.
  The consequence is that a library call occupies the box's sequential RPC loop, and with it card
  detection, for as long as it runs — so call them rarely and never on a timer.
- **Cover art takes two requests.** `get_single_coverart` / `get_album_coverart` answer the first
  request for a song with `CACHE_PENDING` and queue the extraction on a worker thread
  (`coverart_cache_manager.py`); an empty string means "no artwork". Only a later request returns a
  file name, so a single call never yields a cover. `LibraryParser.coverArt` models the three
  outcomes and both callers retry.
- **Never let cover art gate other state.** Every screen `combine`s the player state, and `combine`
  emits nothing until *all* inputs have emitted once — so a cover flow whose first value needs a
  round trip freezes the title, progress and controls with it. `PlayerRepository.coverUrl` is a
  `StateFlow` that starts at null and is filled in by a background resolver for exactly this
  reason. The same caution applies to any future flow that needs the network to produce its first
  value.
- **There is no `core` RPC package.** `core.version`, `core.started_at` and `core.git_state` are
  *published topics*; asking for `core.version` over RPC answers with an error. Use
  `player.ctrl.playerstatus` as a reachability ping — it returns the poller's cached dict without
  touching MPD. Note the box does not publish `core.version` at all, only `core.git_state`, so
  Coil subscribes to the `core.` prefix and shows whichever it gets.
- Never call `list_all_dirs` (unbounded memory on large libraries) — use `get_folder_content` one
  level at a time instead.
- The RPC port is unauthenticated and unencrypted. Design is LAN-only; never add or expose
  commands beyond playback (no `host.shutdown` etc.) so an accidentally exposed port can't take
  the box down.

## Architecture decisions to preserve

These are settled decisions from `docs/implementation-plan.md`, not open questions:

- **Stack:** Kotlin + Jetpack Compose, MVVM/unidirectional state flow, JeroMQ for transport,
  androidx.media3 (`SimpleBasePlayer`) for the media session, kotlinx.serialization, Hilt, Room +
  DataStore for persistence. Module split: `:app`, `:core-transport`, `:core-domain`, `:core-data`,
  `:feature-media`, `:feature-shortcuts`. Versions are pinned exactly in `gradle/libs.versions.toml`
  — lint reports newer ones as warnings, and that is fine; reproducibility is the point (§13.3).
- **No Google Play Services / proprietary dependencies, anywhere.** This is deliberate (keeps the
  F-Droid option open at effectively zero cost) and must hold even when a convenient library
  appears later.
- **Room is the single source of truth** for library/favourites data; repositories return `Flow`
  from the database, never straight from RPC. `playerstatus`/`volume.level` are PubSub-sourced and
  never persisted.
- **Multi-box model:** exactly one *active* box at a time (one RPC/SUB socket pair, torn down and
  rebuilt on switch). The `Box` entity and `boxId` foreign keys are present in the schema from the
  first commit even though the launch UI is single-box-oriented — retrofitting this later means a
  Room migration across every table.
- **Coil (this app) vs Coil (the image-loading library):** name collision is known and accepted;
  alias the library import to keep it unambiguous in code and review.
- **i18n is not an afterthought.** English is the source language for everything (code, comments,
  commits, docs, UI strings). No hardcoded user-facing strings, no string concatenation, use
  `<plurals>`, format numbers/dates via the platform. Launch locales: en, de, fr, es, nl.
- **No custom fonts** — Material 3 default type scale only (keeps full Latin-1 coverage for all
  launch locales without APK cost).
- **Dynamic colour (Material You) is off by default**, toggle in settings; brand colours are fixed
  regardless of theme (the token set now lives in `app/src/main/kotlin/.../ui/theme/Color.kt`).

## The `coil://play` deep link

`PlayDeepLink` in `:feature-shortcuts` owns the format; `PlayShortcutActivity` in `:app` answers it.
It is **exported** (`AndroidManifest.xml`), so it is not a private shortcut mechanism: an automation
app, an NFC tag, an `<a href>` in a note, another app or `adb shell am start -a
android.intent.action.VIEW -d "coil://play?…"` all start a favourite the same way a home screen
shortcut does. No window opens — one RPC, a toast, `finish()`.

```
coil://play?box=<boxId>&type=folder&path=<relpath>
coil://play?box=<boxId>&type=album&albumartist=<artist>&album=<album>
coil://play?box=<boxId>&type=track&url=<mpd url>
…&favorite=<id>     # optional; only counts the launch and feeds the launcher's ranking
```

- **`box` is required and authoritative.** `parse` returns null without it, `playOn` sends the
  command to *that* box whether or not it is active (a one-shot socket via `callOn` when it is not),
  and `setActive` follows so the app and the notification agree afterwards (§7.3). A link for the
  bedroom box starts the bedroom box while the living room one is playing. This is deliberate:
  favourites are per box, so a folder path from one box means nothing on another.
- **`boxId` is a random UUID** minted in `BoxRepositoryImpl.add`, so a link cannot be written by
  hand and a fresh install mints new ids. "Copy link" and "Share link" on a favourite's menu exist
  precisely because that id is otherwise invisible. If hand-written links are ever wanted, the shape
  to add is `host=<hostname>` resolved against the configured boxes, not a second id scheme.
- Adding anything to this format means widening what an unprivileged external intent can ask Coil to
  do. Keep it to starting playback — the same limit the RPC surface itself observes.

## Project conventions

- Project language is English throughout: code, comments, commit messages, documentation.
- Translations are contributed as PRs against `res/values-<locale>/strings.xml`; no unreviewed
  machine translation (a partial, human-reviewed translation is fine — an unreviewed complete one
  is not).
- Scope discipline: Coil is deliberately a playback remote only. Card management, box system
  settings, timers, and shutdown/reboot stay out of scope — see README "What Coil does not do" and
  the implementation plan §1 and §16.
- Keep `CHANGELOG.md` current: add an entry under `## [Unreleased]` for any user-facing change in
  the same commit/PR that makes it, not as a follow-up. When cutting a release, rename
  `[Unreleased]` to the new `[x.y.z]` version heading (matching `versionName`) and start a fresh
  empty `Unreleased` section above it.

## Implementation status

Phases 1–7 of `docs/implementation-plan.md` §14 are implemented, along with most of phase 8. What
still needs doing, in rough order of importance:

1. **First run against a real box happened and found three bugs** (all fixed): `as_thread`
   discarding every result, `core.version` not being an RPC, and the volume slider firing a command
   per frame. What is confirmed working on hardware: the DEALER framing, PubSub status, play/pause/
   next/prev, `play_folder`, and favourites. Still unconfirmed on hardware: the library views and
   cover art (both were broken by `as_thread` and have not been retested), the connection test,
   mDNS discovery, the media session and notification, launcher shortcuts, and multi-box switching.
2. **The four translations are unreviewed drafts.** `values-de|fr|es|nl/strings.xml` each carry a
   header saying so. Per the rule above they must be read by a fluent speaker before a release —
   missing strings fall back to English, so correcting them incrementally is safe.
3. **No instrumented or UI tests.** Unit tests cover the parsers, the command catalogue and the
   domain models; everything above that is untested.
4. **Automatic mode is best-effort by design**, and its boot path is the weakest part: Android may
   refuse to start a foreground service from `BootReceiver`, which `AutoSessionStarter` treats as a
   normal outcome. In practice the mode becomes reliable once the app has been opened after a
   reboot. The settings screen says so in plain language.
5. **Tablet layout** is untouched — the UI is single-column everywhere.
6. **Android 16's local network restriction will eventually break Coil outright.** Access to
   local-network addresses — which is every socket this app opens, plus `NsdManager` discovery and
   the HTTP cover fetches — will require the `NEARBY_WIFI_DEVICES` permission, granted by the user
   as "Nearby devices". It is opt-in during Android 16, so nothing is broken today and no
   permission is declared yet. Test the enforced behaviour with
   `adb shell am compat enable RESTRICT_LOCAL_NETWORK app.coilforphoniebox.debug` (then reboot).
   When enforcement lands, the permission needs declaring *and* requesting — the natural place is
   just before the mDNS scan in the add-box flow, where the reason is self-evident.

### Deviations from the plan worth knowing about

- **`shuffle` and `repeat` take an `option` string**, not MPD-style flags, and `mute` sets an
  absolute state rather than toggling. The plan and the web UI's command table both suggested
  otherwise; the plugin signatures in the upstream Python source are what the code follows. Same
  for `get_folder_content`, which returns both an absolute `path` and a `relpath` — only `relpath`
  is usable, because that is what `play_folder` expects.
- **`PlayShortcutActivity` lives in `:app`, not `:feature-shortcuts`**, so that its failure toast
  stays in the single `strings.xml`. `:feature-shortcuts` therefore has no string resources at all.
  For the same reason the media notification's text reaches `:feature-media` through the
  `MediaNotificationTexts` interface, implemented in `:app`.
- **"Add another box" lives on the settings screen, and is always visible there.** §7.5's collapsed
  top bar — a plain indicator, not a switcher, while there is one box — is kept, but it made a second
  box unreachable: the switcher sheet holds the only other "Add box" entry, and settings offered one
  solely when *no* box existed. One box was therefore a dead end, which reads as multi-box being
  unimplemented rather than merely hidden. Settings now also carries a box picker once there are two
  or more, mirroring the switcher, since that is where boxes are configured. Adding a box
  deliberately does **not** make it active: nothing should tear down a live connection the user did
  not ask to change.
- **A single track is favouritable**, which the plan's `FOLDER | ALBUM` favourite type (§6.3, §7.2)
  does not allow. From a playing song, "save this" is genuinely ambiguous between the track and its
  folder, so both are offered by name instead of one being guessed at: a tap on the player's star
  saves the folder, a long press opens a menu with folder and track as separate entries. The cost is
  a `TRACK` type plus a `trackUrl` column (Room schema **version 2**, migrated in
  `core-data/.../db/Migrations.kt` — no destructive fallback, favourites are the one thing here that
  cannot be rebuilt from the box), a `track` variant of the `coil://play` deep link, and settings
  backup **format version 2**.
- **Long press in the library opens a context menu, it does not toggle a favourite.** The plan (§14,
  phase 2) has long press as the way to favourite something, which is undiscoverable and was the
  only way to do it. Every library row and album cell now carries a ⋮ button, and both it and a long
  press open the same three-entry menu: Play, save as favourite, and Details. The details sheet is
  built strictly from cached data — opening it must never put a request on the RPC socket the box
  shares with its card reader.
- **`LibraryFolderEntity` has one column the plan's schema does not**: `contentCachedAt`, recording
  when a folder's *own* contents were last fetched, as opposed to when the row was written by its
  parent's listing. It is what drives the "Updated 3 days ago" hint per level.
- **The connection is reference counted** (`ConnectionManager.acquire/release`) rather than tied to
  the process, which is what makes "only while Coil is open" true rather than approximate.
- **`app/lint.xml` suppresses one `ObsoleteSdkInt` warning.** Following lint's advice there (merging
  `mipmap-anydpi-v26` into `mipmap-anydpi`) makes AAPT2 stop resolving the launcher icon.

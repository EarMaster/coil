# Phoniebox v3 — protocol reference

Distilled from the source of `MiczFlor/RPi-Jukebox-RFID`, branch `future3/main`, with the
differences on `future3/develop` called out where they exist — see "More than one player backend".
Reviewed August 2026. **Everything here should be verified against a running box** before architecture is built on it — see `tools/probe_phoniebox.py`.

---

## Endpoints

| Channel | Pattern | TCP | WebSocket |
|---|---|---|---|
| RPC (commands) | REQ/REP | 5555 | 5556 |
| Publishing (status) | PUB/SUB | 5558 | 5557 |

Both bind to `tcp://*`, so both are reachable across the local network.

**Use the TCP ports on the JVM and Android.** JeroMQ does not support the `ws://` transport.

---

## RPC

### Request

```json
{
  "id": "<uuid4>",
  "package": "player",
  "plugin": "ctrl",
  "method": "play_folder",
  "args": [],
  "kwargs": { "folder": "Audiobooks/Bibi" },
  "as_thread": false,
  "tsp": 1722681240000000000
}
```

- `id` is optional but required to correlate responses
- `method` is optional when the plugin itself is callable
- `as_thread` runs the call on its own thread **and returns the `Thread` object instead of the
  function's result** (`jukebox/plugs.py`). It is fire-and-forget only: any call whose answer you
  need must omit it, even a slow one. Verified against a real box — sending it is what made every
  library call come back empty
- `tsp` (timestamp in nanoseconds) makes the server return its processing time

### Response

```json
{ "id": "...", "result": ... }
{ "id": "...", "error": { ... } }
```

When `tsp` was sent, the response additionally carries `total_processing_time` in milliseconds.

**A response is always sent**, even without an `id`. The client must drain every reply or its receive queue fills up.

### There is no `core` RPC package

`core.*` names are **published topics only**. Calling `core.version` over RPC answers with an
error, which is a convincing way to make a working box look unreachable. For a reachability check
use `player.ctrl.playerstatus`: it returns the status poller's cached dict without touching MPD, so
it is cheap and proves the jukebox app itself is answering.

### Socket type

The server is a **plain `zmq.REP` socket** with a blocking `recv()` loop.

A client-side `DEALER` works provided it sends the **empty delimiter frame** before the payload — the REP socket manages the envelope itself. The advantage over `REQ` is that multiple outstanding requests do not break the channel. The web UI uses `REQ` and carries a code comment noting that concurrent requests fail with it.

### Critical: the socket is shared

The same REP socket is bound to `inproc`, `tcp` and `ws` simultaneously. Internal calls from GPIO and the RFID reader run over it. Everything is processed strictly sequentially.

**Consequence:** a blocking `list_albums` delays card detection on the box. Library data must never be fetched periodically — cache it and refresh only on demand.

---

## Publishing

`SUB` socket on port 5558. Messages arrive as two frames: topic (text) and payload (JSON).

### Relevant topics

| Topic | Contents |
|---|---|
| `playerstatus` | Core status: essentially the MPD status plus song data |
| `volume.level` | Current volume |
| `core.git_state` | Identifies the installed source state; the closest thing to a version |
| `core.version` | Documented, but **not published** by `daemon.py` — subscribe to the `core.` prefix rather than this exact topic |
| `core.started_at` | Start timestamp |
| `rfid.card_id` | Most recently read card |
| `host.temperature.cpu` | CPU temperature |
| `batt_status` | Only on boxes with a battery module |

### `playerstatus` — expected fields

`state` (`play`/`pause`/`stop`), `elapsed`, `duration`, `songid`, `pos`, `playlistlength`, `file`, `artist`, `album`, `title`, `repeat`, `random`, `single`

**Parse leniently.** MPD returns some numbers as strings, and depending on content whole fields are missing — with web radio, `duration` and `album` for instance. In kotlinx.serialization use `ignoreUnknownKeys = true` plus custom converters.

### Last value cache

The publishing server retains the most recent state. When a new subscriber connects, the complete current state is resent automatically.

**No initial `playerstatus` RPC is needed on connect.** Subscribing is enough. A `resend` command exists to force the state after an uncertain reconnect.

### Publish frequency

The box polls MPD itself every 250 ms (`mpd_status_poll_interval = 0.25`) and publishes afterwards — **unconditionally**, including while stopped. The docstring in the repository claims "published only on change"; the code does not.

For a continuously running service that is roughly 345,000 messages per day. The data volume is irrelevant, the CPU time for JSON parsing is not.

**Mitigation:** compare the raw string against the previous one and discard on equality without parsing. At rest the cost drops to almost nothing.

---

## Commands

Full reference: `src/webapp/src/commands/index.js` in the Phoniebox repository.

### Player — `player.ctrl.*`

`play`, `pause`, `toggle`, `prev`, `next`, `seek`, `shuffle(option)`, `repeat(option)`, `playerstatus`

### Content — `player.ctrl.*`

`get_folder_content`, `list_albums`, `list_songs_by_artist_and_album(albumartist, album)`, `get_single_coverart`, `get_album_coverart`, `play_folder(folder)`, `play_album(albumartist, album)`, `play_single(song_url)`, `playlistinfo`, `update`, `update_wait`

### More than one player backend — `future3/develop` only

[#2694](https://github.com/MiczFlor/RPi-Jukebox-RFID/pull/2694) ("provider-neutral player and library
contracts", merged 2026-08-04) replaced `playermpd` with a `PlayerCoordinator` in
`src/jukebox/components/player/`, which fans the same method names out to any number of registered
backends. [#2699](https://github.com/MiczFlor/RPi-Jukebox-RFID/pull/2699) adds Spotify as a second
one; it is `enabled: false` by default, so most boxes still have exactly one.

**`player.ctrl` remains the right address on both branches.** `mpd_plugin.initialize_mpd_player`
registers with `package = plugs.loaded_as(module)`, and `loaded_as` resolves the **config alias**,
not the module path — `jukebox.default.yaml` maps `player: playermpd` on `future3/main` and
`player: player.plugin` on `future3/develop`. Do not read the module rename as an address change.

Where it does matter:

- **`list_albums` concatenates every backend's catalogue** once more than one is registered, and
  each entry gains `provider`, `content_uri`, `content_type` and `cover_url`. MPD sets all four
  itself, so this is one contract rather than a Spotify special case. A pre-#2694 box sends none of
  them, which reads as `mpd` / no URI / `album`.
- **`content_uri` is load-bearing.** `_content_backend_name(provider, content_uri)` routes on it and
  falls back to the *default* backend — MPD — when it is absent. `PlayerMPD.play_album` is `clear()`
  then `findadd` then `play()`, and `findadd` matching nothing is not an error, so an album sent
  without its URI **empties the queue and plays silence, with no error reply.**
- **Send `content_uri` and `provider` only when you have them.** On `future3/main`, `play_album` and
  `get_album_coverart` take exactly two arguments and answer an unexpected keyword with a
  `TypeError`. Such a box never returns a URI either, so there is never one to send.
- **`get_single_coverart` can answer with an absolute URL** instead of a name in the box's cover
  cache, when the backend serves its own artwork — Spotify returns `https://i.scdn.co/image/…`.
- `list_library_sources` returns `{id, label, views}` per backend, and
  `list_library_items(provider, content_types)` is the provider-aware replacement for `list_albums`
  (identical rows today; each backend's implementation delegates to the other). Neither exists
  before #2694, which makes the former a one-question capability probe for everything above.
- `provider` is a **free-form string** each backend names itself with at `register_backend`, not a
  closed set. Echo an unknown one back rather than trying to model it.

`playlistinfo` returns the **current MPD queue**, one object per track with `file`, `pos`, `id`,
`title`, `artist`, `album`, `track` and `duration`. It is a real `@plugs.tag` method on both
`future3/main` and `future3/develop`, but the web UI never calls it, so it is absent from
`src/webapp/src/commands/index.js` — do not take that file as the full surface. The queue is never
published on a topic, so this is the only way to learn it.

Do **not** use `list_all_dirs` — the repository explicitly warns about memory consumption on large libraries. Use `get_folder_content` lazily, one level at a time.

`get_folder_content(folder)` returns entries of the form
`{ type: directory|file|stream|podcast, name, path, relpath }`. **Use `relpath`**: `path` is absolute
on the box's filesystem, while `play_folder` and `play_single` expect a path relative to the music
library root.

`shuffle(option)` and `repeat(option)` take named strings, not MPD flags — `enable`/`disable` for
shuffle, `disable`/`enable_repeat`/`enable_repeat_single` for repeat.

### There is no way to play a queue position

MPD has `play <pos>`, and `playermpd` **uses it internally** — `_next_in_stopped_state` calls
`self.mpd_client.play(pos)`. It is simply never exposed over RPC. Every route was checked on
`future3/main` and `future3/develop`; all of them are closed:

| Route | Why not |
|---|---|
| `play` | `def play(self)` — no arguments, on both branches |
| `resume` | The only RPC that *does* jump (`mpd_client.seek(songpos, elapsed)`), but `songpos` is read from `current_folder_status["CURRENTSONGPOS"]`, internal state written by the box's own status poll, and **no RPC writes it** |
| `map_filename_to_playlist_pos`, `remove`, `move` | all three `raise NotImplementedError` |
| `queue_load(folder)` | body is `pass` — a stub |
| `seek(new_time)` | `mpd_client.seekcur(...)`, so time within the current song only |
| `play_single(song_url)` | `clear()` + `addid()` + `play()`: **destroys the queue**, leaving one track, so next/prev stop working and the box falls silent at its end. Never use it to reach a track inside something already playing |
| `misc.py` | no generic passthrough; nothing can invoke an arbitrary method or argument |
| `rpc_command_alias.py` | aliases only rename existing methods; no positional variant |
| MPD on 6600 | `mpd.default.conf` has `bind_to_address "localhost"` — unreachable from the LAN |
| an `.m3u` in `shared/playlists/` | no filesystem access to the box, and no `load` RPC to read one |

So Coil walks the queue with `next`/`prev` instead — see AGENTS.md, "The queue, and why skipping
into it is stepped". Sending `play` with a `pos` kwarg anyway is a **safe capability probe**: the
plugin's signature rejects it before its body runs, and `jukebox/rpc/server.py` wraps `plugs.call`
in a try/except, so the reply is
`{"error": {"message": "TypeError: play() got an unexpected keyword argument 'pos'"}}` and playback
is untouched. Note this is also why an *unknown method name* and an *unknown argument* look alike
from the outside: both come back as an error reply, not a crash.

`update` triggers the MPD database scan. `update_wait` blocks until completion and should be avoided because of the shared socket.

### Timers — `timers.*`

Four plugins: `timer_stop_player`, `timer_shutdown`, `timer_idle_shutdown`, `timer_fade_volume`.
Each takes `start(wait_seconds)`, `cancel()` and `get_state()`, and publishes its state on the topic
`timers.<plugin>` — **on change only, not once a second**. `get_state` returns
`{enabled, remaining_seconds, wait_seconds, type}`.

Coil uses **`timer_stop_player` and no other**: the two shutdown plugins would switch the box off,
which is out of scope by design (§16). See AGENTS.md "The sleep timer, and the timers Coil refuses".

`GenericTimerClass.start` **ignores the call when the timer is already running** ("Ignoring start
command" in the box's log, then it returns). Changing a running timer's duration means `cancel`
first, otherwise the old duration silently stands.

### Volume — `volume.ctrl.*`

`get_volume`, `set_volume(volume)`, `change_volume(step)`, `mute(mute)`, `get_soft_max_volume`

Despite the name, `mute` is **not** a toggle: it takes the state you want as a `mute` boolean.

`get_soft_max_volume` returns the upper limit — use it as `deviceVolumeMax` in the media session.

### Cover art

`get_single_coverart` returns a **filename** in the cover cache. The image is fetched over HTTP from the box's web server, not over ZMQ:

```
http://<host>/cover-cache/<file>
```

---

## Security

The RPC port is **unauthenticated and unencrypted**. Anyone who can reach it can also shut the box down.

- Design for LAN use only
- No port forwarding; remote access only through a VPN
- Do not expose commands beyond playback in the app (`host.shutdown` and similar stay out) — this limits the damage if a port is accidentally exposed

---

## MPD directly

Phoniebox v3 uses MPD as its player backend. Port 6600 is bound to `localhost` in `mpd.default.conf`.

Direct MPD access bypasses the jukebox logic entirely — volume plugin, card handling, per-folder resume behaviour. Conceivable only as an emergency fallback, not as an architecture.

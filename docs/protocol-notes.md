# Phoniebox v3 — protocol reference

Distilled from the source of `MiczFlor/RPi-Jukebox-RFID`, branch `future3/main`.
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
- `as_thread` runs the call on its own thread — use for potentially slow calls
- `tsp` (timestamp in nanoseconds) makes the server return its processing time

### Response

```json
{ "id": "...", "result": ... }
{ "id": "...", "error": { ... } }
```

When `tsp` was sent, the response additionally carries `total_processing_time` in milliseconds.

**A response is always sent**, even without an `id`. The client must drain every reply or its receive queue fills up.

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
| `core.version` | Version identifier, usable as a connection check |
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

`get_folder_content`, `list_albums`, `list_songs_by_artist_and_album(albumartist, album)`, `get_single_coverart`, `get_album_coverart`, `play_folder(folder)`, `play_album(albumartist, album)`, `play_single(song_url)`, `update`, `update_wait`

Do **not** use `list_all_dirs` — the repository explicitly warns about memory consumption on large libraries. Use `get_folder_content` lazily, one level at a time.

`update` triggers the MPD database scan. `update_wait` blocks until completion and should be avoided because of the shared socket.

### Volume — `volume.ctrl.*`

`get_volume`, `set_volume(volume)`, `change_volume(step)`, `mute`, `get_soft_max_volume`

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

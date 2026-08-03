# AGENTS.md

Source of truth for working in this repository. `CLAUDE.md` points here; keep this file
authoritative and update `CLAUDE.md` only if the pointer itself needs to change.

## What this repo is

Coil is a planned native Android remote control for [Phoniebox](https://github.com/MiczFlor/RPi-Jukebox-RFID)
v3 (`future3/main`), talking to the box over its ZeroMQ RPC/PubSub interface on the local
network. See `README.md` for the user-facing pitch and feature list.

**Current state: pre-implementation.** There is no `:app` module and no root Gradle build yet.
The repo currently holds: the full design/architecture spec, a standalone JVM transport spike,
a Python protocol probe, brand assets, and a static HTML mockup. Treat `docs/implementation-plan.md`
as the detailed spec for anything not covered below — this file is a condensed map of it, not a
replacement.

## Repo layout

| Path | Contents |
|---|---|
| `docs/implementation-plan.md` | Full architecture/design spec — tech stack, module layout, transport design, data model, media session, multi-box design, branding, i18n rules, phased build plan. **Read before implementing anything non-trivial.** |
| `docs/protocol-notes.md` | Condensed Phoniebox v3 ZMQ protocol reference, distilled from the upstream Python source. |
| `docs/pages/` | The GitHub Pages site (Jekyll), deployed by `pages.yml` — landing page and privacy policy, served at `coilforphoniebox.app` via the `CNAME` file in this folder. Kept separate from the planning docs above so the Pages *site* only contains user-facing content — the planning docs are still public in the repo, just not part of the deployed website. |
| `spike/` | Standalone Gradle/Kotlin JVM project validating the transport approach (JeroMQ DEALER client) against a real box, independent of Android. |
| `tools/probe_phoniebox.py` | Python/pyzmq script that does the same validation from the other side (REQ vs DEALER framing, pipelining, PubSub schema dump) — used to sanity-check protocol assumptions against a live box before trusting them in Kotlin. |
| `android/` | Reference bundle for theme (`theme/Color.kt`, `theme/Theme.kt`) and adaptive icon XML (`res/`) to be dropped into the real `:app` module once it exists. Not a buildable module on its own (no manifest, no build file). |
| `brand/` | Logo mark SVG. |
| `mockup/` | Static HTML UI mockup (`coil-mockup.html`) — visual reference only, not implementation. |
| `CHANGELOG.md` | [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)-format history. **Update the `## [Unreleased]` section as part of any user-facing change** — new feature, fix, or behaviour change. `release.yml` extracts release notes straight from the `## [x.y.z]` heading matching `versionName`, so headings must stay exact and the `Unreleased` section shouldn't go stale. |

## CI/CD

`.github/workflows/` mirrors the setup from a sibling project, adapted for Coil ahead of time:

- `ci.yml` / `codeql.yml` — build/test/lint and CodeQL analysis on PRs to `main`. Both start with a
  `detect` job that checks for a root `./gradlew` and skip (not fail) everything else until it
  exists — these are inert no-ops until Phase 1 (module skeleton) lands.
- `release.yml` — tags `main` from `app/build.gradle.kts`'s `versionName`, builds a signed
  APK/AAB, and cuts a GitHub Release with notes pulled from `CHANGELOG.md`. Gated the same way:
  a no-op until `app/build.gradle.kts` exists. Needs `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD` repo secrets before it can actually sign a build. Release notes come
  from `CHANGELOG.md`'s `## [x.y.z]` heading matching `versionName` — see that file's own note.
- `google-play.yml` — reusable workflow (`workflow_call`/manual dispatch) that uploads a tagged
  release's AAB to Google Play, package `app.coilforphoniebox`. Needs a `SERVICE_ACCOUNT_JSON`
  repo secret and an active Play Console listing.
- `pages.yml` — deploys `docs/pages/` via Jekyll to GitHub Pages on push to `main` (path-filtered
  to `docs/pages/**`). GitHub Pages must be enabled in repo settings with source "GitHub Actions",
  and the `coilforphoniebox.app` DNS must point at GitHub Pages, for the `CNAME` file in
  `docs/pages/` to actually resolve.

## Commands

There is no root build yet, so the README's `./gradlew assembleDebug` is aspirational until
Phase 1 (module skeleton) lands — see the phase plan in `docs/implementation-plan.md` §14.

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
- Never call `list_all_dirs` (unbounded memory on large libraries) — use `get_folder_content` one
  level at a time instead.
- The RPC port is unauthenticated and unencrypted. Design is LAN-only; never add or expose
  commands beyond playback (no `host.shutdown` etc.) so an accidentally exposed port can't take
  the box down.

## Architecture decisions to preserve

These are settled decisions from `docs/implementation-plan.md`, not open questions:

- **Stack:** Kotlin + Jetpack Compose, MVVM/unidirectional state flow, JeroMQ for transport,
  androidx.media3 (`SimpleBasePlayer`) for the media session, kotlinx.serialization, Hilt, Room +
  DataStore for persistence. Planned module split: `:app`, `:core-transport`, `:core-domain`,
  `:core-data`, `:feature-media`, `:feature-shortcuts`.
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
  regardless of theme (see `android/theme/Color.kt` for the token set).

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

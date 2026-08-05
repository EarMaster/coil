# Changelog

All notable changes to Coil are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning
follows [Semantic Versioning](https://semver.org/). Release notes on GitHub Releases are extracted
automatically from the `## [x.y.z]` heading matching `versionName` in `app/build.gradle.kts` — see
`.github/workflows/release.yml`. Keep headings exact for that to keep working.

## [Unreleased]

## [0.9.0] - 2026-08-05

### Added
- **Media controls can be switched off entirely.** "Show controls" had two settings, both of which
  put a notification up sooner or later; it now has three, with "Never" as the third. In that mode
  no media session is created at all — no notification, no lock screen controls, no hardware volume
  buttons reaching the box — and Coil controls the box from its own screens only. Choosing it while
  the box is playing takes the notification down there and then rather than at the next restart
- **A link that just opens Coil**, `coil://open`, alongside the existing link that starts a
  favourite — optionally naming a box, so it opens showing that one. Box management hands the link
  out per box under "Copy link to this box". It sends the box nothing and never starts playback

### Changed
- **Boxes are managed on their own screen**, reached from "Manage boxes" in settings, instead of
  being a stretch of the settings list where picking a box, adding a box and editing the current
  box sat next to each other and looked alike. Each box now has its own page: name, address and
  ports, its `coil://open` link, and removing it. A box that is not the active one can be renamed
  or re-addressed without switching to it first, which was previously impossible. Switching boxes
  stays where it was, in the name at the top of the screen; settings keeps the library actions,
  which act on the box Coil is controlling now

### Fixed
- **Cover art never loaded on a real box.** A Phoniebox serves artwork over plain HTTP, and Android
  blocks cleartext HTTP by default, so every cover request was refused before it left the phone.
  Nothing showed it: a refused request draws the same placeholder as a song with no artwork, and the
  ZMQ sockets are raw TCP and so were never affected — which is why everything except covers worked

## [0.8.0] - 2026-08-05

### Added
- **Player**: cover art, title and album, progress with seek, transport controls, volume slider,
  shuffle and repeat, and a star that saves the folder that is playing — with a long press to
  choose explicitly between saving the folder and saving just the track
- **Library browsing** by folder and by album, with folders loaded one level at a time, album covers
  fetched as they come into view, pull-to-refresh per level and a "last updated" hint. The back
  gesture goes up one folder, and leaves the library only from the top level
- **Sleep timer**: stops playback after 15 minutes to 2 hours and leaves the box switched on. The
  box's own shutdown and idle-shutdown timers are deliberately not offered — Coil never switches the
  box off. Shuffle, repeat and the timer now share one "playback options" menu on the player instead
  of competing with the transport controls, and a running timer shows the time left
- **Library search**: a field above the tabs finds folders, albums and tracks in one list,
  ignoring case and accents — "bar" finds "Bär". It searches what Coil has loaded from the box,
  since the Phoniebox interface has no search of its own; settings can walk the whole library once
  to make all of it searchable
- **A menu on every library item** — reachable from its ⋮ button or by a long press — offering
  Play, saving it as a favourite, and a details panel with what Coil knows about it: path, artist,
  album, track number, length, file name and when it was last read from the box
- **Lock screen and notification controls** that appear on their own when the box starts playing,
  including control of the box from the phone's hardware volume buttons; switchable between "only
  while Coil is open" and "whenever the box plays"
- **Favourites** for a folder, an album or a single track, with launcher shortcuts — up to four
  dynamic ones plus pinned ones using cover art as their icon, each starting its own box with a
  single command and without opening the app
- **A link for any favourite**, from its menu: copy or share it and anything that opens links —
  an automation app, an NFC tag, a note — starts that favourite on the box it belongs to, whichever
  box Coil is currently showing
- **Multiple boxes**: "Add another box" in settings, a switcher in the top bar showing each box's
  reachability, an add flow with an mDNS scan and a connection test, a box picker and per-box
  settings on the settings screen, and a collapsed top bar while only one box is configured.
  Switching box returns the library to its root, since folders belong to the box they came from
- **Settings**: theme, wallpaper colours, language shortcut, per-box address and rescan, background
  mode with a plain-language note about its limits, and settings export and import
- **Five locales**: English as the source, plus German, French, Spanish and Dutch. The four
  translations are unreviewed drafts and are marked as such in their files
- **A tablet layout for the player**: on a screen wider than it is tall, the cover sits beside the
  title and controls instead of above them, so the transport controls stay in view on a family
  tablet and on a phone held sideways. The rest of the app is unchanged
- Project scaffolding: architecture and protocol documentation (`docs/`), brand assets (`brand/`),
  Android theme reference bundle (`android/`), static UI mockup (`mockup/`)

### Changed
- Targets Android 16 (API 36), which the Play Store requires for new apps from 31 August 2026.
  Supported devices are unchanged: Android 8.0 and newer
- The volume slider now sends one command when it settles instead of one per frame of the drag,
  so dragging it is smooth and does not flood the box

### Fixed
- Folder and album views stayed empty, and cover art never loaded: the requests were asking the
  box to run them in a background thread, which throws the answer away
- The player stopped updating — title, album and progress frozen while playback carried on —
  because the whole player display waited on the cover art lookup before showing anything
- Cover art never appeared even once it was fetchable: the box answers the first request for a
  song by *starting* the artwork extraction, and Coil mistook that answer for a file name
- "Test connection" failed against a perfectly reachable box, because it asked for a version
  over a channel that only publishes it
- The album list is now fetched when the tab is first opened, instead of only when the box
  happened to be idle at app start
- Transport validation: standalone JeroMQ spike (`spike/`) and Python protocol probe
  (`tools/probe_phoniebox.py`) against a live Phoniebox v3 box
- CI/CD scaffolding: build/test/lint, CodeQL, release, Google Play deploy, and GitHub Pages
  workflows (`.github/workflows/`), gated to no-op until the `:app` module exists

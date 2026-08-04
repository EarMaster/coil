# Changelog

All notable changes to Coil are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versioning
follows [Semantic Versioning](https://semver.org/). Release notes on GitHub Releases are extracted
automatically from the `## [x.y.z]` heading matching `versionName` in `app/build.gradle.kts` — see
`.github/workflows/release.yml`. Keep headings exact for that to keep working.

## [Unreleased]

### Added
- **Player**: cover art, title and album, progress with seek, transport controls, volume slider,
  shuffle and repeat, and a star that saves the folder that is playing — with a long press to
  choose explicitly between saving the folder and saving just the track
- **Library browsing** by folder and by album, with folders loaded one level at a time, album covers
  fetched as they come into view, pull-to-refresh per level and a "last updated" hint
- **A menu on every library item** — reachable from its ⋮ button or by a long press — offering
  Play, saving it as a favourite, and a details panel with what Coil knows about it: path, artist,
  album, track number, length, file name and when it was last read from the box
- **Lock screen and notification controls** that appear on their own when the box starts playing,
  including control of the box from the phone's hardware volume buttons; switchable between "only
  while Coil is open" and "whenever the box plays"
- **Favourites** for a folder, an album or a single track, with launcher shortcuts — up to four
  dynamic ones plus pinned ones using cover art as their icon, each starting its own box with a
  single command and without opening the app
- **Multiple boxes**: a switcher in the top bar showing each box's reachability, an add flow with an
  mDNS scan and a connection test, per-box settings, and a collapsed presentation when only one box
  is configured
- **Settings**: theme, wallpaper colours, language shortcut, per-box address and rescan, background
  mode with a plain-language note about its limits, and settings export and import
- **Five locales**: English as the source, plus German, French, Spanish and Dutch. The four
  translations are unreviewed drafts and are marked as such in their files
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

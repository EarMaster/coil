<div align="center">

<img src="brand/coil-mark.svg" width="88" alt="">

# Coil

**A native Android remote for [Phoniebox](https://github.com/MiczFlor/RPi-Jukebox-RFID).**

[coilforphoniebox.app](https://coilforphoniebox.app)

</div>

---

Phoniebox turns a Raspberry Pi into an RFID jukebox: put a card on the box, audio plays. It comes with a web UI that works well on a desktop and awkwardly on a phone.

Coil is a proper Android app for the same job. Open it, see what is playing, pick something else, hand the phone back. When the box starts playing, playback controls appear on your lock screen — no app launch needed.

<!--
Screenshots go here once captured. Suggested layout:

<div align="center">
  <img src="docs/screenshots/player.png" width="200" alt="Player">
  <img src="docs/screenshots/library.png" width="200" alt="Library">
  <img src="docs/screenshots/favourites.png" width="200" alt="Favourites">
  <img src="docs/screenshots/lockscreen.png" width="200" alt="Lock screen">
</div>
-->

## Features

- **Player** with cover art, scrubbing, transport controls and volume
- **Library browsing** by folder or album, play anything you find
- **Lock screen and notification controls** that appear on their own when the box starts playing, and can be switched off entirely
- **Favourites** for a folder, an album or a single track, with home screen shortcuts, so one tap starts an audiobook without opening the app
- **Multiple boxes** — one per room, switched from the top bar, each with its own favourites
- **Hardware volume buttons** control the box while the media session is active
- **Dark mode** built for bedtime rather than bolted on
- **English, German, French, Spanish and Dutch**

Coil keeps everything on your device and your network. No account, no telemetry, no cloud.

## What Coil does not do

Card management, system settings and shutdown stay in the Phoniebox web UI. Coil covers the thing you do twenty times a day — putting something on — and deliberately leaves administration alone. Beyond keeping the app focused, it means Coil never sends a command that could take the box down.

## Requirements

- A Phoniebox running **v3** (`future3/main`). Earlier versions are not supported
- The box and the phone on the **same local network**
- Android 8.0 or newer

Coil talks to the box over its ZeroMQ interface. That interface is unauthenticated and unencrypted, so Coil is built for local network use only. Don't forward those ports — use a VPN if you need access from outside the house.

## Installing

Available on the **Play Store**, or as a signed APK under [Releases](../../releases).

> **Pick one and stay with it.** Play Store builds and GitHub APKs carry different signatures, which Android treats as different apps. Moving between them means uninstalling first, which loses your configuration. Use the settings export before you switch.

## Contributing

Issues and pull requests are welcome. For anything substantial, please open an issue first — direction is still worth discussing.

**Translations** go in `res/values-<locale>/strings.xml` as pull requests. English is the source language. Missing strings fall back to English, so a partial translation is safe to merge and a language can grow over several pull requests. Please don't submit unreviewed machine translation: a rough draft that a fluent speaker then corrects is genuinely useful, but a locale nobody has read is worse than none, because it looks finished.

**Testing against your own box** is the most useful thing you can offer. Different Phoniebox versions, library sizes and hardware turn up problems that no amount of reading the source will.

Project language is English — code, comments, commit messages and documentation.

## Building from source

```bash
git clone https://github.com/<org>/coil.git
cd coil
./gradlew assembleDebug
```

Requires JDK 17 and Android SDK 36, plus a `local.properties` with `sdk.dir` pointing at your SDK
(escape a Windows drive letter as `sdk.dir=C\:/Android/Sdk`). No API keys, no proprietary
dependencies, no extra setup.

Architecture and protocol notes are in [`docs/`](docs/) — worth a look before touching the transport layer, since the Phoniebox RPC socket has some sharp edges.

## Relationship to Phoniebox

Coil is an independent third-party client. It is **not affiliated with or endorsed by** the Phoniebox project. Phoniebox is the work of [MiczFlor and contributors](https://github.com/MiczFlor/RPi-Jukebox-RFID); questions about the box itself belong there.

## License

See [`LICENSE`](LICENSE).

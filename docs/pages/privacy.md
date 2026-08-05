---
layout: page
title: Privacy Policy
permalink: /privacy/
include_in_header: true
include_in_footer: true
---

# Privacy Policy for Coil

**Last updated: 2026-08-04**

Coil is a remote control app for [Phoniebox](https://github.com/MiczFlor/RPi-Jukebox-RFID), a
Raspberry Pi based RFID jukebox. This policy explains what the app does and does not do with
your data.

## Short version

**Coil does not collect, store, or transmit any personal data.** There is no account, no
analytics, no advertising, no crash reporting, and no server operated by us that the app talks
to. Everything the app does happens on your phone and on your own local network, between your
phone and your own Phoniebox device.

## What Coil stores, and where

All of the following is stored **only on your device** and is never sent to us or to any third
party:

- **Box configuration** — the hostname/IP address, ports, and display name of each Phoniebox you
  add
- **App settings** — theme, language, and other preferences
- **Library cache and favourites** — folder and album listings read from your Phoniebox, and any
  favourites you create, so the app works offline and starts quickly

This data lives in the app's local storage. Uninstalling the app deletes it. If you use the
settings export feature, the exported file is created and saved by you, on your device — we never
see it.

## What Coil communicates over the network

Coil talks **only** to the Phoniebox device(s) you configure, over your local network:

- Playback commands and status, over the box's ZeroMQ interface (unencrypted, by design of the
  Phoniebox protocol — this is why Coil is built for local network use only, and why we recommend
  against exposing those ports to the internet)
- Cover art images, fetched directly from your Phoniebox's own web server

Coil does not contact any server operated by us, and does not contact any third-party service.
There is nothing to opt out of, because nothing is sent anywhere beyond your own network.

## Permissions

Coil requests a small number of Android permissions, each used only for the stated purpose:

- **Notifications** — to show playback controls in a media notification and on the lock screen
- **Foreground service (media playback)** — to keep the connection to your Phoniebox alive while
  something is playing, so playback controls stay available
- **Local network access** — to connect to your Phoniebox and, optionally, to discover boxes on
  your network automatically

None of these permissions are used to collect data about you.

## Third parties

Coil has no third-party SDKs, no advertising network, and no analytics provider. We do not sell,
rent, or share any data, because we do not collect any.

## Children's privacy

Coil does not knowingly collect personal information from anyone, including children, because it
does not collect personal information from anyone.

## Changes to this policy

If this policy changes, the updated version will be published at this same address with a new
"last updated" date.

## Contact

Questions about this policy or the app can be sent to **coil@wiedemann.rocks**.

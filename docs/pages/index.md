---
layout: default
permalink: /
---

## What Coil does not do

Card management, system settings and shutdown stay in the Phoniebox web UI. Coil covers the
thing you do twenty times a day — putting something on — and deliberately leaves administration
alone. Beyond keeping the app focused, it means Coil never sends a command that could take the
box down.

That applies to timers too. The box can shut itself down on a timer, or after being idle for a
while; Coil offers neither. Its sleep timer is the one that stops the player and leaves the box
on.

## Requirements

- A Phoniebox running **v3** (`future3/main`). Earlier versions are not supported
- The box and the phone on the **same local network**
- Android 8.0 or newer

Coil talks to the box over its ZeroMQ interface. That interface is unauthenticated and
unencrypted, so Coil is built for local network use only. Don't forward those ports — use a VPN
if you need access from outside the house.

## Open source

Coil is free software under the [GPL-3.0](https://github.com/EarMaster/coil/blob/main/LICENSE),
and the [source is on GitHub](https://github.com/EarMaster/coil). Issues, pull requests and
translations are welcome — for anything substantial, please open an issue first, since direction
is still worth discussing.

Testing against your own box is the most useful thing you can offer. Different Phoniebox
versions, library sizes and hardware turn up problems that no amount of reading the source will.

## Relationship to Phoniebox

Coil is an independent third-party client. It is **not affiliated with or endorsed by** the
Phoniebox project. Phoniebox is the work of
[MiczFlor and contributors](https://github.com/MiczFlor/RPi-Jukebox-RFID); questions about the
box itself belong there.

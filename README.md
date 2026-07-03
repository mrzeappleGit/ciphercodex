# CipherCodex

A book-reading system in the Cipher design language, in two halves that stay in sync:

| | |
|---|---|
| `android/` | **CipherCodex** — Android EPUB reader (Kotlin, Jetpack Compose, Material 3) |
| `x4-os/` | **CipherCodex OS** — custom firmware for the XTEINK X4 e-reader (ESP32-C3), forked from [CrossPoint Reader](https://github.com/crosspoint-reader/crosspoint-reader) (MIT) |

Both speak the **KOReader sync (kosync) protocol**, so reading position follows you between the phone and the e-ink device — and interoperates with any KOReader install or kosync server, including the default `sync.koreader.rocks` or a self-hosted [koreader-sync-server](https://github.com/koreader/koreader-sync-server).

## Design

The Cipher system (see `ciphercodex-design-brief.md`): void black `#0A0A0F`, cyan `#00E5FF` / magenta `#FF2A93` accents, Orbitron display type, Share Tech Mono UI text, angular cut-corner geometry. The reading surface is deliberately exempt — no neon, Literata serif, Night (`#121212`) and Sepia (`#F4ECD8`) themes. On the X4's monochrome e-ink the brand translates to the "Cipher" theme: solid black header bands, the five-bar mark, square geometry, full-inversion selection.

## Sync architecture

- **Document identity**: KOReader's partial-MD5 ("binary" matching method) — both clients compute identical digests for the same EPUB file, so a book imported on the phone and copied to the X4's SD card is the *same* book to the server.
- **Position**: the Android app pushes `ciphercodex:s=<spineIndex>;o=<charOffset>` plus a 0–1 percentage; the X4 pushes KOReader-style xpaths plus percentage. Each side decodes the other's chapter index and falls back to percentage — and any real KOReader device still syncs at percentage precision.
- **Policy**: pull on book open (with a jump/stay prompt when another device is ahead), push on close/pause.

## Android app

```
cd android
.\gradlew.bat assembleDebug     # or open in Android Studio
```
Requires JDK 17+ and the Android SDK (API 35). Import EPUBs from the library screen; configure sync under SYSTEM → SYNC (server, username, password — register or login, then enable).

## X4 firmware

```
cd x4-os
git submodule update --init --recursive
pio run -e default              # build
pio run -e default -t upload    # flash over USB-C
```
The built image is `.pio/build/default/firmware.bin`; it can also be flashed with
`esptool.py --chip esp32c3 --port <COM port> --baud 921600 write_flash 0x10000 firmware.bin`,
via the SD-card update path (place `firmware.bin` on the card → Settings → SD firmware update), or any web flasher that accepts a custom `firmware.bin`. Recovery mode: hold **UP + POWER** at boot. Note: some third-party-store X4 units ship USB-flash-locked and need unlocking first.

Configure sync on the device under Settings → KOReader sync with the same server and account as the app.

## Credits

CipherCodex OS is built on [CrossPoint Reader](https://github.com/crosspoint-reader/crosspoint-reader) by Dave Allie and contributors (MIT), on the Free-Ink SDK. The kosync protocol belongs to the [KOReader](https://github.com/koreader/koreader) project.

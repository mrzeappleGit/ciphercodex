# Hardware audit — reMarkable 2 (CipherCodex target device)

Audited 2026-07-11 over USB SSH (10.11.99.1), first on OS 3.16.2.3, re-audited after updating the
device to 3.27.3.0. Pen/touch ABS ranges get measured with `input-probe` in Phase 0.

## OS and platform

| Item | Observed |
|---|---|
| OS version | 3.27.3.0 (updated 2026-07-11 from 3.16.2.3 via stock updater) |
| Kernel | Linux 5.4.70-v1.6.3-rm11x armv7l (SMP PREEMPT) |
| SoC | Freescale i.MX7 Dual, ARMv7 rev 5 |
| RAM | 1 027 664 kB total (~847 MB available at idle under stock) |
| Rootfs | `/dev/root` ~256 MB, ~95 % full — never install here |
| User storage | `/dev/mmcblk2p4` on `/home`, ext4, 6.6 GB, 4.3 GB free (survived update) |
| Boot config | `/dev/mmcblk2p1` vfat on `/var/lib/uboot` (A/B slot switching lives here) |
| Qt on device | 6.8.2 (`/usr/lib/libQt6Core.so.6.8.2`; was 6.5.2 on OS 3.16) |
| Shell | BusyBox v1.35.0 (head/sort/etc. are BusyBox variants) |

## Display

- No kernel e-paper driver; `/sys/class/graphics/fb0` exists but the panel is driven from
  userspace (reMarkable 2 SWTCON architecture).
- **Official path**: Qt platform plugin `/usr/lib/plugins/platforms/libepaper.so`, licensed
  **LGPL-2.1** (license + `main.cpp` in `/usr/share/common-licenses/epaper-qpa/`).
- Documented invocation: stop `xochitl`, run with `-platform epaper` and
  `QT_QUICK_BACKEND=epaper`. Pure Qt Quick only (no Qt Widgets).
- Touch coordinate transform: `QT_QPA_EVDEV_TOUCHSCREEN_PARAMETERS="rotate=180:invertx"`.
- Panel: 1872 x 1404, 226 DPI, 16-level grayscale.

## Input devices (`/proc/bus/input/devices`)

| Device | Node | Details |
|---|---|---|
| Power button | `event0` | `30370000.snvs:snvs-powerkey` |
| Marker (pen) | `event1` | Wacom I2C Digitizer, Bus 0018, Vendor 056a. ABS bitmask `f000003` = X, Y, PRESSURE, DISTANCE, TILT_X, TILT_Y. KEY bitmask `1c03` includes pen/rubber/stylus tools. |
| Touch | `event2` | `pt_mt`, multitouch (ABS_MT_* present) |

Exact min/max/resolution for pressure, tilt, X/Y: to be measured with
`input-probe /dev/input/event1 10` on device (Phase 0). Do not assume 4096 pressure levels —
measure.

## Power / battery

- `/sys/class/power_supply/max77818_battery` — `type=Battery`, `capacity` (percent), `status`
  (Charging/Discharging/Full). Verified live: capacity=68, status=Charging.
- `/sys/class/power_supply/max77818-charger` — `type=USB`, `status` shows charger presence.

## Network / services

- USB CDC ethernet gadget: device is `10.11.99.1`, host `10.11.99.2`; dropbear SSH, root login.
- SSH key auth installed (2026-07-11); root password rotates on OS update but keys persist in
  `/home/root/.ssh/authorized_keys`.
- Stock running services: xochitl, swupdate (+ "fake update engine" stub), rm-sync, memfaultd,
  crashuploader, chronyd, collectd, wpa_supplicant, systemd-networkd/resolved/udevd/logind, dbus,
  getty x2.

## Update / recovery

- OS updates via `swupdate` with A/B rootfs slots; u-boot env on `/var/lib/uboot`.
- Recovery: stock UI restored by `systemctl start xochitl` (our shell never touches the rootfs);
  worst case, reMarkable's official recovery via USB (device can always be re-imaged from the
  other slot).
- Full `/home` backup: `device-backups/rm2-home-2026-07-11.tar` (2.03 GB, verified, on host PC).

## SDK / toolchain

- Official per-version SDKs (x86_64 Linux self-extracting, Qt + sysroot + cross gcc):
  `https://storage.googleapis.com/remarkable-codex-toolchain/<os-version>/rm2/...`
- Anonymous bucket listing is denied; exact URLs come from https://developer.remarkable.com/links.
- No SDK published for 3.27.3.0 (probed bucket directly: 404). Pinned **3.27.0.97 /
  remarkable-production-image-5.7.119** — same 3.27 line, Qt 6.8. Recorded deviation: SDK is
  three patch releases behind the device OS.

## Update / host-key note

- The OS update regenerated the device's dropbear host keys — expect an SSH
  "REMOTE HOST IDENTIFICATION HAS CHANGED" warning after every update; fix with
  `ssh-keygen -R 10.11.99.1`. Key-based root auth persists (`/home` survives updates).

## Measured input ranges (input-probe on device, OS 3.27.3.0)

Pen (`event1`, Wacom I2C):

| Axis | Range | Notes |
|---|---|---|
| ABS_X | 0–20966, res 100 pts/mm | runs along the panel's long (portrait-vertical) axis |
| ABS_Y | 0–15725, res 100 pts/mm | runs along the short axis |
| ABS_PRESSURE | 0–4095 | 4,096 levels confirmed |
| ABS_DISTANCE | 0–255 | hover height — proximity/palm-rejection signal |
| ABS_TILT_X/Y | −9000–9000 | |

Tool keys: `BTN_TOOL_PEN`, `BTN_TOOL_RUBBER` (Marker Plus eraser) arrive on proximity.

Touch (`event2`, pt_mt): 32 slots, `ABS_MT_POSITION` 1404×1872 (matches panel portrait),
`ABS_MT_PRESSURE` 0–255, plus touch major/minor/orientation.

**Pen→screen transform (verified on device, `PenReader` calib=1):**
`screen_x = ABS_Y / 15725 × width`, `screen_y = (1 − ABS_X / 20966) × height` (portrait).

## Display/render constraints learned in Phase 0

- The epaper QPA delivers **touch but not stylus** events to Qt — pen must be read directly
  from evdev (SDK docs say the same; confirmed on device).
- Touch transform: `QT_QPA_EVDEV_TOUCHSCREEN_PARAMETERS=inverty` (verified with an on-screen
  touch-position probe; the SDK-documented `rotate=180:invertx` mirrors X on this device/OS).
- `libqsgepaper.so` (scenegraph backend, **CLOSED license** — run on top of it, never link it)
  contains the userspace SWTCON driver + waveform tables and renders only its own node types:
  custom `QSGGeometryNode`/materials draw **nothing**; text, rectangles, and image nodes work.
  Ink therefore renders via `QQuickPaintedItem` (image node) with per-segment dirty rects.
- Backend auto-selects waveforms by scanning content (`epimageutils::scanForContentType`).

## Open items

- [x] Re-audit after OS update — done 2026-07-11 (3.27.3.0, Qt 6.8.2, epaper plugin present, LGPL)
- [x] Battery sysfs path and fields
- [x] Measure pen ABS ranges + eraser/tool events
- [x] Measure touch ABS ranges
- [x] Pen coordinate transform calibrated (calib=1)
- [x] Touch transform calibrated (inverty)
- [x] Pen-to-ink latency: perceived parity with stock xochitl (owner test, hello v3 painted-item
      path). Quantified glass-to-ink measurement still to be scripted.
- [x] Pressure→width verified visually (squared curve)
- [ ] Marker Plus eraser: owner's Marker has no eraser end — BTN_TOOL_RUBBER handling implemented
      but untested on hardware; retest when a Marker Plus is available
- [ ] Suspend/resume behavior (power button, `systemctl suspend`)

# Hardware audit — reMarkable 2 (CipherCodex target device)

Audited 2026-07-11 over USB SSH (10.11.99.1). Items marked **[re-verify]** must be re-checked
after the pending OS update; pen/touch ranges get measured with `input-probe` in Phase 0.

## OS and platform

| Item | Observed |
|---|---|
| OS version | 3.16.2.3 **[re-verify — update in progress]** |
| Kernel | Linux 5.4.70-v1.5.1-rm11x armv7l (SMP PREEMPT) |
| SoC | Freescale i.MX7 Dual, ARMv7 rev 5 |
| RAM | 994 900 kB total (~807 MB available at idle under stock) |
| Rootfs | `/dev/root` 255.7 MB, **95 % full** — never install here |
| User storage | `/dev/mmcblk2p4` on `/home`, ext4, 6.6 GB, 4.3 GB free |
| Boot config | `/dev/mmcblk2p1` vfat on `/var/lib/uboot` (A/B slot switching lives here) |
| Qt on device | 6.5.2 (`/usr/lib/libQt6*.so.6.5.2`) **[re-verify]** |
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

- `/sys/class/power_supply/` enumeration returned empty over non-interactive BusyBox shell
  **[re-verify with input-probe deploy session — likely max77818 battery]**.

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

- Official per-version SDKs (x86_64 Linux self-extracting, Qt 6.5 + sysroot + cross gcc):
  `https://storage.googleapis.com/remarkable-codex-toolchain/<os-version>/rm2/...`
- No SDK exists for 3.16.x → device being updated to latest (3.27.0.97 listed as newest rm2).
- Anonymous bucket listing is denied; exact URLs come from https://developer.remarkable.com/links.

## Open items

- [ ] Re-audit after OS update (version, Qt, epaper plugin still present + LGPL)
- [ ] Measure pen ABS ranges + eraser/tool events with input-probe
- [ ] Measure touch ABS ranges
- [ ] Battery sysfs path and fields
- [ ] Suspend/resume behavior (power button, `systemctl suspend`)
- [ ] Pen-to-ink latency through epaper QPA vs stock xochitl

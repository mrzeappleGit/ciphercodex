#!/bin/sh
# Runs ON the device. Detached from any ssh session: xochitl always comes back
# when the shell exits, even if the host disconnects mid-session.
systemctl stop xochitl
# CCX_TOUCH may be legitimately empty (= no transform); only default when unset.
# inverty verified on device 2026-07-11 (the SDK-documented rotate=180:invertx is wrong here).
export QT_QPA_EVDEV_TOUCHSCREEN_PARAMETERS="${CCX_TOUCH-inverty}"
export QT_QUICK_BACKEND=epaper
cd /home/root/ciphercodex
./ciphercodex-shell -platform epaper > shell.log 2>&1
systemctl start xochitl

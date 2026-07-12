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
echo "$(date) exit=$?" >> shell-exits.log
# frequent dev stop/start cycles trip xochitl's 4-per-10min start limit; without this
# systemd refuses the restart and the OS recovery reboots the whole device
systemctl reset-failed xochitl 2>/dev/null
systemctl start xochitl

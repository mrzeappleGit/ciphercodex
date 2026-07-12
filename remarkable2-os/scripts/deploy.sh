#!/usr/bin/env bash
# Deploy Phase 0 hello screen to the USB-connected device and run it once.
# Reversible: xochitl restarts when the app exits (EXIT button) or via restore-stock.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
DEV=${DEV:-root@10.11.99.1}

ssh "$DEV" "mkdir -p /home/root/ciphercodex"
scp build-rm2/ciphercodex-shell build-rm2/input-probe "$DEV:/home/root/ciphercodex/"

ssh -t "$DEV" '
    systemctl stop xochitl
    export QT_QPA_EVDEV_TOUCHSCREEN_PARAMETERS="rotate=180:invertx"
    export QT_QUICK_BACKEND=epaper
    cd /home/root/ciphercodex
    ./ciphercodex-shell -platform epaper; rc=$?
    systemctl start xochitl
    exit $rc
'

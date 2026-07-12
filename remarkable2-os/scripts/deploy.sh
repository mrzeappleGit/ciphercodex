#!/usr/bin/env bash
# Deploy Phase 0 hello screen to the USB-connected device and run it once.
# Reversible: xochitl restarts when the app exits (EXIT button) or via restore-stock.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
DEV=${DEV:-root@10.11.99.1}

ssh "$DEV" "mkdir -p /home/root/ciphercodex; kill \$(pidof ciphercodex-shell) 2>/dev/null; true"
scp build-rm2/ciphercodex-shell build-rm2/input-probe scripts/run-on-device.sh "$DEV:/home/root/ciphercodex/"

# Detached: xochitl restarts when the shell exits even if this ssh session dies.
ssh "$DEV" "chmod +x /home/root/ciphercodex/*; nohup setsid /home/root/ciphercodex/run-on-device.sh >/dev/null 2>&1 & sleep 3; pidof ciphercodex-shell >/dev/null && echo shell running"

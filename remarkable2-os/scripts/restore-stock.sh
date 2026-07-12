#!/usr/bin/env bash
# Restore the stock UI no matter what state the shell is in.
set -euo pipefail
DEV=${DEV:-root@10.11.99.1}
ssh "$DEV" '
    pkill -f ciphercodex-shell 2>/dev/null || true
    systemctl start xochitl
    systemctl status xochitl --no-pager | head -3
'

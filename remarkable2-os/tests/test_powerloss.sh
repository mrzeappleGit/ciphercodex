#!/usr/bin/env bash
# Kill-atomicity test: child appends strokes (one committed tx each), parent
# SIGKILLs it at a random moment, then the reopened DB must pass
# integrity_check and contain every stroke id the child printed (printed
# only after its commit returned). Repeats until RUNS runs had real data.
# NOTE: SIGKILL leaves the kernel page cache intact — this proves transaction
# atomicity, NOT hard power-cut durability. The synchronous=FULL guarantee is
# enforced structurally (Storage::open refuses weaker pragmas) and the real
# power-cut test is the on-device Phase 1 gate: pull power mid-writing.
set -euo pipefail
BIN=${1:?usage: test_powerloss.sh <path-to-test_storage-binary> [runs]}
RUNS=${2:-5}

ok=0
attempts=0
while [ "$ok" -lt "$RUNS" ]; do
    attempts=$((attempts + 1))
    if [ "$attempts" -gt $((RUNS * 4)) ]; then
        echo "FAIL: child kept dying before committing anything" >&2
        exit 1
    fi
    DIR=$(mktemp -d)
    "$BIN" --powerloss-child "$DIR" > "$DIR/ids.txt" &
    PID=$!
    sleep "0.$((RANDOM % 50 + 10))"   # kill 0.10-0.59 s in
    kill -9 "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
    if [ -s "$DIR/ids.txt" ]; then
        "$BIN" --powerloss-verify "$DIR" "$DIR/ids.txt"
        ok=$((ok + 1))
    fi
    rm -rf "$DIR"
done
echo "POWERLOSS TEST PASSED ($ok runs, $attempts attempts)"

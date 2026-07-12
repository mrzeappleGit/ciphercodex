#!/usr/bin/env bash
# Host-side storage tests inside the SDK container: native g++ against the
# x86_64 sysroot's Qt6Core + sqlite3. Usage: scripts/test.sh (any cwd).
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/sdk-version.env

IMAGE=ccx-rm2-sdk:$SDK_OS_VERSION
docker image inspect "$IMAGE" >/dev/null 2>&1 \
    || { echo "SDK image $IMAGE missing — run scripts/build.sh once first" >&2; exit 1; }

# MSYS_NO_PATHCONV: stop Git Bash rewriting /work into a Windows path
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W 2>/dev/null || pwd):/work" -w /work "$IMAGE" bash -c '
    set -e
    SYS=/opt/codex-sdk/sysroots/x86_64-codexsdk-linux
    TGT=/opt/codex-sdk/sysroots/cortexa7hf-neon-remarkable-linux-gnueabi
    BUILD=/tmp/host-tests   # container fs, not the Windows bind mount (WAL needs real mmap/fsync)
    mkdir -p "$BUILD"
    # sqlite3.h ships only in the target sysroot; the header is platform-independent
    cp "$TGT/usr/include/sqlite3.h" "$BUILD/"
    # Sysroot libs are glibc 2.38, container is 2.36 — link and run against the
    # sysroot glibc via its own dynamic linker + explicit libm.
    g++ -std=c++17 -g -fPIC \
        src/storage/storage.cpp tests/test_storage.cpp \
        -Isrc/storage -I"$BUILD" \
        -I"$SYS/usr/include" -I"$SYS/usr/include/QtCore" \
        -L"$SYS/usr/lib" -L"$SYS/lib" \
        -lQt6Core -l:libsqlite3.so.0 -l:libm.so.6 -lpthread -ldl \
        -Wl,--dynamic-linker,"$SYS/lib/ld-linux-x86-64.so.2" \
        -Wl,-rpath,"$SYS/usr/lib" -Wl,-rpath,"$SYS/lib" \
        -o "$BUILD/test_storage"
    "$BUILD/test_storage"
    bash tests/test_powerloss.sh "$BUILD/test_storage"
'
echo "All host tests passed."

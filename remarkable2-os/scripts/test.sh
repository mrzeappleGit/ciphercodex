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
    LINK="-Wl,--dynamic-linker,$SYS/lib/ld-linux-x86-64.so.2 -Wl,-rpath,$SYS/usr/lib -Wl,-rpath,$SYS/lib"
    g++ -std=c++17 -g -fPIC \
        src/storage/storage.cpp tests/test_storage.cpp \
        -Isrc/storage -I"$BUILD" \
        -I"$SYS/usr/include" -I"$SYS/usr/include/QtCore" \
        -L"$SYS/usr/lib" -L"$SYS/lib" \
        -lQt6Core -l:libsqlite3.so.0 -l:libm.so.6 -lpthread -ldl \
        $LINK -o "$BUILD/test_storage"
    "$BUILD/test_storage"
    bash tests/test_powerloss.sh "$BUILD/test_storage"

    # v2 schema test: same deps as test_storage (Qt6Core + sqlite3).
    g++ -std=c++17 -g -fPIC \
        src/storage/storage.cpp tests/test_storage_v2.cpp \
        -Isrc/storage -I"$BUILD" \
        -I"$SYS/usr/include" -I"$SYS/usr/include/QtCore" \
        -L"$SYS/usr/lib" -L"$SYS/lib" \
        -lQt6Core -l:libsqlite3.so.0 -l:libm.so.6 -lpthread -ldl \
        $LINK -o "$BUILD/test_storage_v2"
    "$BUILD/test_storage_v2"

    # kosync + digest pure-logic test: Qt6Core only, no sqlite, no Qt6Network
    # (kosyncclient.cpp is intentionally excluded from the link).
    g++ -std=c++17 -g -fPIC \
        src/library/digest.cpp src/sync/kosync.cpp tests/test_kosync.cpp \
        -Isrc/library -Isrc/sync \
        -I"$SYS/usr/include" -I"$SYS/usr/include/QtCore" \
        -L"$SYS/usr/lib" -L"$SYS/lib" \
        -lQt6Core -l:libm.so.6 -lpthread -ldl \
        $LINK -o "$BUILD/test_kosync"
    "$BUILD/test_kosync"

    # library core: import/dedupe/CRUD/progress/cascade. PDF cover path (CCX_HAVE_PDFIUM)
    # is compiled out on host, so no PDFium/Qt6Gui needed; PDFs import with a filename title.
    g++ -std=c++17 -g -fPIC \
        src/storage/storage.cpp src/library/library.cpp src/library/digest.cpp \
        tests/test_library.cpp \
        -Isrc -Isrc/storage -Isrc/library -I"$BUILD" \
        -I"$SYS/usr/include" -I"$SYS/usr/include/QtCore" \
        -L"$SYS/usr/lib" -L"$SYS/lib" \
        -lQt6Core -l:libsqlite3.so.0 -l:libm.so.6 -lpthread -ldl \
        $LINK -o "$BUILD/test_library"
    "$BUILD/test_library"

    # epub built-text goldens: XhtmlMapper + buildChapterText + resolvePath/spine numbering.
    # Qt6Core only (QZipReader/QZipWriter are Q_CORE_EXPORT symbols in libQt6Core; the private
    # headers live at QtCore/<ver>/QtCore/private, reachable via -I .../QtCore/<ver>).
    QTPRIV=$(ls -d "$SYS/usr/include/QtCore"/6.*/ 2>/dev/null | head -1)
    g++ -std=c++17 -g -fPIC \
        src/epub/epubdocument.cpp tests/test_epub_text.cpp \
        -Isrc \
        -I"$SYS/usr/include" -I"$SYS/usr/include/QtCore" -I"$QTPRIV" \
        -L"$SYS/usr/lib" -L"$SYS/lib" \
        -lQt6Core -l:libm.so.6 -lpthread -ldl \
        $LINK -o "$BUILD/test_epub_text"
    "$BUILD/test_epub_text"
'
echo "All host tests passed."

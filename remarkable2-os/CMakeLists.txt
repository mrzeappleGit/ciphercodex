cmake_minimum_required(VERSION 3.16)
project(ciphercodex-shell VERSION 0.1.0 LANGUAGES CXX C)

find_package(Qt6 REQUIRED COMPONENTS Quick Network)
qt_standard_project_setup(REQUIRES 6.5)

# Storage: sqlite3 C API, statically linked from the SDK sysroot (device ships no sqlite).
add_library(ccx-storage STATIC src/storage/storage.h src/storage/storage.cpp)
target_include_directories(ccx-storage PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/src)
find_library(SQLITE3_STATIC_LIB libsqlite3.a REQUIRED)
target_link_libraries(ccx-storage PUBLIC Qt6::Core ${SQLITE3_STATIC_LIB})

# Reader (Phase 2a): digest, library, PDFium wrapper, kosync. PDFium ships on the device
# and in the sysroot; we link it and use vendored BSD headers (src/pdf/pdfium/).
find_library(PDFIUM_LIB pdfium REQUIRED)
add_library(ccx-reader STATIC
    src/library/digest.h    src/library/digest.cpp
    src/library/library.h   src/library/library.cpp
    src/pdf/pdfdocument.h   src/pdf/pdfdocument.cpp
    src/epub/epubdocument.h src/epub/epubdocument.cpp
    src/epub/epubrender.h   src/epub/epubrender.cpp
    src/sync/kosync.h       src/sync/kosync.cpp
    src/sync/kosyncclient.cpp
)
target_include_directories(ccx-reader PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_compile_definitions(ccx-reader PRIVATE CCX_HAVE_PDFIUM) # library's PDF cover/title path
# CorePrivate: QZipReader for EPUB containers.
target_link_libraries(ccx-reader PUBLIC
    Qt6::Core Qt6::CorePrivate Qt6::Gui Qt6::Network ccx-storage ${PDFIUM_LIB})

qt_add_executable(ciphercodex-shell
    src/main.cpp
    src/penreader.h src/penreader.cpp
    src/inkitem.h src/inkitem.cpp
    src/epscreenmode.h src/epscreenmode.cpp
    src/notebookcontroller.h src/notebookcontroller.cpp
    src/readercontroller.h src/readercontroller.cpp
    src/pdf/pdfview.h src/pdf/pdfview.cpp
    src/epub/epubview.h src/epub/epubview.cpp
)
qt_add_qml_module(ciphercodex-shell
    URI CipherCodex
    VERSION 1.0
    QML_FILES
        src/Main.qml
        src/qml/HomeScreen.qml
        src/qml/NotebookListScreen.qml
        src/qml/PageScreen.qml
        src/qml/LibraryScreen.qml
        src/qml/BookDetailScreen.qml
        src/qml/PdfReaderScreen.qml
        src/qml/EpubReaderScreen.qml
        src/qml/SettingsScreen.qml
        src/qml/KeptScreen.qml
)
# src/pdf + src/epub on the path so generated QML type registration finds the headers by basename
target_include_directories(ciphercodex-shell PRIVATE src src/pdf src/epub)
# GuiPrivate: qpa/qwindowsysteminterface.h — pen->mouse synthesis for UI chrome
target_link_libraries(ciphercodex-shell PRIVATE Qt6::Quick Qt6::GuiPrivate ccx-storage ccx-reader ${CMAKE_DL_LIBS})

# Standalone evdev probe for the hardware audit — no Qt, prints ABS ranges and live events.
add_executable(input-probe src/tools/input_probe.c)

install(TARGETS ciphercodex-shell input-probe RUNTIME DESTINATION .)

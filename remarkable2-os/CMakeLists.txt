cmake_minimum_required(VERSION 3.16)
project(ciphercodex-shell VERSION 0.1.0 LANGUAGES CXX C)

find_package(Qt6 REQUIRED COMPONENTS Quick)
qt_standard_project_setup(REQUIRES 6.5)

# Storage: sqlite3 C API, statically linked from the SDK sysroot (device ships no sqlite).
add_library(ccx-storage STATIC src/storage/storage.h src/storage/storage.cpp)
target_include_directories(ccx-storage PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/src)
find_library(SQLITE3_STATIC_LIB libsqlite3.a REQUIRED)
target_link_libraries(ccx-storage PUBLIC Qt6::Core ${SQLITE3_STATIC_LIB})

qt_add_executable(ciphercodex-shell
    src/main.cpp
    src/penreader.h src/penreader.cpp
    src/inkitem.h src/inkitem.cpp
    src/epscreenmode.h src/epscreenmode.cpp
    src/notebookcontroller.h src/notebookcontroller.cpp
)
qt_add_qml_module(ciphercodex-shell
    URI CipherCodex
    VERSION 1.0
    QML_FILES
        src/Main.qml
        src/qml/HomeScreen.qml
        src/qml/NotebookListScreen.qml
        src/qml/PageScreen.qml
)
target_include_directories(ciphercodex-shell PRIVATE src)
# GuiPrivate: qpa/qwindowsysteminterface.h — pen->mouse synthesis for UI chrome
target_link_libraries(ciphercodex-shell PRIVATE Qt6::Quick Qt6::GuiPrivate ccx-storage ${CMAKE_DL_LIBS})

# Standalone evdev probe for the hardware audit — no Qt, prints ABS ranges and live events.
add_executable(input-probe src/tools/input_probe.c)

install(TARGETS ciphercodex-shell input-probe RUNTIME DESTINATION .)

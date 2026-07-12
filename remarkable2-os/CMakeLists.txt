cmake_minimum_required(VERSION 3.16)
project(ciphercodex-shell VERSION 0.0.1 LANGUAGES CXX C)

find_package(Qt6 REQUIRED COMPONENTS Quick)
qt_standard_project_setup(REQUIRES 6.5)

qt_add_executable(ciphercodex-shell src/main.cpp)
qt_add_qml_module(ciphercodex-shell
    URI CipherCodex
    VERSION 1.0
    QML_FILES src/Main.qml
)
target_link_libraries(ciphercodex-shell PRIVATE Qt6::Quick)

# Standalone evdev probe for the hardware audit — no Qt, prints ABS ranges and live events.
add_executable(input-probe src/tools/input_probe.c)

install(TARGETS ciphercodex-shell input-probe RUNTIME DESTINATION .)

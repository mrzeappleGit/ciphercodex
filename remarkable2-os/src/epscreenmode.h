#pragma once
#include <QQuickItem>

// ===========================================================================
// UNDOCUMENTED / CLOSED-ABI DEPENDENCY. Isolated here on purpose.
//
// The reMarkable epaper scenegraph backend (libqsgepaper.so, LICENSE: CLOSED)
// exports EPScreenModeItem: a QQuickItem that tags its screen region with an
// e-paper waveform mode. This is how xochitl gets fast pen tracking. There is
// no documented/importable QML module for it (xochitl wraps it privately), so
// we reach the exported class by symbol at runtime.
//
// Mode enum (read from the plugin's own metaobject on device, OS 3.27.3.0):
//   Pen=0  Mono=1  Animation=2  UI=3  Content=4  Sleep=5
// Pen(0) = the low-latency partial-refresh waveform used while inking.
//
// Everything degrades gracefully: if any symbol is missing (e.g. a future OS
// renames it), attachScreenMode() returns nullptr and the app runs exactly as
// before — slower "grays" waveform, but fully functional.
// ===========================================================================
namespace epscreenmode {

enum Mode { Pen = 0, Mono = 1, Animation = 2, UI = 3, Content = 4, Content_Sleep = 5 };

// Create an EPScreenModeItem as a child of `canvas`, filling it, at the given mode.
// Returns the item (a QQuickItem) or nullptr if the closed plugin isn't usable.
// The returned item can have setMode() called on it via setMode() below.
QQuickItem *attachScreenMode(QQuickItem *canvas, int mode);

// Change the mode of an item returned by attachScreenMode(). No-op if null.
void setMode(QQuickItem *item, int mode);

} // namespace epscreenmode

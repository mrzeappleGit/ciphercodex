import QtQuick
import CipherCodex

// Phase 0 hello screen v2: display + touch via the epaper QPA, Marker via raw evdev
// (PenReader), ink via scene-graph nodes (InkItem) instead of Canvas repaints.
Window {
    id: root
    visible: true
    visibility: Window.FullScreen
    width: Screen.width
    height: Screen.height
    color: "white"

    PenReader {
        id: pen
        onPenDown: (x, y, p) => ink.penDown(x, y, p)
        onPenMove: (x, y, p) => ink.penMove(x, y, p)
        onPenUp: () => ink.penUp()
    }

    // Header band
    Rectangle {
        id: header
        width: parent.width; height: 120
        color: "black"
        Row {
            anchors { left: parent.left; leftMargin: 40; verticalCenter: parent.verticalCenter }
            spacing: 10
            Repeater {
                model: [46, 70, 96, 70, 46]
                Rectangle { width: 14; height: modelData; color: "white"; anchors.verticalCenter: parent.verticalCenter }
            }
        }
        Text {
            anchors.centerIn: parent
            text: "CIPHERCODEX"
            color: "white"
            font { pixelSize: 48; letterSpacing: 12; bold: true }
        }
    }

    InkItem {
        id: ink
        anchors { top: header.bottom; left: parent.left; right: parent.right; bottom: footer.top }
    }

    // Touch draws nothing; it only proves palm rejection state and taps buttons.
    Text {
        id: info
        anchors { top: header.bottom; topMargin: 20; horizontalCenter: parent.horizontalCenter }
        font.pixelSize: 28
        color: "black"
        Timer {
            interval: 300; running: true; repeat: true
            onTriggered: info.text =
                (pen.near ? (pen.eraser ? "ERASER" : "PEN") : "away")
                + "  pressure " + pen.pressure.toFixed(2)
                + "  tilt " + pen.tiltX + "/" + pen.tiltY
                + "  calib " + pen.calib
        }
    }

    Row {
        id: footer
        anchors { bottom: parent.bottom; horizontalCenter: parent.horizontalCenter }
        height: 110
        spacing: 40
        Rectangle {
            width: 260; height: 90; border { color: "black"; width: 4 }
            Text { anchors.centerIn: parent; text: "CLEAR"; font { pixelSize: 32; bold: true } }
            TapHandler { onTapped: ink.clear() }
        }
        Rectangle {
            width: 260; height: 90; border { color: "black"; width: 4 }
            Text { anchors.centerIn: parent; text: "CALIB " + pen.calib; font { pixelSize: 32; bold: true } }
            TapHandler { onTapped: pen.calib = (pen.calib + 1) % 8 }
        }
        Rectangle {
            width: 260; height: 90; color: "black"
            Text { anchors.centerIn: parent; text: "EXIT"; color: "white"; font { pixelSize: 32; bold: true } }
            TapHandler { onTapped: Qt.quit() }
        }
    }
}

import QtQuick

// Phase 0 hello screen: proves display, touch, and (if the QPA delivers them) Marker events.
// Monochrome only. Black header band + five-bar mark = CipherCodex identity.
Window {
    id: root
    visible: true
    color: "white"

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

    // Live input probe: draws from any pointer device, reports what Qt sees.
    Canvas {
        id: canvas
        anchors { top: header.bottom; left: parent.left; right: parent.right; bottom: footer.top }
        property var lastPos: null
        onPaint: {} // painted incrementally via drawSeg
        function drawSeg(from, to, w) {
            var ctx = getContext("2d")
            ctx.strokeStyle = "black"
            ctx.lineWidth = w
            ctx.lineCap = "round"
            ctx.beginPath()
            ctx.moveTo(from.x, from.y)
            ctx.lineTo(to.x, to.y)
            ctx.stroke()
            requestPaint()
        }

        PointHandler {
            id: probe
            acceptedDevices: PointerDevice.AllDevices
            onActiveChanged: canvas.lastPos = active ? probe.point.position : null
            onPointChanged: {
                if (!active) return
                var dev = probe.point.device
                info.text = "device: " + dev.name + "  type: " + dev.type
                        + "  pressure: " + probe.point.pressure.toFixed(3)
                        + "  rotation: " + probe.point.rotation.toFixed(1)
                if (canvas.lastPos) {
                    var w = probe.point.pressure > 0 ? 1 + probe.point.pressure * 8 : 3
                    canvas.drawSeg(canvas.lastPos, probe.point.position, w)
                }
                canvas.lastPos = probe.point.position
            }
        }
    }

    Text {
        id: info
        anchors { top: header.bottom; topMargin: 20; horizontalCenter: parent.horizontalCenter }
        text: "draw with finger or Marker"
        font.pixelSize: 28
        color: "black"
    }

    // Footer: clear + exit (exit returns control to the launcher script -> xochitl restarts)
    Row {
        id: footer
        anchors { bottom: parent.bottom; horizontalCenter: parent.horizontalCenter }
        height: 110
        spacing: 40
        Rectangle {
            width: 300; height: 90; border { color: "black"; width: 4 }
            Text { anchors.centerIn: parent; text: "CLEAR"; font { pixelSize: 32; bold: true } }
            TapHandler {
                onTapped: {
                    var ctx = canvas.getContext("2d")
                    ctx.clearRect(0, 0, canvas.width, canvas.height)
                    canvas.requestPaint()
                }
            }
        }
        Rectangle {
            width: 300; height: 90; color: "black"
            Text { anchors.centerIn: parent; text: "EXIT"; color: "white"; font { pixelSize: 32; bold: true } }
            TapHandler { onTapped: Qt.quit() }
        }
    }
}

pragma ComponentBehavior: Bound
import QtQuick
import CipherCodex

// The device's sleeping face (design 11): dot-grid ground, corner brackets, framed brand
// card. Painted once before suspend — e-ink holds it at zero power until wake.
Rectangle {
    id: sleep
    color: "white"

    signal dismissed()

    DotGrid { anchors.fill: parent; pitch: 36; dot: 1 }

    Repeater {  // corner brackets
        model: 4
        Item {
            id: corner
            required property int index
            readonly property bool onRight: corner.index === 1 || corner.index === 3
            readonly property bool onBottom: corner.index >= 2
            x: onRight ? sleep.width - 56 : 16
            y: onBottom ? sleep.height - 56 : 16
            width: 40; height: 40
            Rectangle {
                width: 40; height: Theme.frame; color: "black"
                y: corner.onBottom ? 40 - Theme.frame : 0
            }
            Rectangle {
                width: Theme.frame; height: 40; color: "black"
                x: corner.onRight ? 40 - Theme.frame : 0
            }
        }
    }

    Rectangle {  // brand card
        anchors.centerIn: parent
        width: card.implicitWidth + 180
        height: card.implicitHeight + 160
        color: "white"
        border { color: "black"; width: Theme.frame }
        Column {
            id: card
            anchors.centerIn: parent
            spacing: 40
            Row {  // barcode motif, bars bottom-aligned (150x180 in the design)
                anchors.horizontalCenter: parent.horizontalCenter
                height: 180
                spacing: 12
                Repeater {
                    model: [130, 170, 110, 180, 140]
                    Rectangle {
                        required property int modelData
                        anchors.bottom: parent.bottom
                        width: 20; height: modelData
                        color: "black"
                    }
                }
            }
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "CIPHERCODEX"
                // synthesized bold allowed on the wordmark only (contract rule 4)
                font { family: Theme.mono; pixelSize: 60; letterSpacing: 8; bold: true }
            }
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: "A BEAUTIFUL TYPEWRITER"
                font { family: Theme.mono; pixelSize: Theme.caption; letterSpacing: 4 }
            }
        }
    }

    Text {
        anchors { bottom: parent.bottom; bottomMargin: 90; horizontalCenter: parent.horizontalCenter }
        text: "press any key to wake"
        font { family: Theme.mono; pixelSize: Theme.micro; letterSpacing: 2 }
    }

    // MouseArea, NOT TapHandler: it accepts and CONSUMES the press, so nothing beneath —
    // tiles, rail buttons, EXIT — can fire through the sleeping face (TapHandler's default
    // policy takes only a passive grab and delivery continues to items below). Dismissal
    // is gated in Main.qml (ignored while the suspend is still landing).
    MouseArea { anchors.fill: parent; onClicked: sleep.dismissed() }
}

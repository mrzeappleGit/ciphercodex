pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import CipherCodex

Item {
    id: home

    required property var pen
    required property var controller
    required property var reader

    component Tile: Rectangle {
        id: tile
        property string label
        property string tag: ""
        signal activated()
        height: 240
        color: tileTap.pressed ? "black" : "white"
        border { color: "black"; width: 4 }
        Text {
            anchors.centerIn: parent
            text: tile.label
            color: tileTap.pressed ? "white" : "black"
            font { pixelSize: 44; letterSpacing: 10; bold: true }
        }
        Text {
            visible: tile.tag !== ""
            anchors { right: parent.right; rightMargin: 24; bottom: parent.bottom; bottomMargin: 16 }
            text: tile.tag
            color: tileTap.pressed ? "white" : "black"
            font.pixelSize: 22
        }
        TapHandler { id: tileTap; onTapped: tile.activated() }
    }

    Rectangle {
        id: header
        width: parent.width; height: 120
        color: "black"
        Row {
            anchors { left: parent.left; leftMargin: 40; verticalCenter: parent.verticalCenter }
            spacing: 10
            Repeater {
                model: [46, 70, 96, 70, 46]
                Rectangle {
                    required property int modelData
                    width: 14; height: modelData; color: "white"
                    anchors.verticalCenter: parent.verticalCenter
                }
            }
        }
        Text {
            anchors.centerIn: parent
            text: "CIPHERCODEX"
            color: "white"
            font { pixelSize: 48; letterSpacing: 12; bold: true }
        }
    }

    Column {
        anchors { top: header.bottom; topMargin: 60; left: parent.left; leftMargin: 80; right: parent.right; rightMargin: 80 }
        spacing: 40
        Tile {
            width: parent.width; label: "LIBRARY"
            onActivated: home.StackView.view.push(libListComp)
        }
        Tile {
            width: parent.width; label: "NOTEBOOKS"
            onActivated: home.StackView.view.push(nbListComp)
        }
        Tile { width: parent.width; label: "KEPT"; tag: "PHASE 3" }
        Tile { width: parent.width; label: "STATS"; tag: "PHASE 3" }
        Tile {
            width: parent.width; label: "SETTINGS"
            onActivated: home.StackView.view.push(settingsComp)
        }
    }

    Component {
        id: nbListComp
        NotebookListScreen { pen: home.pen; controller: home.controller }
    }

    Component {
        id: libListComp
        LibraryScreen { reader: home.reader }
    }

    Component {
        id: settingsComp
        SettingsScreen { reader: home.reader }
    }
}

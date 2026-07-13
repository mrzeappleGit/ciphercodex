pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import CipherCodex

Item {
    id: home

    required property var pen
    required property var controller
    required property var reader

    // Live tile captions + sync chip state, refreshed on entry and after every sync.
    property string libCaption: ""
    property string nbCaption: ""
    property string keptCaption: ""
    property bool syncing: false
    property string chipText: ""

    function refreshChip() {
        if (home.syncing) { home.chipText = "SYNCING..."; return }
        const d = home.reader.webdavConfig()
        if (d.configured !== true) { home.chipText = "SYNC OFF"; return }
        const ts = parseInt(home.reader.setting("webdav_last_sync_at", "0"))
        if (!ts) { home.chipText = "SYNC ON"; return } // configured but never completed a run
        const mins = Math.floor((Date.now() - ts) / 60000)
        if (mins < 1) home.chipText = "SYNCED · JUST NOW"
        else if (mins < 60) home.chipText = "SYNCED · " + mins + " MIN AGO"
        else if (mins < 1440) home.chipText = "SYNCED · " + Math.floor(mins / 60) + " H AGO"
        else home.chipText = "SYNCED · " + Math.floor(mins / 1440) + " D AGO"
    }

    function refresh() {
        const c = home.reader.view("", 0, 0).counts
        home.libCaption = c.all + " BOOKS · " + c.reading + " READING"
        const nbs = home.controller.notebooks()
        let pages = 0
        for (let i = 0; i < nbs.length; i++) pages += nbs[i].pageCount
        home.nbCaption = nbs.length + " NOTEBOOKS · " + pages + " PAGES"
        home.keptCaption = home.reader.keptHighlights().length + " HIGHLIGHTS"
        home.refreshChip()
    }

    Component.onCompleted: {
        // An auto-sync from Main.qml may already be in flight when Home first loads.
        home.syncing = home.reader.webdavConfig().syncing === true
        home.refresh()
    }
    StackView.onActivated: home.refresh()

    Connections {
        target: home.reader
        function onSyncStarted() { home.syncing = true; home.refreshChip() }
        function onSyncFinished(ok, summary) { home.syncing = false; home.refreshChip() }
        // auto-sync runs while Home is visible; merged rows must update the tile counts too
        function onSyncedDataChanged() { home.refresh() }
    }

    component Tile: Rectangle {
        id: tile
        property string label
        property string caption
        default property alias iconData: iconBox.data
        signal activated()
        readonly property bool down: tileTap.pressed
        readonly property color fg: down ? "white" : "black"
        readonly property color bg: down ? "black" : "white"
        height: 220
        color: tile.bg
        border { color: "black"; width: Theme.frame }
        Item {
            id: iconBox
            width: 56; height: 56
            anchors { left: parent.left; leftMargin: 48; verticalCenter: parent.verticalCenter }
        }
        Column {
            anchors { left: iconBox.right; leftMargin: 36; verticalCenter: parent.verticalCenter }
            spacing: 8
            Text {
                text: tile.label
                color: tile.fg
                font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.tileLabel; letterSpacing: 2 }
            }
            Text {
                text: tile.caption
                color: tile.fg
                font { family: Theme.mono; pixelSize: Theme.caption }
            }
        }
        Text {
            anchors { right: parent.right; rightMargin: 48; verticalCenter: parent.verticalCenter }
            text: "→"
            color: tile.fg
            font.pixelSize: 34
        }
        TapHandler { id: tileTap; onTapped: tile.activated() }
    }

    Rectangle {
        id: header
        width: parent.width; height: Theme.homeHeader
        color: "black"
        Row {
            anchors { left: parent.left; leftMargin: Theme.pad; verticalCenter: parent.verticalCenter }
            spacing: 22
            Item {
                width: 46; height: 56
                anchors.verticalCenter: parent.verticalCenter
                Row {
                    anchors.bottom: parent.bottom
                    spacing: 4
                    Repeater {
                        model: [40, 52, 32, 56, 36]
                        Rectangle {
                            required property int modelData
                            width: 6; height: modelData; color: "white"
                            anchors.bottom: parent.bottom
                        }
                    }
                }
            }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: "CIPHERCODEX"
                color: "white"
                // Synthesized bold on the mono face is allowed for the wordmark only.
                font { family: Theme.mono; pixelSize: 38; letterSpacing: 5; bold: true }
            }
        }
        Item {
            id: chipHit
            anchors { right: parent.right; rightMargin: Theme.pad - 16; verticalCenter: parent.verticalCenter }
            width: chip.width + 32; height: Theme.touch
            Rectangle {
                id: chip
                anchors.centerIn: parent
                width: chipLabel.implicitWidth + 40
                height: chipLabel.implicitHeight + 20
                color: chipTap.pressed ? "white" : "black"
                border { color: "white"; width: Theme.hairline }
                Text {
                    id: chipLabel
                    anchors.centerIn: parent
                    text: home.chipText
                    color: chipTap.pressed ? "black" : "white"
                    font { family: Theme.mono; pixelSize: Theme.caption; letterSpacing: 1 }
                }
            }
            TapHandler {
                id: chipTap
                onTapped: if (home.reader.webdavConfig().configured === true) home.reader.syncNow()
            }
        }
    }

    Column {
        anchors { top: header.bottom; topMargin: 48; left: parent.left; leftMargin: Theme.pad; right: parent.right; rightMargin: Theme.pad }
        spacing: 24
        Tile {
            id: libTile
            width: parent.width; label: "LIBRARY"; caption: home.libCaption
            onActivated: home.StackView.view.push(libListComp)
            // Three book spines, one leaning
            Rectangle { x: 0; y: 6; width: 12; height: 50; color: libTile.fg }
            Rectangle { x: 16; y: 2; width: 12; height: 54; color: libTile.fg }
            Rectangle { x: 34; y: 4; width: 12; height: 52; color: libTile.fg; rotation: 8 }
        }
        Tile {
            id: nbTile
            width: parent.width; label: "NOTEBOOKS"; caption: home.nbCaption
            onActivated: home.StackView.view.push(nbListComp)
            // Page outline + pen stroke
            Rectangle { x: 2; y: 0; width: 40; height: 56; color: nbTile.bg; border { color: nbTile.fg; width: 3 } }
            Rectangle { x: 14; y: 25; width: 48; height: 6; color: nbTile.fg; rotation: -45 }
        }
        Tile {
            id: keptTile
            width: parent.width; label: "KEPT"; caption: home.keptCaption
            onActivated: home.StackView.view.push(keptComp)
            // Oversized quote-corner
            Rectangle { x: 4; y: 6; width: 14; height: 44; color: keptTile.fg }
            Rectangle { x: 4; y: 6; width: 44; height: 14; color: keptTile.fg }
        }
        Rectangle {
            // STATS — disabled, no screen yet: hatch ground, hairline border, white-backed labels
            width: parent.width; height: 220
            color: "white"
            border { color: "black"; width: Theme.hairline }
            Hatch { anchors.fill: parent; anchors.margins: Theme.hairline }
            Rectangle {
                id: statsIconBack
                width: 68; height: 68; color: "white"
                anchors { left: parent.left; leftMargin: 48; verticalCenter: parent.verticalCenter }
                Item {
                    anchors.centerIn: parent
                    width: 54; height: 56
                    // Three ascending bars
                    Rectangle { x: 0; y: 36; width: 14; height: 20; color: "black" }
                    Rectangle { x: 20; y: 20; width: 14; height: 36; color: "black" }
                    Rectangle { x: 40; y: 4; width: 14; height: 52; color: "black" }
                }
            }
            Column {
                anchors { left: statsIconBack.right; leftMargin: 30; verticalCenter: parent.verticalCenter }
                spacing: 8
                Rectangle {
                    width: statsLabel.implicitWidth + 28; height: statsLabel.implicitHeight + 8; color: "white"
                    Text {
                        id: statsLabel
                        anchors.centerIn: parent
                        text: "STATS"
                        color: "black"
                        font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.tileLabel; letterSpacing: 2 }
                    }
                }
                Rectangle {
                    width: statsSoon.implicitWidth + 28; height: statsSoon.implicitHeight + 8; color: "white"
                    Text {
                        id: statsSoon
                        anchors.centerIn: parent
                        text: "COMING SOON"
                        color: "black"
                        font { family: Theme.mono; pixelSize: Theme.caption }
                    }
                }
            }
        }
        Tile {
            id: setTile
            width: parent.width; label: "SETTINGS"; caption: "sync · display · device"
            onActivated: home.StackView.view.push(settingsComp)
            // Three slider lines with offset knobs
            Rectangle { x: 0; y: 10; width: 56; height: 4; color: setTile.fg }
            Rectangle { x: 36; y: 5; width: 14; height: 14; color: setTile.fg }
            Rectangle { x: 0; y: 26; width: 56; height: 4; color: setTile.fg }
            Rectangle { x: 6; y: 21; width: 14; height: 14; color: setTile.fg }
            Rectangle { x: 0; y: 42; width: 56; height: 4; color: setTile.fg }
            Rectangle { x: 24; y: 37; width: 14; height: 14; color: setTile.fg }
        }
    }

    Component {
        id: nbListComp
        NotebookListScreen { pen: home.pen; controller: home.controller; reader: home.reader }
    }

    Component {
        id: libListComp
        LibraryScreen { reader: home.reader }
    }

    Component {
        id: settingsComp
        SettingsScreen { reader: home.reader }
    }

    Component {
        id: keptComp
        KeptScreen { reader: home.reader }
    }
}

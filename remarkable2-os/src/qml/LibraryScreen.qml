pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import CipherCodex

Item {
    id: lib

    required property var reader

    property var books: []
    property var counts: ({ all: 0, unread: 0, reading: 0, finished: 0 })
    property string query: ""
    property int filter: 0    // 0 all,1 unread,2 reading,3 finished
    property int sortMode: 0  // 0 recent,1 title,2 author,3 added,4 progress
    property string flash: ""
    readonly property var sortNames: ["RECENT", "TITLE", "AUTHOR", "ADDED", "PROGRESS"]

    function reload() {
        const r = lib.reader.view(lib.query, lib.filter, lib.sortMode)
        lib.books = r.books
        lib.counts = r.counts
    }

    Component.onCompleted: reload()
    StackView.onActivated: reload()  // progress/last-opened changed while a book was open
    Connections {
        target: lib.reader
        // a sync started from Home merged rows while this list was open
        function onSyncedDataChanged() { lib.reload() }
    }

    // Filter chip — Theme.chip border, 12/24 padding, inverted when active or pressed
    // Transparent 90px-tall hit wrapper around the visual chip: vertical touch padding
    // without handler margins, so adjacent chips' hit zones can never overlap sideways.
    component Chip: Item {
        id: c
        property alias label: ct.text
        property bool active: false
        signal tapped()
        readonly property bool dark: c.active !== chipTap.pressed
        width: chipBox.width
        height: Theme.touch
        Rectangle {
            id: chipBox
            anchors.centerIn: parent
            width: ct.implicitWidth + 48
            height: ct.implicitHeight + 24
            color: c.dark ? "black" : "white"
            border { color: "black"; width: Theme.chip }
            Text {
                id: ct
                anchors.centerIn: parent
                color: c.dark ? "white" : "black"
                font { family: Theme.display; weight: Font.Bold; pixelSize: 22 }
            }
        }
        TapHandler { id: chipTap; onTapped: c.tapped() }
    }

    // Header band — 140px solid black: ← LIBRARY left, IMPORT chip right
    Rectangle {
        id: header
        width: parent.width; height: Theme.headerBand
        color: "black"

        Rectangle {  // back affordance (whole left group pops)
            id: backArea
            anchors { left: parent.left; leftMargin: Theme.pad; verticalCenter: parent.verticalCenter }
            width: backRow.implicitWidth + 24
            height: Theme.touch
            color: backTap.pressed ? "white" : "black"
            Row {
                id: backRow
                anchors.verticalCenter: parent.verticalCenter
                spacing: 24
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "←"
                    color: backTap.pressed ? "black" : "white"
                    font.pixelSize: 34
                }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "LIBRARY"
                    color: backTap.pressed ? "black" : "white"
                    font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.h2; letterSpacing: 2 }
                }
            }
            TapHandler { id: backTap; onTapped: lib.StackView.view.pop() }
        }

        Rectangle {  // IMPORT action chip (doubles as the flash message)
            anchors { right: parent.right; rightMargin: Theme.pad; verticalCenter: parent.verticalCenter }
            width: importLabel.implicitWidth + 56
            height: 88
            color: importTap.pressed ? "white" : "black"
            border { color: "white"; width: Theme.chip }
            Text {
                id: importLabel
                anchors.centerIn: parent
                text: lib.flash !== "" ? lib.flash : "IMPORT"
                color: importTap.pressed ? "black" : "white"
                font { family: Theme.display; weight: Font.Bold; pixelSize: 26; letterSpacing: 1 }
            }
            TapHandler {
                id: importTap
                margin: 1  // 88px chip + 1px each side = the 90px touch floor
                onTapped: {
                    const s = lib.reader.importInbox()
                    lib.flash = "+" + s.imported + " DUP " + s.duplicates + " FAIL " + s.failed
                    flashTimer.restart()
                    lib.reload()
                }
            }
        }
    }

    Timer { id: flashTimer; interval: 2500; onTriggered: lib.flash = "" }

    // Search row — 96px, Theme.frame bottom border
    Item {
        id: searchRow
        anchors { top: header.bottom; left: parent.left; right: parent.right }
        height: 96

        Item {  // magnifier built from rectangles (glyph policy)
            id: searchIcon
            anchors { left: parent.left; leftMargin: Theme.pad; verticalCenter: parent.verticalCenter }
            width: 30; height: 30
            Rectangle {
                width: 20; height: 20; radius: 10
                color: "white"
                border { color: "black"; width: 3 }
            }
            Rectangle {
                x: 14; y: 16
                width: 3; height: 12
                rotation: 45
                color: "black"
            }
        }

        Text {
            visible: searchInput.text === ""
            anchors { left: searchIcon.right; leftMargin: 20; verticalCenter: parent.verticalCenter }
            text: "Search library..."
            color: "#999999"
            font { family: Theme.reading; pixelSize: 26 }
        }
        TextInput {
            id: searchInput
            anchors {
                left: searchIcon.right; leftMargin: 20
                right: parent.right; rightMargin: Theme.pad
                top: parent.top; bottom: parent.bottom
            }
            verticalAlignment: TextInput.AlignVCenter
            clip: true
            color: "black"
            font { family: Theme.reading; pixelSize: 26 }
            onTextChanged: { lib.query = text; lib.reload() }
        }

        Rectangle {
            anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
            height: Theme.frame
            color: "black"
        }
    }

    // Filter chips row — 96px, sort control right
    Item {
        id: chips
        anchors { top: searchRow.bottom; left: parent.left; right: parent.right }
        height: 96

        Row {
            anchors { left: parent.left; leftMargin: Theme.pad; verticalCenter: parent.verticalCenter }
            spacing: 16
            Chip { label: "ALL " + lib.counts.all; active: lib.filter === 0; onTapped: { lib.filter = 0; lib.reload() } }
            Chip { label: "UNREAD " + lib.counts.unread; active: lib.filter === 1; onTapped: { lib.filter = 1; lib.reload() } }
            Chip { label: "READING " + lib.counts.reading; active: lib.filter === 2; onTapped: { lib.filter = 2; lib.reload() } }
            Chip { label: "FINISHED " + lib.counts.finished; active: lib.filter === 3; onTapped: { lib.filter = 3; lib.reload() } }
        }

        Item {  // sort cycler
            anchors { right: parent.right; rightMargin: Theme.pad; top: parent.top; bottom: parent.bottom }
            width: Math.max(Theme.touch, sortLabel.implicitWidth + 24)
            Rectangle {
                anchors.fill: parent
                color: sortTap.pressed ? "black" : "white"
            }
            Text {
                id: sortLabel
                anchors { right: parent.right; verticalCenter: parent.verticalCenter }
                text: "SORT: " + lib.sortNames[lib.sortMode] + " ▼"  // U+25BC: on device; U+25BE is not
                color: sortTap.pressed ? "white" : "black"
                font { family: Theme.mono; pixelSize: 20 }
            }
            TapHandler { id: sortTap; onTapped: { lib.sortMode = (lib.sortMode + 1) % 5; lib.reload() } }
        }

        Rectangle {  // top rule of the list
            anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
            height: Theme.hairline
            color: "black"
        }
    }

    Text {  // empty-library / no-results placeholder
        visible: lib.books.length === 0
        anchors { top: chips.bottom; topMargin: 120; horizontalCenter: parent.horizontalCenter }
        horizontalAlignment: Text.AlignHCenter
        color: "black"
        font { family: Theme.reading; pixelSize: 26 }
        text: lib.counts.all === 0
              ? "No books yet\nDrop PDFs into the inbox and tap IMPORT"
              : "No matches"
    }

    ListView {
        id: list
        anchors {
            top: chips.bottom
            left: parent.left; right: parent.right
            bottom: parent.bottom
        }
        clip: true
        model: lib.books
        delegate: Rectangle {
            id: row
            required property var modelData
            property bool confirming: false
            readonly property bool inverted: rowTap.pressed && !row.confirming
            width: list.width
            height: 154
            color: row.inverted ? "black" : "white"
            border { color: "black"; width: row.confirming ? Theme.frame : 0 }

            Rectangle {  // cover box: real cover or dot-grid + letter badge
                id: coverBox
                visible: !row.confirming
                anchors { left: parent.left; leftMargin: Theme.pad; verticalCenter: parent.verticalCenter }
                width: 88; height: 118
                color: "white"
                border { color: "black"; width: Theme.chip }
                Image {
                    id: cover
                    anchors { fill: parent; margins: Theme.chip }
                    fillMode: Image.PreserveAspectFit
                    cache: false
                    source: row.modelData.coverPath !== "" ? "file://" + row.modelData.coverPath : ""
                }
                DotGrid {
                    visible: cover.status !== Image.Ready
                    anchors { fill: parent; margins: Theme.chip }
                    pitch: 7; dot: 1.6
                }
                Rectangle {  // corner letter badge on the placeholder
                    visible: cover.status !== Image.Ready
                    anchors { left: parent.left; bottom: parent.bottom }
                    width: 32; height: 32
                    color: "black"
                    Text {
                        anchors.centerIn: parent
                        text: row.modelData.title.charAt(0).toUpperCase()
                        color: "white"
                        font { family: Theme.display; weight: Font.Bold; pixelSize: 22 }
                    }
                }
            }

            Item {  // status mark: unread ○ / reading half-disc / finished ●
                id: statusMark
                visible: !row.confirming
                anchors { left: coverBox.right; leftMargin: 24; verticalCenter: parent.verticalCenter }
                width: 30; height: 30
                readonly property color ink: row.inverted ? "white" : "black"
                Rectangle {
                    anchors.fill: parent
                    radius: width / 2
                    color: row.modelData.state === 2 ? statusMark.ink : "transparent"
                    border { color: statusMark.ink; width: 3 }
                }
                Item {  // left half-disc for reading
                    visible: row.modelData.state === 1
                    width: parent.width / 2; height: parent.height
                    clip: true
                    Rectangle {
                        width: statusMark.width; height: statusMark.height
                        radius: width / 2
                        color: statusMark.ink
                    }
                }
            }

            Column {
                visible: !row.confirming
                anchors {
                    left: statusMark.right; leftMargin: 24
                    right: progressText.left; rightMargin: 24
                    verticalCenter: parent.verticalCenter
                }
                spacing: 6
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    text: row.modelData.title
                    color: row.inverted ? "white" : "black"
                    font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.body; letterSpacing: 1 }
                }
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    text: (row.modelData.author !== "" ? row.modelData.author + " · " : "")
                          + (row.modelData.format === 1 ? "EPUB" : "PDF")
                    color: row.inverted ? "white" : "black"
                    font { family: Theme.mono; pixelSize: Theme.caption }
                }
            }

            Text {
                id: progressText
                visible: !row.confirming
                anchors { right: parent.right; rightMargin: Theme.pad; verticalCenter: parent.verticalCenter }
                text: row.modelData.state === 0 ? "NEW"
                    : row.modelData.state === 2 ? "DONE"
                    : Math.round(row.modelData.percentage * 100) + "%"
                color: row.inverted ? "white" : "black"
                // no bold: Share Tech Mono ships one weight; synthesis smears on 1-bit e-ink
                font { family: Theme.mono; pixelSize: Theme.secondary }
            }

            Item {  // in-place confirm: DELETE THIS BOOK? · CANCEL · CONFIRM
                visible: row.confirming
                anchors { fill: parent; leftMargin: Theme.pad; rightMargin: 24 }
                Text {
                    anchors { left: parent.left; verticalCenter: parent.verticalCenter }
                    text: "DELETE THIS BOOK?"
                    color: "black"
                    font { family: Theme.display; weight: Font.Bold; pixelSize: 22 }
                }
                Row {
                    anchors { right: parent.right; verticalCenter: parent.verticalCenter }
                    spacing: 24
                    // 90px-tall wrapper Items carry the taps with NO handler margin: hit zones
                    // stay horizontally exact so a CANCEL edge tap can never reach CONFIRM.
                    Item {
                        width: cancelBox.width
                        height: Theme.touch
                        anchors.verticalCenter: parent.verticalCenter
                        Rectangle {
                            id: cancelBox
                            anchors.centerIn: parent
                            width: cancelLabel.implicitWidth + 40
                            height: cancelLabel.implicitHeight + 20
                            color: cancelTap.pressed ? "black" : "white"
                            border { color: "black"; width: Theme.chip }
                            Text {
                                id: cancelLabel
                                anchors.centerIn: parent
                                text: "CANCEL"
                                color: cancelTap.pressed ? "white" : "black"
                                font { family: Theme.display; weight: Font.Bold; pixelSize: 22 }
                            }
                        }
                        TapHandler { id: cancelTap; onTapped: row.confirming = false }
                    }
                    Item {
                        width: confirmBox.width
                        height: Theme.touch
                        anchors.verticalCenter: parent.verticalCenter
                        Rectangle {
                            id: confirmBox
                            anchors.centerIn: parent
                            width: confirmLabel.implicitWidth + 40
                            height: confirmLabel.implicitHeight + 20
                            color: confirmTap.pressed ? "white" : "black"
                            border { color: "black"; width: Theme.chip }
                            Text {
                                id: confirmLabel
                                anchors.centerIn: parent
                                text: "CONFIRM"
                                color: confirmTap.pressed ? "black" : "white"
                                font { family: Theme.display; weight: Font.Bold; pixelSize: 22 }
                            }
                        }
                        TapHandler {
                            id: confirmTap
                            onTapped: { lib.reader.deleteBook(row.modelData.id); lib.reload() }
                        }
                    }
                }
            }

            Rectangle {  // row divider
                anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
                height: Theme.hairline
                color: "black"
            }

            TapHandler {
                id: rowTap
                enabled: !row.confirming
                onTapped: lib.StackView.view.push(detailComp, {
                    reader: lib.reader,
                    book: row.modelData
                })
                onLongPressed: row.confirming = true
            }
        }
    }

    Component {
        id: detailComp
        BookDetailScreen { reader: lib.reader }
    }
}

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

    component Btn: Rectangle {
        id: b
        property alias label: t.text
        property bool inverted: false
        property bool active: false
        signal tapped()
        readonly property bool dark: b.active || (btnTap.pressed !== b.inverted)
        width: Math.max(90, t.implicitWidth + 40)
        height: 90
        color: b.dark ? "black" : "white"
        border { color: b.inverted ? "white" : "black"; width: 4 }
        Text {
            id: t
            anchors.centerIn: parent
            color: b.dark ? "white" : "black"
            font { pixelSize: 26; bold: true }
        }
        TapHandler { id: btnTap; onTapped: b.tapped() }
    }

    Rectangle {
        id: header
        width: parent.width; height: 120
        color: "black"
        Btn {
            anchors { left: parent.left; leftMargin: 30; verticalCenter: parent.verticalCenter }
            label: "BACK"
            inverted: true
            onTapped: lib.StackView.view.pop()
        }
        Text {
            anchors.centerIn: parent
            text: "LIBRARY"
            color: "white"
            font { pixelSize: 44; letterSpacing: 10; bold: true }
        }
        Row {
            anchors { right: parent.right; rightMargin: 40; verticalCenter: parent.verticalCenter }
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
    }

    // Toolbar: IMPORT / SORT / search
    Item {
        id: toolbar
        anchors { top: header.bottom; left: parent.left; right: parent.right }
        height: 130
        Row {
            anchors { left: parent.left; leftMargin: 60; verticalCenter: parent.verticalCenter }
            spacing: 20
            Btn {
                label: lib.flash !== "" ? lib.flash : "IMPORT"
                onTapped: {
                    const s = lib.reader.importInbox()
                    lib.flash = "+" + s.imported + " DUP " + s.duplicates + " FAIL " + s.failed
                    flashTimer.restart()
                    lib.reload()
                }
            }
            Btn {
                label: "SORT: " + lib.sortNames[lib.sortMode]
                onTapped: { lib.sortMode = (lib.sortMode + 1) % 5; lib.reload() }
            }
            Rectangle {
                width: 460; height: 90
                color: "white"
                border { color: "black"; width: 4 }
                Text {
                    visible: searchInput.text === ""
                    anchors { left: parent.left; leftMargin: 20; verticalCenter: parent.verticalCenter }
                    text: "SEARCH"
                    color: "#999999"
                    font { pixelSize: 26; bold: true }
                }
                TextInput {
                    id: searchInput
                    anchors { fill: parent; leftMargin: 20; rightMargin: 20 }
                    verticalAlignment: TextInput.AlignVCenter
                    clip: true
                    font { pixelSize: 26; bold: true }
                    onTextChanged: { lib.query = text; lib.reload() }
                }
            }
        }
    }

    Timer { id: flashTimer; interval: 2500; onTriggered: lib.flash = "" }

    // Filter chips with full-library counts
    Row {
        id: chips
        anchors { top: toolbar.bottom; left: parent.left; leftMargin: 60 }
        spacing: 20
        Btn { label: "ALL " + lib.counts.all; active: lib.filter === 0; onTapped: { lib.filter = 0; lib.reload() } }
        Btn { label: "UNREAD " + lib.counts.unread; active: lib.filter === 1; onTapped: { lib.filter = 1; lib.reload() } }
        Btn { label: "READING " + lib.counts.reading; active: lib.filter === 2; onTapped: { lib.filter = 2; lib.reload() } }
        Btn { label: "FINISHED " + lib.counts.finished; active: lib.filter === 3; onTapped: { lib.filter = 3; lib.reload() } }
    }

    Text {  // empty-library / no-results placeholder
        visible: lib.books.length === 0
        anchors { top: chips.bottom; topMargin: 120; horizontalCenter: parent.horizontalCenter }
        horizontalAlignment: Text.AlignHCenter
        color: "#6B6B6B"
        font.pixelSize: 28
        text: lib.counts.all === 0
              ? "No books yet\nDrop PDFs into the inbox and tap IMPORT"
              : "No matches"
    }

    ListView {
        id: list
        anchors {
            top: chips.bottom; topMargin: 30
            left: parent.left; leftMargin: 60
            right: parent.right; rightMargin: 60
            bottom: parent.bottom; bottomMargin: 30
        }
        clip: true
        spacing: 20
        model: lib.books
        delegate: Rectangle {
            id: row
            required property var modelData
            property bool confirming: false
            width: list.width
            height: 200
            color: rowTap.pressed && !row.confirming ? "black" : "white"
            border { color: "black"; width: 4 }

            Rectangle {  // cover box (image or title-block fallback)
                id: coverBox
                visible: !row.confirming
                anchors { left: parent.left; leftMargin: 24; verticalCenter: parent.verticalCenter }
                width: 116; height: 160
                color: "white"
                border { color: "black"; width: 3 }
                Image {
                    id: cover
                    anchors { fill: parent; margins: 3 }
                    fillMode: Image.PreserveAspectFit
                    cache: false
                    source: row.modelData.coverPath !== "" ? "file://" + row.modelData.coverPath : ""
                }
                Text {
                    visible: cover.status !== Image.Ready
                    anchors { fill: parent; margins: 8 }
                    text: row.modelData.title
                    wrapMode: Text.WordWrap
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                    elide: Text.ElideRight
                    maximumLineCount: 5
                    font { pixelSize: 16; bold: true }
                }
            }

            Column {
                visible: !row.confirming
                anchors {
                    left: coverBox.right; leftMargin: 30
                    right: parent.right; rightMargin: 30
                    verticalCenter: parent.verticalCenter
                }
                spacing: 10
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    text: row.modelData.title
                    color: rowTap.pressed ? "white" : "black"
                    font { pixelSize: 34; bold: true }
                }
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    visible: row.modelData.author !== ""
                    text: row.modelData.author
                    color: rowTap.pressed ? "white" : "black"
                    font.pixelSize: 26
                }
                Row {
                    spacing: 16
                    Text {
                        text: row.modelData.format === 1 ? "EPUB" : "PDF"
                        color: rowTap.pressed ? "white" : "black"
                        font { pixelSize: 22; bold: true }
                    }
                    Text {
                        text: row.modelData.state === 0 ? "UNREAD"
                            : Math.round(row.modelData.percentage * 100) + "%"
                        color: rowTap.pressed ? "white" : "black"
                        font.pixelSize: 22
                    }
                }
            }

            Row {
                visible: row.confirming
                anchors.centerIn: parent
                spacing: 40
                Btn {
                    label: "DELETE"
                    onTapped: { lib.reader.deleteBook(row.modelData.id); lib.reload() }
                }
                Btn { label: "CANCEL"; onTapped: row.confirming = false }
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

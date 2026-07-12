pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import CipherCodex

Item {
    id: kept

    required property var reader

    property var rows: []          // flattened header + highlight rows for the ListView
    property string flash: ""      // export result path (or error), shown briefly

    // keptHighlights() is created_at DESC; group by book keeping first-appearance order, then
    // flatten to header rows + highlight rows (bookId can recur non-contiguously if two books
    // interleave by time, so pre-group rather than trust adjacency).
    function reload() {
        const items = kept.reader.keptHighlights()
        const groups = []
        const byId = ({})
        for (var i = 0; i < items.length; ++i) {
            const h = items[i]
            var g = byId[h.bookId]
            if (g === undefined) {
                g = { title: h.bookTitle, author: h.bookAuthor, rows: [] }
                byId[h.bookId] = g
                groups.push(g)
            }
            g.rows.push(h)
        }
        const flat = []
        for (var j = 0; j < groups.length; ++j) {
            const grp = groups[j]
            flat.push({ header: true, title: grp.title, author: grp.author })
            for (var k = 0; k < grp.rows.length; ++k)
                flat.push({ header: false, h: grp.rows[k] })
        }
        kept.rows = flat
    }

    function openHighlight(h) {
        if (h.format === 1) {
            kept.StackView.view.push(epubReaderComp, {
                bookId: h.bookId,
                filePath: h.filePath,
                title: h.bookTitle,
                startSpine: h.spineIndex,
                startCharOffset: h.startChar
            })
        } else {
            kept.StackView.view.push(pdfReaderComp, {
                bookId: h.bookId,
                filePath: h.filePath,
                title: h.bookTitle,
                startPage: h.spineIndex
            })
        }
    }

    Component.onCompleted: reload()
    StackView.onActivated: reload()  // highlights may have changed while reading

    component Btn: Rectangle {
        id: b
        property alias label: btnText.text
        property bool inverted: false
        signal tapped()
        width: Math.max(90, btnText.implicitWidth + 48)
        height: 90
        color: (btnTap.pressed !== b.inverted) ? "black" : "white"
        border { color: b.inverted ? "white" : "black"; width: 4 }
        Text {
            id: btnText
            anchors.centerIn: parent
            color: (btnTap.pressed !== b.inverted) ? "white" : "black"
            font { pixelSize: 28; bold: true }
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
            onTapped: kept.StackView.view.pop()
        }
        Row {
            anchors.centerIn: parent
            spacing: 20
            Row {
                anchors.verticalCenter: parent.verticalCenter
                spacing: 8
                Repeater {
                    model: [30, 46, 62, 46, 30]
                    Rectangle {
                        required property int modelData
                        width: 10; height: modelData; color: "white"
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }
            }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: "KEPT"
                color: "white"
                font { pixelSize: 44; letterSpacing: 10; bold: true }
            }
        }
    }

    Row {
        id: actions
        anchors { top: header.bottom; topMargin: 24; left: parent.left; leftMargin: 60 }
        spacing: 30
        Btn {
            label: "EXPORT MARKDOWN"
            onTapped: {
                const ok = kept.reader.exportKeptMarkdown("/home/root/ciphercodex/kept.md")
                kept.flash = ok ? "Wrote /home/root/ciphercodex/kept.md" : "Export failed"
            }
        }
        Text {
            anchors.verticalCenter: parent.verticalCenter
            visible: kept.flash !== ""
            text: kept.flash
            font.pixelSize: 22
        }
    }

    Text {
        visible: kept.rows.length === 0
        anchors.centerIn: parent
        text: "NOTHING KEPT YET"
        color: "#6B6B6B"
        font { pixelSize: 34; bold: true }
    }

    ListView {
        id: list
        anchors {
            top: actions.bottom; topMargin: 24
            left: parent.left; leftMargin: 60
            right: parent.right; rightMargin: 60
            bottom: parent.bottom; bottomMargin: 30
        }
        clip: true
        spacing: 16
        model: kept.rows
        delegate: Rectangle {
            id: row
            required property var modelData
            width: list.width
            height: row.modelData.header ? 96 : 160
            color: (!row.modelData.header && rowTap.pressed) ? "black" : "white"
            border { color: "black"; width: row.modelData.header ? 0 : 3 }

            Text {  // book header
                visible: row.modelData.header
                anchors { left: parent.left; leftMargin: 8; right: parent.right; rightMargin: 8
                          verticalCenter: parent.verticalCenter }
                elide: Text.ElideRight
                text: row.modelData.header
                      ? (row.modelData.author
                         ? row.modelData.title + "  —  " + row.modelData.author
                         : row.modelData.title)
                      : ""
                font { pixelSize: 32; bold: true }
            }

            Column {  // highlight
                visible: !row.modelData.header
                anchors { left: parent.left; leftMargin: 20; right: parent.right; rightMargin: 20
                          verticalCenter: parent.verticalCenter }
                spacing: 8
                Text {
                    width: parent.width
                    wrapMode: Text.WordWrap
                    maximumLineCount: 3
                    elide: Text.ElideRight
                    text: row.modelData.header ? "" : ("“" + row.modelData.h.text + "”")
                    color: rowTap.pressed ? "white" : "black"
                    font.pixelSize: 26
                }
                Text {
                    visible: !row.modelData.header && row.modelData.h.note
                            && row.modelData.h.note !== ""
                    width: parent.width
                    elide: Text.ElideRight
                    text: row.modelData.header ? "" : row.modelData.h.note
                    color: rowTap.pressed ? "white" : "black"
                    font { pixelSize: 22; italic: true }
                }
            }

            TapHandler {
                id: rowTap
                enabled: !row.modelData.header
                onTapped: kept.openHighlight(row.modelData.h)
            }
        }
    }

    Component {
        id: epubReaderComp
        EpubReaderScreen { reader: kept.reader }
    }

    Component {
        id: pdfReaderComp
        PdfReaderScreen { reader: kept.reader }
    }
}

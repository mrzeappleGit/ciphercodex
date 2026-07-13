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
    Connections {
        target: kept.reader
        // a sync started from Home merged rows while this list was open
        function onSyncedDataChanged() { kept.reload() }
    }

    // Header band — 140px solid black, back + title left, action chip right
    Rectangle {
        id: header
        width: parent.width; height: Theme.headerBand
        color: "black"

        Item {  // back zone: full band height, padded past the visible glyphs
            anchors { left: parent.left; top: parent.top; bottom: parent.bottom }
            width: backRow.width + Theme.pad * 2
            Rectangle { anchors.fill: parent; color: "white"; visible: keptBackTap.pressed }
            Row {
                id: backRow
                anchors { left: parent.left; leftMargin: Theme.pad; verticalCenter: parent.verticalCenter }
                spacing: 24
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "←"; color: keptBackTap.pressed ? "black" : "white"
                    font.pixelSize: 34  // default family: Rajdhani has no U+2190
                }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "KEPT"; color: keptBackTap.pressed ? "black" : "white"
                    font { family: Theme.display; pixelSize: Theme.h2; weight: Font.Bold; letterSpacing: 2 }
                }
            }
            TapHandler { id: keptBackTap; onTapped: kept.StackView.view.pop() }
        }

        Item {  // export zone: full band height so the touch target is >= 90px
            anchors { right: parent.right; top: parent.top; bottom: parent.bottom }
            width: exportChip.width + Theme.pad * 2
            Rectangle {
                id: exportChip
                anchors { right: parent.right; rightMargin: Theme.pad; verticalCenter: parent.verticalCenter }
                width: exportText.implicitWidth + 56; height: 88
                color: exportTap.pressed ? "white" : "black"
                border { color: exportTap.pressed ? "black" : "white"; width: Theme.chip }
                Text {
                    id: exportText
                    anchors.centerIn: parent
                    text: "EXPORT"
                    color: exportTap.pressed ? "black" : "white"
                    font { family: Theme.display; pixelSize: 26; weight: Font.Bold; letterSpacing: 1 }
                }
            }
            TapHandler {
                id: exportTap
                onTapped: {
                    const ok = kept.reader.exportKeptMarkdown("/home/root/ciphercodex/kept.md")
                    kept.flash = ok ? "Wrote /home/root/ciphercodex/kept.md" : "Export failed"
                }
            }
        }
    }

    Text {  // export result flash; 0-height when empty so the list sits flush under the band
        id: flashLine
        anchors { top: header.bottom; topMargin: visible ? 20 : 0
                  left: parent.left; leftMargin: Theme.pad
                  right: parent.right; rightMargin: Theme.pad }
        visible: kept.flash !== ""
        height: visible ? implicitHeight : 0
        elide: Text.ElideRight
        text: kept.flash
        color: "black"
        font { family: Theme.mono; pixelSize: Theme.caption }
    }

    Text {
        visible: kept.rows.length === 0
        anchors.centerIn: parent
        text: "NOTHING KEPT YET"
        color: "black"
        font { family: Theme.display; pixelSize: 34; weight: Font.Bold; letterSpacing: 2 }
    }

    ListView {
        id: list
        anchors {
            top: flashLine.bottom; topMargin: 40
            left: parent.left; leftMargin: Theme.pad
            right: parent.right; rightMargin: Theme.pad
            bottom: parent.bottom; bottomMargin: 40
        }
        clip: true
        spacing: 24
        model: kept.rows
        delegate: Rectangle {
            id: row
            required property var modelData
            width: list.width
            height: row.modelData.header
                    ? 96
                    : Math.max(Theme.touch, quoteCol.implicitHeight + 16)
            color: (!row.modelData.header && rowTap.pressed) ? "black" : "white"

            // Book group header: Rajdhani Bold 30 over a 3px rule, bottom-anchored so the
            // slack above it reads as group separation.
            Rectangle {
                visible: row.modelData.header
                anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
                height: Theme.chip
                color: "black"
            }
            Text {
                visible: row.modelData.header
                anchors { left: parent.left; right: parent.right; bottom: parent.bottom; bottomMargin: 17 }
                elide: Text.ElideRight
                text: row.modelData.header
                      ? (row.modelData.author
                         ? row.modelData.title + " — " + row.modelData.author
                         : row.modelData.title)
                      : ""
                font { family: Theme.display; pixelSize: 30; weight: Font.Bold
                       capitalization: Font.AllUppercase }
            }

            // Highlight row: 6px leader bar, italic quote, mono meta, → affordance
            Rectangle {
                id: leader
                visible: !row.modelData.header
                anchors { left: parent.left; top: parent.top; bottom: parent.bottom }
                width: 6
                color: rowTap.pressed ? "white" : "black"
            }
            Column {
                id: quoteCol
                visible: !row.modelData.header
                anchors { left: leader.right; leftMargin: 24
                          right: arrow.left; rightMargin: 24
                          verticalCenter: parent.verticalCenter }
                spacing: 8
                Text {
                    width: parent.width
                    wrapMode: Text.WordWrap
                    maximumLineCount: 3
                    elide: Text.ElideRight
                    lineHeight: 1.5
                    text: row.modelData.header ? "" : ("“" + row.modelData.h.text + "”")
                    color: rowTap.pressed ? "white" : "black"
                    font { family: Theme.reading; italic: true; pixelSize: 26 }
                }
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    text: row.modelData.header ? ""
                          : ((row.modelData.h.format === 1
                              ? "ch. " + (row.modelData.h.spineIndex + 1)
                              : "p. " + (row.modelData.h.spineIndex + 1))
                             + (row.modelData.h.note && row.modelData.h.note !== ""
                                ? " · note: " + row.modelData.h.note : ""))
                    color: rowTap.pressed ? "white" : "black"
                    font { family: Theme.mono; pixelSize: Theme.micro }
                }
            }
            Text {
                id: arrow
                visible: !row.modelData.header
                anchors { right: parent.right; rightMargin: 8; verticalCenter: parent.verticalCenter }
                text: "→"
                color: rowTap.pressed ? "white" : "black"
                font.pixelSize: 26
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

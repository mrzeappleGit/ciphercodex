pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import CipherCodex

Item {
    id: detail

    required property var reader
    property var book: ({})           // set via StackView.push
    property bool confirming: false
    property var bmItems: []          // [{id,page,spineIndex,charOffset,percentage,label}]

    readonly property bool isEpub: detail.book.format === 1
    // A book synced from a peer whose file never downloaded has an empty file_path ("ghost" book):
    // opening it would hand the reader an empty path. Gate the read actions on the file being present.
    readonly property bool downloaded: !!detail.book.filePath
    readonly property real pct: detail.book.percentage ? detail.book.percentage : 0

    // Refresh on every activation: also picks up marks added/removed inside the reader on pop-back.
    StackView.onActivated: {
        if (detail.book.id !== undefined)
            detail.bmItems = detail.reader.bookmarks(detail.book.id)
    }

    function humanSize(bytes) {
        if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + " MB"
        if (bytes >= 1024) return Math.round(bytes / 1024) + " KB"
        return bytes + " B"
    }

    // startPage < 0 => RESUME (saved position); 0 => READ FROM START.
    function openAt(startPage) {
        const p = detail.reader.openProgress(detail.book.id) // marks opened
        const resume = startPage < 0
        if (detail.isEpub) {
            // The reader opens immediately and calls pullOnOpen itself (async): the JUMP/STAY
            // prompt appears when the server answers, never blocking the open.
            detail.StackView.view.push(epubReaderComp, {
                bookId: detail.book.id,
                filePath: detail.book.filePath,
                title: detail.book.title,
                startSpine: resume && p.exists ? p.spineIndex : 0,
                startCharOffset: resume && p.exists ? (p.charOffset || 0) : 0
            })
            return
        }
        detail.StackView.view.push(readerComp, {
            bookId: detail.book.id,
            filePath: detail.book.filePath,
            title: detail.book.title,
            startPage: resume ? p.page : startPage
        })
    }

    // Jump straight to a bookmark — same push as openAt, positioned at the mark.
    function openBookmark(bm) {
        detail.reader.openProgress(detail.book.id) // marks opened
        if (detail.isEpub) {
            detail.StackView.view.push(epubReaderComp, {
                bookId: detail.book.id,
                filePath: detail.book.filePath,
                title: detail.book.title,
                startSpine: bm.spineIndex,
                startCharOffset: bm.charOffset || 0
            })
            return
        }
        detail.StackView.view.push(readerComp, {
            bookId: detail.book.id,
            filePath: detail.book.filePath,
            title: detail.book.title,
            startPage: bm.page
        })
    }

    component Btn: Rectangle {
        id: b
        property alias label: t.text
        property bool inverted: false
        signal tapped()
        readonly property bool dark: btnTap.pressed !== b.inverted
        width: Math.max(Theme.touch, t.implicitWidth + 56)
        height: Theme.touch
        color: b.dark ? "black" : "white"
        border { color: "black"; width: Theme.chip }
        Text {
            id: t
            anchors.centerIn: parent
            color: b.dark ? "white" : "black"
            font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.button }
        }
        TapHandler { id: btnTap; onTapped: b.tapped() }
    }

    Rectangle {
        id: header
        width: parent.width; height: Theme.headerBand
        color: "black"
        Item {  // back confined to the left zone (siblings put action chips on the right)
            anchors { left: parent.left; top: parent.top; bottom: parent.bottom }
            width: detailBackRow.width + Theme.pad * 2
            Rectangle { anchors.fill: parent; color: "white"; visible: detailBackTap.pressed }
            Row {
                id: detailBackRow
                anchors { left: parent.left; leftMargin: Theme.pad; verticalCenter: parent.verticalCenter }
                spacing: 24
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "←"
                    color: detailBackTap.pressed ? "black" : "white"
                    font.pixelSize: 34
                }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "BOOK DETAIL"
                    color: detailBackTap.pressed ? "black" : "white"
                    font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.h2; letterSpacing: 2 }
                }
            }
            TapHandler { id: detailBackTap; onTapped: detail.StackView.view.pop() }
        }
    }

    Rectangle {  // cover
        id: coverBox
        anchors { top: header.bottom; topMargin: Theme.pad; left: parent.left; leftMargin: Theme.pad }
        width: 320; height: 440
        color: "white"
        border { color: "black"; width: Theme.frame }
        Image {
            id: cover
            anchors { fill: parent; margins: Theme.frame }
            fillMode: Image.PreserveAspectFit
            cache: false
            source: detail.book.coverPath ? "file://" + detail.book.coverPath : ""
        }
        DotGrid {
            visible: cover.status !== Image.Ready
            anchors { fill: parent; margins: Theme.frame }
            pitch: 8; dot: 1.8
        }
        Rectangle {  // corner letter badge, flush with the frame's outer corner
            visible: cover.status !== Image.Ready
            x: 0; y: parent.height - height
            width: 48; height: 48
            color: "black"
            Text {
                anchors.centerIn: parent
                text: detail.book.title ? detail.book.title.charAt(0).toUpperCase() : "?"
                color: "white"
                font { family: Theme.display; weight: Font.Bold; pixelSize: 30 }
            }
        }
    }

    Column {
        anchors { top: coverBox.top; topMargin: 8; left: coverBox.right; leftMargin: 48
                  right: parent.right; rightMargin: Theme.pad }
        spacing: 14
        Text {
            width: parent.width
            wrapMode: Text.WordWrap
            maximumLineCount: 3
            elide: Text.ElideRight
            text: detail.book.title ? detail.book.title : ""
            font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.tileLabel; letterSpacing: 1 }
        }
        Text {
            width: parent.width
            visible: !!detail.book.author
            wrapMode: Text.WordWrap
            text: detail.book.author ? detail.book.author : ""
            font { family: Theme.mono; pixelSize: 24 }
        }
        Text {
            text: (detail.isEpub ? "EPUB" : "PDF") + " · " + detail.humanSize(detail.book.sizeBytes || 0)
            font { family: Theme.mono; pixelSize: Theme.caption }
        }
        Item { width: 1; height: 12 }
        Rectangle {  // progress bar
            width: parent.width; height: 28
            color: "white"
            border { color: "black"; width: Theme.chip }
            Rectangle {
                x: Theme.chip; y: Theme.chip
                width: Math.round((parent.width - 2 * Theme.chip) * Math.min(1, detail.pct))
                height: parent.height - 2 * Theme.chip
                color: "black"
            }
        }
        Text {
            // No page/pageCount on the book record — percentage is the data we have.
            text: Math.round(detail.pct * 100) + "%"
            font { family: Theme.mono; pixelSize: Theme.caption }
        }
    }

    Rectangle {  // primary action — inverted full-width; Hatch-disabled for ghost books
        id: primaryBtn
        anchors { top: coverBox.bottom; topMargin: 40
                  left: parent.left; leftMargin: Theme.pad; right: parent.right; rightMargin: Theme.pad }
        height: 96
        color: detail.downloaded && !primTap.pressed ? "black" : "white"
        border { color: "black"; width: detail.downloaded ? Theme.chip : Theme.hairline }
        Hatch {
            visible: !detail.downloaded
            anchors { fill: parent; margins: Theme.hairline }
        }
        Rectangle {
            anchors.centerIn: parent
            width: primLabel.implicitWidth + 28
            height: primLabel.implicitHeight + 12
            color: detail.downloaded && !primTap.pressed ? "black" : "white"
            Text {
                id: primLabel
                anchors.centerIn: parent
                text: detail.downloaded ? "RESUME READING" : "NOT DOWNLOADED — SYNC AGAIN"
                color: detail.downloaded && !primTap.pressed ? "white" : "black"
                font { family: Theme.display; weight: Font.Bold; pixelSize: 32; letterSpacing: 2 }
            }
        }
        TapHandler { id: primTap; enabled: detail.downloaded; onTapped: detail.openAt(-1) }
    }

    Rectangle {  // secondary action — kept from the existing screen (not in the mock)
        id: startBtn
        anchors { top: primaryBtn.bottom; topMargin: 24
                  left: parent.left; leftMargin: Theme.pad; right: parent.right; rightMargin: Theme.pad }
        height: Theme.touch
        color: startTap.pressed && detail.downloaded ? "black" : "white"
        border { color: "black"; width: detail.downloaded ? Theme.chip : Theme.hairline }
        Hatch {
            visible: !detail.downloaded
            anchors { fill: parent; margins: Theme.hairline }
        }
        Rectangle {
            anchors.centerIn: parent
            width: startLabel.implicitWidth + 28
            height: startLabel.implicitHeight + 12
            color: startTap.pressed && detail.downloaded ? "black" : "white"
            Text {
                id: startLabel
                anchors.centerIn: parent
                text: "READ FROM START"
                color: startTap.pressed && detail.downloaded ? "white" : "black"
                font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.button; letterSpacing: 1 }
            }
        }
        TapHandler { id: startTap; enabled: detail.downloaded; onTapped: detail.openAt(0) }
    }

    Text {
        id: bmHeading
        visible: detail.bmItems.length > 0
        anchors { top: startBtn.bottom; topMargin: 40; left: parent.left; leftMargin: Theme.pad }
        text: "BOOKMARKS"
        font { family: Theme.mono; pixelSize: Theme.secondary; letterSpacing: 2 }
    }
    Rectangle {
        id: bmTopRule
        visible: bmHeading.visible
        anchors { top: bmHeading.bottom; topMargin: 16
                  left: parent.left; leftMargin: Theme.pad; right: parent.right; rightMargin: Theme.pad }
        height: Theme.hairline
        color: "black"
    }
    ListView {
        id: bmList
        visible: bmHeading.visible
        anchors { top: bmTopRule.bottom; bottom: deleteBox.top; bottomMargin: 40
                  left: parent.left; leftMargin: Theme.pad; right: parent.right; rightMargin: Theme.pad }
        clip: true
        model: detail.bmItems
        delegate: Item {
            id: bmRow
            required property var modelData
            width: bmList.width; height: 96
            Text {
                anchors { left: parent.left; right: bmArrow.left; rightMargin: 24
                          verticalCenter: parent.verticalCenter }
                elide: Text.ElideRight
                text: bmRow.modelData.label ? bmRow.modelData.label : ""
                font { family: Theme.display; weight: Font.DemiBold; pixelSize: 26 }
            }
            Text {
                id: bmArrow
                anchors { right: parent.right; verticalCenter: parent.verticalCenter }
                text: "→"
                font.pixelSize: 28
            }
            Rectangle {
                anchors.bottom: parent.bottom
                width: parent.width; height: Theme.hairline
                color: "black"
            }
            TapHandler {
                enabled: detail.downloaded
                onTapped: detail.openBookmark(bmRow.modelData)
            }
        }
    }

    Rectangle {  // delete — rest: single bordered row; tap expands in place to the confirm pattern
        id: deleteBox
        anchors { bottom: parent.bottom; bottomMargin: Theme.pad
                  left: parent.left; leftMargin: Theme.pad; right: parent.right; rightMargin: Theme.pad }
        height: detail.confirming ? 140 : 96
        color: "white"
        border { color: "black"; width: detail.confirming ? Theme.frame : Theme.hairline }
        Text {
            visible: !detail.confirming
            anchors { left: parent.left; leftMargin: 24; verticalCenter: parent.verticalCenter }
            text: "DELETE"
            font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.button }
        }
        TapHandler { enabled: !detail.confirming; onTapped: detail.confirming = true }

        Text {
            visible: detail.confirming
            anchors { left: parent.left; leftMargin: 32; verticalCenter: parent.verticalCenter }
            text: "DELETE THIS BOOK?"
            font { family: Theme.display; weight: Font.Bold; pixelSize: 28; letterSpacing: 1 }
        }
        Row {
            visible: detail.confirming
            anchors { right: parent.right; rightMargin: 32; verticalCenter: parent.verticalCenter }
            spacing: 16
            Btn { label: "CANCEL"; onTapped: detail.confirming = false }
            Btn {
                label: "CONFIRM"
                inverted: true
                onTapped: {
                    detail.reader.deleteBook(detail.book.id)
                    detail.StackView.view.pop()
                }
            }
        }
    }

    Component {
        id: readerComp
        PdfReaderScreen { reader: detail.reader }
    }

    Component {
        id: epubReaderComp
        EpubReaderScreen { reader: detail.reader }
    }
}

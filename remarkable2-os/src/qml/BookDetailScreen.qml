pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import CipherCodex

Item {
    id: detail

    required property var reader
    property var book: ({})           // set via StackView.push
    property bool confirming: false

    readonly property bool isEpub: detail.book.format === 1
    // A book synced from a peer whose file never downloaded has an empty file_path ("ghost" book):
    // opening it would hand the reader an empty path. Gate the read actions on the file being present.
    readonly property bool downloaded: !!detail.book.filePath

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

    component Btn: Rectangle {
        id: b
        property alias label: t.text
        property bool inverted: false
        signal tapped()
        readonly property bool dark: btnTap.pressed !== b.inverted
        width: Math.max(90, t.implicitWidth + 48)
        height: 100
        color: b.dark ? "black" : "white"
        // b.enabled is Item's built-in flag (default true); when false the item also stops the tap.
        border { color: b.enabled ? (b.inverted ? "white" : "black") : "#999999"; width: 4 }
        Text {
            id: t
            anchors.centerIn: parent
            color: b.enabled ? (b.dark ? "white" : "black") : "#999999"
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
            onTapped: detail.StackView.view.pop()
        }
        Text {
            anchors { left: parent.left; leftMargin: 260; right: parent.right; rightMargin: 40
                      verticalCenter: parent.verticalCenter }
            elide: Text.ElideRight
            text: detail.book.title ? detail.book.title : ""
            color: "white"
            font { pixelSize: 40; bold: true }
        }
    }

    Row {
        anchors { top: header.bottom; topMargin: 60; left: parent.left; leftMargin: 80 }
        spacing: 60

        Rectangle {  // cover
            id: coverBox
            width: 320; height: 440
            color: "white"
            border { color: "black"; width: 4 }
            Image {
                id: cover
                anchors { fill: parent; margins: 4 }
                fillMode: Image.PreserveAspectFit
                cache: false
                source: detail.book.coverPath ? "file://" + detail.book.coverPath : ""
            }
            Text {
                visible: cover.status !== Image.Ready
                anchors { fill: parent; margins: 16 }
                text: detail.book.title ? detail.book.title : ""
                wrapMode: Text.WordWrap
                horizontalAlignment: Text.AlignHCenter
                verticalAlignment: Text.AlignVCenter
                elide: Text.ElideRight
                font { pixelSize: 30; bold: true }
            }
        }

        Column {
            spacing: 30
            width: 700
            Text {
                width: parent.width
                wrapMode: Text.WordWrap
                text: detail.book.title ? detail.book.title : ""
                font { pixelSize: 46; bold: true }
            }
            Text {
                width: parent.width
                visible: detail.book.author !== "" && detail.book.author !== undefined
                wrapMode: Text.WordWrap
                text: detail.book.author ? detail.book.author : ""
                font.pixelSize: 32
            }
            Text {
                text: (detail.isEpub ? "EPUB" : "PDF") + "   " + detail.humanSize(detail.book.sizeBytes || 0)
                font.pixelSize: 28
            }

            Canvas {  // progress ring + %
                id: ring
                width: 220; height: 220
                property real pct: detail.book.percentage ? detail.book.percentage : 0
                onPctChanged: requestPaint()
                onPaint: {
                    const ctx = getContext("2d")
                    ctx.reset()
                    const cx = width / 2, cy = height / 2, r = width / 2 - 14
                    ctx.lineWidth = 14
                    ctx.strokeStyle = "#cccccc"          // track: reads light on e-ink
                    ctx.beginPath(); ctx.arc(cx, cy, r, 0, 2 * Math.PI); ctx.stroke()
                    ctx.strokeStyle = "black"
                    ctx.beginPath()
                    ctx.arc(cx, cy, r, -Math.PI / 2, -Math.PI / 2 + 2 * Math.PI * ring.pct)
                    ctx.stroke()
                }
                Text {
                    anchors.centerIn: parent
                    text: Math.round(ring.pct * 100) + "%"
                    font { pixelSize: 48; bold: true }
                }
            }
        }
    }

    Text {
        visible: !detail.downloaded && !detail.confirming
        anchors { bottom: parent.bottom; bottomMargin: 180; left: parent.left; leftMargin: 80 }
        text: "NOT DOWNLOADED — SYNC AGAIN"
        color: "#999999"
        font { pixelSize: 26; bold: true }
    }

    Row {
        anchors { bottom: parent.bottom; bottomMargin: 60; left: parent.left; leftMargin: 80 }
        spacing: 40
        visible: !detail.confirming
        Btn { label: "RESUME"; enabled: detail.downloaded; onTapped: detail.openAt(-1) }
        Btn { label: "READ FROM START"; enabled: detail.downloaded; onTapped: detail.openAt(0) }
        Btn { label: "DELETE"; onTapped: detail.confirming = true }
    }

    Row {
        anchors { bottom: parent.bottom; bottomMargin: 60; left: parent.left; leftMargin: 80 }
        spacing: 40
        visible: detail.confirming
        Text {
            anchors.verticalCenter: parent.verticalCenter
            text: "DELETE THIS BOOK?"
            font { pixelSize: 30; bold: true }
        }
        Btn {
            label: "DELETE"
            onTapped: {
                detail.reader.deleteBook(detail.book.id)
                detail.StackView.view.pop()
            }
        }
        Btn { label: "CANCEL"; onTapped: detail.confirming = false }
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

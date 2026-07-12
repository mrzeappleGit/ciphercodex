pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import CipherCodex

Item {
    id: pdfReader

    required property var reader
    property var bookId: -1
    property string filePath: ""
    property string title: ""
    property int startPage: 0

    property string panel: ""        // "", "toc", "bookmarks", "search"
    property var tocItems: []
    property var bmItems: []
    property var searchHits: []

    function saveNow() {
        if (pdfView.pageCount > 0)
            pdfReader.reader.saveProgress(pdfReader.bookId, pdfView.pageIndex, pdfView.pageCount)
    }
    function reloadBookmarks() { pdfReader.bmItems = pdfReader.reader.bookmarks(pdfReader.bookId) }
    function seekTo(frac) {
        if (pdfView.pageCount <= 0) return
        let p = Math.round(frac * (pdfView.pageCount - 1))
        pdfView.goToPage(Math.max(0, Math.min(pdfView.pageCount - 1, p)))
    }

    Component.onCompleted: {
        pdfView.openDocument(pdfReader.filePath)
        if (pdfReader.startPage > 0)
            pdfView.goToPage(pdfReader.startPage)
        pdfReader.reloadBookmarks()
    }
    Component.onDestruction: pdfReader.saveNow()  // belt-and-suspenders: turns already save

    component Btn: Rectangle {
        id: b
        property alias label: t.text
        property bool active: false
        signal tapped()
        readonly property bool dark: btnTap.pressed || b.active
        width: Math.max(88, t.implicitWidth + 22)
        height: 90
        color: b.dark ? "black" : "white"
        border { color: "black"; width: 4 }
        Text {
            id: t
            anchors.centerIn: parent
            color: b.dark ? "white" : "black"
            font { pixelSize: 22; bold: true }
        }
        TapHandler { id: btnTap; onTapped: b.tapped() }
    }

    Rectangle {
        id: toolbar
        width: parent.width
        height: 100
        color: "white"
        z: 2
        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 4; color: "black" }
        Row {
            anchors { left: parent.left; leftMargin: 10; verticalCenter: parent.verticalCenter }
            spacing: 6
            Btn { label: "BACK"; onTapped: pdfReader.StackView.view.pop() }
            Item {
                width: 260; height: 90
                Text {
                    anchors { left: parent.left; leftMargin: 6; verticalCenter: parent.verticalCenter }
                    width: parent.width - 6
                    elide: Text.ElideRight
                    text: pdfReader.title
                    font { pixelSize: 22; bold: true }
                }
            }
            Item {
                width: 130; height: 90
                Text {
                    anchors.centerIn: parent
                    text: (pdfView.pageIndex + 1) + " / " + pdfView.pageCount
                    font.pixelSize: 22
                }
            }
            Btn {
                label: "TOC"
                active: pdfReader.panel === "toc"
                onTapped: {
                    if (pdfReader.panel === "toc") { pdfReader.panel = "" }
                    else {
                        pdfReader.tocItems = pdfReader.reader.pdfOutline(pdfReader.filePath)
                        pdfReader.panel = "toc"
                    }
                }
            }
            Btn {
                label: "MARKS"
                active: pdfReader.panel === "bookmarks"
                onTapped: {
                    if (pdfReader.panel === "bookmarks") { pdfReader.panel = "" }
                    else { pdfReader.reloadBookmarks(); pdfReader.panel = "bookmarks" }
                }
            }
            Btn {
                label: "SEARCH"
                active: pdfReader.panel === "search"
                onTapped: pdfReader.panel = (pdfReader.panel === "search" ? "" : "search")
            }
            Btn {
                label: pdfView.fitMode === 0 ? "FIT: W" : "FIT: P"
                onTapped: pdfView.setFit(pdfView.fitMode === 0 ? 1 : 0)
            }
            Btn { label: "Z+"; onTapped: pdfView.zoom = Math.min(4.0, pdfView.zoom * 1.25) }
            Btn { label: "Z-"; onTapped: pdfView.zoom = Math.max(1.0, pdfView.zoom / 1.25) }
        }
    }

    PdfView {
        id: pdfView
        anchors { top: toolbar.bottom; left: parent.left; right: parent.right; bottom: scrubber.top }
        onPageChanged: pdfReader.saveNow()
    }

    // Progress scrubber: tap or drag to jump
    Rectangle {
        id: scrubber
        anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
        height: 48
        color: "white"
        z: 2
        Rectangle { anchors.top: parent.top; width: parent.width; height: 4; color: "black" }
        Rectangle {
            anchors { left: parent.left; bottom: parent.bottom; bottomMargin: 8 }
            height: 20
            color: "black"
            width: pdfView.pageCount > 0 ? scrubber.width * (pdfView.pageIndex + 1) / pdfView.pageCount : 0
        }
        TapHandler { onTapped: (ep) => pdfReader.seekTo(ep.position.x / scrubber.width) }
        DragHandler {
            target: null
            xAxis.enabled: true
            yAxis.enabled: false
            onCentroidChanged: if (active && scrubber.width > 0)
                                   pdfReader.seekTo(centroid.position.x / scrubber.width)
        }
    }

    // ---- Right-side panels (opaque; overlay PdfView) ----

    Rectangle {  // TOC
        id: tocPanel
        visible: pdfReader.panel === "toc"
        anchors { top: toolbar.bottom; right: parent.right; bottom: scrubber.top }
        width: 560
        color: "white"
        border { color: "black"; width: 4 }
        z: 3
        ListView {
            anchors { fill: parent; margins: 16 }
            clip: true
            spacing: 8
            model: pdfReader.tocItems
            delegate: Rectangle {
                id: tocRow
                required property var modelData
                width: ListView.view.width
                height: 84
                color: tocTap.pressed ? "black" : "white"
                border { color: "black"; width: 3 }
                Text {
                    anchors { left: parent.left; right: pageLbl.left
                              leftMargin: 16 + (tocRow.modelData.level || 0) * 24
                              verticalCenter: parent.verticalCenter }
                    elide: Text.ElideRight
                    text: tocRow.modelData.title
                    color: tocTap.pressed ? "white" : "black"
                    font.pixelSize: 24
                }
                Text {
                    id: pageLbl
                    anchors { right: parent.right; rightMargin: 16; verticalCenter: parent.verticalCenter }
                    text: (tocRow.modelData.pageIndex + 1)
                    color: tocTap.pressed ? "white" : "black"
                    font { pixelSize: 22; bold: true }
                }
                TapHandler {
                    id: tocTap
                    onTapped: { pdfView.goToPage(tocRow.modelData.pageIndex); pdfReader.panel = "" }
                }
            }
        }
    }

    Rectangle {  // BOOKMARKS
        id: bmPanel
        visible: pdfReader.panel === "bookmarks"
        anchors { top: toolbar.bottom; right: parent.right; bottom: scrubber.top }
        width: 560
        color: "white"
        border { color: "black"; width: 4 }
        z: 3
        Btn {
            id: addBm
            anchors { top: parent.top; topMargin: 16; horizontalCenter: parent.horizontalCenter }
            label: "ADD BOOKMARK"
            onTapped: {
                pdfReader.reader.addBookmark(pdfReader.bookId, pdfView.pageIndex,
                                             pdfView.pageCount, "Page " + (pdfView.pageIndex + 1))
                pdfReader.reloadBookmarks()
            }
        }
        ListView {
            anchors { top: addBm.bottom; topMargin: 16; left: parent.left; right: parent.right
                      bottom: parent.bottom; leftMargin: 16; rightMargin: 16; bottomMargin: 16 }
            clip: true
            spacing: 8
            model: pdfReader.bmItems
            delegate: Rectangle {
                id: bmRow
                required property var modelData
                width: ListView.view.width
                height: 84
                color: bmTap.pressed ? "black" : "white"
                border { color: "black"; width: 3 }
                Text {
                    anchors { left: parent.left; leftMargin: 16; right: delBm.left; rightMargin: 12
                              verticalCenter: parent.verticalCenter }
                    elide: Text.ElideRight
                    text: bmRow.modelData.label
                    color: bmTap.pressed ? "white" : "black"
                    font.pixelSize: 24
                }
                TapHandler {
                    id: bmTap
                    onTapped: { pdfView.goToPage(bmRow.modelData.page); pdfReader.panel = "" }
                }
                Btn {
                    id: delBm
                    anchors { right: parent.right; rightMargin: 6; verticalCenter: parent.verticalCenter }
                    height: 72
                    label: "DEL"
                    onTapped: {
                        pdfReader.reader.deleteBookmark(bmRow.modelData.id)
                        pdfReader.reloadBookmarks()
                    }
                }
            }
        }
    }

    Rectangle {  // SEARCH
        id: searchPanel
        visible: pdfReader.panel === "search"
        anchors { top: toolbar.bottom; right: parent.right; bottom: scrubber.top }
        width: 560
        color: "white"
        border { color: "black"; width: 4 }
        z: 3
        Row {
            id: searchRow
            anchors { top: parent.top; left: parent.left; right: parent.right; margins: 16 }
            spacing: 12
            Rectangle {
                width: parent.width - 150; height: 84
                color: "white"
                border { color: "black"; width: 3 }
                Text {
                    visible: searchField.text === ""
                    anchors { left: parent.left; leftMargin: 14; verticalCenter: parent.verticalCenter }
                    text: "FIND"
                    color: "#999999"
                    font { pixelSize: 24; bold: true }
                }
                TextInput {
                    id: searchField
                    anchors { fill: parent; leftMargin: 14; rightMargin: 14 }
                    verticalAlignment: TextInput.AlignVCenter
                    clip: true
                    font { pixelSize: 24; bold: true }
                    onAccepted: pdfReader.searchHits = pdfReader.reader.pdfSearch(pdfReader.filePath, text)
                }
            }
            Btn {
                height: 84
                label: "GO"
                onTapped: pdfReader.searchHits = pdfReader.reader.pdfSearch(pdfReader.filePath, searchField.text)
            }
        }
        ListView {
            anchors { top: searchRow.bottom; topMargin: 16; left: parent.left; right: parent.right
                      bottom: parent.bottom; leftMargin: 16; rightMargin: 16; bottomMargin: 16 }
            clip: true
            spacing: 8
            model: pdfReader.searchHits
            delegate: Rectangle {
                id: hitRow
                required property var modelData
                width: ListView.view.width
                height: 84
                color: hitTap.pressed ? "black" : "white"
                border { color: "black"; width: 3 }
                Text {
                    anchors { left: parent.left; leftMargin: 16; verticalCenter: parent.verticalCenter }
                    text: "Page " + (hitRow.modelData.pageIndex + 1) + "  (" + hitRow.modelData.count + ")"
                    color: hitTap.pressed ? "white" : "black"
                    font.pixelSize: 24
                }
                TapHandler {
                    id: hitTap
                    onTapped: { pdfView.goToPage(hitRow.modelData.pageIndex); pdfReader.panel = "" }
                }
            }
        }
    }
}

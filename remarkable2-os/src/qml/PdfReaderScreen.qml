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
    property bool searching: false
    // ready gates progress saves: the page-0 pageChanged that openDocument emits (and the
    // resume seek) must not persist over the saved position before we've settled.
    property bool ready: false

    property var syncPull: null
    property bool showSyncPrompt: false

    // Local save only on turn; network push happens once on leave (never on the hot path).
    function saveNow() {
        if (pdfReader.ready && pdfView.pageCount > 0)
            pdfReader.reader.saveProgress(pdfReader.bookId, pdfView.pageIndex, pdfView.pageCount)
    }
    function pushNow() {
        if (pdfReader.ready && pdfView.pageCount > 0)
            pdfReader.reader.pushProgress(pdfReader.bookId)
    }
    function reloadBookmarks() { pdfReader.bmItems = pdfReader.reader.bookmarks(pdfReader.bookId) }
    function seekTo(frac) {
        if (pdfView.pageCount <= 0) return
        let p = Math.round(frac * (pdfView.pageCount - 1))
        pdfView.goToPage(Math.max(0, Math.min(pdfView.pageCount - 1, p)))
    }
    function runSearch(q) {
        pdfReader.searchHits = []
        pdfReader.searching = true
        pdfView.startSearch(q)   // chunked + cancelable; hits arrive incrementally
    }

    Component.onCompleted: {
        pdfView.openDocument(pdfReader.filePath)
        if (pdfReader.startPage > 0)
            pdfView.goToPage(pdfReader.startPage)
        pdfReader.reloadBookmarks()
        pdfReader.ready = true    // only now do page changes persist
        pdfReader.reader.pullOnOpen(pdfReader.bookId)  // async -> onPullReady
        pdfReader.reader.syncAllDirty()
    }
    // Debounce: a scrubber drag emits many pageChanged; save once it settles (fsync-heavy).
    Timer { id: saveTimer; interval: 400; onTriggered: pdfReader.saveNow() }
    Component.onDestruction: { saveTimer.stop(); pdfReader.saveNow(); pdfReader.pushNow() }

    Connections {
        target: pdfReader.reader
        function onPullReady(bookId, result) {
            if (bookId === pdfReader.bookId && result.state === "RemoteNewer") {
                pdfReader.syncPull = result
                pdfReader.showSyncPrompt = true
            }
        }
    }

    // Sync JUMP/STAY prompt (PDF: spine is the page index) — hard-offset elevation behind.
    Rectangle {
        visible: pdfReader.showSyncPrompt
        anchors {
            centerIn: parent
            horizontalCenterOffset: Theme.lift
            verticalCenterOffset: Theme.lift
        }
        width: 620; height: 220
        color: "white"; border { color: "black"; width: Theme.chip }
        z: 10
    }
    Rectangle {
        visible: pdfReader.showSyncPrompt
        anchors.centerIn: parent
        width: 620; height: 220
        color: "white"; border { color: "black"; width: Theme.chip }
        z: 10
        Column {
            anchors.centerIn: parent
            spacing: 30
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: pdfReader.syncPull
                      ? "SYNC // " + pdfReader.syncPull.device + " @ "
                        + Math.round(pdfReader.syncPull.percentage * 100) + "%"
                      : ""
                font { family: Theme.mono; pixelSize: Theme.secondary }
            }
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: 40
                Btn {
                    label: "JUMP"
                    onTapped: {
                        if (pdfReader.syncPull.spine >= 0) pdfView.goToPage(pdfReader.syncPull.spine)
                        else pdfReader.seekTo(pdfReader.syncPull.percentage)
                        pdfReader.showSyncPrompt = false
                    }
                }
                Btn { label: "STAY"; onTapped: pdfReader.showSyncPrompt = false }
            }
        }
    }

    Connections {
        target: pdfView
        function onSearchHit(page, count) {
            pdfReader.searchHits = pdfReader.searchHits.concat([{ page: page, count: count }])
        }
        function onSearchFinished(total, canceled) { pdfReader.searching = false }
    }

    // Bordered button (panels/dialogs): Rajdhani Bold 24, chip border, inversion feedback.
    component Btn: Rectangle {
        id: b
        property alias label: t.text
        property bool active: false
        signal tapped()
        readonly property bool dark: btnTap.pressed || b.active
        width: Math.max(Theme.touch, t.implicitWidth + 28)
        height: Theme.touch
        color: b.dark ? "black" : "white"
        border { color: "black"; width: Theme.chip }
        Text {
            id: t
            anchors.centerIn: parent
            color: b.dark ? "white" : "black"
            font { family: Theme.display; pixelSize: Theme.button; weight: Font.Bold }
        }
        // margin pads the hit area to >=90px even when the visual is shorter (e.g. DEL)
        TapHandler { id: btnTap; margin: Math.max(0, (Theme.touch - b.height) / 2); onTapped: b.tapped() }
    }

    // Borderless top-bar label: Share Tech Mono 22, inversion when pressed/active.
    component BarBtn: Rectangle {
        id: bb
        property alias label: bt.text
        property int size: Theme.secondary
        property bool active: false
        signal tapped()
        readonly property bool dark: bbTap.pressed || bb.active
        width: Math.max(Theme.touch, bt.implicitWidth + 24)
        height: 88
        color: bb.dark ? "black" : "white"
        Text {
            id: bt
            anchors.centerIn: parent
            color: bb.dark ? "white" : "black"
            font { family: Theme.mono; pixelSize: bb.size }
        }
        TapHandler { id: bbTap; margin: 1; onTapped: bb.tapped() }
    }

    Rectangle {
        id: toolbar
        width: parent.width
        height: 88
        color: "white"
        z: 2
        Row {
            id: barLeft
            anchors { left: parent.left; leftMargin: 40; verticalCenter: parent.verticalCenter }
            spacing: 28
            BarBtn { label: "←"; size: 30; onTapped: pdfReader.StackView.view.pop() }
            BarBtn {
                label: "TOC"
                active: pdfReader.panel === "toc"
                onTapped: {
                    if (pdfReader.panel === "toc") { pdfReader.panel = "" }
                    else {
                        pdfReader.tocItems = pdfView.outline()   // from the already-open doc
                        pdfReader.panel = "toc"
                    }
                }
            }
            BarBtn {
                label: "MARKS"
                active: pdfReader.panel === "bookmarks"
                onTapped: {
                    if (pdfReader.panel === "bookmarks") { pdfReader.panel = "" }
                    else { pdfReader.reloadBookmarks(); pdfReader.panel = "bookmarks" }
                }
            }
            BarBtn {
                label: "SEARCH"
                active: pdfReader.panel === "search"
                onTapped: pdfReader.panel = (pdfReader.panel === "search" ? "" : "search")
            }
        }
        Text {  // title kept from the existing screen (not in the mock); elides to fit
            anchors { left: barLeft.right; leftMargin: 28; right: barRight.left; rightMargin: 28
                      verticalCenter: parent.verticalCenter }
            elide: Text.ElideRight
            text: pdfReader.title
            font { family: Theme.mono; pixelSize: Theme.secondary }
        }
        Row {
            id: barRight
            anchors { right: parent.right; rightMargin: 40; verticalCenter: parent.verticalCenter }
            spacing: 28
            BarBtn {
                label: pdfView.fitMode === 0 ? "FIT: W" : "FIT: P"
                onTapped: pdfView.setFit(pdfView.fitMode === 0 ? 1 : 0)
            }
            BarBtn { label: "Z-"; onTapped: pdfView.zoom = Math.max(1.0, pdfView.zoom / 1.25) }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: Math.round(pdfView.zoom * 100) + "%"
                font { family: Theme.mono; pixelSize: Theme.secondary }
            }
            BarBtn { label: "Z+"; onTapped: pdfView.zoom = Math.min(4.0, pdfView.zoom * 1.25) }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: (pdfView.pageIndex + 1) + " / " + pdfView.pageCount
                font { family: Theme.mono; pixelSize: Theme.secondary }
            }
        }
        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: Theme.chip; color: "black" }
    }

    PdfView {
        id: pdfView
        anchors { top: toolbar.bottom; left: parent.left; right: parent.right; bottom: scrubber.top }
        onPageChanged: if (pdfReader.ready) saveTimer.restart()
    }

    // Progress rail: a 48px dedicated strip OUTSIDE PdfView (it anchors to scrubber.top),
    // so seek taps/drags can never double-fire the page-turn zones — no handler margins.
    // Visual is the design's 12px black rail + 24px thumb at the strip's bottom.
    Item {
        id: scrubber
        anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
        height: 48
        z: 2
        Rectangle {
            id: rail
            anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
            height: 12
            color: "black"
            Rectangle {
                width: 24; height: 24
                y: -6
                x: pdfView.pageCount > 0
                   ? (pdfView.pageIndex + 1) / pdfView.pageCount * (rail.width - width) : 0
                color: "black"
            }
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

    // ---- Right-side panels (opaque floating cards; overlay PdfView) ----
    // All three share the same geometry, so one offset outline serves whichever is open.

    Rectangle {  // hard-offset elevation behind the open panel
        visible: pdfReader.panel !== ""
        anchors { top: toolbar.bottom; topMargin: 24 + Theme.lift
                  right: parent.right; rightMargin: 24 - Theme.lift
                  bottom: scrubber.top; bottomMargin: 24 - Theme.lift }
        width: 560
        color: "white"
        border { color: "black"; width: Theme.chip }
        z: 3
    }

    Rectangle {  // TOC
        id: tocPanel
        visible: pdfReader.panel === "toc"
        anchors { top: toolbar.bottom; topMargin: 24; right: parent.right; rightMargin: 24
                  bottom: scrubber.top; bottomMargin: 24 }
        width: 560
        color: "white"
        border { color: "black"; width: Theme.chip }
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
                border { color: "black"; width: Theme.chip }
                Text {
                    anchors { left: parent.left; right: pageLbl.left
                              leftMargin: 16 + (tocRow.modelData.level || 0) * 24
                              verticalCenter: parent.verticalCenter }
                    elide: Text.ElideRight
                    text: tocRow.modelData.title
                    color: tocTap.pressed ? "white" : "black"
                    font { family: Theme.display; pixelSize: Theme.body; weight: Font.DemiBold }
                }
                Text {
                    id: pageLbl
                    anchors { right: parent.right; rightMargin: 16; verticalCenter: parent.verticalCenter }
                    text: (tocRow.modelData.page + 1)
                    color: tocTap.pressed ? "white" : "black"
                    font { family: Theme.mono; pixelSize: Theme.secondary }
                }
                TapHandler {
                    id: tocTap
                    onTapped: { pdfView.goToPage(tocRow.modelData.page); pdfReader.panel = "" }
                }
            }
        }
    }

    Rectangle {  // BOOKMARKS
        id: bmPanel
        visible: pdfReader.panel === "bookmarks"
        anchors { top: toolbar.bottom; topMargin: 24; right: parent.right; rightMargin: 24
                  bottom: scrubber.top; bottomMargin: 24 }
        width: 560
        color: "white"
        border { color: "black"; width: Theme.chip }
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
                border { color: "black"; width: Theme.chip }
                Text {
                    anchors { left: parent.left; leftMargin: 16; right: delBm.left; rightMargin: 12
                              verticalCenter: parent.verticalCenter }
                    elide: Text.ElideRight
                    text: bmRow.modelData.label
                    color: bmTap.pressed ? "white" : "black"
                    font { family: Theme.display; pixelSize: Theme.body; weight: Font.DemiBold }
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
        anchors { top: toolbar.bottom; topMargin: 24; right: parent.right; rightMargin: 24
                  bottom: scrubber.top; bottomMargin: 24 }
        width: 560
        color: "white"
        border { color: "black"; width: Theme.chip }
        z: 3
        Row {
            id: searchRow
            anchors { top: parent.top; left: parent.left; right: parent.right; margins: 16 }
            spacing: 12
            Rectangle {
                width: parent.width - 150; height: 84
                color: "white"
                border { color: "black"; width: searchField.activeFocus ? Theme.frame : Theme.hairline }
                Text {
                    visible: searchField.text === ""
                    anchors { left: parent.left; leftMargin: 14; verticalCenter: parent.verticalCenter }
                    text: "FIND"
                    color: "#999999"
                    font { family: Theme.reading; pixelSize: Theme.button }
                }
                TextInput {
                    id: searchField
                    anchors { fill: parent; leftMargin: 14; rightMargin: 14 }
                    verticalAlignment: TextInput.AlignVCenter
                    clip: true
                    font { family: Theme.reading; pixelSize: Theme.button }
                    onAccepted: pdfReader.runSearch(text)
                }
            }
            Btn {
                height: 84
                label: pdfReader.searching ? "..." : "GO"
                onTapped: pdfReader.searching ? pdfView.cancelSearch() : pdfReader.runSearch(searchField.text)
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
                border { color: "black"; width: Theme.chip }
                Text {
                    anchors { left: parent.left; leftMargin: 16; verticalCenter: parent.verticalCenter }
                    text: "Page " + (hitRow.modelData.page + 1) + "  (" + hitRow.modelData.count + ")"
                    color: hitTap.pressed ? "white" : "black"
                    font { family: Theme.mono; pixelSize: Theme.secondary }
                }
                TapHandler {
                    id: hitTap
                    onTapped: { pdfView.goToPage(hitRow.modelData.page); pdfReader.panel = "" }
                }
            }
        }
    }
}

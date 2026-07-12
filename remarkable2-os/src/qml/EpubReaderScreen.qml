pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import CipherCodex

Item {
    id: epubReader

    required property var reader
    property var bookId: -1
    property string filePath: ""
    property string title: ""
    property int startSpine: 0
    property int startCharOffset: 0
    property var syncPull: null       // pullOnOpen result, or null; {state,spine,charOffset,percentage,device}

    property string panel: ""         // "", "toc", "bookmarks", "search", "type"
    property var tocItems: []
    property var bmItems: []
    property var searchHits: []
    property bool searching: false
    // ready gates progress saves: the goToLocation seek at open (and its locationChanged)
    // must not persist over the saved position before we've settled.
    property bool ready: false

    // charOffset of the current page's first char, tracked from locationChanged (EpubView has
    // no charOffset property). Seeds from the resume position so a save before the first turn is correct.
    property int charOffset: startCharOffset
    property int jumpDepth: 0          // return-stack depth after following footnote/link jumps
    property string footnoteText: ""   // non-empty => footnote popup shown
    property bool showSyncPrompt: false

    // Typography (persisted in the settings table).
    property string fontName: "Serif"
    property int fontSize: 34
    property real lineSpacing: 1.5
    property int margins: 90
    property bool justify: false

    function saveNow() {
        if (epubReader.ready && epubView.spineCount > 0) {
            epubReader.reader.saveProgress(epubReader.bookId, epubView.spineIndex,
                                           epubReader.charOffset, epubView.percentage)
            epubReader.reader.pushProgress(epubReader.bookId)  // fire-and-forget, dirty-retry
        }
    }
    function reloadBookmarks() { epubReader.bmItems = epubReader.reader.bookmarks(epubReader.bookId) }
    function seekTo(frac) {
        // Chapter-granular whole-book seek: EpubView exposes no percentage seek, so a scrubber
        // tap lands on the nearest spine. ponytail: chapter-granular, fine for a book scrubber.
        if (epubView.spineCount <= 0) return
        let s = Math.round(frac * (epubView.spineCount - 1))
        epubView.goToSpine(Math.max(0, Math.min(epubView.spineCount - 1, s)))
    }
    function runSearch(q) {
        epubReader.searchHits = []
        epubReader.searching = true
        epubView.startSearch(q)   // chunked + cancelable; hits arrive incrementally
    }

    function loadTypo() {
        epubReader.fontName = epubReader.reader.setting("epub_font", "Serif")
        epubReader.fontSize = parseInt(epubReader.reader.setting("epub_size", "34"))
        epubReader.lineSpacing = parseFloat(epubReader.reader.setting("epub_line", "1.5"))
        epubReader.margins = parseInt(epubReader.reader.setting("epub_margins", "90"))
        epubReader.justify = epubReader.reader.setting("epub_justify", "0") === "1"
    }
    function commitTypo() {
        epubReader.reader.setSetting("epub_font", epubReader.fontName)
        epubReader.reader.setSetting("epub_size", "" + epubReader.fontSize)
        epubReader.reader.setSetting("epub_line", "" + epubReader.lineSpacing)
        epubReader.reader.setSetting("epub_margins", "" + epubReader.margins)
        epubReader.reader.setSetting("epub_justify", epubReader.justify ? "1" : "0")
        // EpubView re-paginates and keeps the current offset on typography change.
        epubView.setTypography(epubReader.fontName, epubReader.fontSize,
                               epubReader.lineSpacing, epubReader.margins, epubReader.justify)
    }

    Component.onCompleted: {
        epubReader.loadTypo()
        epubView.openDocument(epubReader.filePath)
        epubView.setTypography(epubReader.fontName, epubReader.fontSize,
                               epubReader.lineSpacing, epubReader.margins, epubReader.justify)
        epubView.goToLocation(epubReader.startSpine, epubReader.startCharOffset)
        epubReader.reloadBookmarks()
        epubReader.ready = true    // only now do turns persist
        if (epubReader.syncPull && epubReader.syncPull.state === "RemoteNewer")
            epubReader.showSyncPrompt = true
    }
    // Debounce: a scrubber/turn burst emits many locationChanged; save once it settles (fsync-heavy).
    Timer { id: saveTimer; interval: 400; onTriggered: epubReader.saveNow() }
    Component.onDestruction: { saveTimer.stop(); epubReader.saveNow() }

    Connections {
        target: epubView
        function onLocationChanged(spine, charOffset) {
            epubReader.charOffset = charOffset
            if (epubReader.ready) saveTimer.restart()
        }
        function onSearchHit(spine, charOffset, snippet) {
            epubReader.searchHits = epubReader.searchHits.concat(
                [{ spine: spine, charOffset: charOffset, snippet: snippet }])
        }
        function onSearchFinished(total, canceled) { epubReader.searching = false }
        function onLinkActivated(href) {
            const r = epubView.follow(href)   // footnote => {footnote:true,text}; jump => navigates + pushes stack
            if (r && r.footnote) epubReader.footnoteText = r.text
            else epubReader.jumpDepth += 1
        }
    }

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
            Btn { label: "BACK"; onTapped: epubReader.StackView.view.pop() }
            Item {
                width: 240; height: 90
                Text {
                    anchors { left: parent.left; leftMargin: 6; verticalCenter: parent.verticalCenter }
                    width: parent.width - 6
                    elide: Text.ElideRight
                    text: epubReader.title
                    font { pixelSize: 22; bold: true }
                }
            }
            Item {
                width: 200; height: 90
                Text {
                    anchors.centerIn: parent
                    text: "CH " + (epubView.spineIndex + 1) + "/" + epubView.spineCount
                          + "  p" + (epubView.pageInSpine + 1) + "/" + epubView.pagesInSpine
                    font.pixelSize: 20
                }
            }
            Btn {
                label: "TOC"
                active: epubReader.panel === "toc"
                onTapped: {
                    if (epubReader.panel === "toc") { epubReader.panel = "" }
                    else {
                        epubReader.tocItems = epubView.toc()   // from the already-open doc
                        epubReader.panel = "toc"
                    }
                }
            }
            Btn {
                label: "MARKS"
                active: epubReader.panel === "bookmarks"
                onTapped: {
                    if (epubReader.panel === "bookmarks") { epubReader.panel = "" }
                    else { epubReader.reloadBookmarks(); epubReader.panel = "bookmarks" }
                }
            }
            Btn {
                label: "SEARCH"
                active: epubReader.panel === "search"
                onTapped: epubReader.panel = (epubReader.panel === "search" ? "" : "search")
            }
            Btn {
                label: "Aa"
                active: epubReader.panel === "type"
                onTapped: epubReader.panel = (epubReader.panel === "type" ? "" : "type")
            }
        }
    }

    EpubView {
        id: epubView
        anchors { top: toolbar.bottom; left: parent.left; right: parent.right; bottom: scrubber.top }
    }

    // Jump-back affordance: after following a link jump, return down the 10-deep stack.
    Btn {
        anchors { left: parent.left; leftMargin: 20; bottom: scrubber.top; bottomMargin: 20 }
        visible: epubReader.jumpDepth > 0
        z: 4
        label: "← BACK"
        onTapped: { epubView.back(); epubReader.jumpDepth -= 1 }
    }

    // Whole-book progress scrubber: tap to jump (chapter-granular); fill reflects percentage.
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
            width: scrubber.width * epubView.percentage
        }
        Text {
            anchors { right: parent.right; rightMargin: 16; verticalCenter: parent.verticalCenter }
            text: Math.round(epubView.percentage * 100) + "%"
            font { pixelSize: 20; bold: true }
        }
        TapHandler { onTapped: (ep) => epubReader.seekTo(ep.position.x / scrubber.width) }
    }

    // ---- Right-side panels (opaque; overlay EpubView) ----

    Rectangle {  // TOC
        visible: epubReader.panel === "toc"
        anchors { top: toolbar.bottom; right: parent.right; bottom: scrubber.top }
        width: 560
        color: "white"
        border { color: "black"; width: 4 }
        z: 3
        ListView {
            anchors { fill: parent; margins: 16 }
            clip: true
            spacing: 8
            model: epubReader.tocItems
            delegate: Rectangle {
                id: tocRow
                required property var modelData
                width: ListView.view.width
                height: 84
                color: tocTap.pressed ? "black" : "white"
                border { color: "black"; width: 3 }
                Text {
                    anchors { left: parent.left; leftMargin: 16; right: parent.right; rightMargin: 16
                              verticalCenter: parent.verticalCenter }
                    elide: Text.ElideRight
                    text: tocRow.modelData.title
                    color: tocTap.pressed ? "white" : "black"
                    font.pixelSize: 24
                }
                TapHandler {
                    id: tocTap
                    onTapped: { epubView.goToSpine(tocRow.modelData.spineIndex); epubReader.panel = "" }
                }
            }
        }
    }

    Rectangle {  // BOOKMARKS
        visible: epubReader.panel === "bookmarks"
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
                epubReader.reader.addBookmark(epubReader.bookId, epubView.spineIndex,
                                              epubReader.charOffset, epubView.percentage,
                                              "Ch " + (epubView.spineIndex + 1))
                epubReader.reloadBookmarks()
            }
        }
        ListView {
            anchors { top: addBm.bottom; topMargin: 16; left: parent.left; right: parent.right
                      bottom: parent.bottom; leftMargin: 16; rightMargin: 16; bottomMargin: 16 }
            clip: true
            spacing: 8
            model: epubReader.bmItems
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
                    text: bmRow.modelData.label + "  " + Math.round(bmRow.modelData.percentage * 100) + "%"
                    color: bmTap.pressed ? "white" : "black"
                    font.pixelSize: 24
                }
                TapHandler {
                    id: bmTap
                    onTapped: {
                        epubView.goToLocation(bmRow.modelData.spineIndex, bmRow.modelData.charOffset)
                        epubReader.panel = ""
                    }
                }
                Btn {
                    id: delBm
                    anchors { right: parent.right; rightMargin: 6; verticalCenter: parent.verticalCenter }
                    height: 72
                    label: "DEL"
                    onTapped: {
                        epubReader.reader.deleteBookmark(bmRow.modelData.id)
                        epubReader.reloadBookmarks()
                    }
                }
            }
        }
    }

    Rectangle {  // SEARCH
        visible: epubReader.panel === "search"
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
                    onAccepted: epubReader.runSearch(text)
                }
            }
            Btn {
                height: 84
                label: epubReader.searching ? "..." : "GO"
                onTapped: epubReader.searching ? epubView.cancelSearch() : epubReader.runSearch(searchField.text)
            }
        }
        ListView {
            anchors { top: searchRow.bottom; topMargin: 16; left: parent.left; right: parent.right
                      bottom: parent.bottom; leftMargin: 16; rightMargin: 16; bottomMargin: 16 }
            clip: true
            spacing: 8
            model: epubReader.searchHits
            delegate: Rectangle {
                id: hitRow
                required property var modelData
                width: ListView.view.width
                height: 96
                color: hitTap.pressed ? "black" : "white"
                border { color: "black"; width: 3 }
                Column {
                    anchors { left: parent.left; leftMargin: 16; right: parent.right; rightMargin: 16
                              verticalCenter: parent.verticalCenter }
                    spacing: 4
                    Text {
                        text: "Ch " + (hitRow.modelData.spine + 1)
                        color: hitTap.pressed ? "white" : "black"
                        font { pixelSize: 20; bold: true }
                    }
                    Text {
                        width: hitRow.width - 32
                        elide: Text.ElideRight
                        text: hitRow.modelData.snippet
                        color: hitTap.pressed ? "white" : "black"
                        font.pixelSize: 22
                    }
                }
                TapHandler {
                    id: hitTap
                    onTapped: {
                        epubView.goToLocation(hitRow.modelData.spine, hitRow.modelData.charOffset)
                        epubReader.panel = ""
                    }
                }
            }
        }
    }

    Rectangle {  // TYPOGRAPHY
        visible: epubReader.panel === "type"
        anchors { top: toolbar.bottom; right: parent.right; bottom: scrubber.top }
        width: 560
        color: "white"
        border { color: "black"; width: 4 }
        z: 3
        Column {
            anchors { fill: parent; margins: 20 }
            spacing: 20
            Text { text: "FONT"; font { pixelSize: 22; bold: true } }
            Grid {
                columns: 2
                spacing: 12
                Repeater {
                    model: ["Serif", "Sans", "Mono", "Garamond"]
                    Btn {
                        required property string modelData
                        width: 244
                        label: modelData
                        active: epubReader.fontName === modelData
                        onTapped: { epubReader.fontName = modelData; epubReader.commitTypo() }
                    }
                }
            }
            Row {
                spacing: 16
                Text {
                    width: 200
                    anchors.verticalCenter: parent.verticalCenter
                    text: "SIZE " + epubReader.fontSize
                    font { pixelSize: 22; bold: true }
                }
                Btn { label: "A-"; onTapped: { epubReader.fontSize = Math.max(20, epubReader.fontSize - 2); epubReader.commitTypo() } }
                Btn { label: "A+"; onTapped: { epubReader.fontSize = Math.min(64, epubReader.fontSize + 2); epubReader.commitTypo() } }
            }
            Row {
                spacing: 16
                Text {
                    width: 200
                    anchors.verticalCenter: parent.verticalCenter
                    text: "LINE " + epubReader.lineSpacing.toFixed(1)
                    font { pixelSize: 22; bold: true }
                }
                Btn { label: "-"; onTapped: { epubReader.lineSpacing = Math.max(1.0, epubReader.lineSpacing - 0.1); epubReader.commitTypo() } }
                Btn { label: "+"; onTapped: { epubReader.lineSpacing = Math.min(2.5, epubReader.lineSpacing + 0.1); epubReader.commitTypo() } }
            }
            Row {
                spacing: 16
                Text {
                    width: 200
                    anchors.verticalCenter: parent.verticalCenter
                    text: "MARGIN " + epubReader.margins
                    font { pixelSize: 22; bold: true }
                }
                Btn { label: "-"; onTapped: { epubReader.margins = Math.max(20, epubReader.margins - 20); epubReader.commitTypo() } }
                Btn { label: "+"; onTapped: { epubReader.margins = Math.min(220, epubReader.margins + 20); epubReader.commitTypo() } }
            }
            Btn {
                label: epubReader.justify ? "JUSTIFY: ON" : "JUSTIFY: OFF"
                active: epubReader.justify
                onTapped: { epubReader.justify = !epubReader.justify; epubReader.commitTypo() }
            }
        }
    }

    // ---- Footnote popup (short same-chapter link targets) ----
    Rectangle {
        visible: epubReader.footnoteText !== ""
        anchors.centerIn: parent
        width: 900
        height: Math.min(700, ftCol.implicitHeight + 120)
        color: "white"
        border { color: "black"; width: 4 }
        z: 5
        Column {
            id: ftCol
            anchors { fill: parent; margins: 24 }
            spacing: 20
            Flickable {
                width: parent.width
                height: parent.height - 110
                clip: true
                contentHeight: ftBody.implicitHeight
                Text {
                    id: ftBody
                    width: parent.width
                    wrapMode: Text.WordWrap
                    text: epubReader.footnoteText
                    font.pixelSize: 28
                }
            }
            Btn {
                anchors.horizontalCenter: parent.horizontalCenter
                label: "CLOSE"
                onTapped: epubReader.footnoteText = ""
            }
        }
    }

    // ---- Sync JUMP/STAY prompt (remote position is ahead) ----
    Rectangle {
        visible: epubReader.showSyncPrompt
        anchors.centerIn: parent
        width: 900
        height: 340
        color: "white"
        border { color: "black"; width: 4 }
        z: 6
        Column {
            anchors.centerIn: parent
            spacing: 30
            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: epubReader.syncPull
                      ? "SYNC // " + epubReader.syncPull.device + " @ "
                        + Math.round(epubReader.syncPull.percentage * 100) + "%"
                      : ""
                font { pixelSize: 34; bold: true }
            }
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: 40
                Btn {
                    label: "JUMP"
                    onTapped: {
                        epubView.goToLocation(epubReader.syncPull.spine, epubReader.syncPull.charOffset)
                        epubReader.showSyncPrompt = false
                    }
                }
                Btn { label: "STAY"; onTapped: epubReader.showSyncPrompt = false }
            }
        }
    }
}

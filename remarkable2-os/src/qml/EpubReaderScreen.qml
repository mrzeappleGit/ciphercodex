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
    property bool searchTruncated: false  // hit the result cap; show a "capped" note
    // ready gates progress saves: the goToLocation seek at open (and its locationChanged)
    // must not persist over the saved position before we've settled.
    property bool ready: false

    // charOffset of the current page's first char, tracked from locationChanged (EpubView has
    // no charOffset property). Seeds from the resume position so a save before the first turn is correct.
    property int charOffset: startCharOffset
    property int jumpDepth: 0          // return-stack depth after following footnote/link jumps
    property string footnoteText: ""   // non-empty => footnote popup shown
    property bool showSyncPrompt: false

    property var hlItems: []           // highlights of the CURRENT chapter (full maps)
    property bool addingNote: false    // note editor open for a fresh selection
    property var editHl: null          // tapped highlight -> edit/delete sheet (null = closed)

    // Typography (persisted in the settings table). Design defaults: Courier Prime
    // ("Typewriter" token, embedded app font) at 34px / 1.75 line; saved settings win.
    property string fontName: "Typewriter"
    property int fontSize: 34
    property real lineSpacing: 1.75
    property int margins: 90
    property bool justify: false

    // Local save only — cheap SQLite upsert, runs on every settled turn. Network push is NOT here
    // (it must never fire on the reading hot path); it happens once on leave via pushNow().
    function saveNow() {
        if (epubReader.ready && epubView.spineCount > 0)
            epubReader.reader.saveProgress(epubReader.bookId, epubView.spineIndex,
                                           epubReader.charOffset, epubView.percentage)
    }
    // Fire-and-forget async push; only on leaving the reader.
    function pushNow() {
        if (epubReader.ready && epubView.spineCount > 0)
            epubReader.reader.pushProgress(epubReader.bookId)
    }
    function reloadBookmarks() { epubReader.bmItems = epubReader.reader.bookmarks(epubReader.bookId) }
    // Reload the current chapter's highlights and hand EpubView the offset ranges to draw.
    function reloadHighlights() {
        if (epubView.spineCount <= 0) return
        epubReader.hlItems = epubReader.reader.highlights(epubReader.bookId, epubView.spineIndex)
        // EpubView renders by offset only; the id lets highlightAt map a tap back to a row.
        epubView.setChapterHighlights(epubReader.hlItems.map(function(h) {
            return { id: h.id, startChar: h.startChar, endChar: h.endChar }
        }))
    }
    function highlightById(id) {
        for (var i = 0; i < epubReader.hlItems.length; ++i)
            if (epubReader.hlItems[i].id === id) return epubReader.hlItems[i]
        return null
    }
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
        epubReader.fontName = epubReader.reader.setting("epub_font", "Typewriter")
        epubReader.fontSize = parseInt(epubReader.reader.setting("epub_size", "34"))
        epubReader.lineSpacing = parseFloat(epubReader.reader.setting("epub_line", "1.75"))
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
        epubReader.reloadHighlights()
        epubReader.ready = true    // only now do turns persist
        epubReader.reader.pullOnOpen(epubReader.bookId)  // async -> onPullReady shows the prompt
        epubReader.reader.syncAllDirty()                 // drain any push that failed offline earlier
    }
    // Debounce: a scrubber/turn burst emits many locationChanged; save once it settles (fsync-heavy).
    Timer { id: saveTimer; interval: 400; onTriggered: epubReader.saveNow() }
    Component.onDestruction: { saveTimer.stop(); epubReader.saveNow(); epubReader.pushNow() }

    Connections {
        target: epubReader.reader
        function onPullReady(bookId, result) {
            if (bookId === epubReader.bookId && result.state === "RemoteNewer") {
                epubReader.syncPull = result
                epubReader.showSyncPrompt = true
            }
        }
    }

    Connections {
        target: epubView
        function onLocationChanged(spine, charOffset) {
            epubReader.charOffset = charOffset
            if (epubReader.ready) saveTimer.restart()
        }
        function onSpineChanged() { epubReader.reloadHighlights() }
        function onSearchHit(spine, charOffset, snippet) {
            epubReader.searchHits = epubReader.searchHits.concat(
                [{ spine: spine, charOffset: charOffset, snippet: snippet }])
        }
        function onSearchFinished(canceled, truncated) {
            epubReader.searching = false
            epubReader.searchTruncated = truncated
        }
        function onLinkActivated(href) {
            const r = epubView.follow(href)   // footnote => {footnote:true,text}; jump => navigates + pushes stack
            if (r && r.footnote) epubReader.footnoteText = r.text
            else epubReader.jumpDepth += 1
        }
    }

    // Panel/dialog button (secondary = bordered, active/pressed = full inversion).
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
            font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.button; letterSpacing: 1 }
        }
        TapHandler { id: btnTap; onTapped: b.tapped() }
    }

    // Selection-toolbar action: full-height cell, pressed = inversion, styled per the design card.
    component SelAction: Item {
        id: sa
        property alias label: sat.text
        signal tapped()
        width: sat.implicitWidth + 44
        height: 90
        Rectangle { anchors.fill: parent; color: "black"; visible: saTap.pressed }
        Text {
            id: sat
            anchors.centerIn: parent
            color: saTap.pressed ? "white" : "black"
            font { family: Theme.display; weight: Font.Bold; pixelSize: 22; letterSpacing: 1 }
        }
        TapHandler { id: saTap; onTapped: sa.tapped() }
    }
    component SelSep: Rectangle { width: Theme.hairline; height: 90; color: "black" }

    // Thin-bar text action: 90x90 touch zone, visual inversion band inside the 72px strip.
    component StripBtn: Item {
        id: sb
        property alias label: sbt.text
        property bool active: false
        signal tapped()
        readonly property bool dark: sbTap.pressed || sb.active
        width: Math.max(Theme.touch, sbt.implicitWidth + 28)
        height: Theme.touch
        Rectangle { anchors.centerIn: parent; width: parent.width; height: 56; color: "black"; visible: sb.dark }
        Text {
            id: sbt
            anchors.centerIn: parent
            color: sb.dark ? "white" : "black"
            font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.caption; letterSpacing: 1 }
        }
        TapHandler { id: sbTap; onTapped: sb.tapped() }
    }

    // ---- Top strip: 72px thin bar — back, chapter/book label, panel actions, percentage ----
    Rectangle {
        id: toolbar
        width: parent.width
        height: 72
        color: "white"
        z: 2
        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: Theme.hairline; color: "black" }

        Item {
            id: backBtn
            anchors { left: parent.left; leftMargin: 20; verticalCenter: parent.verticalCenter }
            width: Theme.touch
            height: Theme.touch
            Rectangle { anchors.centerIn: parent; width: parent.width; height: 56; color: "black"; visible: backTap.pressed }
            Text {
                anchors.centerIn: parent
                text: "←"
                color: backTap.pressed ? "white" : "black"
                font.pixelSize: 34
            }
            TapHandler { id: backTap; onTapped: epubReader.StackView.view.pop() }
        }
        Text {
            anchors { left: backBtn.right; leftMargin: 16; right: stripActions.left; rightMargin: 16
                      verticalCenter: parent.verticalCenter }
            elide: Text.ElideRight
            text: "CH. " + (epubView.spineIndex + 1) + "/" + epubView.spineCount
                  + (epubReader.title !== "" ? " — " + epubReader.title.toUpperCase() : "")
            font { family: Theme.mono; pixelSize: Theme.caption }
        }
        Row {
            id: stripActions
            anchors { right: parent.right; rightMargin: Theme.pad; verticalCenter: parent.verticalCenter }
            spacing: 8
            StripBtn {
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
            StripBtn {
                label: "MARKS"
                active: epubReader.panel === "bookmarks"
                onTapped: {
                    if (epubReader.panel === "bookmarks") { epubReader.panel = "" }
                    else { epubReader.reloadBookmarks(); epubReader.panel = "bookmarks" }
                }
            }
            StripBtn {
                label: "SEARCH"
                active: epubReader.panel === "search"
                onTapped: epubReader.panel = (epubReader.panel === "search" ? "" : "search")
            }
            Item {
                width: pctText.implicitWidth + 16
                height: Theme.touch
                anchors.verticalCenter: parent.verticalCenter
                Text {
                    id: pctText
                    anchors { right: parent.right; verticalCenter: parent.verticalCenter }
                    text: Math.round(epubView.percentage * 100) + "%"
                    font { family: Theme.mono; pixelSize: Theme.caption }
                }
            }
        }
    }

    EpubView {
        id: epubView
        anchors { top: toolbar.bottom; left: parent.left; right: parent.right; bottom: scrubber.top }
        // EpubView emits highlightTapped when a tap lands on a saved highlight (and skips its own
        // page-turn for that tap), so there's no double-processing / page flip underneath.
        onHighlightTapped: (id) => {
            if (epubReader.panel !== "" || epubReader.addingNote) return
            const h = epubReader.highlightById(id)
            if (h) { editNote.text = h.note ? h.note : ""; epubReader.editHl = h }
        }
    }

    // Jump-back affordance: after following a link jump, return down the 10-deep stack.
    Btn {
        anchors { left: parent.left; leftMargin: 20; bottom: scrubber.top; bottomMargin: 20 }
        visible: epubReader.jumpDepth > 0
        z: 4
        label: "← BACK"
        onTapped: { epubView.back(); epubReader.jumpDepth -= 1 }
    }

    // ---- Bottom strip: 72px thin bar — page indicator, seek zone (whole-book scrubber), Aa chip ----
    Rectangle {
        id: scrubber
        anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
        height: 72
        color: "white"
        z: 2
        Rectangle { anchors.top: parent.top; width: parent.width; height: Theme.hairline; color: "black" }
        // Whole-book progress fill (kept from the old scrubber; fill reflects percentage).
        Rectangle {
            anchors { left: parent.left; bottom: parent.bottom }
            height: 6
            color: "black"
            width: scrubber.width * epubView.percentage
        }
        Text {
            id: pageText
            anchors { left: parent.left; leftMargin: Theme.pad; verticalCenter: parent.verticalCenter }
            text: "p. " + (epubView.pageInSpine + 1) + " / " + epubView.pagesInSpine
            font { family: Theme.mono; pixelSize: Theme.caption }
        }
        // Tap-to-seek (chapter-granular) across the middle of the strip, as the old scrubber did.
        Item {
            id: seekZone
            anchors { left: pageText.right; leftMargin: 24; right: aaChip.left; rightMargin: 24
                      top: parent.top; bottom: parent.bottom }
            TapHandler {
                // fraction is zone-relative so the full 0..1 range (first/last chapters)
                // stays reachable even though the zone starts right of the page label
                onTapped: (ep) => epubReader.seekTo(ep.position.x / seekZone.width)
            }
        }
        // "Aa" chip — toggles the existing typography panel.
        Item {
            id: aaChip
            anchors { right: parent.right; rightMargin: Theme.pad; verticalCenter: parent.verticalCenter }
            width: Theme.touch
            height: Theme.touch
            readonly property bool dark: aaTap.pressed || epubReader.panel === "type"
            Rectangle {
                anchors.centerIn: parent
                width: aaText.implicitWidth + 32
                height: 52
                color: aaChip.dark ? "black" : "white"
                border { color: "black"; width: Theme.hairline }
                Text {
                    id: aaText
                    anchors.centerIn: parent
                    text: "Aa"
                    color: aaChip.dark ? "white" : "black"
                    font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.button }
                }
            }
            TapHandler { id: aaTap; onTapped: epubReader.panel = (epubReader.panel === "type" ? "" : "type") }
        }
    }

    // ---- Right-side panels (opaque; overlay EpubView) ----

    Rectangle {  // TOC
        visible: epubReader.panel === "toc"
        anchors { top: toolbar.bottom; right: parent.right; bottom: scrubber.top }
        width: 560
        color: "white"
        border { color: "black"; width: Theme.frame }
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
                border { color: "black"; width: Theme.chip }
                Text {
                    anchors { left: parent.left; leftMargin: 16; right: parent.right; rightMargin: 16
                              verticalCenter: parent.verticalCenter }
                    elide: Text.ElideRight
                    text: tocRow.modelData.title
                    color: tocTap.pressed ? "white" : "black"
                    font { family: Theme.display; weight: Font.DemiBold; pixelSize: 24 }
                }
                TapHandler {
                    id: tocTap
                    onTapped: { epubView.goToSpine(tocRow.modelData.spine); epubReader.panel = "" }
                }
            }
        }
    }

    Rectangle {  // BOOKMARKS
        visible: epubReader.panel === "bookmarks"
        anchors { top: toolbar.bottom; right: parent.right; bottom: scrubber.top }
        width: 560
        color: "white"
        border { color: "black"; width: Theme.frame }
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
                border { color: "black"; width: Theme.chip }
                Text {
                    anchors { left: parent.left; leftMargin: 16; right: delBm.left; rightMargin: 12
                              verticalCenter: parent.verticalCenter }
                    elide: Text.ElideRight
                    text: bmRow.modelData.label + "  " + Math.round(bmRow.modelData.percentage * 100) + "%"
                    color: bmTap.pressed ? "white" : "black"
                    font { family: Theme.display; weight: Font.DemiBold; pixelSize: 24 }
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
        border { color: "black"; width: Theme.frame }
        z: 3
        Row {
            id: searchRow
            anchors { top: parent.top; left: parent.left; right: parent.right; margins: 16 }
            spacing: 12
            Rectangle {
                width: parent.width - 150; height: 84
                color: "white"
                border { color: "black"; width: Theme.hairline }
                Text {
                    visible: searchField.text === ""
                    anchors { left: parent.left; leftMargin: 14; verticalCenter: parent.verticalCenter }
                    text: "FIND"
                    color: "#999999"
                    font { family: Theme.reading; pixelSize: 24 }
                }
                TextInput {
                    id: searchField
                    anchors { fill: parent; leftMargin: 14; rightMargin: 14 }
                    verticalAlignment: TextInput.AlignVCenter
                    clip: true
                    font { family: Theme.reading; pixelSize: 24 }
                    onAccepted: epubReader.runSearch(text)
                }
            }
            Btn {
                height: 84
                label: epubReader.searching ? "..." : "GO"
                onTapped: epubReader.searching ? epubView.cancelSearch() : epubReader.runSearch(searchField.text)
            }
        }
        Text {
            id: capNote
            visible: epubReader.searchTruncated
            anchors { top: searchRow.bottom; topMargin: 12; horizontalCenter: parent.horizontalCenter }
            text: "showing first " + epubReader.searchHits.length + " matches"
            font { family: Theme.mono; pixelSize: Theme.caption }
        }
        ListView {
            anchors { top: capNote.visible ? capNote.bottom : searchRow.bottom; topMargin: 16
                      left: parent.left; right: parent.right
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
                border { color: "black"; width: Theme.chip }
                Column {
                    anchors { left: parent.left; leftMargin: 16; right: parent.right; rightMargin: 16
                              verticalCenter: parent.verticalCenter }
                    spacing: 4
                    Text {
                        text: "Ch " + (hitRow.modelData.spine + 1)
                        color: hitTap.pressed ? "white" : "black"
                        font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.caption }
                    }
                    Text {
                        width: hitRow.width - 32
                        elide: Text.ElideRight
                        text: hitRow.modelData.snippet
                        color: hitTap.pressed ? "white" : "black"
                        font { family: Theme.mono; pixelSize: Theme.secondary }
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
        border { color: "black"; width: Theme.frame }
        z: 3
        Column {
            anchors { fill: parent; margins: 20 }
            spacing: 20
            Text { text: "FONT"; font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.secondary; letterSpacing: 1 } }
            Grid {
                columns: 2
                spacing: 12
                Repeater {
                    model: ["Typewriter", "Serif", "Sans", "Mono", "Garamond"]
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
                    font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.secondary; letterSpacing: 1 }
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
                    font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.secondary; letterSpacing: 1 }
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
                    font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.secondary; letterSpacing: 1 }
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

    // ---- Footnote strip (short same-chapter link targets) — bottom-bar style, tap to dismiss ----
    Rectangle {
        visible: epubReader.footnoteText !== ""
        anchors { left: parent.left; right: parent.right; bottom: scrubber.top }
        height: Math.max(Theme.touch, ftFlick.height + 44 + closeHint.height + 8)
        color: "white"
        z: 5
        Rectangle { anchors.top: parent.top; width: parent.width; height: Theme.hairline; color: "black" }
        Flickable {
            id: ftFlick
            anchors { top: parent.top; topMargin: 22; left: parent.left; leftMargin: Theme.pad
                      right: parent.right; rightMargin: Theme.pad }
            height: Math.min(ftBody.implicitHeight, 260)
            clip: true
            contentHeight: ftBody.implicitHeight
            Text {
                id: ftBody
                width: ftFlick.width
                wrapMode: Text.WordWrap
                text: epubReader.footnoteText
                font { family: Theme.mono; pixelSize: Theme.micro }
            }
        }
        Text {
            id: closeHint
            anchors { top: ftFlick.bottom; topMargin: 8; right: parent.right; rightMargin: Theme.pad }
            text: "TAP TO CLOSE"
            font { family: Theme.mono; pixelSize: Theme.micro }
        }
        TapHandler { onTapped: epubReader.footnoteText = "" }
    }

    // ---- POSITION CONFLICT card (remote position is ahead) ----
    Rectangle {
        visible: epubReader.showSyncPrompt
        anchors.centerIn: parent
        width: 1040
        height: conflictCol.implicitHeight + 96
        color: "white"
        border { color: "black"; width: Theme.frame }
        z: 6
        // Hard-offset elevation outline (painted behind via z: -1).
        Rectangle {
            z: -1; x: Theme.lift; y: Theme.lift; width: parent.width; height: parent.height
            color: "white"; border { color: "black"; width: Theme.frame }
        }
        Column {
            id: conflictCol
            anchors { left: parent.left; right: parent.right; top: parent.top; margins: 48 }
            spacing: 28
            Text {
                text: "POSITION CONFLICT"
                font { family: Theme.display; weight: Font.Bold; pixelSize: 32; letterSpacing: 1 }
            }
            Text {
                width: parent.width
                lineHeight: 1.6
                text: epubReader.syncPull
                      ? "THIS DEVICE — " + Math.round(epubView.percentage * 100) + "%\n"
                        + "OTHER DEVICE — " + epubReader.syncPull.device + " · "
                        + Math.round(epubReader.syncPull.percentage * 100) + "%"
                      : ""
                font { family: Theme.mono; pixelSize: Theme.secondary }
            }
            Row {
                width: parent.width
                spacing: 20
                // Keep local position (was STAY) — inverted primary.
                Rectangle {
                    width: (parent.width - 20) / 2
                    height: Theme.touch
                    color: stayTap.pressed ? "white" : "black"
                    border { color: "black"; width: Theme.chip }
                    Text {
                        anchors.centerIn: parent
                        text: "USE THIS DEVICE"
                        color: stayTap.pressed ? "black" : "white"
                        font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.button }
                    }
                    TapHandler { id: stayTap; onTapped: epubReader.showSyncPrompt = false }
                }
                // Jump to remote position (was JUMP) — bordered secondary.
                Rectangle {
                    width: (parent.width - 20) / 2
                    height: Theme.touch
                    color: jumpTap.pressed ? "black" : "white"
                    border { color: "black"; width: Theme.chip }
                    Text {
                        anchors.centerIn: parent
                        text: epubReader.syncPull
                              ? "USE OTHER (" + Math.round(epubReader.syncPull.percentage * 100) + "%)"
                              : "USE OTHER"
                        color: jumpTap.pressed ? "white" : "black"
                        font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.button }
                    }
                    TapHandler {
                        id: jumpTap
                        onTapped: {
                            // spine<0 = foreign xpointer with no offset: land near the remote percentage
                            if (epubReader.syncPull.spine < 0)
                                epubReader.seekTo(epubReader.syncPull.percentage)
                            else
                                epubView.goToLocation(epubReader.syncPull.spine, epubReader.syncPull.charOffset)
                            epubReader.showSyncPrompt = false
                        }
                    }
                }
            }
        }
    }

    // ---- Selection toolbar (while a selection is committed) ----
    Rectangle {
        visible: epubView.hasSelection && !epubReader.addingNote
        anchors { horizontalCenter: parent.horizontalCenter; bottom: scrubber.top; bottomMargin: 20 }
        width: selRow.width + 2 * Theme.chip
        height: 96
        color: "white"
        border { color: "black"; width: Theme.chip }
        z: 4
        // Hard-offset elevation outline (painted behind via z: -1).
        Rectangle {
            z: -1; x: Theme.lift; y: Theme.lift; width: parent.width; height: parent.height
            color: "white"; border { color: "black"; width: Theme.chip }
        }
        Row {
            id: selRow
            anchors.centerIn: parent
            SelAction {
                label: "HIGHLIGHT"
                onTapped: {
                    const a = epubView.selectionAnchor()
                    epubReader.reader.addHighlight(epubReader.bookId, a.spine, a.startChar,
                                                   a.endChar, a.text, "")
                    epubReader.reloadHighlights()
                    epubView.clearSelection()
                }
            }
            SelSep {}
            SelAction { label: "NOTE"; onTapped: { newNote.text = ""; epubReader.addingNote = true } }
            SelSep {}
            SelAction { label: "COPY"; onTapped: { epubView.copySelection(); epubView.clearSelection() } }
            SelSep {}
            SelAction { label: "X"; onTapped: epubView.clearSelection() }
        }
    }

    // ---- Note editor for a fresh selection (NOTE -> addHighlight with the note) ----
    Rectangle {
        visible: epubReader.addingNote
        anchors.centerIn: parent
        width: 900
        height: 460
        color: "white"
        border { color: "black"; width: Theme.frame }
        z: 7
        Rectangle {
            z: -1; x: Theme.lift; y: Theme.lift; width: parent.width; height: parent.height
            color: "white"; border { color: "black"; width: Theme.frame }
        }
        Column {
            anchors { fill: parent; margins: 24 }
            spacing: 20
            Text { text: "NOTE"; font { family: Theme.display; weight: Font.Bold; pixelSize: 26; letterSpacing: 1 } }
            Rectangle {
                width: parent.width
                height: 240
                color: "white"
                border { color: "black"; width: Theme.hairline }
                Text {
                    visible: newNote.text === ""
                    anchors { left: parent.left; leftMargin: 14; top: parent.top; topMargin: 12 }
                    text: "type a note"
                    color: "#999999"
                    font { family: Theme.reading; pixelSize: 24 }
                }
                TextEdit {
                    id: newNote
                    anchors { fill: parent; margins: 14 }
                    wrapMode: TextEdit.Wrap
                    clip: true
                    font { family: Theme.reading; pixelSize: 24 }
                }
            }
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: 40
                Btn {
                    label: "SAVE"
                    onTapped: {
                        const a = epubView.selectionAnchor()
                        epubReader.reader.addHighlight(epubReader.bookId, a.spine, a.startChar,
                                                       a.endChar, a.text, newNote.text)
                        epubReader.reloadHighlights()
                        epubView.clearSelection()
                        epubReader.addingNote = false
                    }
                }
                Btn { label: "CANCEL"; onTapped: epubReader.addingNote = false }
            }
        }
    }

    // ---- Tapped-highlight edit/delete sheet ----
    Rectangle {
        visible: epubReader.editHl !== null
        anchors.centerIn: parent
        width: 900
        height: Math.min(760, editCol.implicitHeight + 48)
        color: "white"
        border { color: "black"; width: Theme.frame }
        z: 7
        Rectangle {
            z: -1; x: Theme.lift; y: Theme.lift; width: parent.width; height: parent.height
            color: "white"; border { color: "black"; width: Theme.frame }
        }
        Column {
            id: editCol
            anchors { fill: parent; margins: 24 }
            spacing: 18
            Flickable {
                width: parent.width
                height: 220
                clip: true
                contentHeight: hlText.implicitHeight
                Text {
                    id: hlText
                    width: parent.width
                    wrapMode: Text.WordWrap
                    text: epubReader.editHl ? ("“" + epubReader.editHl.text + "”") : ""
                    font { family: Theme.reading; pixelSize: 26 }
                }
            }
            Text { text: "NOTE"; font { family: Theme.display; weight: Font.Bold; pixelSize: 24; letterSpacing: 1 } }
            Rectangle {
                width: parent.width
                height: 200
                color: "white"
                border { color: "black"; width: Theme.hairline }
                Text {
                    visible: editNote.text === ""
                    anchors { left: parent.left; leftMargin: 14; top: parent.top; topMargin: 12 }
                    text: "type a note"
                    color: "#999999"
                    font { family: Theme.reading; pixelSize: 24 }
                }
                TextEdit {
                    id: editNote
                    anchors { fill: parent; margins: 14 }
                    wrapMode: TextEdit.Wrap
                    clip: true
                    font { family: Theme.reading; pixelSize: 24 }
                }
            }
            Row {
                anchors.horizontalCenter: parent.horizontalCenter
                spacing: 30
                Btn {
                    label: "SAVE"
                    onTapped: {
                        epubReader.reader.updateHighlight(epubReader.editHl.id, editNote.text)
                        epubReader.reloadHighlights()
                        epubReader.editHl = null
                    }
                }
                Btn {
                    label: "DELETE"
                    onTapped: {
                        epubReader.reader.deleteHighlight(epubReader.editHl.id)
                        epubReader.reloadHighlights()
                        epubReader.editHl = null
                    }
                }
                Btn { label: "CLOSE"; onTapped: epubReader.editHl = null }
            }
        }
    }
}

pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import CipherCodex

Item {
    id: pageScreen

    required property var pen
    required property var controller
    // set via StackView.push property map (plain properties: push sets them post-creation)
    property var notebookId: -1
    property string notebookTitle: ""

    property var pageIds: []
    property int pageIndex: 0
    property int activeTool: 0  // 0 pencil, 1 stroke-eraser
    property bool showText: false
    property string recognizedText: ""

    function reloadPages() {
        const ps = pageScreen.controller.pages(pageScreen.notebookId)
        const ids = []
        for (let i = 0; i < ps.length; ++i)
            ids.push(ps[i].id)
        pageScreen.pageIds = ids
    }

    function loadPage(i) {
        pageScreen.pageIndex = i
        pageScreen.controller.openPage(pageScreen.pageIds[i], ink, pageScreen.pen)
        pageScreen.recognizedText = pageScreen.controller.pageText(pageScreen.pageIds[i])
    }

    // PageScreen sits at scene (0,0) — StackView fills the window, no transitions —
    // so the ink scene rect is just everything right of the tool rail.
    function syncCanvasRect() {
        pageScreen.pen.canvasRect = Qt.rect(rail.width, 0,
                                            pageScreen.width - rail.width, pageScreen.height)
    }

    Component.onCompleted: {
        reloadPages()
        if (pageIds.length === 0) {
            controller.createPage(notebookId)
            reloadPages()
        }
        loadPage(0)
        syncCanvasRect()
    }
    onWidthChanged: syncCanvasRect()
    onHeightChanged: syncCanvasRect()
    // empty rect = all pen-downs route to QML buttons again
    Component.onDestruction: pageScreen.pen.canvasRect = Qt.rect(0, 0, 0, 0)

    // pen->canvas connection is made in C++ (openPage): no per-sample JS hop, all args intact

    // 90x90 rail button: glyph text or Rectangle-built icon (default slot) over a mono
    // 13px label. Pressed/active = full inversion; disabled = hairline border, no fill.
    component RailBtn: Rectangle {
        id: rb
        default property alias icon: iconBox.data
        property alias glyph: glyphText.text
        property alias label: labelText.text
        property bool active: false
        readonly property bool dark: rb.active || (rb.enabled && tap.pressed)
        readonly property color fg: rb.dark ? "white" : "black"
        signal tapped()
        width: 90
        height: 90
        color: rb.dark ? "black" : "white"
        border { color: "black"; width: rb.enabled ? Theme.chip : Theme.hairline }
        Column {
            anchors.centerIn: parent
            spacing: 2
            Item {
                id: iconBox
                width: 60; height: 34
                anchors.horizontalCenter: parent.horizontalCenter
                Text {
                    id: glyphText
                    anchors.centerIn: parent
                    visible: text.length > 0
                    color: rb.fg
                    font.pixelSize: 30
                }
            }
            Text {
                id: labelText
                anchors.horizontalCenter: parent.horizontalCenter
                visible: text.length > 0
                color: rb.fg
                font { family: Theme.mono; pixelSize: 13 }
            }
        }
        TapHandler { id: tap; onTapped: rb.tapped() }
    }

    component RailDivider: Item {
        width: 90; height: 18
        Rectangle { anchors.centerIn: parent; width: 70; height: 2; color: "black" }
    }

    Rectangle {
        id: rail
        width: 130
        height: parent.height
        z: 1  // chrome above the full-screen InkItem
        color: "white"
        Rectangle {
            anchors.right: parent.right
            width: Theme.frame; height: parent.height
            color: "black"
        }

        Column {
            anchors { top: parent.top; topMargin: 24; horizontalCenter: parent.horizontalCenter }
            spacing: 14

            // ← « » : the only arrows the device fonts cover (↩↶↷ are tofu on hardware)
            RailBtn { glyph: "←"; label: "BACK"; onTapped: pageScreen.StackView.view.pop() }
            RailDivider {}
            RailBtn {
                id: penBtn
                label: "PEN"
                active: pageScreen.activeTool === 0
                onTapped: pageScreen.activeTool = 0
                // pen icon: rotated thin shaft + square tip at the lower-left end
                Item {
                    anchors.centerIn: parent
                    width: 30; height: 30
                    Rectangle { x: 2; y: 13; width: 26; height: 5; rotation: -45; color: penBtn.fg }
                    Rectangle { x: 3; y: 22; width: 6; height: 6; color: penBtn.fg }
                }
            }
            RailBtn {
                id: eraseBtn
                label: "ERASE"   // stroke eraser: whole strokes
                active: pageScreen.activeTool === 1
                onTapped: pageScreen.activeTool = 1
                // erase icon: small bordered rect
                Rectangle {
                    anchors.centerIn: parent
                    width: 26; height: 18
                    color: eraseBtn.dark ? "black" : "white"
                    border { color: eraseBtn.fg; width: 3 }
                }
            }
            RailBtn {
                id: areaBtn
                label: "AREA"    // area eraser: partial, splits strokes
                active: pageScreen.activeTool === 2
                onTapped: pageScreen.activeTool = 2
                // area icon: dashed-feel rect from 4 short bars (open corners)
                Item {
                    anchors.centerIn: parent
                    width: 28; height: 20
                    Rectangle { x: 7; y: 0; width: 14; height: 3; color: areaBtn.fg }
                    Rectangle { x: 7; y: 17; width: 14; height: 3; color: areaBtn.fg }
                    Rectangle { x: 0; y: 6; width: 3; height: 8; color: areaBtn.fg }
                    Rectangle { x: 25; y: 6; width: 3; height: 8; color: areaBtn.fg }
                }
            }
            RailBtn {
                glyph: "T"; label: "TEXT"
                active: pageScreen.showText
                onTapped: {
                    pageScreen.showText = !pageScreen.showText
                    pageScreen.recognizedText = pageScreen.controller.pageText(pageScreen.pageIds[pageScreen.pageIndex])
                }
            }
            RailDivider {}
            RailBtn {
                glyph: "«"; label: "UNDO"
                enabled: pageScreen.controller.canUndo
                onTapped: pageScreen.controller.undo()
            }
            RailBtn {
                glyph: "»"; label: "REDO"
                enabled: pageScreen.controller.canRedo
                onTapped: pageScreen.controller.redo()
            }
        }

        Column {
            anchors { bottom: parent.bottom; bottomMargin: 24; horizontalCenter: parent.horizontalCenter }
            spacing: 8

            RailBtn {
                id: pdfBtn
                glyph: "PDF"
                label: "EXPORT"
                onTapped: {
                    const ok = pageScreen.controller.exportNotebookPdf(pageScreen.notebookId,
                        "/home/root/ciphercodex/notebook-" + pageScreen.notebookId + ".pdf")
                    pdfBtn.label = ok ? "SAVED" : "FAILED"
                    pdfResetTimer.restart()
                }
            }
            RailBtn {
                glyph: "←"; label: "PREV"
                enabled: pageScreen.pageIndex > 0
                onTapped: pageScreen.loadPage(pageScreen.pageIndex - 1)
            }
            RailBtn {
                glyph: "→"; label: "NEXT"
                enabled: pageScreen.pageIndex < pageScreen.pageIds.length - 1
                onTapped: pageScreen.loadPage(pageScreen.pageIndex + 1)
            }
            Rectangle {
                // page indicator (not tappable)
                width: 90; height: 64
                color: "white"
                border { color: "black"; width: Theme.chip }
                Text {
                    anchors.centerIn: parent
                    text: (pageScreen.pageIndex + 1) + "/" + pageScreen.pageIds.length
                    color: "black"
                    font { family: Theme.mono; pixelSize: Theme.caption }
                }
            }
            RailBtn {
                glyph: "+"; label: "PAGE"
                onTapped: {
                    pageScreen.controller.createPage(pageScreen.notebookId)
                    pageScreen.reloadPages()
                    pageScreen.loadPage(pageScreen.pageIds.length - 1)
                }
            }
        }
    }

    Timer { id: pdfResetTimer; interval: 2000; onTriggered: pdfBtn.label = "EXPORT" }

    InkItem {
        id: ink
        // Full-screen sheet: 1404x1872 is exactly the 3:4 virtual page (pageHeight() =
        // width*4/3), so every visible pixel has y_norm <= 1.0 and PDF export loses nothing,
        // and pre-rail pages render pixel-identical. The rail overlays the left edge as
        // opaque chrome (z above); pen-downs there route to buttons via pen.canvasRect,
        // so the strip under the rail is a physical margin you can't draw in.
        anchors.fill: parent
        // Marker Plus rubber end overrides the toolbar pick while in proximity (untested on hw)
        tool: pageScreen.pen.eraser ? 1 : pageScreen.activeTool
    }

    Rectangle {  // recognized-text overlay: read-only, derived data
        visible: pageScreen.showText
        z: 2
        anchors { top: parent.top; right: parent.right; margins: 40 }
        width: 560
        height: Math.min(1400, textCol.implicitHeight + 96)
        color: "white"
        border { color: "black"; width: Theme.chip }
        Rectangle {  // hard-offset elevation
            z: -1
            x: Theme.lift; y: Theme.lift
            width: parent.width; height: parent.height
            color: "white"
            border { color: "black"; width: Theme.chip }
        }
        Flickable {
            anchors { fill: parent; margins: 32 }
            contentHeight: textCol.implicitHeight
            clip: true
            Column {
                id: textCol
                width: parent.width
                spacing: 12
                Text {
                    text: "RECOGNIZED TEXT"
                    font { family: Theme.mono; pixelSize: Theme.micro; letterSpacing: 2 }
                }
                Text {
                    width: parent.width
                    wrapMode: Text.Wrap
                    text: pageScreen.recognizedText !== "" ? pageScreen.recognizedText
                          : "No text yet — sync after writing; the phone recognizes at its next sync."
                    font { family: Theme.reading; pixelSize: 26 }
                }
            }
        }
        // MouseArea, NOT TapHandler: it accepts and CONSUMES the press, so the tap-to-close
        // doesn't leak through to the full-screen InkItem beneath (TapHandler's passive grab
        // does not block items underneath — see SleepScreen).
        MouseArea { anchors.fill: parent; onClicked: pageScreen.showText = false }
    }
}

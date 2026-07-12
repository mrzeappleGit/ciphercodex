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
    }

    // PageScreen sits at scene (0,0) — StackView fills the window, no transitions —
    // so the ink scene rect is just everything below the toolbar.
    function syncCanvasRect() {
        pageScreen.pen.canvasRect = Qt.rect(0, toolbar.height,
                                            pageScreen.width, pageScreen.height - toolbar.height)
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

    component Btn: Rectangle {
        id: b
        property alias label: btnText.text
        property bool active: false
        signal tapped()
        width: Math.max(90, btnText.implicitWidth + 28)
        height: 90
        color: (btnTap.pressed || b.active) ? "black" : "white"
        border { color: b.enabled ? "black" : "#999999"; width: 4 }
        Text {
            id: btnText
            anchors.centerIn: parent
            color: !b.enabled ? "#999999"
                              : ((btnTap.pressed || b.active) ? "white" : "black")
            font { pixelSize: 26; bold: true }
        }
        TapHandler { id: btnTap; onTapped: b.tapped() }
    }

    Rectangle {
        id: toolbar
        width: parent.width
        height: 100
        color: "white"
        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 4; color: "black" }

        Row {
            anchors { left: parent.left; leftMargin: 12; verticalCenter: parent.verticalCenter }
            spacing: 8

            Btn { label: "BACK"; onTapped: pageScreen.StackView.view.pop() }
            Item {
                width: 220; height: 90
                Text {
                    anchors { left: parent.left; leftMargin: 8; verticalCenter: parent.verticalCenter }
                    width: parent.width - 8
                    elide: Text.ElideRight
                    text: pageScreen.notebookTitle
                    font { pixelSize: 26; bold: true }
                }
            }
            Item {
                width: 80; height: 90
                Text {
                    anchors.centerIn: parent
                    text: (pageScreen.pageIndex + 1) + "/" + pageScreen.pageIds.length
                    font.pixelSize: 26
                }
            }
            Btn {
                label: "PREV"
                enabled: pageScreen.pageIndex > 0
                onTapped: pageScreen.loadPage(pageScreen.pageIndex - 1)
            }
            Btn {
                label: "NEXT"
                enabled: pageScreen.pageIndex < pageScreen.pageIds.length - 1
                onTapped: pageScreen.loadPage(pageScreen.pageIndex + 1)
            }
            Btn {
                label: "+PAGE"
                onTapped: {
                    pageScreen.controller.createPage(pageScreen.notebookId)
                    pageScreen.reloadPages()
                    pageScreen.loadPage(pageScreen.pageIds.length - 1)
                }
            }
            Btn {
                label: "PENCIL"
                active: pageScreen.activeTool === 0
                onTapped: pageScreen.activeTool = 0
            }
            Btn {
                label: "ERASER"
                active: pageScreen.activeTool === 1
                onTapped: pageScreen.activeTool = 1
            }
            Btn {
                label: "UNDO"
                enabled: pageScreen.controller.canUndo
                onTapped: pageScreen.controller.undo()
            }
            Btn {
                label: "REDO"
                enabled: pageScreen.controller.canRedo
                onTapped: pageScreen.controller.redo()
            }
            Btn {
                id: pdfBtn
                label: "PDF"
                onTapped: {
                    const ok = pageScreen.controller.exportNotebookPdf(pageScreen.notebookId,
                        "/home/root/ciphercodex/notebook-" + pageScreen.notebookId + ".pdf")
                    pdfBtn.label = ok ? "SAVED" : "FAILED"
                    pdfResetTimer.restart()
                }
            }
        }
    }

    Timer { id: pdfResetTimer; interval: 2000; onTriggered: pdfBtn.label = "PDF" }

    InkItem {
        id: ink
        anchors { top: toolbar.bottom; left: parent.left; right: parent.right; bottom: parent.bottom }
        // Marker Plus rubber end overrides the toolbar pick while in proximity (untested on hw)
        tool: pageScreen.pen.eraser ? 1 : pageScreen.activeTool
    }
}

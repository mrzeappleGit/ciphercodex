import QtQuick
import QtQuick.Controls
import CipherCodex

Window {
    id: root
    visible: true
    visibility: Window.FullScreen
    width: Screen.width
    height: Screen.height
    color: "white"

    PenReader { id: pen }
    NotebookController { id: controller }
    ReaderController { id: reader }

    // A WebDAV sync merged notebook rows into the DB: refresh the open page so it never shows
    // stale strokes vs the merged truth. No-op when no page is open.
    Connections {
        target: reader
        function onSyncedDataChanged() { controller.reloadOpenPage() }
    }

    // The focused editable item, or null. Duck-typed: any TextInput/TextEdit exposes insert() +
    // cursorPosition. Drives the on-screen keyboard (the device has no hardware keys).
    readonly property Item kbTarget:
        (root.activeFocusItem
         && typeof root.activeFocusItem.insert === "function"
         && root.activeFocusItem.cursorPosition !== undefined) ? root.activeFocusItem : null

    StackView {
        id: stack
        anchors { top: parent.top; left: parent.left; right: parent.right
                  bottom: keyboard.visible ? keyboard.top : parent.bottom }
        // e-ink: transitions only ghost; every stack op is immediate
        pushEnter: null
        pushExit: null
        popEnter: null
        popExit: null
        replaceEnter: null
        replaceExit: null
        initialItem: HomeScreen { pen: pen; controller: controller; reader: reader }
    }

    Keyboard {
        id: keyboard
        target: root.kbTarget
        visible: root.kbTarget !== null
        anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
        z: 100
    }
}

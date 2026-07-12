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

    // Auto sync (event-driven, never polled): on app open and on returning to Home after a
    // book/notebook was open. Only STARTS from Home (depth 1) so a merge never begins while a
    // page/reader is using the DB — an already-running sync overlapping a newly opened page is
    // safe (per-table txns + reloadOpenPage above). Skipped silently until WebDAV is configured;
    // syncNow() itself no-ops if a run is in flight.
    // ponytail: every Home return runs a full engine pass (~2MB snapshot PUT even when idle);
    // add a max(updated_at) dirty-skip in SyncEngine if bandwidth ever matters.
    Timer {
        id: autoSync
        interval: 1500  // debounce quick home-bounces into one run
        onTriggered: if (stack.depth === 1 && reader.webdavConfig().configured) reader.syncNow()
    }
    Component.onCompleted: autoSync.start()

    // The focused editable item, or null. Duck-typed: any TextInput/TextEdit exposes insert() +
    // cursorPosition. Drives the on-screen keyboard (the device has no hardware keys).
    readonly property Item kbTarget:
        (root.activeFocusItem
         && typeof root.activeFocusItem.insert === "function"
         && root.activeFocusItem.cursorPosition !== undefined) ? root.activeFocusItem : null

    StackView {
        id: stack
        // Back at Home => queue an auto sync; navigating deeper cancels a queued one.
        onDepthChanged: depth === 1 ? autoSync.restart() : autoSync.stop()
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

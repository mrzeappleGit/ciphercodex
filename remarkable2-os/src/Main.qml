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
    PowerButton { id: power; onPressed: root.powerPressed() }

    IdleWatch {
        id: idleWatch
        // settings-table value in minutes (0 disables); CCX_IDLE_MIN env overrides in C++
        timeoutMinutes: parseInt(reader.setting("sleep_idle_min", "10"))
        onIdleTimeout: if (root.sleepState === "") root.powerPressed()
    }
    Connections {
        target: pen
        // Raw ink strokes never become Qt input events, so the event filter can't see
        // writing — count activity per stroke edge (NEVER per-sample: penMove is 200Hz).
        function onPenDown() { idleWatch.poke() }
        function onPenUp() { idleWatch.poke() }
    }

    // Sleep: "" awake · "arming" face is flushing to the EPD · "suspended" suspend issued.
    // Wake-dismiss paths: the waking power press, any tap, or the wall-clock jump probe.
    // Press/tap dismissals within 5s of issuing the suspend are ignored: the freeze lands
    // seconds after systemctl returns, and a "wake" in that gap would freeze the live UI
    // on glass and turn the real wake press into a second suspend.
    property string sleepState: ""
    property double suspendedAt: 0
    property double wokeAt: 0
    function powerPressed() {
        if (root.sleepState === "arming")
            return  // ignore presses while the face is still flushing
        if (root.sleepState === "suspended") {
            if (Date.now() - root.suspendedAt >= 5000)
                root.wakeUp()
            return
        }
        root.sleepState = "arming"
        armTimer.restart()
    }
    function wakeUp() {
        root.sleepState = ""
        root.wokeAt = Date.now()
        armTimer.stop()
        // the waking power press is raw evdev (no Qt event): reset idle by hand, or a
        // timer that was near expiry at suspend would re-sleep moments after waking
        idleWatch.poke()
        // waking is an app-open moment: queue an auto sync when we're sitting at Home
        if (stack.depth === 1)
            autoSync.restart()
    }
    Timer {
        id: armTimer
        // No completion signal exists for the EPD flush (the waveform drive runs inside
        // this process); 1400ms is margin for a full-screen GC16 pass, unmeasured.
        interval: 1400
        onTriggered: {
            root.sleepState = "suspended"
            root.suspendedAt = Date.now()
            power.suspend()
        }
    }
    Timer {
        // Backup wake detector: while suspended the process is frozen, so a wall-clock
        // jump between ticks means we resumed (covers wakes whose key event is lost).
        id: resumeProbe
        interval: 2000
        repeat: true
        running: root.sleepState === "suspended"
        property double last: 0
        onRunningChanged: last = Date.now()
        onTriggered: {
            const now = Date.now()
            if (now - last > 10000)
                root.wakeUp()
            last = now
        }
    }

    // A WebDAV sync merged notebook rows into the DB: refresh the open page so it never shows
    // stale strokes vs the merged truth. No-op when no page is open.
    Connections {
        target: reader
        function onSyncedDataChanged() { controller.reloadOpenPage() }
        // A sync frozen mid-transfer by suspend times out up to 30s AFTER resume (monotonic
        // timers don't advance across sleep) and the wake auto-sync no-ops on its guard.
        // Retry once the zombie run reports failure, only shortly after a wake, from Home.
        function onSyncFinished(ok, summary) {
            if (!ok && stack.depth === 1 && Date.now() - root.wokeAt < 45000)
                autoSync.restart()
        }
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
        // Hidden while sleeping: belt-and-braces against input pass-through, and it takes
        // an open PageScreen's InkItem (and its Pen-waveform EPScreenModeItem region tag)
        // out of the scene so the sleep face flushes with the quality grays waveform
        // instead of the fast 2-level pen mode (no held ghosting through the standby face).
        visible: root.sleepState === ""
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

    SleepScreen {
        // ponytail: raw pen events still route to an open notebook canvas beneath this
        // overlay until suspend lands (~1.4s window); gate pen.canvasRect if it ever matters.
        visible: root.sleepState !== ""
        anchors.fill: parent
        z: 300
        onDismissed: {
            if (root.sleepState === "suspended" && Date.now() - root.suspendedAt >= 5000)
                root.wakeUp()
        }
    }
}

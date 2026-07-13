pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import CipherCodex

Item {
    id: settings

    required property var reader

    property bool syncEnabled: false
    property string status: ""
    property string davStatus: ""
    property bool syncing: false

    Connections {
        target: settings.reader
        function onSyncStarted() { settings.syncing = true; settings.davStatus = "syncing..." }
        function onSyncProgress(step) { settings.davStatus = step }
        function onSyncFinished(ok, summary) {
            settings.syncing = false
            settings.davStatus = ok
                ? ("synced — up " + summary.booksUp + " / down " + summary.booksDown
                   + " books, " + summary.entities + " items")
                : ("sync failed: " + summary.error)
        }
    }

    // Persist the four fields, then run `action` (which uses the stored account).
    // No separate SAVE button: TEST / REGISTER / toggle each commit the config first.
    function commit() {
        settings.reader.setSyncConfig(serverField.text, userField.text, passField.text, deviceField.text)
    }

    function commitDav() {
        settings.reader.setWebdavConfig(davUrlField.text, davUserField.text, davPassField.text)
    }

    // Scroll `item` (a Field) into the Flickable's viewport. The keyboard shrinks the StackView
    // (Main.qml anchors its bottom to keyboard.top), so flick.height already excludes the keyboard
    // — we only keep the focused field inside [contentY, contentY+height]. Deferred via callLater so
    // the height has settled after the keyboard appeared before we read it.
    function ensureVisible(item) {
        const yTop = item.mapToItem(flick.contentItem, 0, 0).y
        const yBottom = yTop + item.height
        if (yBottom > flick.contentY + flick.height)
            flick.contentY = Math.min(yBottom - flick.height, Math.max(0, flick.contentHeight - flick.height))
        else if (yTop < flick.contentY)
            flick.contentY = Math.max(0, yTop)
    }

    Component.onCompleted: {
        const c = settings.reader.syncConfig()   // password is never returned
        serverField.text = c.serverUrl ? c.serverUrl : ""
        userField.text = c.username ? c.username : ""
        deviceField.text = c.deviceName ? c.deviceName : ""
        settings.syncEnabled = c.enabled === true
        const d = settings.reader.webdavConfig()
        davUrlField.text = d.url ? d.url : ""
        davUserField.text = d.user ? d.user : ""
        // An auto-sync started from Home may already be running; without this seed the
        // button would show an idle SYNC NOW whose tap silently no-ops on the m_syncing guard.
        settings.syncing = d.syncing === true
        if (settings.syncing)
            settings.davStatus = "syncing..."
    }

    component Btn: Rectangle {
        id: b
        property alias label: t.text
        property bool inverted: false
        property bool active: false
        signal tapped()
        readonly property bool dark: b.active || (btnTap.pressed !== b.inverted)
        width: t.implicitWidth + 64          // 0 32 padding
        height: 88
        color: b.dark ? "black" : "white"
        border { color: "black"; width: Theme.chip }
        Text {
            id: t
            anchors.centerIn: parent
            color: b.dark ? "white" : "black"
            font { family: Theme.display; pixelSize: Theme.button; weight: Font.Bold }
        }
        // 88px visual, ≥90px touch target
        Item {
            anchors.fill: parent
            anchors.margins: -2
            TapHandler { id: btnTap; onTapped: b.tapped() }
        }
    }

    // Solid black section header strip for the cards.
    component CardHeader: Rectangle {
        property alias title: ht.text
        width: parent.width
        height: 64
        color: "black"
        Text {
            id: ht
            anchors { left: parent.left; leftMargin: 28; verticalCenter: parent.verticalCenter }
            color: "white"
            font { family: Theme.display; pixelSize: Theme.button; weight: Font.Bold; letterSpacing: 2 }
        }
    }

    // Labeled text field: mono micro label above a hairline-bordered value box.
    // Border thickens to Theme.frame while focused (component sheet ACTIVE state).
    component Field: Column {
        id: f
        property alias label: cap.text
        property alias text: input.text
        property string placeholder: ""
        property bool secret: false
        width: 760
        spacing: 8
        Text { id: cap; font { family: Theme.mono; pixelSize: Theme.micro } }
        Rectangle {
            width: parent.width; height: 72
            color: "white"
            border { color: "black"; width: input.activeFocus ? Theme.frame : Theme.hairline }
            Text {
                visible: input.text === ""
                anchors { left: parent.left; leftMargin: 20; verticalCenter: parent.verticalCenter }
                text: f.placeholder
                color: "#999999"
                font { family: Theme.reading; pixelSize: 24 }
            }
            TextInput {
                id: input
                anchors { fill: parent; leftMargin: 20; rightMargin: 20 }
                verticalAlignment: TextInput.AlignVCenter
                clip: true
                echoMode: f.secret ? TextInput.Password : TextInput.Normal
                font { family: Theme.reading; pixelSize: 24 }
                // Once focused (keyboard now up, Flickable height settled), scroll this field into view.
                onActiveFocusChanged: if (activeFocus) Qt.callLater(settings.ensureVisible, f)
            }
        }
        // Label + box together are ≥90px tall: tap anywhere on the field to focus.
        TapHandler { onTapped: input.forceActiveFocus() }
    }

    // Mono caption status line with a filled-circle bullet (glyph-safe ●).
    component StatusLine: Row {
        property alias text: st.text
        visible: st.text !== ""
        width: 760
        spacing: 12
        Rectangle {
            anchors.verticalCenter: parent.verticalCenter
            width: 12; height: 12; radius: 6; color: "black"
        }
        Text {
            id: st
            width: parent.width - 24
            wrapMode: Text.WordWrap
            font { family: Theme.mono; pixelSize: Theme.caption }
        }
    }

    // Hatched non-interactive "coming soon" row with white-backed labels.
    component SoonRow: Rectangle {
        property alias name: nm.text
        width: parent.width
        height: 80
        color: "white"
        border { color: "black"; width: Theme.hairline }
        Hatch { anchors.fill: parent; anchors.margins: Theme.hairline }
        Rectangle {
            anchors { left: parent.left; leftMargin: 24; verticalCenter: parent.verticalCenter }
            width: nm.implicitWidth + 20; height: nm.implicitHeight + 8
            Text {
                id: nm
                anchors.centerIn: parent
                font { family: Theme.display; pixelSize: 24; weight: Font.Medium }
            }
        }
        Rectangle {
            anchors { right: parent.right; rightMargin: 24; verticalCenter: parent.verticalCenter }
            width: cs.implicitWidth + 20; height: cs.implicitHeight + 8
            Text {
                id: cs
                anchors.centerIn: parent
                text: "COMING SOON"
                font { family: Theme.mono; pixelSize: 16 }
            }
        }
    }

    Rectangle {
        id: header
        width: parent.width; height: Theme.headerBand
        color: "black"
        Item {
            // full-height back target over the arrow+title zone; press = inversion
            anchors { left: parent.left; top: parent.top; bottom: parent.bottom }
            width: settingsBackRow.width + Theme.pad * 2
            Rectangle { anchors.fill: parent; color: "white"; visible: settingsBackTap.pressed }
            Row {
                id: settingsBackRow
                anchors { left: parent.left; leftMargin: Theme.pad; verticalCenter: parent.verticalCenter }
                spacing: 24
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "←"
                    color: settingsBackTap.pressed ? "black" : "white"
                    font.pixelSize: 34
                }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "SETTINGS"
                    color: settingsBackTap.pressed ? "black" : "white"
                    font { family: Theme.display; pixelSize: Theme.h2; weight: Font.Bold; letterSpacing: 2 }
                }
            }
            TapHandler { id: settingsBackTap; onTapped: settings.StackView.view.pop() }
        }
    }

    Flickable {
        id: flick
        anchors { top: header.bottom; left: parent.left; right: parent.right; bottom: parent.bottom
                  topMargin: 40; leftMargin: Theme.pad; rightMargin: Theme.pad }
        contentHeight: form.height + 40
        clip: true
    Column {
        id: form
        width: flick.width
        spacing: 36

        // ---- kosync position sync ----
        Rectangle {
            width: parent.width; height: koCol.height
            border { color: "black"; width: Theme.frame }
            Column {
                id: koCol
                width: parent.width
                CardHeader {
                    title: "KOREADER SYNC"
                    Rectangle {
                        id: chip
                        anchors { right: parent.right; rightMargin: 16; verticalCenter: parent.verticalCenter }
                        width: chipText.implicitWidth + 40
                        height: 48
                        readonly property bool lit: settings.syncEnabled !== chipTap.pressed
                        color: chip.lit ? "white" : "black"
                        border { color: "white"; width: Theme.chip }
                        Text {
                            id: chipText
                            anchors.centerIn: parent
                            text: settings.syncEnabled ? "SYNC: ON" : "SYNC: OFF"
                            color: chip.lit ? "black" : "white"
                            font { family: Theme.display; pixelSize: 20; weight: Font.Bold }
                        }
                        Item {
                            // 48px visual chip, ≥90px touch target
                            anchors.fill: parent
                            anchors.margins: -21
                            TapHandler {
                                id: chipTap
                                onTapped: {
                                    settings.commit()
                                    settings.syncEnabled = !settings.syncEnabled
                                    settings.reader.setSyncEnabled(settings.syncEnabled)
                                }
                            }
                        }
                    }
                }
                Column {
                    width: parent.width
                    padding: 28
                    spacing: 20
                    Field { id: serverField; label: "SERVER"; placeholder: "https://sync.example.org" }
                    Field { id: userField; label: "USERNAME"; placeholder: "username" }
                    Field { id: passField; label: "PASSWORD"; placeholder: "password"; secret: true }
                    Field { id: deviceField; label: "DEVICE NAME"; placeholder: "reMarkable 2" }
                    Row {
                        spacing: 16
                        Btn {
                            label: "TEST"
                            onTapped: {
                                settings.commit()
                                const r = settings.reader.testConnection()
                                settings.status = r.ok ? "CONNECTION OK" : ("FAILED: " + r.message)
                            }
                        }
                        Btn {
                            label: "REGISTER"
                            inverted: true
                            onTapped: {
                                settings.commit()
                                const r = settings.reader.registerUser()
                                settings.status = r.ok ? "REGISTERED" : ("FAILED: " + r.message)
                            }
                        }
                    }
                    StatusLine { text: settings.status }
                }
            }
        }

        // ---- WebDAV books + notes sync ----
        Rectangle {
            width: parent.width; height: davCol.height
            border { color: "black"; width: Theme.frame }
            Column {
                id: davCol
                width: parent.width
                CardHeader { title: "BOOKS + NOTES SYNC (WEBDAV)" }
                Column {
                    width: parent.width
                    padding: 28
                    spacing: 20
                    Field { id: davUrlField; label: "URL"; placeholder: "https://host/dav/" }
                    Field { id: davUserField; label: "USERNAME"; placeholder: "user" }
                    Field { id: davPassField; label: "PASSWORD"; placeholder: "app password"; secret: true }
                    Row {
                        spacing: 16
                        Btn {
                            label: "TEST"
                            onTapped: {
                                settings.commitDav()
                                const r = settings.reader.testWebdav()
                                settings.davStatus = r.ok ? "WEBDAV OK" : ("FAILED: " + r.message)
                            }
                        }
                        Btn {
                            label: settings.syncing ? "SYNCING..." : "SYNC NOW"
                            inverted: true
                            active: settings.syncing
                            onTapped: { if (!settings.syncing) { settings.commitDav(); settings.reader.syncNow() } }
                        }
                    }
                    StatusLine { text: settings.davStatus }
                }
            }
        }

        // ---- coming soon (non-interactive) ----
        Column {
            width: parent.width
            spacing: 14
            Text {
                text: "COMING SOON"
                font { family: Theme.mono; pixelSize: Theme.micro; letterSpacing: 2 }
            }
            Column {
                width: parent.width
                spacing: -Theme.hairline   // shared borders between rows
                SoonRow { name: "WI-FI" }
                SoonRow { name: "STORAGE" }
                SoonRow { name: "BATTERY · HANDEDNESS" }
            }
        }
    }
    }
}

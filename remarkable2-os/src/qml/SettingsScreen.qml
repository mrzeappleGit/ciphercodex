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
    }

    component Btn: Rectangle {
        id: b
        property alias label: t.text
        property bool inverted: false
        property bool active: false
        signal tapped()
        readonly property bool dark: b.active || (btnTap.pressed !== b.inverted)
        width: Math.max(90, t.implicitWidth + 40)
        height: 90
        color: b.dark ? "black" : "white"
        border { color: b.inverted ? "white" : "black"; width: 4 }
        Text {
            id: t
            anchors.centerIn: parent
            color: b.dark ? "white" : "black"
            font { pixelSize: 26; bold: true }
        }
        TapHandler { id: btnTap; onTapped: b.tapped() }
    }

    // Labeled text field (border box + placeholder), matching the library search style.
    component Field: Column {
        id: f
        property alias label: cap.text
        property alias text: input.text
        property string placeholder: ""
        property bool secret: false
        width: 760
        spacing: 8
        Text { id: cap; font { pixelSize: 24; bold: true } }
        Rectangle {
            width: parent.width; height: 90
            color: "white"
            border { color: "black"; width: 4 }
            Text {
                visible: input.text === ""
                anchors { left: parent.left; leftMargin: 20; verticalCenter: parent.verticalCenter }
                text: f.placeholder
                color: "#999999"
                font { pixelSize: 26; bold: true }
            }
            TextInput {
                id: input
                anchors { fill: parent; leftMargin: 20; rightMargin: 20 }
                verticalAlignment: TextInput.AlignVCenter
                clip: true
                echoMode: f.secret ? TextInput.Password : TextInput.Normal
                font { pixelSize: 26; bold: true }
                // Once focused (keyboard now up, Flickable height settled), scroll this field into view.
                onActiveFocusChanged: if (activeFocus) Qt.callLater(settings.ensureVisible, f)
            }
        }
    }

    Rectangle {
        id: header
        width: parent.width; height: 120
        color: "black"
        Btn {
            anchors { left: parent.left; leftMargin: 30; verticalCenter: parent.verticalCenter }
            label: "BACK"
            inverted: true
            onTapped: settings.StackView.view.pop()
        }
        Text {
            anchors.centerIn: parent
            text: "SETTINGS"
            color: "white"
            font { pixelSize: 44; letterSpacing: 10; bold: true }
        }
    }

    Flickable {
        id: flick
        anchors { top: header.bottom; left: parent.left; right: parent.right; bottom: parent.bottom
                  topMargin: 50; leftMargin: 80; rightMargin: 40 }
        contentHeight: form.height + 60
        clip: true
    Column {
        id: form
        width: 820
        spacing: 30

        Row {
            spacing: 30
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: "KOREADER SYNC"
                font { pixelSize: 34; letterSpacing: 4; bold: true }
            }
            Btn {
                label: settings.syncEnabled ? "SYNC: ON" : "SYNC: OFF"
                active: settings.syncEnabled
                onTapped: {
                    settings.commit()
                    settings.syncEnabled = !settings.syncEnabled
                    settings.reader.setSyncEnabled(settings.syncEnabled)
                }
            }
        }

        Field { id: serverField; label: "SERVER URL"; placeholder: "https://sync.example.org" }
        Field { id: userField; label: "USERNAME"; placeholder: "username" }
        Field { id: passField; label: "PASSWORD"; placeholder: "password"; secret: true }
        Field { id: deviceField; label: "DEVICE NAME"; placeholder: "reMarkable 2" }

        Row {
            spacing: 30
            Btn {
                label: "TEST CONNECTION"
                onTapped: {
                    settings.commit()
                    const r = settings.reader.testConnection()
                    settings.status = r.ok ? "CONNECTION OK" : ("FAILED: " + r.message)
                }
            }
            Btn {
                label: "REGISTER"
                onTapped: {
                    settings.commit()
                    const r = settings.reader.registerUser()
                    settings.status = r.ok ? "REGISTERED" : ("FAILED: " + r.message)
                }
            }
        }

        Text {
            visible: settings.status !== ""
            text: settings.status
            font { pixelSize: 26; bold: true }
        }

        // ---- WebDAV books + notes sync ----
        Rectangle { width: 820; height: 4; color: "black" }
        Text {
            text: "BOOKS + NOTES SYNC (WebDAV)"
            font { pixelSize: 34; letterSpacing: 4; bold: true }
        }
        Field { id: davUrlField; label: "WEBDAV URL"; placeholder: "https://host/dav/" }
        Field { id: davUserField; label: "USERNAME"; placeholder: "user" }
        Field { id: davPassField; label: "PASSWORD"; placeholder: "app password"; secret: true }
        Row {
            spacing: 30
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
                active: settings.syncing
                onTapped: { if (!settings.syncing) { settings.commitDav(); settings.reader.syncNow() } }
            }
        }
        Text {
            visible: settings.davStatus !== ""
            text: settings.davStatus
            width: 820; wrapMode: Text.WordWrap
            font { pixelSize: 26; bold: true }
        }
    }
    }
}

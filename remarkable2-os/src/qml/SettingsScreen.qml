pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import CipherCodex

Item {
    id: settings

    required property var reader

    property bool syncEnabled: false
    property string status: ""

    // Persist the four fields, then run `action` (which uses the stored account).
    // No separate SAVE button: TEST / REGISTER / toggle each commit the config first.
    function commit() {
        settings.reader.setSyncConfig(serverField.text, userField.text, passField.text, deviceField.text)
    }

    Component.onCompleted: {
        const c = settings.reader.syncConfig()   // password is never returned
        serverField.text = c.serverUrl ? c.serverUrl : ""
        userField.text = c.username ? c.username : ""
        deviceField.text = c.deviceName ? c.deviceName : ""
        settings.syncEnabled = c.enabled === true
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

    Column {
        anchors { top: header.bottom; topMargin: 50; left: parent.left; leftMargin: 80 }
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
    }
}

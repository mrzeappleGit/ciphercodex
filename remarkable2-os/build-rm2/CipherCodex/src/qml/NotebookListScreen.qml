pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import CipherCodex

Item {
    id: nbList

    required property var pen
    required property var controller

    property var items: []

    function reload() { nbList.items = nbList.controller.notebooks() }

    Component.onCompleted: reload()
    StackView.onActivated: reload()  // page counts change while a notebook is open

    component Btn: Rectangle {
        id: b
        property alias label: btnText.text
        // inverted = white-on-black at rest (header band / EXIT); pressed state flips either way
        property bool inverted: false
        signal tapped()
        width: Math.max(90, btnText.implicitWidth + 48)
        height: 90
        color: (btnTap.pressed !== b.inverted) ? "black" : "white"
        border { color: b.inverted ? "white" : "black"; width: 4 }
        Text {
            id: btnText
            anchors.centerIn: parent
            color: (btnTap.pressed !== b.inverted) ? "white" : "black"
            font { pixelSize: 28; bold: true }
        }
        TapHandler { id: btnTap; onTapped: b.tapped() }
    }

    Rectangle {
        id: header
        width: parent.width; height: 120
        color: "black"
        Btn {
            anchors { left: parent.left; leftMargin: 30; verticalCenter: parent.verticalCenter }
            label: "BACK"
            inverted: true
            onTapped: nbList.StackView.view.pop()
        }
        Text {
            anchors.centerIn: parent
            text: "NOTEBOOKS"
            color: "white"
            font { pixelSize: 44; letterSpacing: 10; bold: true }
        }
    }

    Btn {
        id: newBtn
        anchors { top: header.bottom; topMargin: 30; left: parent.left; leftMargin: 60 }
        label: "NEW NOTEBOOK"
        onTapped: {
            nbList.controller.createNotebook("Notebook " + (nbList.items.length + 1))
            nbList.reload()
        }
    }

    ListView {
        id: list
        anchors {
            top: newBtn.bottom; topMargin: 30
            left: parent.left; leftMargin: 60
            right: parent.right; rightMargin: 60
            bottom: exitBtn.top; bottomMargin: 30
        }
        clip: true
        spacing: 20
        model: nbList.items
        delegate: Rectangle {
            id: row
            required property var modelData
            property bool confirming: false
            width: list.width
            height: 130
            color: rowTap.pressed && !row.confirming ? "black" : "white"
            border { color: "black"; width: 4 }
            Text {
                visible: !row.confirming
                anchors { left: parent.left; leftMargin: 30; verticalCenter: parent.verticalCenter }
                width: parent.width - 300
                elide: Text.ElideRight
                text: row.modelData.title
                color: rowTap.pressed ? "white" : "black"
                font { pixelSize: 34; bold: true }
            }
            Text {
                visible: !row.confirming
                anchors { right: parent.right; rightMargin: 30; verticalCenter: parent.verticalCenter }
                text: row.modelData.pageCount + (row.modelData.pageCount === 1 ? " page" : " pages")
                color: rowTap.pressed ? "white" : "black"
                font.pixelSize: 28
            }
            Row {
                visible: row.confirming
                anchors.centerIn: parent
                spacing: 40
                Btn {
                    label: "DELETE"
                    onTapped: {
                        nbList.controller.deleteNotebook(row.modelData.id)
                        nbList.reload()
                    }
                }
                Btn { label: "CANCEL"; onTapped: row.confirming = false }
            }
            TapHandler {
                id: rowTap
                enabled: !row.confirming
                onTapped: nbList.StackView.view.push(pageComp, {
                    notebookId: row.modelData.id,
                    notebookTitle: row.modelData.title
                })
                onLongPressed: row.confirming = true
            }
        }
    }

    // Dev affordance: hand the panel back to xochitl (launcher script restarts it on exit)
    Btn {
        id: exitBtn
        anchors { bottom: parent.bottom; bottomMargin: 20; horizontalCenter: parent.horizontalCenter }
        label: "EXIT TO STOCK"
        inverted: true
        onTapped: Qt.quit()
    }

    Component {
        id: pageComp
        PageScreen { pen: nbList.pen; controller: nbList.controller }
    }
}

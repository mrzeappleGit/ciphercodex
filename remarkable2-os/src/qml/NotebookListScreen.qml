pragma ComponentBehavior: Bound
import QtQuick
import QtQuick.Controls
import CipherCodex

Item {
    id: nbList

    required property var pen
    required property var controller
    required property var reader  // only for onSyncedDataChanged below

    property var items: []
    property string query: ""

    function reload() {
        nbList.items = nbList.query === "" ? nbList.controller.notebooks()
                                            : nbList.controller.notebooks(nbList.query)
    }

    Component.onCompleted: reload()
    StackView.onActivated: reload()  // page counts change while a notebook is open
    Connections {
        target: nbList.reader
        // a sync started from Home merged rows while this list was open
        function onSyncedDataChanged() { nbList.reload() }
    }

    Rectangle {
        id: header
        width: parent.width; height: Theme.headerBand
        color: "black"

        Rectangle {  // back affordance: arrow + title, one tap zone (full band height >= 90)
            id: backZone
            anchors { left: parent.left; top: parent.top; bottom: parent.bottom }
            width: backRow.width + Theme.pad * 2
            color: backTap.pressed ? "white" : "black"
            Row {
                id: backRow
                anchors { left: parent.left; leftMargin: Theme.pad; verticalCenter: parent.verticalCenter }
                spacing: 24
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "←"
                    color: backTap.pressed ? "black" : "white"
                    font.pixelSize: 34
                }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "NOTEBOOKS"
                    color: backTap.pressed ? "black" : "white"
                    font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.h2; letterSpacing: 2 }
                }
            }
            TapHandler { id: backTap; onTapped: nbList.StackView.view.pop() }
        }

        Item {  // "+ NEW" chip, tap zone padded to full band height
            id: newZone
            anchors { right: parent.right; top: parent.top; bottom: parent.bottom }
            width: newChip.width + Theme.pad * 2
            Rectangle {
                id: newChip
                anchors.centerIn: parent
                width: newText.implicitWidth + 56  // 0 28px padding
                height: 88
                color: newTap.pressed ? "white" : "black"
                border { color: "white"; width: Theme.chip }
                Text {
                    id: newText
                    anchors.centerIn: parent
                    text: "+ NEW"
                    color: newTap.pressed ? "black" : "white"
                    font { family: Theme.display; weight: Font.Bold; pixelSize: 26; letterSpacing: 1 }
                }
            }
            TapHandler {
                id: newTap
                onTapped: {
                    nbList.controller.createNotebook("Notebook " + (nbList.items.length + 1))
                    nbList.reload()
                }
            }
        }
    }

    // Search row — 96px, Theme.frame bottom border (structure copied from LibraryScreen)
    Item {
        id: searchRow
        anchors { top: header.bottom; left: parent.left; right: parent.right }
        height: 96

        Item {  // magnifier built from rectangles (glyph policy)
            id: searchIcon
            anchors { left: parent.left; leftMargin: Theme.pad; verticalCenter: parent.verticalCenter }
            width: 30; height: 30
            Rectangle {
                width: 20; height: 20; radius: 10
                color: "white"
                border { color: "black"; width: 3 }
            }
            Rectangle {
                x: 14; y: 16
                width: 3; height: 12
                rotation: 45
                color: "black"
            }
        }

        Text {
            visible: searchInput.text === ""
            anchors { left: searchIcon.right; leftMargin: 20; verticalCenter: parent.verticalCenter }
            text: "Search notebooks & notes..."
            color: "#999999"
            font { family: Theme.reading; pixelSize: 26 }
        }
        TextInput {
            id: searchInput
            anchors {
                left: searchIcon.right; leftMargin: 20
                right: parent.right; rightMargin: Theme.pad
                top: parent.top; bottom: parent.bottom
            }
            verticalAlignment: TextInput.AlignVCenter
            clip: true
            color: "black"
            font { family: Theme.reading; pixelSize: 26 }
            onTextChanged: { nbList.query = text; nbList.reload() }
        }

        Rectangle {
            anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
            height: Theme.frame
            color: "black"
        }
    }

    ListView {
        id: list
        anchors {
            top: searchRow.bottom
            left: parent.left; right: parent.right
            bottom: exitBtn.top; bottomMargin: 24
        }
        clip: true
        model: nbList.items
        delegate: Rectangle {
            id: row
            required property var modelData
            required property int index
            property bool confirming: false
            readonly property bool inv: rowTap.pressed && !row.confirming
            width: list.width
            height: 170
            color: row.inv ? "black" : "white"

            Rectangle {  // thumbnail placeholder: deterministic ink marks per row index
                id: thumb
                anchors { left: parent.left; leftMargin: Theme.pad; verticalCenter: parent.verticalCenter }
                width: 94; height: 122
                color: row.inv ? "black" : "white"
                border { color: row.inv ? "white" : "black"; width: Theme.chip }
                clip: true
                Repeater {
                    model: 3
                    Rectangle {
                        required property int index
                        // ponytail: fake pen marks, stable per row; replace with real page thumbnails when they exist
                        x: 10 + (row.index * 5 + index * 11) % 12
                        y: 26 + index * 34 + (row.index * 3 + index * 7) % 8
                        width: 42 + (row.index * 7 + index * 13) % 32
                        height: 2 + (row.index + index) % 2
                        rotation: ((row.index * 13 + index * 29) % 25) - 12
                        color: row.inv ? "white" : "black"
                    }
                }
            }

            Column {
                anchors {
                    left: thumb.right; leftMargin: 28
                    right: rightBits.left; rightMargin: 28
                    verticalCenter: parent.verticalCenter
                }
                spacing: 6
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    text: row.modelData.title
                    color: row.inv ? "white" : "black"
                    font {
                        family: Theme.display; weight: Font.Bold
                        pixelSize: Theme.body; capitalization: Font.AllUppercase
                    }
                }
                Text {
                    width: parent.width
                    elide: Text.ElideRight
                    text: row.modelData.pageCount + (row.modelData.pageCount === 1 ? " PAGE" : " PAGES")
                    color: row.inv ? "white" : "black"
                    font { family: Theme.mono; pixelSize: Theme.caption }
                }
            }

            Row {  // arrow at rest, in-row confirm cluster while confirming (invisible items take no width)
                id: rightBits
                anchors { right: parent.right; rightMargin: Theme.pad; verticalCenter: parent.verticalCenter }
                spacing: 16
                Text {
                    visible: !row.confirming
                    anchors.verticalCenter: parent.verticalCenter
                    text: "→"
                    color: row.inv ? "white" : "black"
                    font.pixelSize: 28
                }
                Text {
                    visible: row.confirming
                    anchors.verticalCenter: parent.verticalCenter
                    text: "DELETE THIS NOTEBOOK?"
                    color: "black"
                    font { family: Theme.display; weight: Font.Bold; pixelSize: 24 }
                }
                Item {
                    visible: row.confirming
                    anchors.verticalCenter: parent.verticalCenter
                    width: cancelBtn.width + 16; height: Theme.touch  // pad tap zone to >= 90
                    Rectangle {
                        id: cancelBtn
                        anchors.centerIn: parent
                        width: cancelText.implicitWidth + 44; height: 52  // 12 22 padding
                        color: cancelTap.pressed ? "black" : "white"
                        border { color: "black"; width: Theme.chip }
                        Text {
                            id: cancelText
                            anchors.centerIn: parent
                            text: "CANCEL"
                            color: cancelTap.pressed ? "white" : "black"
                            font { family: Theme.display; weight: Font.Bold; pixelSize: 22 }
                        }
                    }
                    TapHandler { id: cancelTap; onTapped: row.confirming = false }
                }
                Item {
                    visible: row.confirming
                    anchors.verticalCenter: parent.verticalCenter
                    width: confirmBtn.width + 16; height: Theme.touch
                    Rectangle {
                        id: confirmBtn
                        anchors.centerIn: parent
                        width: confirmText.implicitWidth + 44; height: 52
                        color: confirmTap.pressed ? "white" : "black"
                        border { color: "black"; width: Theme.chip }
                        Text {
                            id: confirmText
                            anchors.centerIn: parent
                            text: "CONFIRM"
                            color: confirmTap.pressed ? "black" : "white"
                            font { family: Theme.display; weight: Font.Bold; pixelSize: 22 }
                        }
                    }
                    TapHandler {
                        id: confirmTap
                        onTapped: {
                            nbList.controller.deleteNotebook(row.modelData.id)
                            nbList.reload()
                        }
                    }
                }
            }

            Rectangle {  // divider
                anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
                height: Theme.hairline
                color: "black"
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
    Rectangle {
        id: exitBtn
        anchors { bottom: parent.bottom; bottomMargin: 24; horizontalCenter: parent.horizontalCenter }
        width: exitText.implicitWidth + 72
        height: Theme.touch
        color: exitTap.pressed ? "white" : "black"
        border { color: "black"; width: Theme.chip }
        Text {
            id: exitText
            anchors.centerIn: parent
            text: "EXIT TO STOCK"
            color: exitTap.pressed ? "black" : "white"
            font { family: Theme.display; weight: Font.Bold; pixelSize: Theme.button; letterSpacing: 1 }
        }
        TapHandler { id: exitTap; onTapped: Qt.quit() }
    }

    Component {
        id: pageComp
        PageScreen { pen: nbList.pen; controller: nbList.controller }
    }
}

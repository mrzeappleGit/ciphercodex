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

    StackView {
        id: stack
        anchors.fill: parent
        // e-ink: transitions only ghost; every stack op is immediate
        pushEnter: null
        pushExit: null
        popEnter: null
        popExit: null
        replaceEnter: null
        replaceExit: null
        initialItem: HomeScreen { pen: pen; controller: controller; reader: reader }
    }
}

pragma ComponentBehavior: Bound
import QtQuick

// On-screen keyboard for the reMarkable (no hardware keys). Operates on `target` — any focused
// TextInput/TextEdit — via its native insert()/remove()/cursorPosition, so no per-field wiring is
// needed. Monochrome, no animation, large keys for e-ink + finger/pen. Main.qml binds `target` to
// the window's active focus item when it is a text field, and shows the board while one is focused.
Item {
    id: kb

    property Item target: null
    property bool shift: false
    property bool symbols: false

    readonly property real keyH: 96
    readonly property real gap: 8

    // Three layers. Space/backspace/shift/symbols/done are special tokens handled below.
    readonly property var lettersLower: [
        ["1","2","3","4","5","6","7","8","9","0"],
        ["q","w","e","r","t","y","u","i","o","p"],
        ["a","s","d","f","g","h","j","k","l"],
        ["{shift}","z","x","c","v","b","n","m","{bs}"]
    ]
    readonly property var lettersUpper: [
        ["1","2","3","4","5","6","7","8","9","0"],
        ["Q","W","E","R","T","Y","U","I","O","P"],
        ["A","S","D","F","G","H","J","K","L"],
        ["{shift}","Z","X","C","V","B","N","M","{bs}"]
    ]
    readonly property var symbolRows: [
        ["1","2","3","4","5","6","7","8","9","0"],
        ["@",".","/",":","-","_","~","#","&","?"],
        ["!","'","\"","(",")","+","=","%","*"],
        ["{shift}",",",";","$","\\","|","<",">","{bs}"]
    ]
    readonly property var rows: symbols ? symbolRows : (shift ? lettersUpper : lettersLower)

    function type(ch) {
        if (!target) return
        const p = target.cursorPosition
        target.insert(p, ch)
        target.cursorPosition = p + ch.length
        if (shift && !symbols) shift = false   // one-shot shift, like a phone keyboard
    }
    function backspace() {
        if (!target) return
        const p = target.cursorPosition
        if (p > 0) { target.remove(p - 1, p) }
    }

    // 5 rows (4 letter/number rows + the space/DONE bar), 4 inter-row gaps, 2 margin gaps.
    height: 5 * keyH + 6 * gap
    // opaque panel so page content behind it never shows through on e-ink
    Rectangle { anchors.fill: parent; color: "white"; border { color: "black"; width: 4 } }

    Column {
        anchors { fill: parent; margins: kb.gap }
        spacing: kb.gap
        Repeater {
            model: kb.rows
            Row {
                id: rowItem
                required property var modelData
                spacing: kb.gap
                anchors.horizontalCenter: parent.horizontalCenter
                Repeater {
                    model: rowItem.modelData
                    Rectangle {
                        id: key
                        required property string modelData
                        readonly property bool special: modelData.startsWith("{")
                        // special keys are wider so the alpha rows stay aligned
                        width: modelData === "{shift}" || modelData === "{bs}" ? 150 : 96
                        height: kb.keyH
                        color: keyTap.pressed ? "black" : "white"
                        border { color: "black"; width: 3 }
                        Text {
                            anchors.centerIn: parent
                            color: keyTap.pressed ? "white" : "black"
                            font { pixelSize: key.special ? 26 : 34; bold: true }
                            text: key.modelData === "{shift}" ? (kb.shift ? "SHFT" : "shft")
                                  : key.modelData === "{bs}" ? "⌫"
                                  : key.modelData
                        }
                        TapHandler {
                            id: keyTap
                            onTapped: {
                                if (key.modelData === "{shift}") kb.shift = !kb.shift
                                else if (key.modelData === "{bs}") kb.backspace()
                                else kb.type(key.modelData)
                            }
                        }
                    }
                }
            }
        }
        // Bottom bar: layer toggle, space, done.
        Row {
            spacing: kb.gap
            anchors.horizontalCenter: parent.horizontalCenter
            Rectangle {
                width: 150; height: kb.keyH
                color: symTap.pressed ? "black" : "white"; border { color: "black"; width: 3 }
                Text { anchors.centerIn: parent; text: kb.symbols ? "ABC" : "?123"
                       color: symTap.pressed ? "white" : "black"; font { pixelSize: 26; bold: true } }
                TapHandler { id: symTap; onTapped: kb.symbols = !kb.symbols }
            }
            Rectangle {
                width: 560; height: kb.keyH
                color: spTap.pressed ? "black" : "white"; border { color: "black"; width: 3 }
                Text { anchors.centerIn: parent; text: "space"
                       color: spTap.pressed ? "white" : "black"; font { pixelSize: 26; bold: true } }
                TapHandler { id: spTap; onTapped: kb.type(" ") }
            }
            Rectangle {
                width: 220; height: kb.keyH
                color: doneTap.pressed ? "black" : "white"; border { color: "black"; width: 4 }
                Text { anchors.centerIn: parent; text: "DONE"
                       color: doneTap.pressed ? "white" : "black"; font { pixelSize: 28; bold: true } }
                // Drop focus -> Main.qml hides the board.
                TapHandler { id: doneTap; onTapped: if (kb.target) kb.target.focus = false }
            }
        }
    }
}

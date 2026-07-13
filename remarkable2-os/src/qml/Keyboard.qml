pragma ComponentBehavior: Bound
import QtQuick
import CipherCodex

// On-screen keyboard for the reMarkable (no hardware keys). Operates on `target` — any focused
// TextInput/TextEdit — via its native insert()/remove()/cursorPosition, so no per-field wiring is
// needed. Monochrome, no animation, large keys for e-ink + finger/pen. Main.qml binds `target` to
// the window's active focus item when it is a text field, and shows the board while one is focused.
Item {
    id: kb

    property Item target: null
    property bool shift: false
    property bool symbols: false

    readonly property real keyH: 104
    readonly property real gap: 8      // between keys in a row
    readonly property real rowGap: 10  // between rows

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
    // No {shift} here: shift is a letter-layer concern; a shift key in the symbols layer leaked its
    // state back into the letters. The freed slot is a plain backtick.
    readonly property var symbolRows: [
        ["1","2","3","4","5","6","7","8","9","0"],
        ["@",".","/",":","-","_","~","#","&","?"],
        ["!","'","\"","(",")","+","=","%","*"],
        ["`",",",";","$","\\","|","<",">","{bs}"]
    ]
    readonly property var rows: symbols ? symbolRows : (shift ? lettersUpper : lettersLower)

    // resetShift defaults true: a real character consumes the one-shot shift. Space passes false so
    // it can't steal a pending shift meant for the next letter.
    function type(ch, resetShift) {
        if (!target) return
        const p = target.cursorPosition
        target.insert(p, ch)
        target.cursorPosition = p + ch.length
        if (resetShift !== false && shift && !symbols) shift = false   // one-shot shift, like a phone keyboard
    }
    function backspace() {
        if (!target) return
        const p = target.cursorPosition
        if (p > 0) { target.remove(p - 1, p) }
    }

    // Content-fit: 5 rows x 104 + 4 x rowGap + top/bottom padding. The mock's literal 750
    // left a ~136px dead strip under the bottom row; fit instead (more page stays visible).
    height: Theme.frame + 20 + 5 * 104 + 4 * rowGap + 30
    // opaque panel so page content behind it never shows through on e-ink
    Rectangle { anchors.fill: parent; color: "white" }
    // frame border along the top edge only, per the design
    Rectangle { anchors { top: parent.top; left: parent.left; right: parent.right }
                height: Theme.frame; color: "black" }

    Column {
        anchors { fill: parent; topMargin: Theme.frame + 20; leftMargin: 20; rightMargin: 20; bottomMargin: 30 }
        spacing: kb.rowGap
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
                        width: key.special ? 160 : 120
                        height: kb.keyH
                        color: keyTap.pressed ? "black" : "white"
                        border { color: "black"; width: Theme.hairline }
                        Text {
                            anchors.centerIn: parent
                            color: keyTap.pressed ? "white" : "black"
                            font { family: Theme.display; weight: Font.Bold
                                   pixelSize: key.special ? 26 : 28 }
                            text: key.modelData === "{shift}" ? (kb.shift ? "SHFT" : "shft")
                                  : key.modelData === "{bs}" ? "DEL"  // no device font covers U+232B (was tofu)
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
        // Bottom bar: layer toggle, space, return (drops focus -> Main.qml hides the board).
        Row {
            spacing: kb.gap
            anchors.horizontalCenter: parent.horizontalCenter
            Rectangle {
                width: 180; height: kb.keyH
                color: symTap.pressed ? "black" : "white"
                border { color: "black"; width: Theme.hairline }
                Text { anchors.centerIn: parent; text: kb.symbols ? "ABC" : "?123"
                       color: symTap.pressed ? "white" : "black"
                       font { family: Theme.display; weight: Font.Bold; pixelSize: 24 } }
                // Clear shift on layer switch so a one-shot shift never bleeds across layers.
                TapHandler { id: symTap; onTapped: { kb.symbols = !kb.symbols; kb.shift = false } }
            }
            Rectangle {
                width: 700; height: kb.keyH
                color: spTap.pressed ? "black" : "white"
                border { color: "black"; width: Theme.hairline }
                Text { anchors.centerIn: parent; text: "SPACE"
                       color: spTap.pressed ? "white" : "black"
                       font { family: Theme.display; weight: Font.Bold; pixelSize: 24; letterSpacing: 4 } }
                TapHandler { id: spTap; onTapped: kb.type(" ", false) }
            }
            Rectangle {
                // primary action: inverted at rest, inverts back when pressed
                width: 260; height: kb.keyH
                color: doneTap.pressed ? "white" : "black"
                border { color: "black"; width: Theme.hairline }
                Text { anchors.centerIn: parent; text: "RETURN"
                       color: doneTap.pressed ? "black" : "white"
                       font { family: Theme.display; weight: Font.Bold; pixelSize: 24 } }
                // Drop focus -> Main.qml hides the board.
                TapHandler { id: doneTap; onTapped: if (kb.target) kb.target.focus = false }
            }
        }
    }
}

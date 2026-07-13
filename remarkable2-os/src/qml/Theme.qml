pragma Singleton
import QtQuick

// Design tokens from "CipherCodex OS.dc.html" (claude.ai/design). Pure 1-bit e-ink system:
// black on white only, full inversion is the only feedback state, no motion, no grays.
QtObject {
    // Families — embedded TTFs registered in main.cpp (assets/fonts/, OFL licensed)
    readonly property string display: "Rajdhani"       // titles, tiles, buttons (600/700)
    readonly property string mono: "Share Tech Mono"   // captions, meta, status lines
    readonly property string reading: "Courier Prime"  // reading body + field values

    // Type scale (px)
    readonly property int h1: 56           // display / hero
    readonly property int h2: 40           // screen title in the header band
    readonly property int tileLabel: 44    // home tile label
    readonly property int button: 24       // buttons/chips (32 on wide primaries)
    readonly property int body: 28         // list primary label
    readonly property int secondary: 22    // mono secondary
    readonly property int caption: 20      // mono caption
    readonly property int micro: 18        // mono micro
    readonly property int readingBody: 34  // Courier Prime reading text

    // Border weights: 4 major frames/tiles/cards, 3 buttons/chips, 2 fields/dividers
    readonly property int frame: 4
    readonly property int chip: 3
    readonly property int hairline: 2

    // Metrics
    readonly property int headerBand: 140  // management screens (readers use thin bars)
    readonly property int homeHeader: 168
    readonly property int touch: 90        // min touch target
    readonly property int pad: 56          // screen side padding
    // Elevation is a duplicated hard outline offset down-right (no shadows on e-ink)
    readonly property int lift: 10
}

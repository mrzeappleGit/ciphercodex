import QtQuick

// 1-bit dot lattice — cover placeholders, grounds (design's radial-gradient dots).
// Painted once per size; pure black dots on transparent, e-ink safe.
Canvas {
    property real pitch: 8
    property real dot: 1.8
    onPaint: {
        const g = getContext("2d")
        g.clearRect(0, 0, width, height)
        g.fillStyle = "black"
        for (let y = pitch / 2; y < height; y += pitch)
            for (let x = pitch / 2; x < width; x += pitch) {
                g.beginPath()
                g.arc(x, y, dot, 0, 2 * Math.PI)
                g.fill()
            }
    }
    onWidthChanged: requestPaint()
    onHeightChanged: requestPaint()
}

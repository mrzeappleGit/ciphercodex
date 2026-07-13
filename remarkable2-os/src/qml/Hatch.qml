import QtQuick

// 45° diagonal hatch — the "disabled / coming soon" ground from the component sheet.
// Painted once per size; pure black hairlines, e-ink safe.
Canvas {
    property real gap: 12
    onPaint: {
        const g = getContext("2d")
        g.clearRect(0, 0, width, height)
        g.strokeStyle = "black"
        g.lineWidth = 1.5
        for (let d = -height; d < width; d += gap) {
            g.beginPath()
            g.moveTo(d, height)
            g.lineTo(d + height, 0)
            g.stroke()
        }
    }
    onWidthChanged: requestPaint()
    onHeightChanged: requestPaint()
}

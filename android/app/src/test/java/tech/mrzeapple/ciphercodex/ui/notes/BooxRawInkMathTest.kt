package tech.mrzeapple.ciphercodex.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class BooxRawInkMathTest {
    @Test
    fun mapsViewCoordsPressureAndTime() {
        val p = BooxRawInkMath.rawPointToInk(
            x = 702f, y = 936f, pressure = 512f, timestampMs = 1100L,
            strokeStartMs = 1000L, viewW = 1404f, viewH = 1872f, maxPressure = 4096f,
        )
        assertEquals(0.5f, p.x, 1e-6f)
        assertEquals(0.5f, p.y, 1e-6f)
        assertEquals(2047, p.pressure) // 512*GAIN(4)/4096 * 4095 = 2047.5, truncated
        assertEquals(100L, p.t)
    }

    @Test
    fun firmWritingReachesFullScale() {
        // Go 10.3 firm handwriting peaks ~1024/4096 (hardware logcat): with the
        // calibration gain that must map to (near) full wire pressure, not a hairline.
        val p = BooxRawInkMath.rawPointToInk(
            x = 0f, y = 0f, pressure = 1024f, timestampMs = 0L,
            strokeStartMs = 0L, viewW = 100f, viewH = 100f, maxPressure = 4096f,
        )
        assertEquals(4095, p.pressure)
    }

    @Test
    fun clampsEverythingOutOfRange() {
        val p = BooxRawInkMath.rawPointToInk(
            x = -5f, y = 99999f, pressure = 99999f, timestampMs = 500L,
            strokeStartMs = 1000L, viewW = 1404f, viewH = 1872f, maxPressure = 4096f,
        )
        assertEquals(0f, p.x, 0f)
        assertEquals(1f, p.y, 0f)
        assertEquals(4095, p.pressure)
        assertEquals(0L, p.t) // timestamp before stroke start clamps to 0
    }

    @Test
    fun nonPositiveMaxPressureUsesFallback() {
        val p = BooxRawInkMath.rawPointToInk(
            x = 0f, y = 0f, pressure = 4096f, timestampMs = 0L,
            strokeStartMs = 0L, viewW = 100f, viewH = 100f, maxPressure = 0f,
        )
        assertEquals(4095, p.pressure) // 4096/4096 * 4095, clamped
    }

    @Test
    fun zeroViewSizeDoesNotDivideByZero() {
        val p = BooxRawInkMath.rawPointToInk(
            x = 10f, y = 10f, pressure = 100f, timestampMs = 0L,
            strokeStartMs = 0L, viewW = 0f, viewH = 0f, maxPressure = 4096f,
        )
        assertEquals(1f, p.x, 0f) // 10 / max(0,1) = 10, clamped to 1
        assertEquals(1f, p.y, 0f)
    }
}

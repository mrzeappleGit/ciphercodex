package tech.mrzeapple.ciphercodex.ui.notes

import android.os.Build
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint

/** Gate for the Boox raw-ink fast path. Onyx SDK classes may only be referenced
 *  behind this check (or in code only reachable behind it). */
fun isOnyxDevice(): Boolean = Build.MANUFACTURER.equals("ONYX", ignoreCase = true)

object BooxRawInkMath {
    /** Wacom EMR digitizers report 4096 pressure levels.
     *  ponytail: fallback + calibration knob in one — if the Go 10.3 Gen 2's
     *  EpdController.getMaxTouchPressure() reads 0/absent, hardware QA tunes this. */
    const val FALLBACK_MAX_PRESSURE = 4096f

    /** One raw SDK touch point (view-local px, raw pressure, epoch-ms timestamp) →
     *  wire InkPoint (page-normalized 0..1, pressure 0..4095, ms since stroke start). */
    fun rawPointToInk(
        x: Float,
        y: Float,
        pressure: Float,
        timestampMs: Long,
        strokeStartMs: Long,
        viewW: Float,
        viewH: Float,
        maxPressure: Float,
    ): InkPoint {
        val maxP = if (maxPressure > 0f) maxPressure else FALLBACK_MAX_PRESSURE
        return InkPoint(
            x = (x / viewW.coerceAtLeast(1f)).coerceIn(0f, 1f),
            y = (y / viewH.coerceAtLeast(1f)).coerceIn(0f, 1f),
            pressure = ((pressure / maxP) * 4095f).toInt().coerceIn(0, 4095),
            t = (timestampMs - strokeStartMs).coerceAtLeast(0L),
        )
    }
}

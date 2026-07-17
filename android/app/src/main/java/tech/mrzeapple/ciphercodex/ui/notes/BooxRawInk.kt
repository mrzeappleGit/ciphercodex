package tech.mrzeapple.ciphercodex.ui.notes

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint
import tech.mrzeapple.ciphercodex.sync.webdav.InkRender

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

private class RawInkView(ctx: Context) : View(ctx) {
    var helper: TouchHelper? = null
    var opened = false
}

/** Boox wet-ink layer: the EPD controller draws the stroke in hardware (rM2-class
 *  latency, pressure-width via the fountain brush); the finished stroke's points are
 *  converted and committed through the exact pipeline the Jetpack Ink path uses.
 *  While raw drawing is enabled the EPD defers normal view refreshes, so the
 *  committed-strokes Canvas underneath recomposes invisibly per commit — the screen
 *  catches up whenever raw mode is released (undo/redo pulse, tool switch, dispose). */
@Composable
fun BooxRawInkOverlay(
    vm: InkEditorViewModel,
    modifier: Modifier = Modifier,
) {
    val helperRef = remember { arrayOfNulls<TouchHelper>(1) }
    val viewRef = remember { arrayOfNulls<RawInkView>(1) }
    // Plain mutable holder, not compose state — the observer and the pulse both run on
    // main and only need to read/write the latest value, not trigger recomposition.
    val pausedRef = remember { BooleanArray(1) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Raw mode locks its screen region; never leave it enabled while paused. Also
        // never enable before openRawDrawing() has run (addObserver replays ON_RESUME
        // synchronously at composition, ahead of the layout listener's first call).
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    pausedRef[0] = true
                    if (viewRef[0]?.opened == true) helperRef[0]?.setRawDrawingEnabled(false)
                }
                Lifecycle.Event.ON_RESUME -> {
                    pausedRef[0] = false
                    if (viewRef[0]?.opened == true) helperRef[0]?.setRawDrawingEnabled(true)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) {
        vm.repaintTick.drop(1).collect {
            if (viewRef[0]?.opened != true) return@collect
            // Release raw mode so the undone/redone stroke list repaints on the EPD,
            // then re-arm. ponytail: 200ms settle covers Room emission + recompose +
            // draw; tune on hardware if the repaint races it.
            helperRef[0]?.setRawDrawingEnabled(false)
            delay(200)
            // Skip the re-arm if the activity paused during the delay — the lifecycle
            // observer's ON_RESUME becomes the sole re-enable in that case.
            if (!pausedRef[0]) helperRef[0]?.setRawDrawingEnabled(true)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = RawInkView(ctx)
            val callback = object : RawInputCallback() {
                override fun onBeginRawDrawing(b: Boolean, p: TouchPoint) {}
                override fun onEndRawDrawing(b: Boolean, p: TouchPoint) {}
                override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint) {}
                override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
                    val pts = list.points ?: return
                    if (pts.isEmpty()) return
                    val maxP = runCatching {
                        EpdController.getMaxTouchPressure()
                    }.getOrDefault(BooxRawInkMath.FALLBACK_MAX_PRESSURE)
                    val t0 = pts.first().timestamp
                    val viewW = view.width.toFloat()
                    val viewH = view.height.toFloat()
                    vm.commitStroke(pts.map { p ->
                        BooxRawInkMath.rawPointToInk(
                            p.x, p.y, p.pressure, p.timestamp, t0, viewW, viewH, maxP,
                        )
                    })
                }
                // Hardware eraser end / erase gestures unused — ERASE is a toolbar tool
                // on the normal (non-raw) path.
                override fun onBeginRawErasing(b: Boolean, p: TouchPoint) {}
                override fun onEndRawErasing(b: Boolean, p: TouchPoint) {}
                override fun onRawErasingTouchPointMoveReceived(p: TouchPoint) {}
                override fun onRawErasingTouchPointListReceived(l: TouchPointList) {}
            }
            val helper = TouchHelper.create(view, callback)
            view.helper = helper
            helperRef[0] = helper
            viewRef[0] = view
            view.addOnLayoutChangeListener { v, left, top, right, bottom, oL, oT, oR, oB ->
                val rv = v as RawInkView
                if (v.width <= 0 || v.height <= 0) return@addOnLayoutChangeListener
                if (!rv.opened) {
                    rv.opened = true
                    val limit = Rect()
                    // ponytail: local rect per the Onyx demo; if strokes land offset on
                    // hardware, switch to getGlobalVisibleRect (known one-line fix).
                    v.getLocalVisibleRect(limit)
                    helper.setStrokeWidth(9f * v.width / InkRender.PAGE_W)
                        .setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
                        .setStrokeColor(Color.BLACK)
                        .setLimitRect(limit, ArrayList())
                        .openRawDrawing()
                    helper.setRawDrawingEnabled(true)
                } else if (right - left != oR - oL || bottom - top != oB - oT) {
                    // Activity declares configChanges="orientation|screenSize", so
                    // rotation resizes this view without recreating the composable —
                    // refresh brush width + limit rect instead of re-opening (committed
                    // strokes are unaffected; only wet visuals/input region go stale).
                    val limit = Rect()
                    v.getLocalVisibleRect(limit)
                    helper.setStrokeWidth(9f * v.width / InkRender.PAGE_W)
                    helper.setLimitRect(limit, ArrayList())
                }
            }
            view
        },
        onRelease = { v ->
            (v as RawInkView).helper?.closeRawDrawing()
            helperRef[0] = null
            viewRef[0] = null
        },
    )
}

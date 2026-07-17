package tech.mrzeapple.ciphercodex.ui.notes

import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.mrzeapple.ciphercodex.CipherCodexApp
import tech.mrzeapple.ciphercodex.sync.webdav.InkGeometry
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoint
import tech.mrzeapple.ciphercodex.sync.webdav.InkPoints
import tech.mrzeapple.ciphercodex.sync.webdav.InkRender
import tech.mrzeapple.ciphercodex.ui.components.CipherCaption
import tech.mrzeapple.ciphercodex.ui.theme.LocalCipherColors

/** Full-screen stylus editor for one notebook page. Committed strokes render through
 *  the same [InkGeometry]/[InkRender] path as the PNG thumbnails (no drift); wet ink is
 *  drawn live by Jetpack Ink's [InProgressStrokesView] and handed off to Room the moment
 *  a stroke finishes — never both layers at once for the same stroke. */
@Composable
fun InkEditorScreen(
    pageGuid: String,
    title: String,
    onPrevPage: (() -> Unit)?,
    onNextPage: (() -> Unit)?,
    onAddPage: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalCipherColors.current
    val app = LocalContext.current.applicationContext as CipherCodexApp
    // Deliberate: keyed viewModel() accumulates one VM per pageGuid in the ViewModelStore for
    // the screen's lifetime (never cleared on navigation) — an in-flight commitStroke on the
    // OLD page's VM must keep running (mutex-serialized Room write) after ‹/›/+PAGE navigates.
    val vm: InkEditorViewModel = viewModel(
        key = pageGuid,
        factory = InkEditorViewModel.factory(app, pageGuid),
    )
    val strokes by vm.strokes.collectAsState()
    val tool by vm.tool.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val canRedo by vm.canRedo.collectAsState()
    BackHandler { vm.onEditorClosed(); onClose() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CipherCaption(
                text = "${title.uppercase()} · DONE",
                color = c.cyan,
                modifier = Modifier
                    .weight(1f)
                    .clickable { vm.onEditorClosed(); onClose() },
            )
            CipherCaption(
                text = "PEN",
                color = if (tool == EditorTool.PEN) c.cyan else c.muted,
                modifier = Modifier
                    .clickable { vm.tool.value = EditorTool.PEN }
                    .padding(start = 12.dp),
            )
            CipherCaption(
                text = "ERASE",
                color = if (tool == EditorTool.ERASE) c.cyan else c.muted,
                modifier = Modifier
                    .clickable { vm.tool.value = EditorTool.ERASE }
                    .padding(start = 12.dp),
            )
            CipherCaption(
                text = "UNDO",
                color = if (canUndo) c.cyan else c.muted,
                modifier = Modifier
                    .clickable(enabled = canUndo) { vm.undo() }
                    .padding(start = 12.dp),
            )
            CipherCaption(
                text = "REDO",
                color = if (canRedo) c.cyan else c.muted,
                modifier = Modifier
                    .clickable(enabled = canRedo) { vm.redo() }
                    .padding(start = 12.dp),
            )
            CipherCaption(
                text = "+PAGE",
                color = c.cyan,
                modifier = Modifier
                    .clickable { onAddPage() }
                    .padding(start = 12.dp),
            )
            if (onPrevPage != null) {
                CipherCaption(
                    text = "‹",
                    color = c.cyan,
                    modifier = Modifier
                        .clickable { onPrevPage() }
                        .padding(start = 12.dp),
                )
            }
            if (onNextPage != null) {
                CipherCaption(
                    text = "›",
                    color = c.cyan,
                    modifier = Modifier
                        .clickable { onNextPage() }
                        .padding(start = 12.dp),
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .aspectRatio(InkRender.PAGE_W.toFloat() / InkRender.PAGE_H)
                    .fillMaxWidth()
                    .background(Color.White),
            ) {
                // Committed strokes — the exact PNG-renderer look (round-cap black lines).
                Canvas(Modifier.fillMaxSize()) {
                    for (s in strokes) {
                        val pts = InkPoints.decode(s.pointsB64)
                        for (seg in InkGeometry.strokeSegments(pts, s.baseWidth)) {
                            drawLine(
                                color = Color.Black,
                                start = Offset(seg.x0 * size.width, seg.y0 * size.height),
                                end = Offset(seg.x1 * size.width, seg.y1 * size.height),
                                strokeWidth = seg.width * (size.width / InkRender.PAGE_W),
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }

                if (tool == EditorTool.PEN) {
                    // Wet ink: a real InProgressStrokesView driven directly off the raw
                    // MotionEvent stream, stylus pointers only. A finished stroke is
                    // converted to InkPoints and committed; the wet overlay only drops it
                    // once that commit lands in Room (see commitStroke's onCommitted).
                    // key(pageGuid): AndroidView's factory runs once per node, so without
                    // this the closures below would keep capturing the FIRST page's `vm`
                    // across ‹/›/+PAGE navigation (the call site never unmounts).
                    key(pageGuid) {
                        if (isOnyxDevice()) {
                            // Boox: hardware raw ink (rM2-class latency + pressure);
                            // same commitStroke pipeline, no wet-view handoff needed.
                            BooxRawInkOverlay(vm, Modifier.fillMaxSize())
                        } else {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                InProgressStrokesView(ctx).also { view ->
                                    view.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
                                        override fun onStrokesFinished(finished: Map<InProgressStrokeId, Stroke>) {
                                            val viewW = view.width.toFloat().coerceAtLeast(1f)
                                            val viewH = view.height.toFloat().coerceAtLeast(1f)
                                            val entries = finished.entries.toList()
                                            entries.forEachIndexed { index, (_, stroke) ->
                                                val inputs = stroke.inputs
                                                val points = (0 until inputs.size).map { i ->
                                                    val inp = inputs.get(i)
                                                    InkPoint(
                                                        x = inp.x / viewW,
                                                        y = inp.y / viewH,
                                                        // ponytail: NO_PRESSURE (-1f) devices coerce to 0, same
                                                        // clamp path as real low-pressure samples.
                                                        pressure = (inp.pressure * 4095).toInt().coerceIn(0, 4095),
                                                        t = inp.elapsedTimeMillis,
                                                    )
                                                }
                                                // Only the LAST stroke's commit removes the wet batch: opMutex
                                                // is fair/FIFO, so its onCommitted fires after every earlier
                                                // commit in this batch has landed in Room — removing per-stroke
                                                // would drop wet ink for strokes whose Room write is still queued.
                                                if (index == entries.lastIndex) {
                                                    vm.commitStroke(points) { view.removeFinishedStrokes(finished.keys) }
                                                } else {
                                                    vm.commitStroke(points)
                                                }
                                            }
                                        }
                                    })
                                    val pointerIdToStrokeId = HashMap<Int, InProgressStrokeId>()
                                    view.setOnTouchListener { v, event ->
                                        val actingIndex = event.actionIndex
                                        val pointerId = event.getPointerId(actingIndex)
                                        when (event.actionMasked) {
                                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                                                if (event.getToolType(actingIndex) != MotionEvent.TOOL_TYPE_STYLUS) {
                                                    // Palm-first posture: a non-stylus ACTION_DOWN must still be
                                                    // consumed (return true, start no stroke) or Android drops the
                                                    // whole gesture stream, so the stylus's later POINTER_DOWN
                                                    // (normal handwriting: palm rests down before the pen touches)
                                                    // would never arrive.
                                                    return@setOnTouchListener true
                                                }
                                                // ponytail: epsilon 0.1f is the stock-brush sample tolerance
                                                // (stroke-space mesh simplification); revisit if strokes facet.
                                                val brush = Brush.createWithColorIntArgb(
                                                    StockBrushes.pressurePenLatest,
                                                    android.graphics.Color.BLACK,
                                                    (9f * v.width / InkRender.PAGE_W).coerceAtLeast(0.1f),
                                                    0.1f,
                                                )
                                                pointerIdToStrokeId[pointerId] = view.startStroke(event, pointerId, brush)
                                                true
                                            }
                                            MotionEvent.ACTION_MOVE -> {
                                                // No top-level tool-type gate here: actionIndex is always slot 0
                                                // on ACTION_MOVE, so a resting palm/finger in slot 0 would drop
                                                // the whole batch. Filter per-pointer instead.
                                                for (i in 0 until event.pointerCount) {
                                                    if (event.getToolType(i) != MotionEvent.TOOL_TYPE_STYLUS) continue
                                                    val pid = event.getPointerId(i)
                                                    val sid = pointerIdToStrokeId[pid] ?: continue
                                                    view.addToStroke(event, pid, sid)
                                                }
                                                true
                                            }
                                            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                                                if (event.getToolType(actingIndex) != MotionEvent.TOOL_TYPE_STYLUS) {
                                                    return@setOnTouchListener false
                                                }
                                                val sid = pointerIdToStrokeId.remove(pointerId)
                                                    ?: return@setOnTouchListener false
                                                view.finishStroke(event, pointerId, sid)
                                                true
                                            }
                                            MotionEvent.ACTION_CANCEL -> {
                                                // CANCEL is single-pointer/slot-0 but ends the WHOLE gesture, so
                                                // drain every in-flight stroke (no tool-type gate) rather than
                                                // just the acting pointer — otherwise other pointers' wet ink
                                                // leaks until the view is recreated by page navigation.
                                                pointerIdToStrokeId.values.forEach { view.cancelStroke(it, event) }
                                                pointerIdToStrokeId.clear()
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                }
                            },
                        )
                        }
                    }
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(pageGuid) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (down.type != PointerType.Stylus) return@awaitEachGesture
                                    down.consume()
                                    vm.eraseAt(down.position.x / size.width, down.position.y / size.height)
                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.pressed) {
                                            vm.eraseAt(change.position.x / size.width, change.position.y / size.height)
                                            change.consume()
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            },
                    )
                }
            }
        }
    }
}

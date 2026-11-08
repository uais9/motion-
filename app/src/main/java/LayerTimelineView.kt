package com.motion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// The timeline: one row per layer, top layer first.
// - drag empty space sideways: move the playhead
// - drag up or down: scroll the rows
// - tap a bar: select it
// - drag a selected bar's edges: trim it
// - long-press a bar, then drag: move it in time, or to another row (stacking order)
// - pinch: zoom
// - keyframe diamonds of the selected layer: tap one to select it (tap again to deselect, tap
//   several to select several), drag a selected one to move all selected keyframes in time
// Every edit snaps to whole frames.
class LayerTimelineView(context: Context) : View(context) {

    private var project: Project = Project()
    private var comp: Comp? = null
    private var playhead = 0L
    private var selectedId = -1L
    private var selKeys: Set<Long> = emptySet() // layer times of the selected keyframes (selected layer)
    private var multiIds: Set<Long> = emptySet() // layers selected together (multi-select), for Sequence Layers and parenting

    var onScrub: ((Long) -> Unit)? = null
    var onSelect: ((Long) -> Unit)? = null
    var onMove: ((Long, Long, Int) -> Unit)? = null
    var onTrim: ((Long, Int, Long) -> Unit)? = null
    var onKeySelect: ((Long, Boolean) -> Unit)? = null // layer time, true = toggle it, false = select only it
    var onKeysMove: ((Long) -> Unit)? = null // how far the selected keyframes moved, in ticks
    var onKeysScale: ((Long, Double) -> Unit)? = null // anchor (layer time) and factor: the selected keyframes were stretched

    private val density = resources.displayMetrics.density
    private val rulerH = 22f * density
    private val rowH = 34f * density
    private val rowGap = 3f * density
    private val topPad = rulerH + 4f * density
    private var pxPerSec = 70f * density
    private var scrollY = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var downPlayhead = 0L
    private var moved = false
    private var mode = 0
    private var movingId = -1L
    private var movingDelta = 0L
    private var movingRow = 0
    private var trimId = -1L
    private var trimEdge = 0
    private var trimTarget = 0L
    private var keyHit = NO_KEY
    private var keyDragging = false
    private var keyDelta = 0L
    private var keyScaling = false // long-press the first or last of 2 or more selected keyframes, then drag: scale in time
    private var scaleAnchor = 0L
    private var scaleFactor = 1.0

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val rect = RectF()
    private val handleRect = RectF()

    private val rowPaint = Paint().apply { color = Color.parseColor("#1E1E27") }
    private val outsidePaint = Paint().apply { color = Color.parseColor("#AA0B0B0E") }
    private val rulerBgPaint = Paint().apply { color = Color.parseColor("#15151A") }
    private val barPaint = Paint().apply { isAntiAlias = true }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }
    private val diamond = android.graphics.Path()
    private val diamondPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }
    private val tickPaint = Paint().apply {
        color = Color.GRAY
        strokeWidth = density
    }
    private val labelPaint = Paint().apply {
        color = Color.LTGRAY
        textSize = 10f * density
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 11f * density
        isAntiAlias = true
    }
    private val headPaint = Paint().apply {
        color = Color.WHITE
        strokeWidth = 2f * density
        isAntiAlias = true
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pxPerSec = (pxPerSec * detector.scaleFactor)
                    .coerceIn(12f * density, 400f * density)
                invalidate()
                return true
            }
        }
    )

    private val longPress = Runnable {
        val c = comp
        if (c != null && !moved && trimId < 0L && keyHit == NO_KEY && !scaleDetector.isInProgress) {
            val i = layerAt(downX, downY)
            if (i >= 0) {
                movingId = c.layers[i].id
                movingDelta = 0L
                movingRow = i
                onSelect?.invoke(movingId)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }
    }

    // like Alt-dragging the first or last selected keyframe in After Effects
    private val keyScalePress = Runnable {
        val mn = selKeys.minOrNull()
        val mx = selKeys.maxOrNull()
        if (keyHit != NO_KEY && !moved && selKeys.size >= 2 && mn != null && mx != null && (keyHit == mn || keyHit == mx)) {
            keyScaling = true
            scaleAnchor = if (keyHit == mn) mx else mn
            scaleFactor = 1.0
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    fun setData(p: Project, c: Comp?, t: Long, selected: Long, keys: Set<Long> = emptySet(), multi: Set<Long> = emptySet()) {
        project = p
        multiIds = multi
        comp = c
        playhead = t
        selectedId = selected
        selKeys = keys
        invalidate()
    }

    // ---------- geometry ----------

    private fun pxPerTick(): Double = pxPerSec.toDouble() / TICKS_PER_SECOND

    private fun xOf(t: Long): Float = width / 2f + ((t - playhead) * pxPerTick()).toFloat()

    private fun ticksOfDx(dx: Float): Long = Math.round(dx / pxPerTick())

    private fun rowTop(row: Int): Float = topPad + row * (rowH + rowGap) - scrollY

    private fun clampScroll() {
        val n = comp?.layers?.size ?: 0
        val content = topPad + n * (rowH + rowGap) + 4f * density
        val maxScroll = max(0f, content - height)
        scrollY = scrollY.coerceIn(0f, maxScroll)
    }

    private fun rowAt(y: Float): Int {
        val c = comp ?: return 0
        val row = floor(((y + scrollY - topPad) / (rowH + rowGap)).toDouble()).toInt()
        return row.coerceIn(0, max(0, c.layers.size - 1))
    }

    private fun layerAt(x: Float, y: Float): Int {
        val c = comp ?: return -1
        if (y + scrollY < topPad) return -1
        val row = ((y + scrollY - topPad) / (rowH + rowGap)).toInt()
        if (row < 0 || row >= c.layers.size) return -1
        val rt = rowTop(row)
        if (y < rt || y > rt + rowH) return -1
        val t = playhead + ticksOfDx(x - width / 2f)
        val l = c.layers[row]
        return if (t >= l.inPoint && t < l.outPoint) row else -1
    }

    // 1 = left edge, 2 = right edge, 0 = none. Only the selected layer has edge handles.
    private fun edgeAt(x: Float, y: Float): Int {
        val c = comp ?: return 0
        val idx = c.layers.indexOfFirst { it.id == selectedId }
        if (idx < 0) return 0
        val rt = rowTop(idx)
        if (y < rt || y > rt + rowH) return 0
        val l = c.layers[idx]
        val left = xOf(l.inPoint)
        val right = xOf(l.outPoint)
        val zone = max(16f * density, min(26f * density, (right - left) / 3f))
        val dl = abs(x - left)
        val dr = abs(x - right)
        if (dl <= zone && dl <= dr) return 1
        if (dr <= zone) return 2
        return 0
    }

    // the keyframe diamond of the selected layer under the finger: its layer time, or NO_KEY
    private fun keyAt(x: Float, y: Float): Long {
        val c = comp ?: return NO_KEY
        val idx = c.layers.indexOfFirst { it.id == selectedId }
        if (idx < 0) return NO_KEY
        val l = c.layers[idx]
        val ky = rowTop(idx) + rowH - 9f * density
        if (abs(y - ky) > 17f * density) return NO_KEY
        var best = NO_KEY
        var bestDx = Float.MAX_VALUE
        for (prop in l.props.values) {
            for (key in prop.keys) {
                val kt = l.startTime + key.time
                if (kt < l.inPoint || kt > l.outPoint) continue
                val dx = abs(x - xOf(kt))
                if (dx <= 19f * density && dx < bestDx) {
                    bestDx = dx
                    best = key.time
                }
            }
        }
        return best
    }

    private fun secLabel(t: Long): String {
        val totalMs = t / TICKS_PER_MS
        val s = (totalMs / 1000L).toInt()
        val base = String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        return if (totalMs % 1000L != 0L) "$base.5" else base
    }

    // ---------- drawing ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        canvas.drawColor(Color.parseColor("#15151A"))
        val c = comp
        if (c == null) {
            canvas.drawText("No composition", 12f * density, rulerH + 20f * density, labelPaint)
            return
        }
        clampScroll()
        val k = pxPerTick()

        for (i in c.layers.indices) {
            val rt = rowTop(i)
            if (rt + rowH < rulerH || rt > h) continue
            canvas.drawRect(0f, rt, w, rt + rowH, rowPaint)
        }

        val x0 = xOf(0L)
        val x1 = xOf(c.durationTicks)
        if (x0 > 0f) canvas.drawRect(0f, rulerH, min(x0, w), h, outsidePaint)
        if (x1 < w) canvas.drawRect(max(x1, 0f), rulerH, w, h, outsidePaint)

        for (i in c.layers.indices) {
            val l = c.layers[i]
            var inP = l.inPoint
            var outP = l.outPoint
            var row = i
            if (l.id == movingId) {
                inP += movingDelta
                outP += movingDelta
                row = movingRow
            }
            if (l.id == trimId) {
                if (trimEdge == 1) inP = trimTarget else outP = trimTarget
            }
            val rt = rowTop(row)
            if (rt + rowH < rulerH || rt > h) continue
            val left = xOf(inP)
            val right = max(xOf(outP), left + 2f)
            if (right < 0f || left > w) continue

            val f = project.footage.firstOrNull { it.id == l.footageId }
            barPaint.color = when {
                !l.enabled -> Color.parseColor("#4A4F5C")
                l.kind == LayerKind.SOLID -> darker(l.fill)
                l.kind == LayerKind.NULL -> Color.parseColor("#8E5BD0")
                l.kind == LayerKind.SHAPE -> Color.parseColor("#D9822B")
                f != null && f.kind == FootageKind.IMAGE -> Color.parseColor("#2F9E6D")
                else -> Color.parseColor("#3B5BDB")
            }
            rect.set(left, rt + 2f * density, right, rt + rowH - 2f * density)
            canvas.drawRoundRect(rect, 5f * density, 5f * density, barPaint)
            if (right - left > 30f * density) {
                canvas.save()
                canvas.clipRect(rect)
                val tx = max(left, 0f) + 6f * density
                canvas.drawText(l.name, tx, rt + rowH * 0.62f, textPaint)
                canvas.restore()
            }

            // keyframe diamonds (keyframe times are measured from the layer's start time);
            // the selected ones are yellow and follow the finger while they are dragged
            val shift = if (l.id == movingId) movingDelta else 0L
            val isSel = l.id == selectedId
            for (prop in l.props.values) {
                for (key in prop.keys) {
                    val picked = isSel && key.time in selKeys
                    val dragShift = if (picked && keyDragging) keyDelta else 0L
                    var kt = l.startTime + key.time + shift + dragShift
                    if (picked && keyScaling) kt = l.startTime + shift + scaleAnchor + Math.round((key.time - scaleAnchor) * scaleFactor)
                    if (kt < inP || kt > outP) continue
                    diamondPaint.color = if (picked) Color.parseColor("#FFD54A") else Color.WHITE
                    val dr = (if (picked) 6f else 4.5f) * density
                    if (key.rove) {
                        // a roving keyframe is a circle (its time is worked out for you)
                        canvas.drawCircle(xOf(kt), rect.bottom - 7f * density, dr * 0.9f, diamondPaint)
                    } else {
                        drawDiamond(canvas, xOf(kt), rect.bottom - 7f * density, dr)
                    }
                }
            }
            diamondPaint.color = Color.WHITE

            if (l.id in multiIds && l.id != movingId) {
                borderPaint.color = Color.parseColor("#FFB04A")
                canvas.drawRoundRect(rect, 5f * density, 5f * density, borderPaint)
            }
            if (l.id == movingId) {
                borderPaint.color = Color.YELLOW
                canvas.drawRoundRect(rect, 5f * density, 5f * density, borderPaint)
            } else if (l.id == selectedId) {
                borderPaint.color = Color.parseColor("#9DB4FF")
                canvas.drawRoundRect(rect, 5f * density, 5f * density, borderPaint)
                val hw = 7f * density
                val hh = (rect.height()) * 0.6f
                val mid = rect.centerY()
                handleRect.set(left, mid - hh / 2f, left + hw, mid + hh / 2f)
                canvas.drawRoundRect(handleRect, 3f * density, 3f * density, handlePaint)
                handleRect.set(right - hw, mid - hh / 2f, right, mid + hh / 2f)
                canvas.drawRoundRect(handleRect, 3f * density, 3f * density, handlePaint)
            }
        }

        canvas.drawRect(0f, 0f, w, rulerH, rulerBgPaint)
        val steps = doubleArrayOf(0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0)
        var step = 60.0
        for (s in steps) {
            if (s * pxPerSec >= 56f * density) {
                step = s
                break
            }
        }
        val stepTicks = (step * TICKS_PER_SECOND).toLong()
        val halfW = (cx / k).toLong()
        var t = max(0L, ((playhead - halfW) / stepTicks) * stepTicks)
        val endT = playhead + halfW + stepTicks
        while (t <= endT) {
            val x = xOf(t)
            canvas.drawLine(x, rulerH * 0.55f, x, rulerH, tickPaint)
            canvas.drawText(secLabel(t), x + 3f * density, rulerH * 0.5f, labelPaint)
            t += stepTicks
        }
        canvas.drawLine(cx, 0f, cx, h, headPaint)
        canvas.drawCircle(cx, 4f * density, 4f * density, headPaint)
    }

    // a solid's bar is its colour, a bit darker so the name can be read
    private fun darker(argb: Int): Int =
        Color.rgb(((argb shr 16) and 255) * 3 / 4, ((argb shr 8) and 255) * 3 / 4, (argb and 255) * 3 / 4)

    private fun drawDiamond(canvas: Canvas, x: Float, y: Float, r: Float) {
        diamond.reset()
        diamond.moveTo(x, y - r)
        diamond.lineTo(x + r, y)
        diamond.lineTo(x, y + r)
        diamond.lineTo(x - r, y)
        diamond.close()
        canvas.drawPath(diamond, diamondPaint)
    }

    // ---------- touch ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        val c = comp
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                lastY = e.y
                downPlayhead = playhead
                moved = false
                mode = 0
                movingId = -1L
                movingDelta = 0L
                trimId = -1L
                trimEdge = 0
                keyHit = keyAt(e.x, e.y)
                keyDragging = false
                keyDelta = 0L
                keyScaling = false
                removeCallbacks(keyScalePress)
                if (keyHit != NO_KEY) postDelayed(keyScalePress, ViewConfiguration.getLongPressTimeout().toLong())
                if (keyHit == NO_KEY) {
                    val edge = edgeAt(e.x, e.y)
                    if (edge != 0 && c != null) {
                        val l = c.layers.firstOrNull { it.id == selectedId }
                        if (l != null) {
                            trimId = l.id
                            trimEdge = edge
                            trimTarget = if (edge == 1) l.inPoint else l.outPoint
                        }
                    }
                }
                if (trimId < 0L && keyHit == NO_KEY) {
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                moved = true
                removeCallbacks(longPress)
                trimId = -1L
                movingId = -1L
                keyHit = NO_KEY
                keyDragging = false
                keyDelta = 0L
                keyScaling = false
                removeCallbacks(keyScalePress)
                lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                moved = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1 || scaleDetector.isInProgress) {
                    moved = true
                    removeCallbacks(longPress)
                    trimId = -1L
                    movingId = -1L
                    keyHit = NO_KEY
                    keyDragging = false
                    keyDelta = 0L
                    keyScaling = false
                    removeCallbacks(keyScalePress)
                    lastY = e.y
                } else if (c != null) {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (trimId >= 0L) {
                        val l = c.layers.firstOrNull { it.id == trimId }
                        if (l != null) {
                            val base = if (trimEdge == 1) l.inPoint else l.outPoint
                            val b = trimBounds(project, c, l, trimEdge)
                            val raw = snapFrame(base + ticksOfDx(dx), c.frameRate)
                            trimTarget = if (b.first <= b.second) raw.coerceIn(b.first, b.second) else base
                            invalidate()
                        }
                    } else if (movingId >= 0L) {
                        val l = c.layers.firstOrNull { it.id == movingId }
                        if (l != null) {
                            val b = moveBounds(c, l)
                            val raw = snapFrame(ticksOfDx(dx), c.frameRate)
                            movingDelta = if (b.first <= b.second) raw.coerceIn(b.first, b.second) else 0L
                            movingRow = rowAt(e.y)
                            invalidate()
                        }
                    } else if (keyHit != NO_KEY) {
                        removeCallbacks(longPress)
                        if (keyScaling) {
                            val denom = (keyHit - scaleAnchor).toDouble()
                            if (denom != 0.0) scaleFactor = ((keyHit + ticksOfDx(dx) - scaleAnchor) / denom).coerceAtLeast(0.05)
                            invalidate()
                        } else if (moved) {
                            removeCallbacks(keyScalePress)
                            if (!keyDragging) {
                                keyDragging = true
                                // dragging a keyframe that is not selected selects just that one
                                if (keyHit !in selKeys) onKeySelect?.invoke(keyHit, false)
                            }
                            val l = c.layers.firstOrNull { it.id == selectedId }
                            if (l != null && selKeys.isNotEmpty()) {
                                val raw = snapFrame(ticksOfDx(dx), c.frameRate)
                                val lo = -(l.startTime + (selKeys.minOrNull() ?: 0L))
                                val hi = c.durationTicks - (l.startTime + (selKeys.maxOrNull() ?: 0L))
                                keyDelta = if (lo <= hi) raw.coerceIn(lo, hi) else 0L
                            }
                            invalidate()
                        }
                    } else if (moved) {
                        removeCallbacks(longPress)
                        if (mode == 0) mode = if (abs(dy) > abs(dx)) 2 else 1
                        if (mode == 1) {
                            val target = downPlayhead - ticksOfDx(dx)
                            onScrub?.invoke(snapFrame(target, c.frameRate).coerceIn(0L, c.durationTicks))
                        } else {
                            scrollY -= (e.y - lastY)
                            clampScroll()
                            invalidate()
                        }
                    }
                    lastY = e.y
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (trimId >= 0L) {
                    val id = trimId
                    val ed = trimEdge
                    val target = trimTarget
                    trimId = -1L
                    trimEdge = 0
                    if (moved) onTrim?.invoke(id, ed, target)
                    invalidate()
                } else if (movingId >= 0L) {
                    val id = movingId
                    val d = movingDelta
                    val row = movingRow
                    movingId = -1L
                    movingDelta = 0L
                    onMove?.invoke(id, d, row)
                    invalidate()
                } else if (keyHit != NO_KEY && keyScaling) {
                    val anchor = scaleAnchor
                    val factor = scaleFactor
                    keyHit = NO_KEY
                    keyScaling = false
                    removeCallbacks(keyScalePress)
                    if (abs(factor - 1.0) > 0.001) onKeysScale?.invoke(anchor, factor)
                    invalidate()
                } else if (keyHit != NO_KEY) {
                    removeCallbacks(keyScalePress)
                    val hit = keyHit
                    val d = keyDelta
                    val wasDragging = keyDragging
                    keyHit = NO_KEY
                    keyDragging = false
                    keyDelta = 0L
                    if (wasDragging) {
                        if (d != 0L) onKeysMove?.invoke(d)
                    } else if (!moved) {
                        onKeySelect?.invoke(hit, true)
                    }
                    invalidate()
                } else if (!moved && c != null) {
                    val i = layerAt(e.x, e.y)
                    onSelect?.invoke(if (i >= 0) c.layers[i].id else -1L)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                movingId = -1L
                movingDelta = 0L
                trimId = -1L
                trimEdge = 0
                keyHit = NO_KEY
                keyDragging = false
                keyDelta = 0L
                keyScaling = false
                removeCallbacks(keyScalePress)
                invalidate()
            }
        }
        return true
    }

    private companion object {
        const val NO_KEY = Long.MIN_VALUE
    }
}

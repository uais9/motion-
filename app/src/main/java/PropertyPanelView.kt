package com.motion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// The property panel for the selected layer, one row per property.
//  - stopwatch: turn animation (keyframes) on or off
//  - drag a value sideways: change it (drag away up or down for fine control)
//  - tap a value: type an exact number.  Long-press a value: reset it
//  - diamond: add or delete a keyframe at the playhead (animated properties only)
class PropertyPanelView(context: Context) : View(context) {

    private class Row(
        val path: String,
        val label: String,
        val dims: Int,
        val unitsPerDp: Double,
        val lo: Double?,
        val hi: Double?
    )

    companion object {
        private val ROWS = listOf(
            Row(Path.POSITION, "Position", 2, 4.0, null, null),
            Row(Path.SCALE, "Scale", 2, 0.8, null, null),
            Row(Path.ROTATION, "Rotation", 1, 0.8, null, null),
            Row(Path.OPACITY, "Opacity", 1, 0.5, 0.0, 100.0),
            Row(Path.ANCHOR, "Anchor", 2, 4.0, null, null)
        )

        fun labelOf(path: String): String = ROWS.firstOrNull { it.path == path }?.label ?: path
    }

    var onScrubStart: (() -> Unit)? = null
    var onScrub: ((String, List<Double>) -> Unit)? = null
    var onScrubEnd: (() -> Unit)? = null
    var onTapValue: ((String) -> Unit)? = null
    var onReset: ((String) -> Unit)? = null
    var onToggle: ((String) -> Unit)? = null
    var onKey: ((String) -> Unit)? = null

    private var layer: Layer? = null
    private var layerTime = 0L

    private val density = resources.displayMetrics.density
    private val rowH = 34f * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var activeRow = -1
    private var activeDim = 0
    private var activeKind = 0
    private var scrubbing = false
    private var longFired = false
    private var downX = 0f
    private var downY = 0f
    private var startValue: List<Double> = emptyList()

    private val cellPaint = Paint().apply { isAntiAlias = true }
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val textPaint = Paint().apply { isAntiAlias = true }
    private val diamond = android.graphics.Path()

    fun setData(l: Layer?, lt: Long, @Suppress("UNUSED_PARAMETER") defaults: Map<String, List<Double>>) {
        layer = l
        layerTime = lt
        invalidate()
    }

    private val longPress = Runnable {
        if (activeKind == 2 && !scrubbing && activeRow >= 0) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onReset?.invoke(ROWS[activeRow].path)
        }
    }

    // ---------- layout ----------

    private fun rowTop(i: Int): Float = 4f * density + i * rowH

    private fun watchRect(i: Int): RectF =
        RectF(6f * density, rowTop(i), 42f * density, rowTop(i) + rowH)

    private fun cellRect(i: Int, d: Int): RectF {
        val x0 = 112f * density + d * 80f * density
        return RectF(x0, rowTop(i) + 3f * density, x0 + 74f * density, rowTop(i) + rowH - 3f * density)
    }

    private fun keyRect(i: Int): RectF =
        RectF(width - 42f * density, rowTop(i), width - 6f * density, rowTop(i) + rowH)

    private fun hit(x: Float, y: Float) {
        activeRow = -1
        activeKind = 0
        activeDim = 0
        for (i in ROWS.indices) {
            if (watchRect(i).contains(x, y)) {
                activeRow = i
                activeKind = 1
                return
            }
            if (keyRect(i).contains(x, y)) {
                activeRow = i
                activeKind = 3
                return
            }
            for (d in 0 until ROWS[i].dims) {
                if (cellRect(i, d).contains(x, y)) {
                    activeRow = i
                    activeKind = 2
                    activeDim = d
                    return
                }
            }
        }
    }

    // ---------- drawing ----------

    private fun drawDiamond(canvas: Canvas, x: Float, y: Float, r: Float, animated: Boolean, hasKey: Boolean) {
        diamond.reset()
        diamond.moveTo(x, y - r)
        diamond.lineTo(x + r, y)
        diamond.lineTo(x, y + r)
        diamond.lineTo(x - r, y)
        diamond.close()
        if (animated && hasKey) {
            fillPaint.color = Color.parseColor("#FFD54A")
            canvas.drawPath(diamond, fillPaint)
        }
        linePaint.strokeWidth = 1.6f * density
        linePaint.color = if (animated) Color.WHITE else Color.parseColor("#4A4F5C")
        canvas.drawPath(diamond, linePaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#14141A"))
        val l = layer
        if (l == null) {
            textPaint.color = Color.GRAY
            textPaint.textSize = 13f * density
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("Tap a layer bar to edit its properties", 12f * density, 30f * density, textPaint)
            return
        }
        val blue = Color.parseColor("#4C8DFF")
        for (i in ROWS.indices) {
            val row = ROWS[i]
            val prop = l.props[row.path]
            val animated = prop != null && prop.animated
            val hasKey = prop != null && prop.keys.any { it.time == layerTime }
            val vals = Eval.layerValue(l, row.path, layerTime)
            val top = rowTop(i)
            val baseline = top + rowH * 0.64f

            val wr = watchRect(i)
            val wx = wr.centerX()
            val wy = wr.centerY() + 1f * density
            linePaint.strokeWidth = 1.6f * density
            linePaint.color = if (animated) blue else Color.GRAY
            canvas.drawCircle(wx, wy, 7f * density, linePaint)
            canvas.drawLine(wx, wy, wx, wy - 4f * density, linePaint)
            canvas.drawLine(wx, wy, wx + 3f * density, wy + 1.5f * density, linePaint)
            canvas.drawLine(wx - 2f * density, wy - 9f * density, wx + 2f * density, wy - 9f * density, linePaint)

            textPaint.color = Color.LTGRAY
            textPaint.textSize = 12f * density
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(row.label, 46f * density, baseline, textPaint)

            for (d in 0 until row.dims) {
                val r = cellRect(i, d)
                val on = scrubbing && activeRow == i && activeDim == d
                cellPaint.color = if (on) Color.parseColor("#3A3F66") else Color.parseColor("#25252F")
                canvas.drawRoundRect(r, 6f * density, 6f * density, cellPaint)
                textPaint.color = if (animated) Color.parseColor("#7FB0FF") else Color.WHITE
                textPaint.textAlign = Paint.Align.CENTER
                val s = if (d < vals.size) String.format(Locale.US, "%.1f", vals[d]) else "-"
                canvas.drawText(s, r.centerX(), baseline, textPaint)
            }
            textPaint.textAlign = Paint.Align.LEFT

            val kr = keyRect(i)
            drawDiamond(canvas, kr.centerX(), kr.centerY(), 6f * density, animated, hasKey)
        }
    }

    // ---------- touch ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val l = layer
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                scrubbing = false
                longFired = false
                hit(e.x, e.y)
                if (l != null && activeKind == 2 && activeRow >= 0) {
                    startValue = Eval.layerValue(l, ROWS[activeRow].path, layerTime)
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (l != null && activeKind == 2 && activeRow >= 0 && !longFired) {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (!scrubbing && abs(dx) > slop && abs(dx) > abs(dy)) {
                        scrubbing = true
                        removeCallbacks(longPress)
                        onScrubStart?.invoke()
                    }
                    if (scrubbing && startValue.isNotEmpty()) {
                        val row = ROWS[activeRow]
                        val fine = abs(dy) > 48f * density
                        val factor = if (fine) 0.1 else 1.0
                        var v = startValue.getOrElse(activeDim) { 0.0 } +
                            (dx / density) * row.unitsPerDp * factor
                        val lo = row.lo
                        val hi = row.hi
                        if (lo != null) v = max(v, lo)
                        if (hi != null) v = min(v, hi)
                        val out = startValue.toMutableList()
                        if (activeDim < out.size) out[activeDim] = v
                        onScrub?.invoke(row.path, out)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (scrubbing) {
                    onScrubEnd?.invoke()
                } else if (!longFired && activeRow >= 0) {
                    val path = ROWS[activeRow].path
                    val moved = abs(e.x - downX) > slop || abs(e.y - downY) > slop
                    if (!moved) {
                        when (activeKind) {
                            1 -> onToggle?.invoke(path)
                            2 -> onTapValue?.invoke(path)
                            3 -> onKey?.invoke(path)
                        }
                    }
                }
                scrubbing = false
                activeRow = -1
                activeKind = 0
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (scrubbing) onScrubEnd?.invoke()
                scrubbing = false
                activeRow = -1
                activeKind = 0
                invalidate()
            }
        }
        return true
    }
}

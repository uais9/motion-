package com.motion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// The graph editor, modelled on After Effects' value graph (EffectCraft's keyframe crate has
// the same idea: keyframes with eased, bezier segments).
//  - the chips on top choose the property (only animated ones can be graphed)
//  - the plot draws the property's value over time, one line per number (x, y...)
//  - the segment between two keyframes is shaped with two bezier handles (influence and speed)
//  - tap the plot to choose a segment, drag a handle to reshape it
//  - the chips at the bottom set the segment to a preset: Linear, Ease, In, Out, In-Out or Hold
class GraphEditorView(context: Context) : View(context) {

    private class Preset(val label: String, val interp: Interp, val ease: List<Double>)

    private class Mapping(val t0: Long, val t1: Long, val vmin: Double, val vmax: Double)

    companion object {
        private val PATHS = listOf(Path.POSITION, Path.SCALE, Path.ROTATION, Path.OPACITY, Path.ANCHOR)
        private val NAMES = listOf("Position", "Scale", "Rotation", "Opacity", "Anchor")
        private val PRESETS = listOf(
            Preset("Linear", Interp.LINEAR, listOf(0.0, 0.0, 1.0, 1.0)),
            Preset("Ease", Interp.BEZIER, listOf(0.25, 0.1, 0.25, 1.0)),
            Preset("In", Interp.BEZIER, listOf(0.42, 0.0, 1.0, 1.0)),
            Preset("Out", Interp.BEZIER, listOf(0.0, 0.0, 0.58, 1.0)),
            Preset("In-Out", Interp.BEZIER, listOf(0.42, 0.0, 0.58, 1.0)),
            Preset("Hold", Interp.HOLD, listOf(0.33, 0.0, 0.67, 1.0))
        )
        private val DEFAULT_EASE = listOf(0.33, 0.0, 0.67, 1.0)
    }

    var onEaseStart: (() -> Unit)? = null
    var onEase: ((String, Int, Interp, List<Double>) -> Unit)? = null
    var onEaseEnd: (() -> Unit)? = null
    var onPreset: ((String, Int, Interp, List<Double>) -> Unit)? = null

    private var layer: Layer? = null
    private var layerTime = 0L
    private var lastLayerId = -1L
    private var selPath = Path.POSITION
    private var userSeg = -1
    private var userChose = false

    private val density = resources.displayMetrics.density
    private val chipH = 24f * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // touch state: 1 top chip, 2 bottom chip, 3 handle 1, 4 handle 2, 5 plot
    private var target = 0
    private var targetIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val linePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        isAntiAlias = true
        textSize = 11f * density
    }
    private val dimColors = intArrayOf(
        Color.parseColor("#FF6B6B"),
        Color.parseColor("#6BE58E"),
        Color.parseColor("#6BB5FF")
    )

    fun setData(l: Layer?, lt: Long) {
        val id = l?.id ?: -1L
        if (id != lastLayerId) {
            userSeg = -1
            userChose = false
            lastLayerId = id
        }
        layer = l
        layerTime = lt
        // show an animated property by default, so the graph is not empty
        if (l != null && !userChose && l.props[selPath]?.animated != true) {
            for (pth in PATHS) {
                if (l.props[pth]?.animated == true) {
                    selPath = pth
                    userSeg = -1
                    break
                }
            }
        }
        invalidate()
    }

    // ---------- helpers ----------

    private fun currentProp(): Prop? = layer?.props?.get(selPath)

    private fun segIndex(keys: List<Keyframe>): Int {
        if (keys.size < 2) return -1
        if (userSeg in 0 until keys.size - 1) return userSeg
        var i = 0
        while (i < keys.size - 2 && layerTime >= keys[i + 1].time) i++
        return i
    }

    private fun plotRect(): RectF =
        RectF(
            34f * density,
            chipH + 12f * density,
            width - 10f * density,
            height - chipH - 12f * density
        )

    private fun chipRect(i: Int, count: Int, top: Float): RectF {
        val w = (width - 8f * density) / count
        val x0 = 4f * density + i * w
        return RectF(x0 + 1.5f * density, top, x0 + w - 1.5f * density, top + chipH)
    }

    private fun topChip(i: Int): RectF = chipRect(i, PATHS.size, 2f * density)

    private fun bottomChip(i: Int): RectF = chipRect(i, PRESETS.size, height - chipH - 2f * density)

    private fun mapping(p: Prop): Mapping? {
        if (p.keys.size < 2) return null
        val first = p.keys.first().time
        val last = p.keys.last().time
        if (last <= first) return null
        val pad = max(1L, (last - first) / 16)
        var vmin = Double.MAX_VALUE
        var vmax = -Double.MAX_VALUE
        for (i in 0..80) {
            val t = first + (last - first) * i / 80
            for (v in Eval.valueAt(p, t)) {
                vmin = min(vmin, v)
                vmax = max(vmax, v)
            }
        }
        for (k in p.keys) {
            for (v in k.value) {
                vmin = min(vmin, v)
                vmax = max(vmax, v)
            }
        }
        if (vmax - vmin < 1e-6) {
            vmax += 1.0
            vmin -= 1.0
        }
        val padV = (vmax - vmin) * 0.1
        return Mapping(first - pad, last + pad, vmin - padV, vmax + padV)
    }

    private fun xOf(m: Mapping, r: RectF, t: Long): Float =
        r.left + ((t - m.t0).toDouble() / (m.t1 - m.t0) * r.width()).toFloat()

    private fun yOf(m: Mapping, r: RectF, v: Double): Float =
        r.bottom - ((v - m.vmin) / (m.vmax - m.vmin) * r.height()).toFloat()

    private fun timeAt(m: Mapping, r: RectF, x: Float): Double =
        m.t0 + (x - r.left) / r.width() * (m.t1 - m.t0).toDouble()

    private fun valueAtY(m: Mapping, r: RectF, y: Float): Double =
        m.vmin + (r.bottom - y) / r.height() * (m.vmax - m.vmin)

    // the number (x, y...) that changes most across the segment: the handles are drawn on it
    private fun referenceDim(a: Keyframe, b: Keyframe): Int {
        var best = 0
        var bestDelta = -1.0
        for (d in a.value.indices) {
            val delta = abs(b.value.getOrElse(d) { a.value[d] } - a.value[d])
            if (delta > bestDelta) {
                bestDelta = delta
                best = d
            }
        }
        return best
    }

    private fun easeOf(k: Keyframe): List<Double> = if (k.ease.size == 4) k.ease else DEFAULT_EASE

    // handle positions (in screen pixels) for the segment, or null when there is nothing to shape
    private fun handles(p: Prop, seg: Int, m: Mapping, r: RectF): FloatArray? {
        val a = p.keys[seg]
        val b = p.keys[seg + 1]
        if (a.interp != Interp.BEZIER) return null
        val d = referenceDim(a, b)
        val va = a.value.getOrElse(d) { 0.0 }
        val vb = b.value.getOrElse(d) { va }
        if (abs(vb - va) < 1e-9) return null
        val e = easeOf(a)
        val span = (b.time - a.time).toDouble()
        val h1t = a.time + (e[0] * span).toLong()
        val h2t = a.time + (e[2] * span).toLong()
        return floatArrayOf(
            xOf(m, r, h1t), yOf(m, r, va + e[1] * (vb - va)),
            xOf(m, r, h2t), yOf(m, r, va + e[3] * (vb - va))
        )
    }

    // ---------- drawing ----------

    private fun drawChip(canvas: Canvas, r: RectF, text: String, on: Boolean, enabled: Boolean) {
        fillPaint.color = when {
            on -> Color.parseColor("#3D6BFF")
            enabled -> Color.parseColor("#2A2A35")
            else -> Color.parseColor("#1C1C24")
        }
        canvas.drawRoundRect(r, 6f * density, 6f * density, fillPaint)
        textPaint.color = if (enabled || on) Color.WHITE else Color.parseColor("#6A6F7C")
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 10.5f * density
        canvas.drawText(text, r.centerX(), r.centerY() + 4f * density, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#14141A"))
        val l = layer
        if (l == null) {
            textPaint.color = Color.parseColor("#C8CCD8")
            textPaint.textSize = 13f * density
            canvas.drawText("Tap a layer bar in the timeline first.", 14f * density, 34f * density, textPaint)
            return
        }

        for (i in PATHS.indices) {
            val animated = l.props[PATHS[i]]?.animated == true
            drawChip(canvas, topChip(i), NAMES[i], PATHS[i] == selPath, animated)
        }

        val p = currentProp()
        val r = plotRect()
        val m = if (p != null) mapping(p) else null
        if (p == null || m == null) {
            textPaint.color = Color.parseColor("#C8CCD8")
            textPaint.textSize = 13f * density
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("Nothing to graph yet.", 14f * density, r.centerY() - 8f * density, textPaint)
            canvas.drawText("Turn on the stopwatch, then set a 2nd", 14f * density, r.centerY() + 12f * density, textPaint)
            canvas.drawText("keyframe at a different time.", 14f * density, r.centerY() + 30f * density, textPaint)
            return
        }

        val seg = segIndex(p.keys)
        val presetSel = if (seg >= 0) p.keys[seg] else null
        for (i in PRESETS.indices) {
            val pr = PRESETS[i]
            var on = false
            if (presetSel != null && presetSel.interp == pr.interp) {
                on = pr.interp != Interp.BEZIER || easeNear(easeOf(presetSel), pr.ease)
            }
            drawChip(canvas, bottomChip(i), pr.label, on, seg >= 0)
        }

        // selected segment
        if (seg >= 0) {
            fillPaint.color = Color.parseColor("#22FFFFFF")
            canvas.drawRect(xOf(m, r, p.keys[seg].time), r.top, xOf(m, r, p.keys[seg + 1].time), r.bottom, fillPaint)
        }

        // frame and value labels
        linePaint.strokeWidth = density
        linePaint.color = Color.parseColor("#33FFFFFF")
        canvas.drawRect(r, linePaint)
        textPaint.color = Color.GRAY
        textPaint.textSize = 9.5f * density
        canvas.drawText(String.format(Locale.US, "%.0f", m.vmax), 2f * density, r.top + 8f * density, textPaint)
        canvas.drawText(String.format(Locale.US, "%.0f", m.vmin), 2f * density, r.bottom, textPaint)

        // the curves
        val dims = p.keys[0].value.size
        val steps = 100
        val firstT = p.keys.first().time
        val lastT = p.keys.last().time
        for (d in 0 until dims) {
            linePaint.color = if (dims == 1) Color.WHITE else dimColors[min(d, dimColors.size - 1)]
            linePaint.strokeWidth = 1.8f * density
            var px = 0f
            var py = 0f
            for (i in 0..steps) {
                val t = firstT + (lastT - firstT) * i / steps
                val v = Eval.valueAt(p, t).getOrElse(d) { 0.0 }
                val x = xOf(m, r, t)
                val y = yOf(m, r, v)
                if (i > 0) canvas.drawLine(px, py, x, y, linePaint)
                px = x
                py = y
            }
        }

        // keyframes
        for (k in p.keys) {
            for (d in 0 until dims) {
                fillPaint.color = if (dims == 1) Color.WHITE else dimColors[min(d, dimColors.size - 1)]
                canvas.drawCircle(xOf(m, r, k.time), yOf(m, r, k.value.getOrElse(d) { 0.0 }), 4f * density, fillPaint)
            }
        }

        // handles of the selected segment
        if (seg >= 0) {
            val h = handles(p, seg, m, r)
            if (h != null) {
                val a = p.keys[seg]
                val b = p.keys[seg + 1]
                val d = referenceDim(a, b)
                linePaint.color = Color.parseColor("#FFD54A")
                linePaint.strokeWidth = 1.4f * density
                canvas.drawLine(
                    xOf(m, r, a.time), yOf(m, r, a.value.getOrElse(d) { 0.0 }), h[0], h[1], linePaint
                )
                canvas.drawLine(
                    xOf(m, r, b.time), yOf(m, r, b.value.getOrElse(d) { 0.0 }), h[2], h[3], linePaint
                )
                fillPaint.color = Color.parseColor("#FFD54A")
                canvas.drawCircle(h[0], h[1], 6f * density, fillPaint)
                canvas.drawCircle(h[2], h[3], 6f * density, fillPaint)
            }
        }

        // playhead
        val lt = layerTime
        if (lt >= m.t0 && lt <= m.t1) {
            linePaint.color = Color.WHITE
            linePaint.strokeWidth = density
            val x = xOf(m, r, lt)
            canvas.drawLine(x, r.top, x, r.bottom, linePaint)
        }
    }

    private fun easeNear(a: List<Double>, b: List<Double>): Boolean {
        for (i in 0 until 4) {
            if (abs(a[i] - b[i]) > 0.01) return false
        }
        return true
    }

    // ---------- touch ----------

    private fun hitTest(x: Float, y: Float) {
        target = 0
        targetIndex = -1
        val l = layer ?: return
        for (i in PATHS.indices) {
            if (topChip(i).contains(x, y)) {
                target = 1
                targetIndex = i
                return
            }
        }
        val p = currentProp() ?: return
        val m = mapping(p) ?: return
        val seg = segIndex(p.keys)
        for (i in PRESETS.indices) {
            if (bottomChip(i).contains(x, y)) {
                target = 2
                targetIndex = i
                return
            }
        }
        val r = plotRect()
        if (seg >= 0) {
            val h = handles(p, seg, m, r)
            if (h != null) {
                val reach = 28f * density
                val d1 = abs(x - h[0]) + abs(y - h[1])
                val d2 = abs(x - h[2]) + abs(y - h[3])
                if (d1 <= reach && d1 <= d2) {
                    target = 3
                    return
                }
                if (d2 <= reach) {
                    target = 4
                    return
                }
            }
        }
        if (r.contains(x, y)) target = 5
        if (l.id < 0L) target = 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
                hitTest(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (target == 3 || target == 4) {
                    val p = currentProp()
                    if (p != null) {
                        val moved = abs(e.x - downX) > slop || abs(e.y - downY) > slop
                        if (!dragging && moved) {
                            dragging = true
                            onEaseStart?.invoke()
                        }
                        if (dragging) dragHandle(p, e.x, e.y)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    onEaseEnd?.invoke()
                } else {
                    val moved = abs(e.x - downX) > slop || abs(e.y - downY) > slop
                    if (!moved) tap(e.x)
                }
                dragging = false
                target = 0
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onEaseEnd?.invoke()
                dragging = false
                target = 0
            }
        }
        return true
    }

    private fun tap(x: Float) {
        when (target) {
            1 -> {
                selPath = PATHS[targetIndex]
                userSeg = -1
                userChose = true
                invalidate()
            }
            2 -> {
                val p = currentProp() ?: return
                val seg = segIndex(p.keys)
                if (seg < 0) return
                val pr = PRESETS[targetIndex]
                onPreset?.invoke(selPath, seg, pr.interp, pr.ease)
            }
            5 -> {
                val p = currentProp() ?: return
                val m = mapping(p) ?: return
                val t = timeAt(m, plotRect(), x)
                var i = 0
                while (i < p.keys.size - 2 && t >= p.keys[i + 1].time) i++
                userSeg = i
                invalidate()
            }
        }
    }

    private fun dragHandle(p: Prop, x: Float, y: Float) {
        val m = mapping(p) ?: return
        val seg = segIndex(p.keys)
        if (seg < 0) return
        val a = p.keys[seg]
        val b = p.keys[seg + 1]
        val d = referenceDim(a, b)
        val va = a.value.getOrElse(d) { 0.0 }
        val vb = b.value.getOrElse(d) { va }
        if (abs(vb - va) < 1e-9) return
        val r = plotRect()
        val span = (b.time - a.time).toDouble()
        val nx = ((timeAt(m, r, x) - a.time) / span).coerceIn(0.0, 1.0)
        val ny = ((valueAtY(m, r, y) - va) / (vb - va)).coerceIn(-1.0, 2.0)
        val e = easeOf(a)
        val next = if (target == 3) listOf(nx, ny, e[2], e[3]) else listOf(e[0], e[1], nx, ny)
        onEase?.invoke(selPath, seg, Interp.BEZIER, next)
    }
}

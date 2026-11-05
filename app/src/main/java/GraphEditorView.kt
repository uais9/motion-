package com.motion

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// The graph editor, modelled on After Effects' value graph (EffectCraft's keyframe crate has
// the same idea: keyframes with eased, bezier segments).
//  - the chips on top choose the property (only animated ones can be graphed)
//  - the plot draws the property's value over time, one line per number (x, y...)
//  - the segment between two keyframes is shaped with two bezier handles (influence and speed)
//  - tap the plot to choose a segment, drag a handle to reshape it
//  - the chips at the bottom: Linear, Easy Ease (both ends), Ease In (arrival at the end keyframe),
//    Ease Out (departure from the start keyframe), Hold, and Numbers (the Keyframe Velocity dialog)
//    Easy Ease = speed 0 with 33.33% influence, as in After Effects
//  - the Value | Speed switch (top right of the plot) swaps to the speed graph: the speed curve of the
//    property, with influence (horizontal) and speed (vertical) handles, like After Effects
class GraphEditorView(context: Context) : View(context) {

    // side: 0 = whole segment, 1 = only the start handle (ease out), 2 = only the end handle (ease in),
    // 3 = not a preset, opens the numbers dialog
    private class Preset(val label: String, val interp: Interp, val ease: List<Double>, val side: Int = 0)

    private class Mapping(val t0: Long, val t1: Long, val vmin: Double, val vmax: Double)

    companion object {
        private val PATHS = listOf(Path.POSITION, Path.SCALE, Path.ROTATION, Path.OPACITY, Path.ANCHOR)
        private val NAMES = listOf("Position", "Scale", "Rotation", "Opacity", "Anchor")
        private val PRESETS = listOf(
            Preset("Linear", Interp.LINEAR, listOf(0.0, 0.0, 1.0, 1.0)),
            Preset("Easy Ease", Interp.BEZIER, listOf(0.3333, 0.0, 0.6667, 1.0)),
            Preset("Ease In", Interp.BEZIER, listOf(0.3333, 0.0, 0.6667, 1.0), 2),
            Preset("Ease Out", Interp.BEZIER, listOf(0.3333, 0.0, 0.6667, 1.0), 1),
            Preset("Hold", Interp.HOLD, listOf(0.33, 0.0, 0.67, 1.0)),
            Preset("Numbers", Interp.BEZIER, listOf(0.3333, 0.0, 0.6667, 1.0), 3)
        )
        private val DEFAULT_EASE = listOf(0.33, 0.0, 0.67, 1.0)
        // Keyframe.time is in ticks (see TICKS_PER_SECOND), so speeds come out in units per second
        private val TIME_UNITS_PER_SEC = TICKS_PER_SECOND.toDouble()
        private const val MIN_INFLUENCE = 0.001
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
    private var speedMode = false
    private var fitSegment = false // Fit seg: the plot frames only the selected segment, Fit all: the whole property
    private var frozenMap: Mapping? = null // speed axis is locked while a speed handle is dragged

    private val density = resources.displayMetrics.density
    private val chipH = 24f * density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // touch state: 1 top chip, 2 bottom chip, 3 handle 1, 4 handle 2, 5 plot, 6 Value/Speed switch
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
        val si = if (fitSegment) segIndex(p.keys) else -1
        val first = if (si >= 0) p.keys[si].time else p.keys.first().time
        val last = if (si >= 0) p.keys[si + 1].time else p.keys.last().time
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
        for (k in (if (si >= 0) listOf(p.keys[si], p.keys[si + 1]) else p.keys)) {
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

    private fun modeRect(i: Int, r: RectF): RectF {
        val w = 46f * density
        val h = 18f * density
        val x0 = r.right - 4f * density - 3 * w + i * w
        val y0 = r.top + 4f * density
        return RectF(x0, y0, x0 + w - 2f * density, y0 + h)
    }

    // average speed of a segment in units per second: signed for a single number (it can go
    // down), a length for several numbers (x, y...)
    private fun segAvg(a: Keyframe, b: Keyframe): Double {
        val span = (b.time - a.time).toDouble()
        if (span <= 0.0) return 0.0
        if (a.value.size == 1) {
            return (b.value.getOrElse(0) { a.value[0] } - a.value[0]) / span * TIME_UNITS_PER_SEC
        }
        return vecLen(a, b) / span * TIME_UNITS_PER_SEC
    }

    // length of the change between two keyframes over all numbers (x, y...)
    private fun vecLen(a: Keyframe, b: Keyframe): Double {
        var sum = 0.0
        for (d in a.value.indices) {
            val dv = b.value.getOrElse(d) { a.value[d] } - a.value[d]
            sum += dv * dv
        }
        return sqrt(sum)
    }

    // speed (units per second) at time t inside segment seg, measured from the evaluated values,
    // so it always matches what the layer really does
    private fun speedAt(p: Prop, seg: Int, t: Long): Double {
        val a = p.keys[seg]
        val b = p.keys[seg + 1]
        val dt = max(1L, (b.time - a.time) / 200)
        val t0 = max(a.time, t - dt)
        val t1 = min(b.time, t + dt)
        if (t1 <= t0) return 0.0
        val v0 = Eval.valueAt(p, t0)
        val v1 = Eval.valueAt(p, t1)
        if (v0.size == 1) return (v1.getOrElse(0) { v0[0] } - v0[0]) / (t1 - t0) * TIME_UNITS_PER_SEC
        var sum = 0.0
        for (d in v0.indices) {
            val dv = v1.getOrElse(d) { v0[d] } - v0[d]
            sum += dv * dv
        }
        return sqrt(sum) / (t1 - t0) * TIME_UNITS_PER_SEC
    }

    // same time range as the value graph, the vertical axis is speed (below 0 for a property going down)
    private fun speedMapping(p: Prop, m: Mapping): Mapping {
        val fm = frozenMap
        if (fm != null) return Mapping(m.t0, m.t1, fm.vmin, fm.vmax)
        var lo = 0.0
        var hi = 1e-6
        val segs = if (fitSegment) {
            listOf(segIndex(p.keys)).filter { it >= 0 }
        } else {
            (0 until p.keys.size - 1).toList()
        }
        for (seg in segs) {
            val a = p.keys[seg]
            val b = p.keys[seg + 1]
            for (i in 0..40) {
                val sp = speedAt(p, seg, a.time + (b.time - a.time) * i / 40)
                lo = min(lo, sp)
                hi = max(hi, sp)
            }
        }
        return Mapping(m.t0, m.t1, lo * 1.15, hi * 1.15)
    }

    // speed-graph handles in screen pixels: x = influence, y = speed at the keyframe.
    // ease = [x1, y1, x2, y2]: out influence = x1, out speed = avg * y1 / x1,
    // in influence = 1 - x2, in speed = avg * (1 - y2) / (1 - x2), avg = average speed of the segment
    private fun speedHandles(p: Prop, seg: Int, sm: Mapping, r: RectF): FloatArray? {
        val a = p.keys[seg]
        val b = p.keys[seg + 1]
        if (a.interp != Interp.BEZIER) return null
        val span = (b.time - a.time).toDouble()
        val avg = segAvg(a, b)
        if (abs(avg) < 1e-9 || span <= 0.0) return null
        val e = easeOf(a)
        val infOut = max(e[0], MIN_INFLUENCE)
        val infIn = max(1.0 - e[2], MIN_INFLUENCE)
        val speedOut = avg * e[1] / infOut
        val speedIn = avg * (1.0 - e[3]) / infIn
        return floatArrayOf(
            xOf(sm, r, a.time + (infOut * span).toLong()), yOf(sm, r, speedOut),
            xOf(sm, r, b.time - (infIn * span).toLong()), yOf(sm, r, speedIn)
        )
    }

    private fun activeHandles(p: Prop, seg: Int, m: Mapping, r: RectF): FloatArray? =
        if (speedMode) speedHandles(p, seg, speedMapping(p, m), r) else handles(p, seg, m, r)

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
            if (presetSel != null && presetSel.interp == pr.interp && pr.side != 3) {
                val e = easeOf(presetSel)
                on = when (pr.side) {
                    1 -> abs(e[0] - pr.ease[0]) < 0.01 && abs(e[1] - pr.ease[1]) < 0.01
                    2 -> abs(e[2] - pr.ease[2]) < 0.01 && abs(e[3] - pr.ease[3]) < 0.01
                    else -> pr.interp != Interp.BEZIER || easeNear(e, pr.ease)
                }
            }
            drawChip(canvas, bottomChip(i), pr.label, on, seg >= 0)
        }

        // plot background
        fillPaint.color = Color.parseColor("#1B1B24")
        canvas.drawRect(r, fillPaint)

        // selected segment
        if (seg >= 0) {
            fillPaint.color = Color.parseColor("#22FFFFFF")
            canvas.drawRect(xOf(m, r, p.keys[seg].time), r.top, xOf(m, r, p.keys[seg + 1].time), r.bottom, fillPaint)
        }

        // frame and value labels
        val sm = if (speedMode) speedMapping(p, m) else null
        linePaint.strokeWidth = density
        linePaint.color = Color.parseColor("#33FFFFFF")
        canvas.drawRect(r, linePaint)
        textPaint.color = Color.GRAY
        textPaint.textSize = 9.5f * density
        val hi = if (sm != null) sm.vmax else m.vmax
        val lo = if (sm != null) sm.vmin else m.vmin
        canvas.drawText(String.format(Locale.US, "%.0f", hi), 2f * density, r.top + 8f * density, textPaint)
        canvas.drawText(String.format(Locale.US, "%.0f", lo), 2f * density, r.bottom, textPaint)
        textPaint.color = Color.parseColor("#8A8FA0")
        textPaint.textSize = 10f * density
        canvas.drawText(if (speedMode) "Speed graph (units/s)" else "Value graph", r.left + 6f * density, r.top + 12f * density, textPaint)


        canvas.save()
        canvas.clipRect(r)
        if (sm != null) {
            drawSpeedGraph(canvas, p, sm, r, seg)
        } else {
        // the curves
        val dims = p.keys[0].value.size
        val steps = 100
        val firstT = max(p.keys.first().time, m.t0)
        val lastT = min(p.keys.last().time, m.t1)
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
        }
        canvas.restore()

        // say why there are no handles
        if (seg >= 0 && activeHandles(p, seg, m, r) == null) {
            val a = p.keys[seg]
            val b = p.keys[seg + 1]
            val d = referenceDim(a, b)
            val flat = if (speedMode) {
                abs(segAvg(a, b)) < 1e-9
            } else {
                abs(b.value.getOrElse(d) { 0.0 } - a.value.getOrElse(d) { 0.0 }) < 1e-9
            }
            val msg = if (flat) {
                "Same value at both keyframes: change one to see a curve"
            } else {
                "Tap Ease, In, Out or In-Out to get handles"
            }
            textPaint.color = Color.parseColor("#FFD54A")
            textPaint.textSize = 10.5f * density
            canvas.drawText(msg, r.left + 8f * density, r.bottom - 8f * density, textPaint)
        }

        // Value | Speed switch
        drawChip(canvas, modeRect(0, r), if (fitSegment) "Fit seg" else "Fit all", fitSegment, true)
        drawChip(canvas, modeRect(1, r), "Value", !speedMode, true)
        drawChip(canvas, modeRect(2, r), "Speed", speedMode, true)

        // playhead
        val lt = layerTime
        if (lt >= m.t0 && lt <= m.t1) {
            linePaint.color = Color.WHITE
            linePaint.strokeWidth = density
            val x = xOf(m, r, lt)
            canvas.drawLine(x, r.top, x, r.bottom, linePaint)
        }
    }

    private fun drawSpeedGraph(canvas: Canvas, p: Prop, sm: Mapping, r: RectF, seg: Int) {
        // the speed curve, one segment after the other
        linePaint.color = Color.parseColor("#B28CFF")
        linePaint.strokeWidth = 1.8f * density
        for (s in 0 until p.keys.size - 1) {
            val a = p.keys[s]
            val b = p.keys[s + 1]
            var px = 0f
            var py = 0f
            for (i in 0..60) {
                val t = a.time + (b.time - a.time) * i / 60
                val x = xOf(sm, r, t)
                val y = yOf(sm, r, speedAt(p, s, t))
                if (i > 0) canvas.drawLine(px, py, x, y, linePaint)
                px = x
                py = y
            }
        }

        // zero line, keyframes sit on it
        val y0 = yOf(sm, r, 0.0)
        linePaint.color = Color.parseColor("#44FFFFFF")
        linePaint.strokeWidth = density
        canvas.drawLine(r.left, y0, r.right, y0, linePaint)
        fillPaint.color = Color.WHITE
        for (k in p.keys) canvas.drawCircle(xOf(sm, r, k.time), y0, 3.5f * density, fillPaint)

        // influence / speed handles of the selected segment
        if (seg < 0) return
        val h = speedHandles(p, seg, sm, r) ?: return
        val a = p.keys[seg]
        val b = p.keys[seg + 1]
        val e = easeOf(a)
        val avg = segAvg(a, b)
        val infOut = max(e[0], MIN_INFLUENCE)
        val infIn = max(1.0 - e[2], MIN_INFLUENCE)
        val xa = xOf(sm, r, a.time)
        val xb = xOf(sm, r, b.time)
        linePaint.color = Color.parseColor("#FFD54A")
        linePaint.strokeWidth = 1.4f * density
        canvas.drawLine(xa, h[1], h[0], h[1], linePaint)
        canvas.drawLine(xb, h[3], h[2], h[3], linePaint)
        fillPaint.color = Color.parseColor("#FFD54A")
        canvas.drawCircle(h[0], h[1], 6f * density, fillPaint)
        canvas.drawCircle(h[2], h[3], 6f * density, fillPaint)
        canvas.drawCircle(xa, h[1], 3f * density, fillPaint)
        canvas.drawCircle(xb, h[3], 3f * density, fillPaint)

        // numbers, like the Keyframe Velocity dialog
        textPaint.color = Color.parseColor("#FFD54A")
        textPaint.textSize = 9.5f * density
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(
            String.format(Locale.US, "%.0f%%  %.0f/s", infOut * 100, avg * e[1] / infOut),
            min(h[0] + 8f * density, r.right - 80f * density), max(h[1] - 8f * density, r.top + 24f * density), textPaint
        )
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            String.format(Locale.US, "%.0f%%  %.0f/s", infIn * 100, avg * (1.0 - e[3]) / infIn),
            max(h[2] - 8f * density, r.left + 80f * density), max(h[3] - 8f * density, r.top + 24f * density), textPaint
        )
        textPaint.textAlign = Paint.Align.LEFT
    }

    // Keyframe Velocity: speed and influence, typed in. Out = leaving the start keyframe,
    // In = arriving at the end keyframe (same numbers the speed graph handles show).
    private fun showVelocityDialog(p: Prop, seg: Int) {
        val a = p.keys[seg]
        val b = p.keys[seg + 1]
        val avg = segAvg(a, b)
        if (abs(avg) < 1e-9) {
            AlertDialog.Builder(context)
                .setTitle("Keyframe Velocity")
                .setMessage("Both keyframes have the same value, so there is no speed to set.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val e = easeOf(a)
        val infOut = max(e[0], MIN_INFLUENCE)
        val infIn = max(1.0 - e[2], MIN_INFLUENCE)
        val signed = a.value.size == 1
        fun field(v: Double): EditText = EditText(context).apply {
            setText(String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.'))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val speedOut = field(avg * e[1] / infOut)
        val inflOut = field(infOut * 100)
        val speedIn = field(avg * (1.0 - e[3]) / infIn)
        val inflIn = field(infIn * 100)
        fun row(label: String, f: EditText): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(f, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val pad = (20 * density).toInt()
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply { text = "Outgoing (start keyframe)" })
            addView(row("Speed (units/s)", speedOut))
            addView(row("Influence (%)", inflOut))
            addView(TextView(context).apply { text = "Incoming (end keyframe)"; setPadding(0, pad / 2, 0, 0) })
            addView(row("Speed (units/s)", speedIn))
            addView(row("Influence (%)", inflIn))
        }
        AlertDialog.Builder(context)
            .setTitle("Keyframe Velocity")
            .setView(form)
            .setPositiveButton("Apply") { _, _ ->
                val so = speedOut.text.toString().toDoubleOrNull()
                val io = inflOut.text.toString().toDoubleOrNull()
                val si = speedIn.text.toString().toDoubleOrNull()
                val ii = inflIn.text.toString().toDoubleOrNull()
                if (so != null && io != null && si != null && ii != null) {
                    val fo = (io / 100.0).coerceIn(MIN_INFLUENCE, 1.0)
                    val fi = (ii / 100.0).coerceIn(MIN_INFLUENCE, 1.0)
                    val lo = if (signed) -1.0 else 0.0
                    val x1 = fo
                    val y1 = (fo * so / avg).coerceIn(lo, 2.0)
                    val x2 = 1.0 - fi
                    val y2 = (1.0 - fi * si / avg).coerceIn(-1.0, if (signed) 2.0 else 1.0)
                    onPreset?.invoke(selPath, seg, Interp.BEZIER, listOf(x1, y1, x2, y2))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        for (i in 0..2) {
            if (modeRect(i, r).contains(x, y)) {
                target = 6
                targetIndex = i
                return
            }
        }
        if (seg >= 0) {
            val h = activeHandles(p, seg, m, r)
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
                            if (speedMode) {
                                val m = mapping(p)
                                if (m != null) frozenMap = speedMapping(p, m)
                            }
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
                frozenMap = null
                target = 0
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onEaseEnd?.invoke()
                dragging = false
                frozenMap = null
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
                if (pr.side == 3) {
                    showVelocityDialog(p, seg)
                    return
                }
                // a segment that is not Bezier yet starts from the Bezier that looks the same as Linear
                val cur = if (p.keys[seg].interp == Interp.BEZIER) easeOf(p.keys[seg]) else listOf(0.3333, 0.3333, 0.6667, 0.6667)
                val next = when (pr.side) {
                    1 -> listOf(pr.ease[0], pr.ease[1], cur[2], cur[3])
                    2 -> listOf(cur[0], cur[1], pr.ease[2], pr.ease[3])
                    else -> pr.ease
                }
                onPreset?.invoke(selPath, seg, pr.interp, next)
            }
            6 -> {
                when (targetIndex) {
                    0 -> fitSegment = !fitSegment
                    1 -> speedMode = false
                    else -> speedMode = true
                }
                invalidate()
            }
            5 -> {
                val p = currentProp() ?: return
                val m = mapping(p) ?: return
                if (fitSegment) {
                    val seg0 = segIndex(p.keys)
                    val frac = (x - plotRect().left) / plotRect().width()
                    if (seg0 >= 0 && frac < 0.15f) userSeg = max(0, seg0 - 1)
                    if (seg0 >= 0 && frac > 0.85f) userSeg = min(p.keys.size - 2, seg0 + 1)
                    invalidate()
                    return
                }
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
        if (speedMode) {
            dragSpeedHandle(p, m, seg, x, y)
            return
        }
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

    // dragging on the speed graph: x sets the influence, y sets the speed (never below 0),
    // both converted back to the cubic-bezier ease [x1, y1, x2, y2] the segment is stored with
    private fun dragSpeedHandle(p: Prop, m: Mapping, seg: Int, x: Float, y: Float) {
        val a = p.keys[seg]
        val b = p.keys[seg + 1]
        val span = (b.time - a.time).toDouble()
        val avg = segAvg(a, b)
        if (abs(avg) < 1e-9 || span <= 0.0) return
        val r = plotRect()
        val sm = speedMapping(p, m)
        val signed = a.value.size == 1
        val raw = valueAtY(sm, r, y)
        val speed = if (signed) raw else raw.coerceAtLeast(0.0)
        val t = timeAt(sm, r, x)
        val e = easeOf(a)
        val next = if (target == 3) {
            val inf = ((t - a.time) / span).coerceIn(MIN_INFLUENCE, 1.0)
            listOf(inf, (inf * speed / avg).coerceIn(if (signed) -1.0 else 0.0, 2.0), e[2], e[3])
        } else {
            val inf = ((b.time - t) / span).coerceIn(MIN_INFLUENCE, 1.0)
            listOf(e[0], e[1], 1.0 - inf, (1.0 - inf * speed / avg).coerceIn(-1.0, if (signed) 2.0 else 1.0))
        }
        onEase?.invoke(selPath, seg, Interp.BEZIER, next)
    }
}

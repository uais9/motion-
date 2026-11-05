package com.motion

import kotlin.math.abs

// All the keyframe logic in one place.
//  - the data (Keyframe, Prop, Interp) lives in Model.kt
//  - Eval.valueAt (Eval.kt) answers "what is the value at time t?" and calls cubicBezier() below
//  - saving and loading keyframes is in ProjectJson.kt
// Everything here is a pure function: old property in, new property out.

// ---------- easing ----------

// Cubic-bezier easing, same idea as CSS cubic-bezier(x1, y1, x2, y2).
// e = [x1, y1, x2, y2]: the two handles of the curve between two keyframes.
fun cubicBezier(x: Double, e: List<Double>): Double {
    if (e.size < 4) return x
    val x1 = e[0]
    val y1 = e[1]
    val x2 = e[2]
    val y2 = e[3]
    var lo = 0.0
    var hi = 1.0
    var s = x
    for (n in 0 until 40) {
        val bx = cubicAt(s, x1, x2)
        if (abs(bx - x) < 1e-7) break
        if (bx < x) lo = s else hi = s
        s = (lo + hi) / 2.0
    }
    return cubicAt(s, y1, y2)
}

private fun cubicAt(s: Double, p1: Double, p2: Double): Double {
    val m = 1.0 - s
    return 3.0 * m * m * s * p1 + 3.0 * m * s * s * p2 + s * s * s
}

// ---------- keyframes ----------

// Changes one property of one layer.
fun Project.withLayerProp(
    compId: Long,
    layerId: Long,
    path: String,
    change: (Prop) -> Prop
): Project = mapComp(compId) { c ->
    c.copy(layers = c.layers.map { l ->
        if (l.id != layerId) {
            l
        } else {
            val old = l.props[path]
            if (old == null) l else l.copy(props = l.props + (path to change(old)))
        }
    })
}

// Sets a property's value at layer time lt.
// Fixed property: just changes the value. Animated property: adds or replaces a keyframe at lt.
fun setPropAt(prop: Prop, lt: Long, value: List<Double>): Prop {
    if (!prop.animated) return prop.copy(value = value)
    val old = prop.keys.firstOrNull { it.time == lt }
    val key = if (old != null) old.copy(value = value) else Keyframe(lt, value)
    val keys = (prop.keys.filter { it.time != lt } + key).sortedBy { it.time }
    return prop.copy(keys = keys)
}

// Stopwatch: turns animation on (one keyframe at lt) or off (keeps the current value).
fun toggleAnimated(prop: Prop, lt: Long, current: List<Double>): Prop =
    if (prop.animated) Prop(current) else Prop(prop.value, listOf(Keyframe(lt, current)))

// Deletes the keyframe at lt. Deleting the last one makes the property fixed again.
fun deleteKeyAt(prop: Prop, lt: Long): Prop {
    val gone = prop.keys.firstOrNull { it.time == lt } ?: return prop
    val keys = prop.keys.filter { it.time != lt }
    return if (keys.isEmpty()) Prop(gone.value) else prop.copy(keys = keys)
}

// ---------- graph editor ----------

// Sets how the segment that starts at keyframe `index` moves to the next keyframe.
fun withKeyEase(prop: Prop, index: Int, interp: Interp, ease: List<Double>): Prop {
    if (index < 0 || index >= prop.keys.size) return prop
    val keys = prop.keys.toMutableList()
    keys[index] = keys[index].copy(interp = interp, ease = ease)
    return prop.copy(keys = keys)
}

// ---------- tests ----------

object KeyframeTest {
    fun run(): List<String> {
        val out = ArrayList<String>()
        fun check(name: String, ok: Boolean) {
            out.add((if (ok) "PASS " else "FAIL ") + name)
        }
        fun near(a: Double, b: Double): Boolean = Math.abs(a - b) < 1e-6

        val sec = TICKS_PER_SECOND
        val fixed = Prop(listOf(10.0))
        check(
            "setting a fixed property just changes its value",
            setPropAt(fixed, sec, listOf(5.0)) == Prop(listOf(5.0))
        )

        val a = toggleAnimated(fixed, 0L, listOf(10.0))
        check(
            "stopwatch on makes one keyframe at the playhead",
            a.animated && a.keys.size == 1 && a.keys[0].time == 0L && a.keys[0].value == listOf(10.0)
        )

        val b = setPropAt(a, sec, listOf(110.0))
        check("changing an animated value later adds a keyframe", b.keys.size == 2 && b.keys[1].time == sec)
        check("value halfway between two keyframes", near(Eval.valueAt(b, sec / 2)[0], 60.0))

        val c = setPropAt(b, sec, listOf(210.0))
        check("changing at an existing keyframe replaces it", c.keys.size == 2 && c.keys[1].value == listOf(210.0))

        val d = setPropAt(c, sec / 2, listOf(0.0))
        check("keyframes stay sorted by time", d.keys.map { it.time } == listOf(0L, sec / 2, sec))

        val e1 = deleteKeyAt(c, sec)
        check("deleting a keyframe removes only that one", e1.keys.size == 1 && e1.animated)
        val e2 = deleteKeyAt(e1, 0L)
        check("deleting the last keyframe makes the value fixed", !e2.animated && e2.value == listOf(10.0))

        val f = toggleAnimated(b, 0L, Eval.valueAt(b, sec / 2))
        check("stopwatch off keeps the current value", !f.animated && near(f.value[0], 60.0))

        val r = Project().withNewComp("T", 1080, 1920, FrameRate.FPS_30, secondsToTicks(10.0), 0xFF000000.toInt())
        var p = r.first
        val compId = r.second
        p = p.withFootage(
            listOf(Footage(0L, "v.mp4", "content://v", FootageKind.VIDEO, 576, 1024, secondsToTicks(20.0)))
        )
        p = p.withLayer(compId, p.footage[0].id)
        val layer = p.comps[0].layers[0]
        val after = p.withLayerProp(compId, layer.id, Path.OPACITY) { setPropAt(it, 0L, listOf(30.0)) }
        val l2 = after.comps[0].layers[0]
        check(
            "changing one property leaves the others alone",
            l2.props[Path.OPACITY]?.value == listOf(30.0) &&
                l2.props[Path.POSITION] == layer.props[Path.POSITION]
        )
        return out
    }
}

object GraphTest {
    fun run(): List<String> {
        val out = ArrayList<String>()
        fun check(name: String, ok: Boolean) {
            out.add((if (ok) "PASS " else "FAIL ") + name)
        }

        val sec = TICKS_PER_SECOND
        val base = Prop(
            listOf(0.0),
            listOf(Keyframe(0L, listOf(0.0)), Keyframe(sec, listOf(100.0)))
        )
        val linearAt = Eval.valueAt(base, sec / 4)[0]

        val easeIn = withKeyEase(base, 0, Interp.BEZIER, listOf(0.42, 0.0, 1.0, 1.0))
        check("ease in starts slower than linear", Eval.valueAt(easeIn, sec / 4)[0] < linearAt)

        val easeOut = withKeyEase(base, 0, Interp.BEZIER, listOf(0.0, 0.0, 0.58, 1.0))
        check("ease out starts faster than linear", Eval.valueAt(easeOut, sec / 4)[0] > linearAt)

        val held = withKeyEase(base, 0, Interp.HOLD, listOf(0.0, 0.0, 1.0, 1.0))
        check("hold keeps the first value", Eval.valueAt(held, sec / 2)[0] == 0.0)

        check(
            "a segment that does not exist changes nothing",
            withKeyEase(base, 5, Interp.HOLD, listOf(0.0, 0.0, 1.0, 1.0)) == base
        )

        val overshoot = withKeyEase(base, 0, Interp.BEZIER, listOf(0.3, 1.5, 0.7, 1.0))
        check("a handle pulled up overshoots", Eval.valueAt(overshoot, sec / 2)[0] > 100.0)

        check("only the chosen keyframe changes", easeIn.keys[1] == base.keys[1])

        val easy = withKeyEase(base, 0, Interp.BEZIER, listOf(0.3333, 0.0, 0.6667, 1.0))
        check("easy ease starts slower than linear", Eval.valueAt(easy, sec / 10)[0] < 10.0)
        check("easy ease is symmetric (midpoint is 50)", Math.abs(Eval.valueAt(easy, sec / 2)[0] - 50.0) < 1e-3)

        return out
    }
}

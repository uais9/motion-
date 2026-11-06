package com.motion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// All the keyframe logic in one place.
//  - the data (Keyframe, Prop, Interp) lives in Model.kt
//  - Eval.valueAt (Eval.kt) answers "what is the value at time t?" and calls cubicBezier() below
//  - saving and loading keyframes is in ProjectJson.kt
// Everything here is a pure function: old property in, new property out.
//
// Keyframe types, like After Effects (Keyframe.interp + Keyframe.smooth):
//  Linear, Hold, Bezier (independent handles), Auto Bezier (handles worked out for you, so speed is
//  smooth through the keyframe; dragging a handle turns it into Continuous), Continuous Bezier
//  (speed in = speed out, handles are yours).
// A segment's handles are the 4 numbers in the ease of the keyframe it starts at:
//  out speed of key a = avg * y1 / x1, in speed of key b = avg * (1 - y2) / (1 - x2)
// where x1 and (1 - x2) are the influences and avg is the average speed of the segment.

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

private const val MIN_INFLUENCE = 0.001
private const val AUTO_INFLUENCE = 0.3333
private val DEFAULT_EASE = listOf(0.33, 0.0, 0.67, 1.0)
// a Bezier that looks exactly like Linear
private val LINEAR_EASE = listOf(0.3333, 0.3333, 0.6667, 0.6667)

private fun easeOf(k: Keyframe): List<Double> = if (k.ease.size == 4) k.ease else DEFAULT_EASE

// change of value over a segment: signed for a single number, a length for several (x, y...)
private fun segDelta(a: Keyframe, b: Keyframe): Double {
    if (a.value.size == 1) return b.value.getOrElse(0) { a.value[0] } - a.value[0]
    var sum = 0.0
    for (d in a.value.indices) {
        val dv = b.value.getOrElse(d) { a.value[d] } - a.value[d]
        sum += dv * dv
    }
    return sqrt(sum)
}

// average speed of a segment, value units per tick
private fun segAvgChord(a: Keyframe, b: Keyframe): Double {
    val span = (b.time - a.time).toDouble()
    return if (span <= 0.0) 0.0 else segDelta(a, b) / span
}

// Speed leaving keyframe i (value units per tick), or null for the last keyframe.
fun keySpeedOut(prop: Prop, i: Int): Double? {
    if (i < 0 || i + 1 >= prop.keys.size) return null
    val a = prop.keys[i]
    val avg = segmentAvg(prop, i)
    return when (a.interp) {
        Interp.HOLD -> 0.0
        Interp.LINEAR -> avg
        Interp.BEZIER -> {
            val e = easeOf(a)
            avg * e[1] / max(e[0], MIN_INFLUENCE)
        }
    }
}

// Speed arriving at keyframe i (value units per tick), or null for the first keyframe.
fun keySpeedIn(prop: Prop, i: Int): Double? {
    if (i <= 0 || i >= prop.keys.size) return null
    val a = prop.keys[i - 1]
    val avg = segmentAvg(prop, i - 1)
    return when (a.interp) {
        Interp.HOLD -> 0.0
        Interp.LINEAR -> avg
        Interp.BEZIER -> {
            val e = easeOf(a)
            avg * (1.0 - e[3]) / max(1.0 - e[2], MIN_INFLUENCE)
        }
    }
}

// Sets the speed leaving keyframe i, keeping its influence. Only for a Bezier segment.
private fun setOutSpeed(prop: Prop, i: Int, speed: Double): Prop {
    if (i < 0 || i + 1 >= prop.keys.size) return prop
    val a = prop.keys[i]
    if (a.interp != Interp.BEZIER) return prop
    val avg = segmentAvg(prop, i)
    if (abs(avg) < 1e-15) return prop
    val e = easeOf(a)
    val lo = if (a.value.size == 1) -1.0 else 0.0
    val y1 = (max(e[0], MIN_INFLUENCE) * speed / avg).coerceIn(lo, 2.0)
    val keys = prop.keys.toMutableList()
    keys[i] = a.copy(ease = listOf(e[0], y1, e[2], e[3]))
    return prop.copy(keys = keys)
}

// Sets the speed arriving at keyframe k, keeping its influence. Only for a Bezier segment.
private fun setInSpeed(prop: Prop, k: Int, speed: Double): Prop {
    if (k <= 0 || k >= prop.keys.size) return prop
    val a = prop.keys[k - 1]
    if (a.interp != Interp.BEZIER) return prop
    val avg = segmentAvg(prop, k - 1)
    if (abs(avg) < 1e-15) return prop
    val e = easeOf(a)
    val hi = if (a.value.size == 1) 2.0 else 1.0
    val y2 = (1.0 - max(1.0 - e[2], MIN_INFLUENCE) * speed / avg).coerceIn(-1.0, hi)
    val keys = prop.keys.toMutableList()
    keys[k - 1] = a.copy(ease = listOf(e[0], e[1], e[2], y2))
    return prop.copy(keys = keys)
}

// Continuous: speed coming in becomes the speed going out.
private fun followOut(prop: Prop, k: Int): Prop {
    val out = keySpeedOut(prop, k) ?: return prop
    return setInSpeed(prop, k, out)
}

// Continuous: speed going out becomes the speed coming in.
private fun followIn(prop: Prop, k: Int): Prop {
    val into = keySpeedIn(prop, k) ?: return prop
    return setOutSpeed(prop, k, into)
}

// Speed an Auto Bezier keyframe gets: the slope between its neighbours, so the motion flows through it.
// At a peak or a dip (the value turns around) the speed is 0, so there is no overshoot.
// The first and last keyframe just take the speed of their only segment.
private fun autoSpeed(prop: Prop, i: Int): Double {
    val keys = prop.keys
    val n = keys.size
    if (n < 2) return 0.0
    if (i == 0) return segmentAvg(prop, 0)
    if (i == n - 1) return segmentAvg(prop, n - 2)
    val p = keys[i - 1]
    val k = keys[i]
    val nx = keys[i + 1]
    val dt = (nx.time - p.time).toDouble()
    if (dt <= 0.0) return 0.0
    if (k.value.size == 1) {
        val vn = nx.value.getOrElse(0) { k.value[0] }
        val dIn = k.value[0] - p.value.getOrElse(0) { k.value[0] }
        val dOut = vn - k.value[0]
        return if (dIn * dOut <= 0.0) 0.0 else (vn - p.value.getOrElse(0) { k.value[0] }) / dt
    }
    var dot = 0.0
    var chord = 0.0
    for (d in k.value.indices) {
        val vp = p.value.getOrElse(d) { k.value[d] }
        val vn = nx.value.getOrElse(d) { k.value[d] }
        dot += (k.value[d] - vp) * (vn - k.value[d])
        chord += (vn - vp) * (vn - vp)
    }
    return if (dot <= 0.0) 0.0 else sqrt(chord) / dt
}

// Works out the handles of every Auto Bezier keyframe (33.33% influence, speed from autoSpeed).
// A segment that is Linear or Hold keeps its type.
private fun relaxAuto(prop: Prop): Prop {
    val keys = prop.keys
    if (keys.none { it.smooth == Smooth.AUTO }) return prop
    val eases = keys.map { easeOf(it).toMutableList() }
    val n = keys.size
    for (i in 0 until n) {
        if (keys[i].smooth != Smooth.AUTO) continue
        val s = autoSpeed(prop, i)
        if (i + 1 < n && keys[i].interp == Interp.BEZIER) {
            val avg = segmentAvg(prop, i)
            val lo = if (keys[i].value.size == 1) -1.0 else 0.0
            eases[i][0] = AUTO_INFLUENCE
            eases[i][1] = if (abs(avg) < 1e-15) 0.0 else (AUTO_INFLUENCE * s / avg).coerceIn(lo, 2.0)
        }
        if (i > 0 && keys[i - 1].interp == Interp.BEZIER) {
            val avg = segmentAvg(prop, i - 1)
            val hi = if (keys[i - 1].value.size == 1) 2.0 else 1.0
            eases[i - 1][2] = 1.0 - AUTO_INFLUENCE
            eases[i - 1][3] = if (abs(avg) < 1e-15) 1.0 else (1.0 - AUTO_INFLUENCE * s / avg).coerceIn(-1.0, hi)
        }
    }
    return prop.copy(keys = keys.mapIndexed { i, k -> k.copy(ease = eases[i]) })
}

// After the keyframes change: Auto Bezier handles are worked out again and Continuous keyframes
// keep the same speed in and out.
private fun smoothen(prop: Prop): Prop {
    var p = relaxAuto(prop)
    for (i in 1 until p.keys.size - 1) {
        if (p.keys[i].smooth == Smooth.CONTINUOUS) p = followOut(p, i)
    }
    return p
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
    return smoothen(prop.copy(keys = keys))
}

// Stopwatch: turns animation on (one keyframe at lt) or off (keeps the current value).
fun toggleAnimated(prop: Prop, lt: Long, current: List<Double>): Prop =
    if (prop.animated) prop.copy(value = current, keys = emptyList()) else prop.copy(keys = listOf(Keyframe(lt, current)))

// Deletes the keyframe at lt. Deleting the last one makes the property fixed again.
fun deleteKeyAt(prop: Prop, lt: Long): Prop {
    val gone = prop.keys.firstOrNull { it.time == lt } ?: return prop
    val keys = prop.keys.filter { it.time != lt }
    return if (keys.isEmpty()) prop.copy(value = gone.value, keys = emptyList()) else smoothen(prop.copy(keys = keys))
}

// Moves keyframe `index` to a new time and value. It stays between its neighbours (minGap ticks away,
// use one frame) so the order never changes.
fun moveKey(prop: Prop, index: Int, newTime: Long, newValue: List<Double>, minGap: Long): Prop {
    if (index < 0 || index >= prop.keys.size) return prop
    val keys = prop.keys.toMutableList()
    val lo = if (index > 0) keys[index - 1].time + minGap else Long.MIN_VALUE
    val hi = if (index + 1 < keys.size) keys[index + 1].time - minGap else Long.MAX_VALUE
    val t = if (lo > hi) keys[index].time else newTime.coerceIn(lo, hi)
    keys[index] = keys[index].copy(time = t, value = newValue)
    return smoothen(prop.copy(keys = keys))
}

// Bounce: replaces the segment that starts at keyframe `index` with a ball-drop. The value falls to the
// end value, then bounces back towards the start value and settles, `bounces` times. Each bounce is
// `bounciness` (0.1 to 0.9) of the height of the one before, and takes less time (free fall: time ~ sqrt(height)).
// It adds real keyframes (on whole frames, tpf ticks), so you can still edit them in the graph.
fun withBounce(prop: Prop, index: Int, bounces: Int, bounciness: Double, tpf: Long): Prop {
    if (index < 0 || index + 1 >= prop.keys.size || tpf <= 0L) return prop
    val n = bounces.coerceIn(1, 8)
    val q = bounciness.coerceIn(0.1, 0.9)
    val a = prop.keys[index]
    val b = prop.keys[index + 1]
    val span = (b.time - a.time).toDouble()
    if (span <= 0.0 || abs(segDelta(a, b)) < 1e-12) return prop

    var total = 1.0
    for (i in 1..n) total += 2.0 * Math.pow(q, i / 2.0)
    val unit = span / total

    // speeding up (value ~ t squared) and slowing down, as exact cubic beziers
    val fall = listOf(1.0 / 3.0, 0.0, 2.0 / 3.0, 1.0 / 3.0)
    val rise = listOf(1.0 / 3.0, 2.0 / 3.0, 2.0 / 3.0, 1.0)

    class Hit(val rel: Double, val value: List<Double>, val ease: List<Double>)
    val hits = ArrayList<Hit>()
    var cursor = unit
    hits.add(Hit(cursor, b.value, rise)) // first time it reaches the end value
    for (i in 1..n) {
        val half = unit * Math.pow(q, i / 2.0)
        val h = Math.pow(q, i.toDouble())
        val peak = b.value.indices.map { d ->
            val from = a.value.getOrElse(d) { b.value[d] }
            b.value[d] + (from - b.value[d]) * h
        }
        hits.add(Hit(cursor + half, peak, fall))
        cursor += 2.0 * half
        if (i < n) hits.add(Hit(cursor, b.value, rise)) // the last one is the end keyframe itself
    }

    val made = ArrayList<Keyframe>()
    var lastTime = a.time
    for (h in hits) {
        val t = a.time + Math.round(h.rel / tpf) * tpf
        if (t <= lastTime || t >= b.time) continue
        made.add(Keyframe(t, h.value, Interp.BEZIER, h.ease))
        lastTime = t
    }
    val first = a.copy(interp = Interp.BEZIER, ease = fall, smooth = Smooth.NONE)
    val last = if (b.smooth == Smooth.NONE) b else b.copy(smooth = Smooth.NONE)
    return prop.copy(keys = prop.keys.take(index) + first + made + last + prop.keys.drop(index + 2))
}

// ---------- motion path (spatial interpolation) ----------
// Position and Anchor (Prop.spatial) move along a curve between keyframes, like After Effects.
// The curve is a cubic bezier: start = this keyframe, handle = outTan, handle = next keyframe's inTan,
// end = next keyframe. The ease (temporal interpolation) sets the speed ALONG the curve, so the
// speed graph shows the real speed of the path.

private fun zeros(n: Int): List<Double> = List(n) { 0.0 }

private fun norm(v: List<Double>): Double {
    var s = 0.0
    for (x in v) s += x * x
    return sqrt(s)
}

// Auto Bezier: the path flows through the keyframe, handles 1/3 of the way to each neighbour.
private fun autoTangents(keys: List<Keyframe>, i: Int): Pair<List<Double>, List<Double>> {
    val c = keys[i].value
    val n = c.size
    if (i == 0 || i == keys.size - 1) return Pair(zeros(n), zeros(n))
    val p = keys[i - 1].value
    val nx = keys[i + 1].value
    val dir = List(n) { nx.getOrElse(it) { c[it] } - p.getOrElse(it) { c[it] } }
    val len = norm(dir)
    if (len < 1e-9) return Pair(zeros(n), zeros(n))
    val dIn = norm(List(n) { c[it] - p.getOrElse(it) { c[it] } })
    val dOut = norm(List(n) { nx.getOrElse(it) { c[it] } - c[it] })
    return Pair(List(n) { -dir[it] / len * dIn / 3.0 }, List(n) { dir[it] / len * dOut / 3.0 })
}

// Handles for a keyframe that has none yet: auto handles, or at the ends a handle 1/3 of the way
// along the straight line (the path stays straight until you move it).
private fun startTangents(keys: List<Keyframe>, i: Int): Pair<List<Double>, List<Double>> {
    val c = keys[i].value
    val n = c.size
    if (i > 0 && i < keys.size - 1) {
        val auto = autoTangents(keys, i)
        if (norm(auto.first) + norm(auto.second) > 1e-9) return auto
    }
    val inT = if (i > 0) List(n) { (keys[i - 1].value.getOrElse(it) { c[it] } - c[it]) / 3.0 } else zeros(n)
    val outT = if (i + 1 < keys.size) List(n) { (keys[i + 1].value.getOrElse(it) { c[it] } - c[it]) / 3.0 } else zeros(n)
    return Pair(inT, outT)
}

// The handles a keyframe really has: (in, out) offsets from its value.
fun spatialTangents(keys: List<Keyframe>, i: Int): Pair<List<Double>, List<Double>> {
    val k = keys[i]
    val n = k.value.size
    return when (k.pathType) {
        PathType.LINEAR -> Pair(zeros(n), zeros(n))
        PathType.AUTO -> autoTangents(keys, i)
        PathType.CONTINUOUS, PathType.BEZIER -> Pair(
            if (k.inTan.size == n) k.inTan else zeros(n),
            if (k.outTan.size == n) k.outTan else zeros(n)
        )
    }
}

private const val CURVE_STEPS = 48

private class SegCurve(
    val p0: List<Double>,
    val p1: List<Double>,
    val p2: List<Double>,
    val p3: List<Double>,
    val cum: DoubleArray // distance travelled along the curve at each of the CURVE_STEPS + 1 samples
)

private fun curvePoint(c: SegCurve, u: Double): List<Double> {
    val m = 1.0 - u
    val w0 = m * m * m
    val w1 = 3.0 * m * m * u
    val w2 = 3.0 * m * u * u
    val w3 = u * u * u
    return List(c.p0.size) { w0 * c.p0[it] + w1 * c.p1[it] + w2 * c.p2[it] + w3 * c.p3[it] }
}

// The curve of the segment that starts at keyframe i, or null when the segment is a straight line.
private fun segCurve(keys: List<Keyframe>, i: Int): SegCurve? {
    if (i < 0 || i + 1 >= keys.size) return null
    val a = keys[i]
    val b = keys[i + 1]
    val n = a.value.size
    if (n < 2) return null
    val outA = spatialTangents(keys, i).second
    val inB = spatialTangents(keys, i + 1).first
    if (norm(outA) < 1e-9 && norm(inB) < 1e-9) return null
    val p3 = List(n) { b.value.getOrElse(it) { a.value[it] } }
    val p1 = List(n) { a.value[it] + outA[it] }
    val p2 = List(n) { p3[it] + inB.getOrElse(it) { 0.0 } }
    val c = SegCurve(a.value, p1, p2, p3, DoubleArray(CURVE_STEPS + 1))
    var prev = c.p0
    for (j in 1..CURVE_STEPS) {
        val pt = curvePoint(c, j.toDouble() / CURVE_STEPS)
        c.cum[j] = c.cum[j - 1] + norm(List(n) { pt[it] - prev[it] })
        prev = pt
    }
    return c
}

// The point a fraction f (0 to 1, by distance travelled) along the path of segment i.
// null = a straight line, the caller interpolates. f past 0 or 1 (an overshoot) carries on along the end handle.
fun spatialPoint(keys: List<Keyframe>, i: Int, f: Double): List<Double>? {
    val c = segCurve(keys, i) ?: return null
    val total = c.cum[CURVE_STEPS]
    if (total < 1e-9) return null
    if (f <= 0.0 || f >= 1.0) {
        val atStart = f <= 0.0
        val base = if (atStart) c.p0 else c.p3
        val from = if (atStart) c.p1 else c.p2
        var dir = List(base.size) { base[it] - from[it] }
        if (norm(dir) < 1e-9) dir = List(base.size) { base[it] - (if (atStart) c.p3[it] else c.p0[it]) }
        val len = norm(dir)
        val dist = (if (atStart) -f else f - 1.0) * total
        if (len < 1e-9 || dist <= 0.0) return base
        return List(base.size) { base[it] + dir[it] / len * dist }
    }
    val target = f * total
    var j = 1
    while (j < CURVE_STEPS && c.cum[j] < target) j++
    val span = c.cum[j] - c.cum[j - 1]
    val frac = if (span < 1e-12) 0.0 else (target - c.cum[j - 1]) / span
    return curvePoint(c, (j - 1 + frac) / CURVE_STEPS)
}

// Distance along the path of segment i (a signed change for one number, a length for several).
fun segmentLength(prop: Prop, i: Int): Double {
    if (i < 0 || i + 1 >= prop.keys.size) return 0.0
    if (prop.spatial) {
        val c = segCurve(prop.keys, i)
        if (c != null) return c.cum[CURVE_STEPS]
    }
    return segDelta(prop.keys[i], prop.keys[i + 1])
}

// Average speed of segment i, value units per tick (along the path for a curved motion path).
fun segmentAvg(prop: Prop, i: Int): Double {
    if (i < 0 || i + 1 >= prop.keys.size) return 0.0
    val span = (prop.keys[i + 1].time - prop.keys[i].time).toDouble()
    return if (span <= 0.0) 0.0 else segmentLength(prop, i) / span
}

// Sets the motion path type of keyframe `index`. Continuous and Bezier fix the handles where they
// are now, so the path does not jump.
fun withPathType(prop: Prop, index: Int, type: PathType): Prop {
    if (!prop.spatial || index < 0 || index >= prop.keys.size) return prop
    val keys = prop.keys.toMutableList()
    val k = keys[index]
    if (type == PathType.LINEAR || type == PathType.AUTO) {
        keys[index] = k.copy(pathType = type)
    } else {
        val now = spatialTangents(prop.keys, index)
        val handles = if (norm(now.first) + norm(now.second) > 1e-9) now else startTangents(prop.keys, index)
        keys[index] = k.copy(pathType = type, inTan = handles.first, outTan = handles.second)
    }
    return prop.copy(keys = keys)
}

// Sets the motion path type on both keyframes of segment `seg`.
fun withSegmentPathType(prop: Prop, seg: Int, type: PathType): Prop =
    withPathType(withPathType(prop, seg, type), seg + 1, type)

// Moves one handle of a keyframe. `vec` is the handle as an offset from the keyframe's value.
// Pulling a handle of an Auto (or Linear) keyframe makes it Continuous: the other handle stays in
// line on the opposite side, keeping its own length. Bezier handles move on their own.
fun withKeyTangent(prop: Prop, index: Int, isOut: Boolean, vec: List<Double>): Prop {
    if (!prop.spatial || index < 0 || index >= prop.keys.size) return prop
    val k = prop.keys[index]
    val n = k.value.size
    val now = spatialTangents(prop.keys, index)
    val start = if (norm(now.first) + norm(now.second) > 1e-9) now else startTangents(prop.keys, index)
    val v = List(n) { vec.getOrElse(it) { 0.0 } }
    var inT = start.first
    var outT = start.second
    val type = if (k.pathType == PathType.BEZIER) PathType.BEZIER else PathType.CONTINUOUS
    if (isOut) outT = v else inT = v
    if (type == PathType.CONTINUOUS) {
        val len = norm(v)
        val otherLen = norm(if (isOut) inT else outT).let { if (it < 1e-9) len else it }
        val other = if (len < 1e-9) zeros(n) else List(n) { -v[it] / len * otherLen }
        if (isOut) inT = other else outT = other
    }
    val keys = prop.keys.toMutableList()
    keys[index] = k.copy(pathType = type, inTan = inT, outTan = outT)
    return prop.copy(keys = keys)
}

// ---------- graph editor ----------

// Sets how the segment that starts at keyframe `index` moves to the next keyframe.
// Like After Effects: dragging a handle of an Auto Bezier keyframe makes it Continuous, and the
// other side of a Continuous keyframe follows, so the speed stays smooth through it.
fun withKeyEase(prop: Prop, index: Int, interp: Interp, ease: List<Double>): Prop {
    if (index < 0 || index >= prop.keys.size) return prop
    if (ease.size < 4) return prop
    val keys = prop.keys.toMutableList()
    val old = easeOf(keys[index])
    val outMoved = abs(old[0] - ease[0]) > 1e-9 || abs(old[1] - ease[1]) > 1e-9
    val inMoved = abs(old[2] - ease[2]) > 1e-9 || abs(old[3] - ease[3]) > 1e-9
    keys[index] = keys[index].copy(interp = interp, ease = ease)
    if (interp == Interp.BEZIER) {
        if (outMoved && keys[index].smooth == Smooth.AUTO) {
            keys[index] = keys[index].copy(smooth = Smooth.CONTINUOUS)
        }
        if (inMoved && index + 1 < keys.size && keys[index + 1].smooth == Smooth.AUTO) {
            keys[index + 1] = keys[index + 1].copy(smooth = Smooth.CONTINUOUS)
        }
    }
    var result = prop.copy(keys = keys)
    if (interp == Interp.BEZIER) {
        if (outMoved && keys[index].smooth == Smooth.CONTINUOUS) result = followOut(result, index)
        if (inMoved && index + 1 < keys.size && keys[index + 1].smooth == Smooth.CONTINUOUS) {
            result = followIn(result, index + 1)
        }
    }
    return result
}

// Changes a keyframe to Bezier, Auto Bezier or Continuous Bezier. Auto and Continuous make both sides
// of the keyframe Bezier (a Hold stays Hold), then work out the handles.
fun withKeySmooth(prop: Prop, index: Int, smooth: Smooth): Prop {
    if (index < 0 || index >= prop.keys.size) return prop
    val keys = prop.keys.toMutableList()
    keys[index] = keys[index].copy(smooth = smooth)
    if (smooth != Smooth.NONE) {
        for (j in intArrayOf(index - 1, index)) {
            if (j < 0 || j + 1 >= keys.size) continue
            val k = keys[j]
            if (k.interp == Interp.LINEAR) keys[j] = k.copy(interp = Interp.BEZIER, ease = LINEAR_EASE)
        }
    }
    var p = relaxAuto(prop.copy(keys = keys))
    if (smooth == Smooth.CONTINUOUS && index > 0 && index < keys.size - 1) p = followOut(p, index)
    return p
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
        // motion path (curved Position)
        fun nearD(a: Double, b: Double): Boolean = Math.abs(a - b) < 1e-3
        val curved = Prop(
            listOf(0.0, 0.0),
            listOf(
                Keyframe(0L, listOf(0.0, 0.0)),
                Keyframe(sec, listOf(100.0, 100.0)),
                Keyframe(sec * 2, listOf(200.0, 0.0))
            ),
            spatial = true
        )
        val mid = Eval.valueAt(curved, sec / 2)
        check("auto motion path bends away from the straight line", Math.abs(mid[0] - 50.0) > 5.0)
        val flat = withSegmentPathType(curved, 0, PathType.LINEAR)
        val flatMid = Eval.valueAt(flat, sec / 2)
        check("linear motion path is a straight line", nearD(flatMid[0], 50.0) && nearD(flatMid[1], 50.0))
        val notSpatial = curved.copy(spatial = false)
        check("a property that is not spatial ignores the path", nearD(Eval.valueAt(notSpatial, sec / 2)[0], 50.0))
        check("the path passes through the keyframes", nearD(Eval.valueAt(curved, sec)[0], 100.0) && nearD(Eval.valueAt(curved, sec)[1], 100.0))
        fun dist(a: List<Double>, b: List<Double>): Double = Math.hypot(a[0] - b[0], a[1] - b[1])
        val q1 = Eval.valueAt(curved, sec / 4)
        val q2 = Eval.valueAt(curved, sec / 2)
        val q3 = Eval.valueAt(curved, sec * 3 / 4)
        val d12 = dist(q1, q2)
        val d23 = dist(q2, q3)
        check("speed along the path is even with linear timing", Math.abs(d12 - d23) < 0.05 * d12)
        val tan = withKeyTangent(curved, 1, true, listOf(60.0, -20.0))
        check("pulling an auto handle makes it continuous", tan.keys[1].pathType == PathType.CONTINUOUS)
        val tin = tan.keys[1].inTan
        val tout = tan.keys[1].outTan
        check("the other handle stays in line, on the opposite side", Math.abs(tin[0] * tout[1] - tin[1] * tout[0]) < 1e-6 && tin[0] * tout[0] + tin[1] * tout[1] < 0.0)
        val bez = withKeyTangent(withPathType(curved, 1, PathType.BEZIER), 1, true, listOf(10.0, 30.0))
        check("bezier handles move independently", bez.keys[1].pathType == PathType.BEZIER && bez.keys[1].inTan != listOf(-10.0, -30.0))
        val withAnim = toggleAnimated(Prop(listOf(5.0, 5.0), spatial = true), 0L, listOf(5.0, 5.0))
        check("the stopwatch keeps a property spatial", withAnim.spatial && deleteKeyAt(withAnim, 0L).spatial)
        val pathBack = after.withLayerProp(compId, layer.id, Path.POSITION) { withPathType(curved, 1, PathType.CONTINUOUS) }
        check("motion paths survive save and load", ProjectJson.decode(ProjectJson.encode(pathBack)).project == pathBack)
        val withSmooth = after.withLayerProp(compId, layer.id, Path.OPACITY) {
            withKeySmooth(
                Prop(
                    listOf(0.0),
                    listOf(
                        Keyframe(0L, listOf(0.0), Interp.BEZIER),
                        Keyframe(sec, listOf(50.0), Interp.BEZIER),
                        Keyframe(sec * 2, listOf(200.0))
                    )
                ),
                1, Smooth.AUTO
            )
        }
        check(
            "smooth keyframes survive save and load",
            ProjectJson.decode(ProjectJson.encode(withSmooth)).project == withSmooth
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

        // Auto Bezier and Continuous Bezier
        fun perSec(v: Double?): Double = (v ?: 0.0) * sec
        fun nearD(a: Double, b: Double): Boolean = Math.abs(a - b) < 1e-3
        val three = Prop(
            listOf(0.0),
            listOf(
                Keyframe(0L, listOf(0.0), Interp.BEZIER),
                Keyframe(sec, listOf(50.0), Interp.BEZIER),
                Keyframe(sec * 2, listOf(200.0))
            )
        )
        val auto = withKeySmooth(three, 1, Smooth.AUTO)
        check("auto bezier: same speed in and out", nearD(perSec(keySpeedIn(auto, 1)), perSec(keySpeedOut(auto, 1))))
        check("auto bezier: speed is the slope between the neighbours (100 per second)", nearD(perSec(keySpeedOut(auto, 1)), 100.0))
        val peak = withKeySmooth(
            Prop(
                listOf(0.0),
                listOf(
                    Keyframe(0L, listOf(0.0), Interp.BEZIER),
                    Keyframe(sec, listOf(100.0), Interp.BEZIER),
                    Keyframe(sec * 2, listOf(0.0))
                )
            ),
            1, Smooth.AUTO
        )
        check("auto bezier at a peak has speed 0 (no overshoot)", nearD(perSec(keySpeedIn(peak, 1)), 0.0) && nearD(perSec(keySpeedOut(peak, 1)), 0.0))
        val e = easeOf(auto.keys[1])
        val dragged = withKeyEase(auto, 1, Interp.BEZIER, listOf(0.5, 0.6, e[2], e[3]))
        check("dragging an auto handle makes it continuous", dragged.keys[1].smooth == Smooth.CONTINUOUS)
        check("the other side follows a continuous keyframe", nearD(perSec(keySpeedIn(dragged, 1)), perSec(keySpeedOut(dragged, 1))))
        val moved = setPropAt(dragged, sec, listOf(80.0))
        check("continuous keeps its smooth speed when the value changes", nearD(perSec(keySpeedIn(moved, 1)), perSec(keySpeedOut(moved, 1))))
        val plain = withKeySmooth(auto, 1, Smooth.NONE)
        check("back to plain bezier keeps the handles", plain.keys[1].smooth == Smooth.NONE && plain.keys[0].ease == auto.keys[0].ease)
        // moving keyframes and bounce
        val mv = moveKey(three, 1, sec * 5, listOf(70.0), sec / 30)
        check("a moved keyframe stays before the next one", mv.keys[1].time == sec * 2 - sec / 30 && mv.keys[1].value == listOf(70.0))
        check("a moved keyframe stays after the previous one", moveKey(three, 1, -sec, listOf(5.0), sec / 30).keys[1].time == sec / 30)
        val drop = Prop(listOf(0.0), listOf(Keyframe(0L, listOf(100.0)), Keyframe(sec * 2, listOf(0.0))))
        val tpfB = sec / 30
        val bounce = withBounce(drop, 0, 3, 0.4, tpfB)
        check("bounce adds keyframes", bounce.keys.size == 2 * 3 + 2)
        check("bounce starts and ends on the right values", nearD(Eval.valueAt(bounce, 0L)[0], 100.0) && nearD(Eval.valueAt(bounce, sec * 2)[0], 0.0))
        check("bounce keys are in order and on whole frames", bounce.keys.zipWithNext().all { it.first.time < it.second.time } && bounce.keys.all { it.time % tpfB == 0L })
        check("the first peak is 40% of the way back up", bounce.keys.drop(2).first().value[0] > 30.0 && bounce.keys.drop(2).first().value[0] < 50.0)
        check("bounce never goes below the end value", (0..60).all { Eval.valueAt(bounce, sec * 2 * it / 60)[0] > -0.5 })
        check("bounce does nothing when both values are the same", withBounce(Prop(listOf(0.0), listOf(Keyframe(0L, listOf(5.0)), Keyframe(sec, listOf(5.0)))), 0, 3, 0.4, tpfB).keys.size == 2)
        val fromLinear = withKeySmooth(
            Prop(listOf(0.0), listOf(Keyframe(0L, listOf(0.0)), Keyframe(sec, listOf(50.0)), Keyframe(sec * 2, listOf(200.0)))),
            1, Smooth.AUTO
        )
        check("auto bezier turns linear segments around the keyframe into bezier", fromLinear.keys[0].interp == Interp.BEZIER && fromLinear.keys[1].interp == Interp.BEZIER)

        return out
    }
}

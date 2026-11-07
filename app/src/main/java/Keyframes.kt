package com.motion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
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
    var p = relaxAuto(roveKeys(prop))
    for (i in 1 until p.keys.size - 1) {
        if (p.keys[i].smooth == Smooth.CONTINUOUS) p = followOut(p, i)
    }
    return p
}

// Roving keyframes: a run of roving keyframes between two keyframes that are locked to time is moved in
// time so the speed along the path is even. The first and last keyframe can never rove.
private fun roveKeys(prop: Prop): Prop {
    if (!prop.spatial || prop.keys.none { it.rove }) return prop
    val keys = prop.keys.toMutableList()
    val n = keys.size
    var i = 1
    while (i < n - 1) {
        if (!keys[i].rove) {
            i++
            continue
        }
        var j = i
        while (j + 1 < n - 1 && keys[j + 1].rove) j++
        val a = i - 1
        val b = j + 1
        val cum = DoubleArray(b - a + 1)
        var total = 0.0
        for (sg in a until b) {
            total += abs(segmentLength(prop, sg))
            cum[sg - a + 1] = total
        }
        if (total > 1e-9) {
            var prevTime = keys[a].time
            for (k in i..j) {
                val t = keys[a].time + ((keys[b].time - keys[a].time) * (cum[k - a] / total)).roundToLong()
                val safe = max(t, prevTime + 1L)
                keys[k] = keys[k].copy(time = safe)
                prevTime = safe
            }
        }
        i = j + 1
    }
    return prop.copy(keys = keys)
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

// Auto Bezier tangents, the rule EffectCraft measured against After Effects 26.3 (a Catmull-Rom spline):
//  middle key: out = (next - previous) / 6, in = the mirror of it
//  end keys:   (neighbour - key) / 6, mirrored the same way
//  key times play no part
private fun autoTangents(keys: List<Keyframe>, i: Int): Pair<List<Double>, List<Double>> {
    val c = keys[i].value
    val n = c.size
    if (keys.size < 2) return Pair(zeros(n), zeros(n))
    val out = when (i) {
        0 -> List(n) { (keys[1].value.getOrElse(it) { c[it] } - c[it]) / 6.0 }
        keys.size - 1 -> List(n) { (c[it] - keys[i - 1].value.getOrElse(it) { c[it] }) / 6.0 }
        else -> List(n) { (keys[i + 1].value.getOrElse(it) { c[it] } - keys[i - 1].value.getOrElse(it) { c[it] }) / 6.0 }
    }
    return Pair(out.map { -it }, out)
}

// Handles for a keyframe that is turned into Bezier or Continuous: an Auto keyframe keeps the handles it
// has now; a Linear one gets handles a third of the way along the straight line to each neighbour.
private fun startTangents(keys: List<Keyframe>, i: Int): Pair<List<Double>, List<Double>> {
    val c = keys[i].value
    val n = c.size
    if (keys[i].pathType != PathType.LINEAR) {
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

// true when handle h lies on the segment's straight line, pointing the right way and not longer than it
private fun onChord(h: List<Double>, chord: List<Double>, clen: Double, sign: Double): Boolean {
    val hl = norm(h)
    if (hl < 1e-9) return true
    var dot = 0.0
    for (d in h.indices) dot += h[d] * chord[d]
    return sign * dot / (hl * clen) > 1.0 - 1e-9 && hl <= clen + 1e-9
}

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
    val p3 = List(n) { b.value.getOrElse(it) { a.value[it] } }
    val chord = List(n) { p3[it] - a.value[it] }
    val clen = norm(chord)
    // the same point twice: no motion, the layer holds (EffectCraft does this too)
    if (clen < 1e-9) return null
    // both handles on the straight line: it IS the straight line, so interpolate it exactly
    if (onChord(outA, chord, clen, 1.0) && onChord(inB, chord, clen, -1.0)) return null
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

// ---------- selected keyframes (the timeline) ----------
// `times` are keyframe times (layer time). These act on every property that has a keyframe there.

// Changes every property of one layer.
fun Project.withLayerAllProps(compId: Long, layerId: Long, change: (String, Prop) -> Prop): Project =
    mapComp(compId) { c ->
        c.copy(layers = c.layers.map { l ->
            if (l.id != layerId) l else l.copy(props = l.props.mapValues { (path, pr) -> change(path, pr) })
        })
    }

// Moves the keyframes at `times` by `delta` ticks. A moved keyframe replaces one it lands on.
fun withKeysMoved(prop: Prop, times: Set<Long>, delta: Long): Prop {
    if (!prop.animated || delta == 0L || prop.keys.none { it.time in times }) return prop
    val moved = prop.keys.filter { it.time in times }.map { it.copy(time = it.time + delta) }
    val landed = moved.map { it.time }.toSet()
    val kept = prop.keys.filter { it.time !in times && it.time !in landed }
    return smoothen(prop.copy(keys = (kept + moved).sortedBy { it.time }))
}

fun withKeysDeleted(prop: Prop, times: Set<Long>): Prop {
    var p = prop
    for (t in times) p = deleteKeyAt(p, t)
    return p
}

// Easy Ease (F9): speed 0 with 33.33% influence on both sides of each selected keyframe.
// side: 0 = both (Easy Ease), 1 = only arriving at the keyframe (Easy Ease In), 2 = only leaving it (Easy Ease Out)
fun withEasyEase(prop: Prop, times: Set<Long>, side: Int = 0): Prop {
    if (!prop.animated || prop.keys.none { it.time in times }) return prop
    val keys = prop.keys.toMutableList()
    val n = keys.size
    fun base(k: Keyframe): List<Double> = if (k.interp == Interp.BEZIER) easeOf(k) else LINEAR_EASE
    for (i in 0 until n) {
        if (keys[i].time !in times) continue
        if (side != 1 && i + 1 < n && keys[i].interp != Interp.HOLD) { // leaving this keyframe
            val e = base(keys[i])
            keys[i] = keys[i].copy(interp = Interp.BEZIER, ease = listOf(0.3333, 0.0, e[2], e[3]))
        }
        if (side != 2 && i > 0 && keys[i - 1].interp != Interp.HOLD) { // arriving at this keyframe
            val e = base(keys[i - 1])
            keys[i - 1] = keys[i - 1].copy(interp = Interp.BEZIER, ease = listOf(e[0], e[1], 0.6667, 1.0))
        }
        keys[i] = keys[i].copy(smooth = Smooth.NONE)
    }
    return smoothen(prop.copy(keys = keys))
}

// Toggle Hold Keyframe: if every selected keyframe already holds, they go back to Linear, otherwise they all hold.
fun withToggleHold(prop: Prop, times: Set<Long>): Prop {
    if (!prop.animated) return prop
    val idx = prop.keys.indices.filter { prop.keys[it].time in times && it + 1 < prop.keys.size }
    if (idx.isEmpty()) return prop
    val allHold = idx.all { prop.keys[it].interp == Interp.HOLD }
    val keys = prop.keys.toMutableList()
    for (i in idx) keys[i] = keys[i].copy(interp = if (allHold) Interp.LINEAR else Interp.HOLD)
    return smoothen(prop.copy(keys = keys))
}

// Copy and paste: the copied keyframes keep their type, handles and spacing.
fun keysAtTimes(prop: Prop, times: Set<Long>): List<Keyframe> = prop.keys.filter { it.time in times }

// Pastes the copied keyframes so the first one lands at layer time `at`. Makes the property animated.
fun withKeysPasted(prop: Prop, copied: List<Keyframe>, at: Long): Prop {
    if (copied.isEmpty() || copied.any { it.value.size != prop.value.size }) return prop
    val first = copied.minOf { it.time }
    val made = copied.map { it.copy(time = at + (it.time - first)) }
    val landed = made.map { it.time }.toSet()
    val kept = prop.keys.filter { it.time !in landed }
    return smoothen(prop.copy(keys = (kept + made).sortedBy { it.time }))
}

// ---------- Keyframe Interpolation dialog ----------
// Like Animation > Keyframe Interpolation in After Effects: temporal, spatial (Position and Anchor only) and roving.
// The order of TemporalType matches the dialog's menu after "Current Settings".

enum class TemporalType { LINEAR, BEZIER, CONTINUOUS, AUTO, HOLD }

private fun nearEase(a: List<Double>, b: List<Double>): Boolean =
    abs(a[0] - b[0]) < 1e-6 && abs(a[1] - b[1]) < 1e-6 && abs(a[2] - b[2]) < 1e-6 && abs(a[3] - b[3]) < 1e-6

// Linear on both sides of keyframe i: the handles lie on the straight line. A segment whose two ends are
// both Linear becomes a plain Linear segment.
private fun makeLinear(prop: Prop, i: Int): Prop {
    val keys = prop.keys.toMutableList()
    if (i + 1 < keys.size && keys[i].interp != Interp.LINEAR) {
        val e = if (keys[i].interp == Interp.BEZIER) easeOf(keys[i]) else LINEAR_EASE
        val ne = listOf(LINEAR_EASE[0], LINEAR_EASE[1], e[2], e[3])
        keys[i] = if (nearEase(ne, LINEAR_EASE)) keys[i].copy(interp = Interp.LINEAR, ease = LINEAR_EASE)
        else keys[i].copy(interp = Interp.BEZIER, ease = ne)
    }
    if (i > 0 && keys[i - 1].interp != Interp.LINEAR) {
        val e = if (keys[i - 1].interp == Interp.BEZIER) easeOf(keys[i - 1]) else LINEAR_EASE
        val ne = listOf(e[0], e[1], LINEAR_EASE[2], LINEAR_EASE[3])
        keys[i - 1] = if (nearEase(ne, LINEAR_EASE)) keys[i - 1].copy(interp = Interp.LINEAR, ease = LINEAR_EASE)
        else keys[i - 1].copy(interp = Interp.BEZIER, ease = ne)
    }
    keys[i] = keys[i].copy(smooth = Smooth.NONE)
    return prop.copy(keys = keys)
}

// Sets the temporal interpolation (in and out) of the keyframes at `times`.
//  Linear / Hold: as named. Hold only holds the outgoing side, like After Effects.
//  Auto Bezier: smooth speed through the keyframe, worked out for you.
//  Bezier: starts from the Auto Bezier handles, the two handles are then independent.
//  Continuous Bezier: starts from the Auto Bezier handles, the two stay smooth through the keyframe.
fun withTemporalType(prop: Prop, times: Set<Long>, type: TemporalType): Prop {
    if (!prop.animated) return prop
    val idx = prop.keys.indices.filter { prop.keys[it].time in times }
    if (idx.isEmpty()) return prop
    var p = prop
    for (i in idx) {
        // a Hold on this keyframe's outgoing side is replaced by Bezier for the Bezier-type methods
        if (type != TemporalType.LINEAR && type != TemporalType.HOLD && p.keys[i].interp == Interp.HOLD && i + 1 < p.keys.size) {
            val ks = p.keys.toMutableList()
            ks[i] = ks[i].copy(interp = Interp.BEZIER, ease = LINEAR_EASE)
            p = p.copy(keys = ks)
        }
        p = when (type) {
            TemporalType.LINEAR -> makeLinear(p, i)
            TemporalType.HOLD -> {
                val ks = p.keys.toMutableList()
                if (i + 1 < ks.size) ks[i] = ks[i].copy(interp = Interp.HOLD, smooth = Smooth.NONE)
                p.copy(keys = ks)
            }
            TemporalType.AUTO -> withKeySmooth(p, i, Smooth.AUTO)
            TemporalType.CONTINUOUS -> {
                val a = withKeySmooth(p, i, Smooth.AUTO)
                val ks = a.keys.toMutableList()
                ks[i] = ks[i].copy(smooth = Smooth.CONTINUOUS)
                a.copy(keys = ks)
            }
            TemporalType.BEZIER -> {
                val a = withKeySmooth(p, i, Smooth.AUTO)
                val ks = a.keys.toMutableList()
                ks[i] = ks[i].copy(smooth = Smooth.NONE)
                a.copy(keys = ks)
            }
        }
    }
    return smoothen(p)
}

// Sets the spatial interpolation (the motion path) of the keyframes at `times`. Position and Anchor only.
fun withSpatialType(prop: Prop, times: Set<Long>, type: PathType): Prop {
    if (!prop.spatial) return prop
    var p = prop
    for (i in prop.keys.indices) {
        if (prop.keys[i].time in times) p = withPathType(p, i, type)
    }
    return p
}

// Roving: Rove Across Time (true) or Lock To Time (false). Position and Anchor only; the first and last keyframe stay locked.
fun withRoving(prop: Prop, times: Set<Long>, rove: Boolean): Prop {
    if (!prop.spatial || !prop.animated) return prop
    val keys = prop.keys.mapIndexed { i, k ->
        if (k.time in times && i > 0 && i < prop.keys.size - 1) k.copy(rove = rove) else k
    }
    return smoothen(prop.copy(keys = keys))
}

// ---------- Animation > Keyframe Assistant ----------

private fun mirrorEase(e: List<Double>): List<Double> = listOf(1.0 - e[2], 1.0 - e[3], 1.0 - e[0], 1.0 - e[1])

// Time-Reverse Keyframes: the selected keyframes of a property play backwards, inside their own time range
// (each property is reversed within its own selected range, like After Effects). A segment that was easing
// in now eases out, and the motion path handles swap sides.
fun withKeysReversed(prop: Prop, times: Set<Long>): Prop {
    val idx = prop.keys.indices.filter { prop.keys[it].time in times }
    if (idx.size < 2) return prop
    val selected = idx.toSet()
    val t0 = prop.keys[idx.first()].time
    val t1 = prop.keys[idx.last()].time
    val moved = idx.map { i ->
        val k = prop.keys[i]
        // the segment that now starts at this keyframe is the one that used to end at it
        val before = if (i - 1 in selected) prop.keys[i - 1] else null
        val interp = before?.interp ?: k.interp
        val ease = if (before != null) mirrorEase(easeOf(before)) else k.ease
        k.copy(time = t0 + t1 - k.time, interp = interp, ease = ease, inTan = k.outTan, outTan = k.inTan)
    }
    val landed = moved.map { it.time }.toSet()
    val kept = prop.keys.filter { it.time !in times && it.time !in landed }
    return smoothen(prop.copy(keys = (kept + moved).sortedBy { it.time }))
}

// The times the selected keyframes get when they are scaled in time around `anchor`, snapped to `snap` ticks.
fun scaledTimes(sorted: List<Long>, anchor: Long, factor: Double, snap: Long): List<Long> {
    var last = Long.MIN_VALUE
    val out = ArrayList<Long>()
    for (t in sorted) {
        var n = anchor + Math.round((t - anchor) * factor)
        if (snap > 0L) n = Math.round(n.toDouble() / snap) * snap
        if (n <= last) n = last + max(1L, snap)
        out.add(n)
        last = n
    }
    return out
}

// Scale in time (Alt-drag the first or last selected keyframe in After Effects): the selected keyframes
// are spread out or squeezed around `anchor`.
fun withKeysScaled(prop: Prop, times: Set<Long>, anchor: Long, factor: Double, snap: Long): Prop {
    if (!prop.animated || factor <= 0.0) return prop
    val sel = prop.keys.filter { it.time in times }.sortedBy { it.time }
    if (sel.isEmpty()) return prop
    val newTimes = scaledTimes(sel.map { it.time }, anchor, factor, snap)
    val moved = sel.mapIndexed { i, k -> k.copy(time = newTimes[i]) }
    val landed = newTimes.toSet()
    val kept = prop.keys.filter { it.time !in times && it.time !in landed }
    return smoothen(prop.copy(keys = (kept + moved).sortedBy { it.time }))
}

// Exponential Scale: between the first and last selected keyframe the scale grows by the same percentage
// every frame (a zoom lens), instead of by the same amount. It replaces the keyframes in between with one
// keyframe per frame, like After Effects.
fun withExponentialScale(prop: Prop, times: Set<Long>, tpf: Long): Prop {
    val sel = prop.keys.filter { it.time in times }
    if (sel.size < 2 || tpf <= 0L) return prop
    val a = sel.first()
    val b = sel.last()
    val made = ArrayList<Keyframe>()
    var t = a.time + tpf
    while (t < b.time) {
        val u = (t - a.time).toDouble() / (b.time - a.time)
        val v = a.value.indices.map { d ->
            val v0 = a.value[d]
            val v1 = b.value.getOrElse(d) { v0 }
            if (v0 > 0.0 && v1 > 0.0) v0 * Math.pow(v1 / v0, u) else v0 + (v1 - v0) * u
        }
        made.add(Keyframe(t, v))
        t += tpf
    }
    val kept = prop.keys.filter { it.time <= a.time || it.time >= b.time }
        .map { if (it.time == a.time) it.copy(interp = Interp.LINEAR, smooth = Smooth.NONE) else it }
    return smoothen(prop.copy(keys = (kept + made).sortedBy { it.time }))
}

// Smoother: replaces the selected keyframes with fewer ones that stay within `tolerance` of the original
// curve (Douglas-Peucker), then makes them Auto Bezier. spatial = true smooths the motion path (Position and
// Anchor), false smooths the value over time.
fun withSmoothed(prop: Prop, times: Set<Long>, tolerance: Double, spatial: Boolean): Prop {
    val idx = prop.keys.indices.filter { prop.keys[it].time in times }
    if (idx.size < 2) return prop
    val sel = idx.map { prop.keys[it] }
    val n = sel.size
    val keep = BooleanArray(n)
    keep[0] = true
    keep[n - 1] = true
    val usePath = spatial && prop.spatial
    fun deviation(i: Int, lo: Int, hi: Int): Double {
        val k = sel[i]
        val a = sel[lo]
        val b = sel[hi]
        val dims = a.value.size
        if (usePath) {
            var vv = 0.0
            var wv = 0.0
            for (d in 0 until dims) {
                val v = b.value.getOrElse(d) { a.value[d] } - a.value[d]
                val w = k.value.getOrElse(d) { a.value[d] } - a.value[d]
                vv += v * v
                wv += w * w
            }
            var dot = 0.0
            for (d in 0 until dims) {
                dot += (b.value.getOrElse(d) { a.value[d] } - a.value[d]) * (k.value.getOrElse(d) { a.value[d] } - a.value[d])
            }
            val u = if (vv < 1e-12) 0.0 else (dot / vv).coerceIn(0.0, 1.0)
            var dist = 0.0
            for (d in 0 until dims) {
                val cp = a.value[d] + (b.value.getOrElse(d) { a.value[d] } - a.value[d]) * u
                val diff = k.value.getOrElse(d) { a.value[d] } - cp
                dist += diff * diff
            }
            return sqrt(dist)
        }
        val u = (k.time - a.time).toDouble() / max(1L, b.time - a.time)
        var worst = 0.0
        for (d in 0 until dims) {
            val lerp = a.value[d] + (b.value.getOrElse(d) { a.value[d] } - a.value[d]) * u
            worst = max(worst, abs(k.value.getOrElse(d) { lerp } - lerp))
        }
        return worst
    }
    fun split(lo: Int, hi: Int) {
        if (hi - lo < 2) return
        var best = -1
        var bestDev = 0.0
        for (i in lo + 1 until hi) {
            val d = deviation(i, lo, hi)
            if (d > bestDev) {
                bestDev = d
                best = i
            }
        }
        if (best >= 0 && bestDev > tolerance) {
            keep[best] = true
            split(lo, best)
            split(best, hi)
        }
    }
    split(0, n - 1)
    val kept = ArrayList<Keyframe>()
    for (i in 0 until n) {
        if (!keep[i]) continue
        val k = sel[i]
        kept.add(
            if (usePath) {
                k.copy(pathType = PathType.AUTO)
            } else {
                // smoothing the value over time makes each keyframe Bezier, like After Effects
                k.copy(
                    interp = if (i < n - 1 && k.interp == Interp.HOLD) Interp.HOLD else if (i < n - 1) Interp.BEZIER else k.interp,
                    ease = if (k.interp == Interp.BEZIER) k.ease else LINEAR_EASE,
                    smooth = Smooth.AUTO
                )
            }
        )
    }
    val others = prop.keys.filter { it.time !in times }
    return smoothen(prop.copy(keys = (others + kept).sortedBy { it.time }))
}

// Wiggler: adds keyframes at `frequency` per second between the first and last selected keyframe, each moved
// by a random amount up to `magnitude` (in the property's own units). The selected keyframes in between are
// moved too; the first and last stay. dims: 0 = all the same, 1 = all independent, 2 = X only, 3 = Y only.
// Smooth noise makes the keyframes Auto Bezier, jagged noise leaves them Linear.
fun withWiggle(prop: Prop, times: Set<Long>, smooth: Boolean, dims: Int, frequency: Double, magnitude: Double, tpf: Long, seed: Long): Prop {
    val sel = prop.keys.filter { it.time in times }
    if (sel.size < 2 || frequency <= 0.0 || magnitude == 0.0 || tpf <= 0L) return prop
    val t0 = sel.first().time
    val t1 = sel.last().time
    val rnd = java.util.Random(seed)
    val n = prop.value.size
    fun noise(): List<Double> {
        val same = (rnd.nextDouble() * 2.0 - 1.0) * magnitude
        return List(n) { d ->
            when (dims) {
                0 -> same
                1 -> (rnd.nextDouble() * 2.0 - 1.0) * magnitude
                else -> if (d == dims - 2) (rnd.nextDouble() * 2.0 - 1.0) * magnitude else 0.0
            }
        }
    }
    val step = max(tpf, (TICKS_PER_SECOND / frequency).roundToLong() / tpf * tpf)
    fun styled(k: Keyframe): Keyframe =
        if (smooth) k.copy(interp = Interp.BEZIER, ease = LINEAR_EASE, smooth = Smooth.AUTO) else k.copy(interp = Interp.LINEAR, smooth = Smooth.NONE)
    val made = ArrayList<Keyframe>()
    var t = t0 + step
    while (t < t1) {
        val base = Eval.valueAt(prop, t)
        val nz = noise()
        made.add(styled(Keyframe(t, List(n) { base.getOrElse(it) { 0.0 } + nz[it] })))
        t += step
    }
    val landed = made.map { it.time }.toSet()
    val keys = ArrayList<Keyframe>()
    for (k in prop.keys) {
        if (k.time in landed) continue
        if (k.time in times && k.time != t0 && k.time != t1) {
            val nz = noise()
            keys.add(styled(k.copy(value = List(n) { k.value.getOrElse(it) { 0.0 } + nz[it] })))
        } else {
            keys.add(k)
        }
    }
    return smoothen(prop.copy(keys = (keys + made).sortedBy { it.time }))
}

// Motion Sketch: the finger path you drew becomes one Position keyframe per frame, replacing the keyframes
// in that time range (like After Effects). `samples` are (layer time, [x, y]). `smoothing` is the Smoother
// tolerance applied to the new keyframes (0 = none).
fun withSketchedPath(prop: Prop, samples: List<Pair<Long, List<Double>>>, tpf: Long, smoothing: Double): Prop {
    if (!prop.spatial || samples.size < 2 || tpf <= 0L) return prop
    val pts = samples.sortedBy { it.first }
    val t0 = Math.round(pts.first().first.toDouble() / tpf) * tpf
    val t1 = Math.round(pts.last().first.toDouble() / tpf) * tpf
    if (t1 <= t0) return prop
    fun at(t: Long): List<Double> {
        var i = 0
        while (i < pts.size - 2 && pts[i + 1].first <= t) i++
        val a = pts[i]
        val b = pts[i + 1]
        val span = (b.first - a.first).toDouble()
        val u = if (span <= 0.0) 0.0 else ((t - a.first) / span).coerceIn(0.0, 1.0)
        return List(prop.value.size) { d ->
            val av = a.second.getOrElse(d) { prop.value[d] }
            val bv = b.second.getOrElse(d) { av }
            av + (bv - av) * u
        }
    }
    val made = ArrayList<Keyframe>()
    var t = t0
    while (t <= t1) {
        made.add(Keyframe(t, at(t)))
        t += tpf
    }
    val kept = prop.keys.filter { it.time < t0 || it.time > t1 }
    val result = smoothen(prop.copy(keys = (kept + made).sortedBy { it.time }))
    return if (smoothing > 0.0) withSmoothed(result, made.map { it.time }.toSet(), smoothing, true) else result
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
        check("auto motion path bends away from the straight line", Math.abs(mid[0] - 50.0) > 3.0)
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
        val aeMid = spatialTangents(curved.keys, 1)
        check("auto handle of a middle key is (next - previous) / 6, as measured in After Effects", nearD(aeMid.second[0], 200.0 / 6.0) && nearD(aeMid.second[1], 0.0) && nearD(aeMid.first[0], -200.0 / 6.0))
        val aeEnd = spatialTangents(curved.keys, 0)
        check("an end key has a handle too: (neighbour - key) / 6", nearD(aeEnd.second[0], 100.0 / 6.0) && nearD(aeEnd.second[1], 100.0 / 6.0))
        val twoKeys = Prop(listOf(0.0, 0.0), listOf(Keyframe(0L, listOf(0.0, 0.0)), Keyframe(sec, listOf(80.0, 40.0))), spatial = true)
        check("a straight two-key move is exactly the straight line", Eval.valueAt(twoKeys, sec / 2) == listOf(40.0, 20.0))
        val same = Prop(
            listOf(0.0, 0.0),
            listOf(
                Keyframe(0L, listOf(0.0, 0.0)),
                Keyframe(sec, listOf(100.0, 100.0)),
                Keyframe(sec * 2, listOf(100.0, 100.0)),
                Keyframe(sec * 3, listOf(200.0, 0.0))
            ),
            spatial = true
        )
        check("two identical keys hold still", nearD(Eval.valueAt(same, sec * 3 / 2)[0], 100.0) && nearD(Eval.valueAt(same, sec * 3 / 2)[1], 100.0))
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
        // the timeline: move, ease, hold, copy, paste, delete
        val tl = Prop(listOf(0.0), listOf(Keyframe(0L, listOf(0.0)), Keyframe(sec, listOf(50.0)), Keyframe(sec * 2, listOf(200.0))))
        check("moving a selected keyframe shifts only that one", withKeysMoved(tl, setOf(sec), sec / 2).keys.map { it.time } == listOf(0L, sec + sec / 2, sec * 2))
        val over = withKeysMoved(tl, setOf(sec), sec)
        check("a keyframe moved onto another replaces it", over.keys.size == 2 && over.keys[1].value == listOf(50.0))
        val ee = withEasyEase(tl, setOf(sec))
        check("easy ease: speed 0, 33.33% influence on both sides of the keyframe", ee.keys[0].ease[2] == 0.6667 && ee.keys[0].ease[3] == 1.0 && ee.keys[1].ease[0] == 0.3333 && ee.keys[1].ease[1] == 0.0)
        val h1 = withToggleHold(tl, setOf(0L, sec))
        check("toggle hold holds, and toggled again goes back to linear", h1.keys[0].interp == Interp.HOLD && h1.keys[1].interp == Interp.HOLD && withToggleHold(h1, setOf(0L, sec)).keys[0].interp == Interp.LINEAR)
        val pasted = withKeysPasted(tl, keysAtTimes(tl, setOf(0L, sec)), sec * 3)
        check("paste puts the keyframes at the playhead with the same spacing", pasted.keys.map { it.time } == listOf(0L, sec, sec * 2, sec * 3, sec * 4))
        check("delete removes the selected keyframes", withKeysDeleted(tl, setOf(0L, sec)).keys.map { it.time } == listOf(sec * 2))
        // the Keyframe Interpolation dialog
        fun perSecond(v: Double?): Double = (v ?: 0.0) * sec
        val autoT = withTemporalType(tl, setOf(sec), TemporalType.AUTO)
        check("temporal auto bezier: same speed in and out", autoT.keys[1].smooth == Smooth.AUTO && nearD(perSecond(keySpeedIn(autoT, 1)), perSecond(keySpeedOut(autoT, 1))))
        val contT = withTemporalType(tl, setOf(sec), TemporalType.CONTINUOUS)
        check("temporal continuous bezier keeps the auto handles and the smooth link", contT.keys[1].smooth == Smooth.CONTINUOUS && nearD(perSecond(keySpeedIn(contT, 1)), perSecond(keySpeedOut(contT, 1))))
        val bezT = withTemporalType(tl, setOf(sec), TemporalType.BEZIER)
        check("temporal bezier starts from the auto handles, then they are independent", bezT.keys[1].smooth == Smooth.NONE && bezT.keys[1].interp == Interp.BEZIER && bezT.keys[0].interp == Interp.BEZIER)
        val linT = withTemporalType(ee, setOf(sec), TemporalType.LINEAR)
        check("temporal linear puts the handles on the straight line", linT.keys[0].interp == Interp.LINEAR && linT.keys[1].interp == Interp.LINEAR)
        check("temporal hold holds the outgoing side only", withTemporalType(tl, setOf(sec), TemporalType.HOLD).keys.map { it.interp } == listOf(Interp.LINEAR, Interp.HOLD, Interp.LINEAR))
        check("spatial interpolation sets the path type of the selected keyframe", withSpatialType(curved, setOf(sec), PathType.LINEAR).keys[1].pathType == PathType.LINEAR)
        val straight = Prop(
            listOf(0.0, 0.0),
            listOf(Keyframe(0L, listOf(0.0, 0.0)), Keyframe(sec, listOf(50.0, 0.0)), Keyframe(sec * 3, listOf(100.0, 0.0))),
            spatial = true
        )
        val roved = withRoving(straight, setOf(sec), true)
        check("rove across time puts the keyframe where the speed is even", roved.keys[1].rove && roved.keys[1].time == sec * 3 / 2)
        check("lock to time stops roving", !withRoving(roved, setOf(sec * 3 / 2), false).keys[1].rove)
        check("the first and last keyframe cannot rove", withRoving(straight, setOf(0L, sec * 3), true).keys.none { it.rove })
        // keyframe assistants
        val rev = withKeysReversed(tl, setOf(0L, sec, sec * 2))
        check("time-reverse plays the values backwards", rev.keys.map { it.value[0] } == listOf(200.0, 50.0, 0.0) && rev.keys.map { it.time } == listOf(0L, sec, sec * 2))
        val eased = Prop(listOf(0.0), listOf(Keyframe(0L, listOf(0.0), Interp.BEZIER, listOf(0.4, 0.0, 1.0, 1.0)), Keyframe(sec, listOf(100.0))))
        val revE = withKeysReversed(eased, setOf(0L, sec))
        check("time-reverse mirrors the easing of the segment", revE.keys[0].interp == Interp.BEZIER && Math.abs(revE.keys[0].ease[2] - 0.6) < 1e-9 && Math.abs(revE.keys[0].ease[1]) < 1e-9)
        check("time-reverse needs two keyframes", withKeysReversed(tl, setOf(sec)) == tl)
        check("scaling keyframes in time around the first one", withKeysScaled(tl, setOf(0L, sec, sec * 2), 0L, 2.0, sec / 30).keys.map { it.time } == listOf(0L, sec * 2, sec * 4))
        val zoom = Prop(listOf(100.0, 100.0), listOf(Keyframe(0L, listOf(100.0, 100.0)), Keyframe(sec * 2, listOf(400.0, 400.0))))
        val expo = withExponentialScale(zoom, setOf(0L, sec * 2), sec / 30)
        check("exponential scale: one keyframe per frame", expo.keys.size == 60 + 1)
        check("exponential scale: halfway is the geometric mean (200), not 250", nearD(Eval.valueAt(expo, sec)[0], 200.0))
        val line5 = Prop(listOf(0.0), listOf(0, 1, 2, 3, 4).map { Keyframe(sec * it, listOf(25.0 * it)) })
        val allT = line5.keys.map { it.time }.toSet()
        check("smoother removes keyframes that lie on the curve", withSmoothed(line5, allT, 5.0, false).keys.size == 2)
        val spike = Prop(listOf(0.0), listOf(0.0, 0.0, 100.0, 0.0, 0.0).mapIndexed { i, v -> Keyframe(sec * i, listOf(v)) })
        check("smoother keeps the keyframes that matter", withSmoothed(spike, spike.keys.map { it.time }.toSet(), 5.0, false).keys.any { it.value[0] == 100.0 })
        check("smoother makes the kept keyframes auto bezier", withSmoothed(line5, allT, 5.0, false).keys.all { it.smooth == Smooth.AUTO })
        val wig = withWiggle(Prop(listOf(0.0), listOf(Keyframe(0L, listOf(0.0)), Keyframe(sec * 2, listOf(0.0)))), setOf(0L, sec * 2), true, 0, 10.0, 5.0, sec / 30, 1L)
        check("wiggler adds keyframes at the frequency", wig.keys.size > 2 && wig.keys.first().value[0] == 0.0 && wig.keys.last().value[0] == 0.0)
        check("wiggler stays within the magnitude", wig.keys.all { Math.abs(it.value[0]) <= 10.0 + 1e-9 })
        val sketch = withSketchedPath(Prop(listOf(0.0, 0.0), spatial = true), listOf(Pair(0L, listOf(0.0, 0.0)), Pair(sec, listOf(100.0, 50.0))), sec / 30, 0.0)
        check("motion sketch makes one keyframe per frame", sketch.keys.size == 31 && nearD(sketch.keys[15].value[0], 50.0) && nearD(sketch.keys[15].value[1], 25.0))
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
        // moving keyframes
        val mv = moveKey(three, 1, sec * 5, listOf(70.0), sec / 30)
        check("a moved keyframe stays before the next one", mv.keys[1].time == sec * 2 - sec / 30 && mv.keys[1].value == listOf(70.0))
        check("a moved keyframe stays after the previous one", moveKey(three, 1, -sec, listOf(5.0), sec / 30).keys[1].time == sec / 30)
        val fromLinear = withKeySmooth(
            Prop(listOf(0.0), listOf(Keyframe(0L, listOf(0.0)), Keyframe(sec, listOf(50.0)), Keyframe(sec * 2, listOf(200.0)))),
            1, Smooth.AUTO
        )
        check("auto bezier turns linear segments around the keyframe into bezier", fromLinear.keys[0].interp == Interp.BEZIER && fromLinear.keys[1].interp == Interp.BEZIER)

        return out
    }
}

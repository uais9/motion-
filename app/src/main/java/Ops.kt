package com.motion

// Every edit is a function: old project in, new project out.
// That makes undo/redo simple and safe.

private fun Project.mapComp(compId: Long, change: (Comp) -> Comp): Project =
    copy(comps = comps.map { if (it.id == compId) change(it) else it })

// Rounds a time to the nearest whole frame. All timeline edits use this.
fun snapFrame(t: Long, fr: FrameRate): Long {
    val tpf = fr.ticksPerFrame
    return Math.round(t.toDouble() / tpf) * tpf
}

fun Project.withNewComp(
    name: String,
    width: Int,
    height: Int,
    frameRate: FrameRate,
    durationTicks: Long,
    background: Int
): Pair<Project, Long> {
    val id = nextId
    val comp = Comp(
        id = id,
        name = name,
        width = width,
        height = height,
        pixelAspect = 1.0,
        frameRate = frameRate,
        durationTicks = durationTicks,
        startTimecode = 0L,
        background = background,
        workAreaStart = 0L,
        workAreaDuration = durationTicks,
        layers = emptyList()
    )
    return Pair(copy(comps = comps + comp, nextId = nextId + 1), id)
}

fun Project.withFootage(items: List<Footage>): Project {
    var n = nextId
    val added = items.map {
        val f = it.copy(id = n)
        n++
        f
    }
    return copy(footage = footage + added, nextId = n)
}

// Adds a layer on TOP of the composition (index 0).
fun Project.withLayer(compId: Long, footageId: Long): Project {
    val comp = comps.firstOrNull { it.id == compId } ?: return this
    val f = footage.firstOrNull { it.id == footageId } ?: return this
    val out = if (f.kind == FootageKind.VIDEO && f.durationTicks > 0L) {
        minOf(f.durationTicks, comp.durationTicks)
    } else {
        comp.durationTicks
    }
    val layer = Layer(
        id = nextId,
        name = f.name,
        footageId = f.id,
        startTime = 0L,
        inPoint = 0L,
        outPoint = out,
        props = defaultTransform(f, comp)
    )
    val updated = comp.copy(layers = listOf(layer) + comp.layers)
    return copy(
        comps = comps.map { if (it.id == compId) updated else it },
        nextId = nextId + 1
    )
}

// Sets fixed values on a layer's properties (used by the Layer settings form).
// Keyframes on a property are kept as they are.
fun Project.withLayerValues(
    compId: Long,
    layerId: Long,
    values: Map<String, List<Double>>
): Project = mapComp(compId) { c ->
    c.copy(layers = c.layers.map { l ->
        if (l.id != layerId) {
            l
        } else {
            var props = l.props
            for ((path, v) in values) {
                val old = props[path]
                props = props + (path to Prop(v, old?.keys ?: emptyList()))
            }
            l.copy(props = props)
        }
    })
}

// Trim: sets a layer's in and out points (comp time). The source stays where it is.
fun Project.withLayerTiming(
    compId: Long,
    layerId: Long,
    inPoint: Long,
    outPoint: Long
): Project = mapComp(compId) { c ->
    c.copy(layers = c.layers.map {
        if (it.id == layerId) it.copy(inPoint = inPoint, outPoint = outPoint) else it
    })
}

// Move: shifts a layer in time (start, in and out together) and puts it on a new row.
fun Project.withLayerMoved(
    compId: Long,
    layerId: Long,
    deltaTicks: Long,
    newIndex: Int
): Project = mapComp(compId) { c ->
    val layer = c.layers.firstOrNull { it.id == layerId }
    if (layer == null) {
        c
    } else {
        val moved = layer.copy(
            startTime = layer.startTime + deltaTicks,
            inPoint = layer.inPoint + deltaTicks,
            outPoint = layer.outPoint + deltaTicks
        )
        val rest = c.layers.filter { it.id != layerId }
        val idx = newIndex.coerceIn(0, rest.size)
        c.copy(layers = rest.take(idx) + moved + rest.drop(idx))
    }
}

// Split: cuts a layer in two at comp time t. The new second half sits above the first.
// Both halves keep the same start time, so keyframes stay where they were.
fun Project.withLayerSplit(compId: Long, layerId: Long, t: Long): Project {
    val comp = comps.firstOrNull { it.id == compId } ?: return this
    val idx = comp.layers.indexOfFirst { it.id == layerId }
    if (idx < 0) return this
    val layer = comp.layers[idx]
    if (t <= layer.inPoint || t >= layer.outPoint) return this
    val first = layer.copy(outPoint = t)
    val second = layer.copy(id = nextId, inPoint = t)
    val newLayers = comp.layers.take(idx) + second + first + comp.layers.drop(idx + 1)
    return copy(
        comps = comps.map { if (it.id == compId) comp.copy(layers = newLayers) else it },
        nextId = nextId + 1
    )
}

fun Project.withLayerRemoved(compId: Long, layerId: Long): Project = mapComp(compId) { c ->
    c.copy(layers = c.layers.filter { it.id != layerId })
}

fun Project.withLayerEnabled(compId: Long, layerId: Long, enabled: Boolean): Project =
    mapComp(compId) { c ->
        c.copy(layers = c.layers.map {
            if (it.id == layerId) it.copy(enabled = enabled) else it
        })
    }

// How far a layer's edge may be dragged. edge: 1 = in point, 2 = out point.
// A video can't show footage before its start or after its end.
fun trimBounds(project: Project, comp: Comp, layer: Layer, edge: Int): Pair<Long, Long> {
    val f = project.footage.firstOrNull { it.id == layer.footageId }
    val tpf = comp.frameRate.ticksPerFrame
    val isVideo = f != null && f.kind == FootageKind.VIDEO && f.durationTicks > 0L
    return if (edge == 1) {
        val lo = if (isVideo) maxOf(0L, layer.startTime) else 0L
        Pair(lo, layer.outPoint - tpf)
    } else {
        val hi = if (isVideo && f != null) {
            minOf(comp.durationTicks, layer.startTime + f.durationTicks)
        } else {
            comp.durationTicks
        }
        Pair(layer.inPoint + tpf, hi)
    }
}

// How far a layer may be moved in time and still stay inside the composition.
fun moveBounds(comp: Comp, layer: Layer): Pair<Long, Long> =
    Pair(-layer.inPoint, comp.durationTicks - layer.outPoint)

object OpsTest {
    fun run(): List<String> {
        val out = ArrayList<String>()

        fun check(name: String, ok: Boolean) {
            out.add((if (ok) "PASS  " else "FAIL  ") + name)
        }

        val fr = FrameRate.FPS_30
        val sec = TICKS_PER_SECOND
        val r = Project().withNewComp(
            "T", 1080, 1920, fr, secondsToTicks(10.0), 0xFF000000.toInt()
        )
        var p = r.first
        val compId = r.second
        p = p.withFootage(
            listOf(
                Footage(0L, "v.mp4", "content://v", FootageKind.VIDEO, 576, 1024, secondsToTicks(34.8)),
                Footage(0L, "s.mp4", "content://s", FootageKind.VIDEO, 576, 1024, secondsToTicks(5.0))
            )
        )
        val longId = p.footage[0].id
        val shortId = p.footage[1].id
        p = p.withLayer(compId, longId)
        val layerId = p.comps[0].layers[0].id

        val split = p.withLayerSplit(compId, layerId, secondsToTicks(4.0))
        val sl = split.comps[0].layers
        check("split makes two layers", sl.size == 2)
        check(
            "second half is on top and starts at the cut",
            sl[0].inPoint == secondsToTicks(4.0) && sl[0].outPoint == secondsToTicks(10.0)
        )
        check(
            "first half ends at the cut",
            sl[1].inPoint == 0L && sl[1].outPoint == secondsToTicks(4.0)
        )
        check("both halves read the same source time", sl[0].startTime == sl[1].startTime)
        check(
            "split ignores a cut outside the layer",
            p.withLayerSplit(compId, layerId, secondsToTicks(12.0)).comps[0].layers.size == 1
        )

        val trimmed = p.withLayerTiming(compId, layerId, secondsToTicks(2.0), secondsToTicks(8.0))
        val tl = trimmed.comps[0].layers[0]
        check(
            "trim sets in and out",
            tl.inPoint == secondsToTicks(2.0) && tl.outPoint == secondsToTicks(8.0)
        )

        val moved = trimmed.withLayerMoved(compId, layerId, secondsToTicks(1.0), 0)
        val ml = moved.comps[0].layers[0]
        check(
            "move shifts start, in and out together",
            ml.startTime == secondsToTicks(1.0) &&
                ml.inPoint == secondsToTicks(3.0) &&
                ml.outPoint == secondsToTicks(9.0)
        )
        val reordered = split.withLayerMoved(compId, sl[0].id, 0L, 1)
        check(
            "moving to another row changes the stacking order",
            reordered.comps[0].layers[1].id == sl[0].id
        )

        check("remove deletes the layer", p.withLayerRemoved(compId, layerId).comps[0].layers.isEmpty())
        check(
            "hide turns a layer off",
            !p.withLayerEnabled(compId, layerId, false).comps[0].layers[0].enabled
        )

        val tpf = fr.ticksPerFrame
        check(
            "snap rounds to the nearest frame",
            snapFrame(sec + tpf / 3, fr) == sec && snapFrame(sec + tpf * 2 / 3, fr) == sec + tpf
        )

        val comp = p.comps[0]
        val longLayer = comp.layers[0]
        val outBounds = trimBounds(p, comp, longLayer, 2)
        check("out edge cannot pass the end of the composition", outBounds.second == comp.durationTicks)
        val inBounds = trimBounds(p, comp, longLayer, 1)
        check(
            "in edge cannot pass the out point",
            inBounds.first == 0L && inBounds.second == longLayer.outPoint - tpf
        )
        val p2 = p.withLayer(compId, shortId)
        val shortLayer = p2.comps[0].layers[0]
        val shortBounds = trimBounds(p2, p2.comps[0], shortLayer, 2)
        check("a short video cannot be stretched past its end", shortBounds.second == secondsToTicks(5.0))
        val mb = moveBounds(comp, tl)
        check("moves stay inside the composition", mb.first == -tl.inPoint && mb.second == comp.durationTicks - tl.outPoint)

        return out
    }
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

object KeyframeTest {
    fun run(): List<String> {
        val out = ArrayList<String>()

        fun check(name: String, ok: Boolean) {
            out.add((if (ok) "PASS  " else "FAIL  ") + name)
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

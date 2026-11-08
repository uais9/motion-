package com.motion

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

// Layer types and the Layer menu: solids, null objects, shape layers, parenting, duplicate, rename,
// colours and Sequence Layers. Like After Effects, a layer does not have to be a video or an image.
// Every function takes a project and gives back a new one (that is what makes undo simple).

// ---------- maths ----------

fun Affine.inverse(): Affine {
    val det = a * d - b * c
    if (abs(det) < 1e-12) return Affine()
    val ia = d / det
    val ib = -b / det
    val ic = -c / det
    val id = a / det
    return Affine(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f))
}

// ---------- parenting ----------
// A child's Position, Scale and Rotation are relative to its parent's ANCHOR POINT, and the child follows
// everything the parent does (Opacity is not passed on). That is how After Effects works.

private fun worldOf(layers: List<Layer>, layer: Layer, t: Long, depth: Int): Affine {
    val local = layerMatrix(layer, t)
    if (layer.parentId < 0L || depth > 32) return local
    val parent = layers.firstOrNull { it.id == layer.parentId } ?: return local
    return spaceOf(layers, parent, t, depth + 1).times(local)
}

// the parent's own transform, moved so that its anchor point is the origin (the space its children live in)
private fun spaceOf(layers: List<Layer>, parent: Layer, t: Long, depth: Int): Affine {
    val a = Eval.layerValue(parent, Path.ANCHOR, t - parent.startTime)
    return worldOf(layers, parent, t, depth).times(Affine.translate(a.getOrElse(0) { 0.0 }, a.getOrElse(1) { 0.0 }))
}

// Maps a layer's own pixels into composition pixels, with all its parents.
fun worldMatrix(layers: List<Layer>, layer: Layer, compTime: Long): Affine = worldOf(layers, layer, compTime, 0)

// The space the layer's transform values are written in: the identity, or its parent's space.
fun parentSpace(layers: List<Layer>, layer: Layer, compTime: Long): Affine {
    val parent = layers.firstOrNull { it.id == layer.parentId } ?: return Affine()
    return spaceOf(layers, parent, compTime, 1)
}

// true when `candidate` is the layer itself or one of its children, grandchildren... (it can not become its parent)
private fun isSelfOrDescendant(layers: List<Layer>, layerId: Long, candidate: Long): Boolean {
    var id = candidate
    var n = 0
    while (id >= 0L && n < 64) {
        if (id == layerId) return true
        id = layers.firstOrNull { it.id == id }?.parentId ?: -1L
        n++
    }
    return false
}

private fun wrapDegrees(d: Double): Double {
    var x = d % 360.0
    if (x > 180.0) x -= 360.0
    if (x < -180.0) x += 360.0
    return x
}

// Sets (or removes, parentId = -1) the parent of a layer. Like After Effects, the child keeps looking the same
// at compTime: its Position, Scale and Rotation are changed to make up for the parent. If a property is
// animated that makes a keyframe at compTime.
fun Project.withLayerParent(compId: Long, layerId: Long, parentId: Long, compTime: Long): Project {
    val c = comps.firstOrNull { it.id == compId } ?: return this
    val l = c.layers.firstOrNull { it.id == layerId } ?: return this
    if (l.parentId == parentId) return this
    if (parentId >= 0L) {
        if (c.layers.none { it.id == parentId }) return this
        if (isSelfOrDescendant(c.layers, layerId, parentId)) return this
    }
    val worldNow = worldMatrix(c.layers, l, compTime)
    val newSpace = if (parentId >= 0L) {
        spaceOf(c.layers, c.layers.first { it.id == parentId }, compTime, 1)
    } else {
        Affine()
    }
    val lt = compTime - l.startTime
    val anchor = Eval.layerValue(l, Path.ANCHOR, lt)
    // local = T(position) R(rotation) S(scale) T(-anchor): undo the anchor to read position, rotation and scale
    val m = newSpace.inverse().times(worldNow).times(Affine.translate(anchor.getOrElse(0) { 0.0 }, anchor.getOrElse(1) { 0.0 }))
    val scaleX = hypot(m.a, m.b)
    val scaleY = if (scaleX < 1e-12) 0.0 else (m.a * m.d - m.b * m.c) / scaleX
    val rotNew = Math.toDegrees(atan2(m.b, m.a))
    val rotOld = Eval.layerValue(l, Path.ROTATION, lt).getOrElse(0) { 0.0 }
    val rot = rotOld + wrapDegrees(rotNew - rotOld)

    var out = this
    if (l.props.containsKey(Path.POSITION_X)) {
        out = out.withLayerProp(compId, layerId, Path.POSITION_X) { setPropAt(it, lt, listOf(m.e)) }
            .withLayerProp(compId, layerId, Path.POSITION_Y) { setPropAt(it, lt, listOf(m.f)) }
    } else {
        out = out.withLayerProp(compId, layerId, Path.POSITION) { setPropAt(it, lt, listOf(m.e, m.f)) }
    }
    out = out.withLayerProp(compId, layerId, Path.SCALE) { setPropAt(it, lt, listOf(scaleX * 100.0, scaleY * 100.0)) }
        .withLayerProp(compId, layerId, Path.ROTATION) { setPropAt(it, lt, listOf(rot)) }
    return out.mapComp(compId) { cc ->
        cc.copy(layers = cc.layers.map { if (it.id == layerId) it.copy(parentId = parentId) else it })
    }
}

// ---------- new layers ----------

private fun transformDefaults(ax: Double, ay: Double, c: Comp): Map<String, Prop> = mapOf(
    Path.ANCHOR to Prop(listOf(ax, ay), spatial = true),
    Path.POSITION to Prop(listOf(c.width / 2.0, c.height / 2.0), spatial = true),
    Path.SCALE to Prop(listOf(100.0, 100.0)),
    Path.ROTATION to Prop(listOf(0.0)),
    Path.OPACITY to Prop(listOf(100.0))
)

// The properties a layer starts with (and what Reset puts back).
fun layerDefaults(project: Project, comp: Comp, layer: Layer): Map<String, Prop> {
    return when (layer.kind) {
        LayerKind.FOOTAGE -> {
            val f = project.footage.firstOrNull { it.id == layer.footageId }
            if (f != null) defaultTransform(f, comp) else emptyMap()
        }
        LayerKind.SOLID, LayerKind.NULL -> transformDefaults(layer.w / 2.0, layer.h / 2.0, comp)
        LayerKind.SHAPE -> {
            val s = minOf(comp.width, comp.height) / 3.0
            transformDefaults(0.0, 0.0, comp) + mapOf(
                Path.SHAPE_SIZE to Prop(listOf(s, s)),
                Path.SHAPE_ROUND to Prop(listOf(0.0)),
                Path.STROKE_WIDTH to Prop(listOf(6.0))
            )
        }
    }
}

private fun uniqueName(c: Comp, base: String): String {
    var n = c.layers.count { it.name.startsWith(base) } + 1
    while (c.layers.any { it.name == "$base $n" }) n++
    return "$base $n"
}

// New layers go on top and last the whole composition, like After Effects.
private fun Project.withNewLayerOnTop(compId: Long, make: (Long, Comp) -> Layer): Project {
    val c = comps.firstOrNull { it.id == compId } ?: return this
    val layer = make(nextId, c)
    return copy(
        comps = comps.map { if (it.id == compId) it.copy(layers = listOf(layer) + it.layers) else it },
        nextId = nextId + 1
    )
}

fun Project.withNewSolid(compId: Long, name: String?, color: Int, w: Int, h: Int): Project =
    withNewLayerOnTop(compId) { id, c ->
        val l = Layer(
            id, name ?: uniqueName(c, "Solid"), -1L, 0L, 0L, c.durationTicks, emptyMap(),
            kind = LayerKind.SOLID, w = w, h = h, fill = color
        )
        l.copy(props = layerDefaults(this, c, l))
    }

// A null object: 100 x 100, shows nothing, there to be a parent for other layers.
fun Project.withNewNull(compId: Long): Project =
    withNewLayerOnTop(compId) { id, c ->
        val l = Layer(
            id, uniqueName(c, "Null"), -1L, 0L, 0L, c.durationTicks, emptyMap(),
            kind = LayerKind.NULL, w = 100, h = 100
        )
        l.copy(props = layerDefaults(this, c, l))
    }

fun Project.withNewShape(compId: Long, type: ShapeType, fill: Int): Project =
    withNewLayerOnTop(compId) { id, c ->
        val l = Layer(
            id, uniqueName(c, "Shape Layer"), -1L, 0L, 0L, c.durationTicks, emptyMap(),
            kind = LayerKind.SHAPE, fill = fill, stroke = 0xFFFFFFFF.toInt(), hasFill = true, hasStroke = false, shapeType = type
        )
        l.copy(props = layerDefaults(this, c, l))
    }

// ---------- Layer menu ----------

// Duplicate Layer: the copy sits right above the original. Children stay with the original.
fun Project.withLayerDuplicated(compId: Long, layerId: Long): Project {
    val c = comps.firstOrNull { it.id == compId } ?: return this
    val i = c.layers.indexOfFirst { it.id == layerId }
    if (i < 0) return this
    val twin = c.layers[i].copy(id = nextId, name = uniqueName(c, c.layers[i].name.trimEnd { it.isDigit() || it == ' ' }.ifBlank { "Layer" }))
    val layers = c.layers.take(i) + twin + c.layers.drop(i)
    return copy(
        comps = comps.map { if (it.id == compId) it.copy(layers = layers) else it },
        nextId = nextId + 1
    )
}

fun Project.withLayerRenamed(compId: Long, layerId: Long, name: String): Project = mapComp(compId) { c ->
    c.copy(layers = c.layers.map { if (it.id == layerId) it.copy(name = name) else it })
}

// colour of a solid, fill and stroke of a shape
fun Project.withLayerColors(compId: Long, layerId: Long, fill: Int, stroke: Int, hasFill: Boolean, hasStroke: Boolean): Project =
    mapComp(compId) { c ->
        c.copy(layers = c.layers.map {
            if (it.id == layerId) it.copy(fill = fill, stroke = stroke, hasFill = hasFill, hasStroke = hasStroke) else it
        })
    }

// Sequence Layers: puts the layers one after the other, in the order given (the order you selected them in),
// each starting where the one before ends, minus `overlap` ticks. The first layer stays where it is.
// crossDissolve fades each layer out and the next one in over the overlap.
fun Project.withLayersSequenced(compId: Long, ids: List<Long>, overlap: Long, crossDissolve: Boolean): Project {
    val c = comps.firstOrNull { it.id == compId } ?: return this
    val ordered = ids.mapNotNull { id -> c.layers.firstOrNull { it.id == id } }
    if (ordered.size < 2) return this
    val moved = HashMap<Long, Layer>()
    var cursor = ordered.first().inPoint
    for ((n, l) in ordered.withIndex()) {
        val len = l.outPoint - l.inPoint
        val shift = cursor - l.inPoint
        var m = l.copy(startTime = l.startTime + shift, inPoint = l.inPoint + shift, outPoint = l.outPoint + shift)
        if (crossDissolve && overlap > 0L) {
            val op = m.props[Path.OPACITY]
            if (op != null) {
                var p: Prop = op
                if (n > 0) p = fade(p, m.inPoint - m.startTime, overlap, 0.0, 100.0)
                if (n < ordered.size - 1) p = fade(p, m.outPoint - m.startTime - overlap, overlap, 100.0, 0.0)
                m = m.copy(props = m.props + (Path.OPACITY to p))
            }
        }
        moved[l.id] = m
        cursor = m.outPoint - overlap
        if (len <= 0L) cursor = m.outPoint
    }
    return mapComp(compId) { cc -> cc.copy(layers = cc.layers.map { moved[it.id] ?: it }) }
}

// opacity keyframes: from `from` at layer time t0 to `to` at t0 + len
private fun fade(prop: Prop, t0: Long, len: Long, from: Double, to: Double): Prop {
    val base = if (prop.animated) prop else toggleAnimated(prop, t0, prop.value)
    return setPropAt(setPropAt(base, t0, listOf(from)), t0 + len, listOf(to))
}

// ---------- tests ----------

object LayersTest {
    fun run(): List<String> {
        val out = ArrayList<String>()
        fun check(name: String, ok: Boolean) {
            out.add((if (ok) "PASS " else "FAIL ") + name)
        }
        fun near(a: Pair<Double, Double>, b: Pair<Double, Double>): Boolean =
            abs(a.first - b.first) < 1e-6 && abs(a.second - b.second) < 1e-6

        val sec = TICKS_PER_SECOND
        val r = Project().withNewComp("T", 1000, 800, FrameRate.FPS_30, secondsToTicks(10.0), 0xFF000000.toInt())
        var p = r.first
        val cid = r.second
        p = p.withNewNull(cid).withNewSolid(cid, null, 0xFFFF0000.toInt(), 200, 100)
        val nul = p.comps[0].layers[1]
        val solid = p.comps[0].layers[0]
        check("new layers go on top", solid.kind == LayerKind.SOLID && nul.kind == LayerKind.NULL)
        check("a null is 100 x 100 with its anchor in the middle", nul.w == 100 && nul.h == 100 && nul.props[Path.ANCHOR]?.value == listOf(50.0, 50.0))
        check("a solid starts in the middle of the composition", solid.props[Path.POSITION]?.value == listOf(500.0, 400.0) && solid.footageId == -1L)
        check("layers get numbered names", nul.name == "Null 1" && solid.name == "Solid 1")
        val shape = p.withNewShape(cid, ShapeType.ELLIPSE, 0xFF00FF00.toInt()).comps[0].layers[0]
        check("a shape layer has size, roundness and stroke width", shape.kind == LayerKind.SHAPE && shape.props.containsKey(Path.SHAPE_SIZE) && shape.props.containsKey(Path.STROKE_WIDTH))

        // parenting
        p = p.withLayerProp(cid, nul.id, Path.POSITION) { setPropAt(it, 0L, listOf(300.0, 200.0)) }
        p = p.withLayerProp(cid, nul.id, Path.SCALE) { setPropAt(it, 0L, listOf(200.0, 200.0)) }
        p = p.withLayerProp(cid, nul.id, Path.ROTATION) { setPropAt(it, 0L, listOf(30.0)) }
        fun world(pr: Project, id: Long): Pair<Double, Double> {
            val layers = pr.comps[0].layers
            return worldMatrix(layers, layers.first { it.id == id }, 0L).apply(10.0, 20.0)
        }
        val before = world(p, solid.id)
        val parented = p.withLayerParent(cid, solid.id, nul.id, 0L)
        check("parenting keeps the child looking the same", near(before, world(parented, solid.id)))
        val moved = parented.withLayerProp(cid, nul.id, Path.POSITION) { setPropAt(it, 0L, listOf(400.0, 200.0)) }
        val a1 = world(parented, solid.id)
        val a2 = world(moved, solid.id)
        check("the child follows the parent", abs(a2.first - a1.first - 100.0) < 1e-6 && abs(a2.second - a1.second) < 1e-6)
        val free = moved.withLayerParent(cid, solid.id, -1L, 0L)
        check("removing the parent keeps the child where it is", near(a2, world(free, solid.id)) && free.comps[0].layers.first { it.id == solid.id }.parentId == -1L)
        check("a layer can not become the child of its own child", parented.withLayerParent(cid, nul.id, solid.id, 0L) == parented)
        check("opacity is not passed on to children", layerOpacity(parented.comps[0].layers.first { it.id == solid.id }, 0L) == 1.0)
        check("deleting a parent lets go of its children", parented.withLayerRemoved(cid, nul.id).comps[0].layers.first().parentId == -1L)

        // duplicate, rename, sequence
        val dup = p.withLayerDuplicated(cid, solid.id)
        check("duplicate puts the copy above the original", dup.comps[0].layers.size == 3 && dup.comps[0].layers[0].name != dup.comps[0].layers[1].name && dup.comps[0].layers[1].id == solid.id)
        check("rename", p.withLayerRenamed(cid, solid.id, "Box").comps[0].layers[0].name == "Box")
        var q = p.withNewSolid(cid, "A", 0xFF0000FF.toInt(), 100, 100).withNewSolid(cid, "B", 0xFF0000FF.toInt(), 100, 100)
        val a = q.comps[0].layers.first { it.name == "A" }
        val b = q.comps[0].layers.first { it.name == "B" }
        q = q.withLayerTiming(cid, a.id, 0L, sec * 2).withLayerTiming(cid, b.id, 0L, sec * 2)
        val seq = q.withLayersSequenced(cid, listOf(a.id, b.id), 0L, false).comps[0].layers
        check("sequence layers puts them one after the other", seq.first { it.id == b.id }.inPoint == sec * 2 && seq.first { it.id == a.id }.inPoint == 0L)
        val seqO = q.withLayersSequenced(cid, listOf(a.id, b.id), sec / 2, false).comps[0].layers
        check("sequence layers with an overlap", seqO.first { it.id == b.id }.inPoint == sec * 3 / 2)
        val seqX = q.withLayersSequenced(cid, listOf(a.id, b.id), sec / 2, true).comps[0].layers
        check("cross dissolve fades the layers", seqX.first { it.id == a.id }.props[Path.OPACITY]!!.animated && seqX.first { it.id == b.id }.props[Path.OPACITY]!!.animated)
        check("new kinds survive save and load", ProjectJson.decode(ProjectJson.encode(parented)).project == parented)
        return out
    }
}

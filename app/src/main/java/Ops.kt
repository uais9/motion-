package com.motion

// Every edit is a function: old project in, new project out.
// That makes undo/redo simple and safe.

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

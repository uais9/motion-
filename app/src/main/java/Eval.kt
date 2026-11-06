package com.motion

import kotlin.math.abs

// The one function that answers "what is this property's value at time t?".
// The preview, the on-screen handles, the graph and the export will all use it.
// The easing math itself (cubicBezier) lives in Keyframes.kt with the rest of the keyframe logic.
object Eval {
    fun valueAt(prop: Prop, t: Long): List<Double> {
        val k = prop.keys
        if (k.isEmpty()) return prop.value
        if (t <= k.first().time) return k.first().value
        if (t >= k.last().time) return k.last().value
        var i = 0
        while (i < k.size - 1 && t >= k[i + 1].time) i++
        val a = k[i]
        val b = k[i + 1]
        val span = (b.time - a.time).toDouble()
        val u = if (span <= 0.0) 1.0 else (t - a.time) / span
        val f = when (a.interp) {
            Interp.HOLD -> return a.value
            Interp.LINEAR -> u
            Interp.BEZIER -> bezier(u, a.ease)
        }
        // Position and Anchor follow their motion path; f (the speed along it) comes from the ease
        if (prop.spatial) {
            val onPath = spatialPoint(k, i, f)
            if (onPath != null) return onPath
        }
        return a.value.indices.map { j ->
            val from = a.value[j]
            val to = b.value.getOrElse(j) { from }
            from + (to - from) * f
        }
    }

    fun layerValue(layer: Layer, path: String, t: Long): List<Double> {
        val p = layer.props[path] ?: return emptyList()
        return valueAt(p, t)
    }

    fun isActive(layer: Layer, t: Long): Boolean =
        layer.enabled && t >= layer.inPoint && t < layer.outPoint

    // Cubic-bezier easing, same idea as CSS cubic-bezier(x1, y1, x2, y2).
    fun bezier(x: Double, e: List<Double>): Double = cubicBezier(x, e)
}

object SelfTest {
    fun run(): List<String> {
        val out = ArrayList<String>()
        fun check(name: String, ok: Boolean) {
            out.add((if (ok) "PASS " else "FAIL ") + name)
        }
        fun near(a: Double, b: Double): Boolean = abs(a - b) < 1e-4

        val sec = TICKS_PER_SECOND
        val lin = Prop(
            listOf(0.0),
            listOf(Keyframe(0L, listOf(0.0)), Keyframe(sec, listOf(100.0)))
        )
        check("linear halfway is 50", near(Eval.valueAt(lin, sec / 2)[0], 50.0))
        check("before first key", near(Eval.valueAt(lin, -5L)[0], 0.0))
        check("after last key", near(Eval.valueAt(lin, sec * 2)[0], 100.0))

        val hold = Prop(
            listOf(0.0),
            listOf(Keyframe(0L, listOf(0.0), Interp.HOLD), Keyframe(sec, listOf(100.0)))
        )
        check("hold keeps first value", near(Eval.valueAt(hold, sec / 2)[0], 0.0))

        val eased = Prop(
            listOf(0.0),
            listOf(
                Keyframe(0L, listOf(0.0), Interp.BEZIER, listOf(0.42, 0.0, 0.58, 1.0)),
                Keyframe(sec, listOf(100.0))
            )
        )
        check("ease in/out midpoint is 50", near(Eval.valueAt(eased, sec / 2)[0], 50.0))
        check("ease in/out starts slowly", Eval.valueAt(eased, sec / 10)[0] < 10.0)

        for (fr in FrameRate.ALL) {
            check(
                "frame length exact at ${fr.label()}",
                fr.ticksPerFrame * fr.num == TICKS_PER_SECOND * fr.den
            )
        }
        check("ticks to microseconds exact", ticksToUs(TICKS_PER_SECOND) == 1_000_000L)
        check("timecode at 30 fps", timecode(secondsToTicks(61.5), FrameRate.FPS_30) == "00:01:01:15")

        val r = Project().withNewComp(
            "Test", 1080, 1920, FrameRate.FPS_2997, secondsToTicks(5.0), 0xFF000000.toInt()
        )
        var p = r.first
        p = p.withFootage(
            listOf(
                Footage(0L, "a.mp4", "content://x", FootageKind.VIDEO, 1920, 1080, secondsToTicks(3.0))
            )
        )
        p = p.withLayer(r.second, p.footage[0].id)
        val comp = p.comps[0]
        val layer = comp.layers[0]
        val animated = layer.copy(
            props = layer.props + (Path.OPACITY to Prop(
                listOf(100.0),
                listOf(
                    Keyframe(0L, listOf(0.0), Interp.BEZIER),
                    Keyframe(secondsToTicks(1.0), listOf(100.0))
                )
            ))
        )
        p = p.copy(comps = listOf(comp.copy(layers = listOf(animated))))
        val back = ProjectJson.decode(ProjectJson.encode(p)).project
        check("save and load gives the same project", back == p)
        check("new layer is on top and fitted", comp.layers.size == 1 && comp.layers[0].props.containsKey(Path.SCALE))
        return out
    }
}

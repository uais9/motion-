package com.motion

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// A 2D affine transform:  x' = a*x + c*y + e   and   y' = b*x + d*y + f
data class Affine(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val e: Double = 0.0,
    val f: Double = 0.0
) {
    fun apply(x: Double, y: Double): Pair<Double, Double> =
        Pair(a * x + c * y + e, b * x + d * y + f)

    // this.times(o) means: apply o first, then this.
    fun times(o: Affine): Affine = Affine(
        a * o.a + c * o.b,
        b * o.a + d * o.b,
        a * o.c + c * o.d,
        b * o.c + d * o.d,
        a * o.e + c * o.f + e,
        b * o.e + d * o.f + f
    )

    companion object {
        fun translate(x: Double, y: Double) = Affine(1.0, 0.0, 0.0, 1.0, x, y)

        fun scale(sx: Double, sy: Double) = Affine(sx, 0.0, 0.0, sy, 0.0, 0.0)

        // Positive degrees turn clockwise on screen (y points down), like After Effects.
        fun rotate(deg: Double): Affine {
            val r = Math.toRadians(deg)
            val cs = cos(r)
            val sn = sin(r)
            return Affine(cs, sn, -sn, cs, 0.0, 0.0)
        }
    }
}

private fun valueOf(layer: Layer, path: String, layerTime: Long, index: Int, def: Double): Double =
    Eval.layerValue(layer, path, layerTime).getOrNull(index) ?: def

// Maps a layer's own pixels (0..width, 0..height) into composition pixels at comp time t.
// Order, like After Effects: move the anchor to the origin, scale, rotate, then move to position.
// Keyframes live in layer time: layer time = comp time minus the layer's start time.
fun layerMatrix(layer: Layer, compTime: Long): Affine {
    val lt = compTime - layer.startTime
    val ax = valueOf(layer, Path.ANCHOR, lt, 0, 0.0)
    val ay = valueOf(layer, Path.ANCHOR, lt, 1, 0.0)
    val px = valueOf(layer, Path.POSITION, lt, 0, 0.0)
    val py = valueOf(layer, Path.POSITION, lt, 1, 0.0)
    val sx = valueOf(layer, Path.SCALE, lt, 0, 100.0) / 100.0
    val sy = valueOf(layer, Path.SCALE, lt, 1, 100.0) / 100.0
    val rot = valueOf(layer, Path.ROTATION, lt, 0, 0.0)
    return Affine.translate(px, py)
        .times(Affine.rotate(rot))
        .times(Affine.scale(sx, sy))
        .times(Affine.translate(-ax, -ay))
}

// Opacity from 0.0 to 1.0.
fun layerOpacity(layer: Layer, compTime: Long): Double {
    val lt = compTime - layer.startTime
    return (valueOf(layer, Path.OPACITY, lt, 0, 100.0) / 100.0).coerceIn(0.0, 1.0)
}

object GeometryTest {
    fun run(): List<String> {
        val out = ArrayList<String>()

        fun check(name: String, ok: Boolean) {
            out.add((if (ok) "PASS  " else "FAIL  ") + name)
        }

        fun near(a: Double, b: Double): Boolean = abs(a - b) < 1e-6

        fun layerWith(props: Map<String, Prop>, startTime: Long = 0L) =
            Layer(1L, "test", 1L, startTime, 0L, TICKS_PER_SECOND * 10, props)

        val fitted = layerWith(
            mapOf(
                Path.ANCHOR to Prop(listOf(360.0, 800.0)),
                Path.POSITION to Prop(listOf(540.0, 540.0)),
                Path.SCALE to Prop(listOf(67.5, 67.5)),
                Path.ROTATION to Prop(listOf(0.0)),
                Path.OPACITY to Prop(listOf(50.0))
            )
        )
        val m = layerMatrix(fitted, 0L)
        val anchorOut = m.apply(360.0, 800.0)
        check(
            "anchor lands on position",
            near(anchorOut.first, 540.0) && near(anchorOut.second, 540.0)
        )
        val corner = m.apply(0.0, 0.0)
        check(
            "top-left corner is where scale puts it",
            near(corner.first, 297.0) && near(corner.second, 0.0)
        )
        check("opacity 50 is 0.5", near(layerOpacity(fitted, 0L), 0.5))

        val turned = layerWith(
            mapOf(
                Path.ANCHOR to Prop(listOf(0.0, 0.0)),
                Path.POSITION to Prop(listOf(100.0, 100.0)),
                Path.SCALE to Prop(listOf(100.0, 100.0)),
                Path.ROTATION to Prop(listOf(90.0)),
                Path.OPACITY to Prop(listOf(100.0))
            )
        )
        val r = layerMatrix(turned, 0L).apply(10.0, 0.0)
        check(
            "90 degrees turns clockwise on screen",
            near(r.first, 100.0) && near(r.second, 110.0)
        )

        val sec = TICKS_PER_SECOND
        val moving = layerWith(
            mapOf(
                Path.ANCHOR to Prop(listOf(0.0, 0.0)),
                Path.POSITION to Prop(
                    listOf(0.0, 0.0),
                    listOf(
                        Keyframe(0L, listOf(0.0, 0.0)),
                        Keyframe(sec, listOf(100.0, 200.0))
                    )
                ),
                Path.SCALE to Prop(listOf(100.0, 100.0)),
                Path.ROTATION to Prop(listOf(0.0)),
                Path.OPACITY to Prop(listOf(100.0))
            )
        )
        val half = layerMatrix(moving, sec / 2).apply(0.0, 0.0)
        check(
            "keyframed position halfway",
            near(half.first, 50.0) && near(half.second, 100.0)
        )

        val late = layerWith(moving.props, startTime = sec)
        val shifted = layerMatrix(late, sec + sec / 2).apply(0.0, 0.0)
        check(
            "keyframes follow the layer's start time",
            near(shifted.first, 50.0) && near(shifted.second, 100.0)
        )

        return out
    }
}

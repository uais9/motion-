package com.motion

enum class FootageKind { VIDEO, IMAGE }

enum class Interp { LINEAR, HOLD, BEZIER }

// How a keyframe's two handles are managed (After Effects' Bezier, Auto Bezier, Continuous Bezier):
//  NONE       independent handles (plain Bezier)
//  AUTO       the handles are worked out automatically, so speed is smooth through the keyframe
//  CONTINUOUS speed coming in equals speed going out; the handles are yours to set
// The order matters: the graph's type menu uses the position in this list.
enum class Smooth { NONE, AUTO, CONTINUOUS }

// One keyframe. For BEZIER, "ease" is a cubic-bezier (x1, y1, x2, y2)
// that shapes the segment starting at this keyframe.
data class Keyframe(
    val time: Long,
    val value: List<Double>,
    val interp: Interp = Interp.LINEAR,
    val ease: List<Double> = listOf(0.33, 0.0, 0.67, 1.0),
    val smooth: Smooth = Smooth.NONE
)

// A property is a fixed value, or a list of keyframes that animate it.
data class Prop(
    val value: List<Double>,
    val keys: List<Keyframe> = emptyList()
) {
    val animated: Boolean get() = keys.isNotEmpty()
}

// Media in the project (a video or an image). Layers point at footage by id.
data class Footage(
    val id: Long,
    val name: String,
    val uri: String,
    val kind: FootageKind,
    val width: Int,
    val height: Int,
    val durationTicks: Long
)

// Property paths, like EffectCraft's "transform/position".
object Path {
    const val ANCHOR = "transform/anchorPoint"
    const val POSITION = "transform/position"
    const val SCALE = "transform/scale"
    const val ROTATION = "transform/rotation"
    const val OPACITY = "transform/opacity"
}

// A layer in a composition.
// startTime: comp time at which the source's own time 0 sits.
// inPoint / outPoint: the span of comp time the layer is visible.
data class Layer(
    val id: Long,
    val name: String,
    val footageId: Long,
    val startTime: Long,
    val inPoint: Long,
    val outPoint: Long,
    val props: Map<String, Prop>,
    val enabled: Boolean = true
)

// A composition. layers[0] is the TOP layer.
data class Comp(
    val id: Long,
    val name: String,
    val width: Int,
    val height: Int,
    val pixelAspect: Double,
    val frameRate: FrameRate,
    val durationTicks: Long,
    val startTimecode: Long,
    val background: Int,
    val workAreaStart: Long,
    val workAreaDuration: Long,
    val layers: List<Layer>
)

data class ProjectSettings(val name: String = "Untitled")

data class Project(
    val settings: ProjectSettings = ProjectSettings(),
    val footage: List<Footage> = emptyList(),
    val comps: List<Comp> = emptyList(),
    val nextId: Long = 1L
)

fun defaultTransform(f: Footage, c: Comp): Map<String, Prop> {
    val fw = maxOf(f.width, 1).toDouble()
    val fh = maxOf(f.height, 1).toDouble()
    val fit = minOf(c.width / fw, c.height / fh) * 100.0
    return mapOf(
        Path.ANCHOR to Prop(listOf(fw / 2.0, fh / 2.0)),
        Path.POSITION to Prop(listOf(c.width / 2.0, c.height / 2.0)),
        Path.SCALE to Prop(listOf(fit, fit)),
        Path.ROTATION to Prop(listOf(0.0)),
        Path.OPACITY to Prop(listOf(100.0))
    )
}

package com.motion

enum class FootageKind { VIDEO, IMAGE }

enum class Interp { LINEAR, HOLD, BEZIER }

// What a layer is. FOOTAGE shows a video or an image; SOLID is a flat colour; NULL is an invisible layer to parent
// other layers to; SHAPE draws a rectangle or an ellipse.
enum class LayerKind { FOOTAGE, SOLID, NULL, SHAPE }

enum class ShapeType { RECT, ELLIPSE }

// How a keyframe's two handles are managed (After Effects' Bezier, Auto Bezier, Continuous Bezier):
//  NONE       independent handles (plain Bezier)
//  AUTO       the handles are worked out automatically, so speed is smooth through the keyframe
//  CONTINUOUS speed coming in equals speed going out; the handles are yours to set
// The order matters: the graph's type menu uses the position in this list.
enum class Smooth { NONE, AUTO, CONTINUOUS }

// Spatial interpolation: the shape of the motion path through a keyframe of Position or Anchor.
//  LINEAR     straight lines
//  AUTO       a smooth curve, worked out for you (After Effects' default)
//  CONTINUOUS you set the handles, the two stay in line so the path is smooth through the keyframe
//  BEZIER     two independent handles (the path can turn a corner)
enum class PathType { LINEAR, AUTO, CONTINUOUS, BEZIER }

// One keyframe. For BEZIER, "ease" is a cubic-bezier (x1, y1, x2, y2)
// that shapes the segment starting at this keyframe.
data class Keyframe(
    val time: Long,
    val value: List<Double>,
    val interp: Interp = Interp.LINEAR,
    val ease: List<Double> = listOf(0.33, 0.0, 0.67, 1.0),
    val smooth: Smooth = Smooth.NONE,
    // motion path (only for a spatial Prop): inTan / outTan are the handles, as offsets from the keyframe's value
    val pathType: PathType = PathType.AUTO,
    val inTan: List<Double> = emptyList(),
    val outTan: List<Double> = emptyList(),
    // Rove Across Time (spatial properties): the time of this keyframe is worked out so the speed along the path
    // is even between the keyframes before and after it. Lock To Time is false.
    val rove: Boolean = false
)

// A property is a fixed value, or a list of keyframes that animate it.
data class Prop(
    val value: List<Double>,
    val keys: List<Keyframe> = emptyList(),
    val spatial: Boolean = false // Position and Anchor move along a motion path
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
    // with Separate Dimensions, Position is replaced by these two (one number each)
    const val POSITION_X = "transform/positionX"
    const val POSITION_Y = "transform/positionY"
    const val SCALE = "transform/scale"
    const val ROTATION = "transform/rotation"
    const val OPACITY = "transform/opacity"
    // shape layers (rectangle and ellipse)
    const val SHAPE_SIZE = "shape/size"
    const val SHAPE_ROUND = "shape/roundness"
    const val STROKE_WIDTH = "shape/strokeWidth"
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
    val enabled: Boolean = true,
    val kind: LayerKind = LayerKind.FOOTAGE,
    val parentId: Long = -1L, // id of the parent layer in the same composition, -1 = none
    val w: Int = 0, // size in pixels of a solid or a null
    val h: Int = 0,
    val fill: Int = 0xFFFFFFFF.toInt(), // colour of a solid, fill colour of a shape (ARGB)
    val stroke: Int = 0xFFFFFFFF.toInt(), // stroke colour of a shape
    val hasFill: Boolean = true,
    val hasStroke: Boolean = false,
    val shapeType: ShapeType = ShapeType.RECT
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
        Path.ANCHOR to Prop(listOf(fw / 2.0, fh / 2.0), spatial = true),
        Path.POSITION to Prop(listOf(c.width / 2.0, c.height / 2.0), spatial = true),
        Path.SCALE to Prop(listOf(fit, fit)),
        Path.ROTATION to Prop(listOf(0.0)),
        Path.OPACITY to Prop(listOf(100.0))
    )
}

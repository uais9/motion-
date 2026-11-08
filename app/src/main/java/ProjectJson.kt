package com.motion

import org.json.JSONArray
import org.json.JSONObject

class Loaded(val project: Project, val warning: String?)

// Project file: readable JSON headed by who saved it and a schema number,
// like EffectCraft's .ecproj.
object ProjectJson {
    const val SCHEMA = 1
    const val SAVED_BY = "motion 0.1"

    fun encode(p: Project): String {
        val root = JSONObject()
        root.put("savedBy", SAVED_BY)
        root.put("schema", SCHEMA)
        root.put("name", p.settings.name)
        root.put("nextId", p.nextId)
        val fa = JSONArray()
        for (f in p.footage) fa.put(footageToJson(f))
        root.put("footage", fa)
        val ca = JSONArray()
        for (c in p.comps) ca.put(compToJson(c))
        root.put("comps", ca)
        return root.toString(2)
    }

    fun decode(text: String): Loaded {
        val root = JSONObject(text)
        val schema = root.optInt("schema", 1)
        val warning = if (schema > SCHEMA) {
            "Saved by a newer version (schema $schema). Unknown fields are dropped when you save."
        } else {
            null
        }
        val footage = ArrayList<Footage>()
        val fa = root.optJSONArray("footage")
        if (fa != null) {
            for (i in 0 until fa.length()) footage.add(footageFromJson(fa.getJSONObject(i)))
        }
        val comps = ArrayList<Comp>()
        val ca = root.optJSONArray("comps")
        if (ca != null) {
            for (i in 0 until ca.length()) comps.add(compFromJson(ca.getJSONObject(i)))
        }
        val project = Project(
            ProjectSettings(root.optString("name", "Untitled")),
            footage,
            comps,
            root.optLong("nextId", 1L)
        )
        return Loaded(project, warning)
    }

    // ---------- writing ----------

    private fun numsToJson(l: List<Double>): JSONArray {
        val a = JSONArray()
        for (d in l) a.put(d)
        return a
    }

    private fun footageToJson(f: Footage): JSONObject {
        val o = JSONObject()
        o.put("id", f.id)
        o.put("name", f.name)
        o.put("uri", f.uri)
        o.put("kind", f.kind.name)
        o.put("width", f.width)
        o.put("height", f.height)
        o.put("durationTicks", f.durationTicks)
        return o
    }

    private fun compToJson(c: Comp): JSONObject {
        val o = JSONObject()
        o.put("id", c.id)
        o.put("name", c.name)
        o.put("width", c.width)
        o.put("height", c.height)
        o.put("pixelAspect", c.pixelAspect)
        o.put("frameRateNum", c.frameRate.num)
        o.put("frameRateDen", c.frameRate.den)
        o.put("durationTicks", c.durationTicks)
        o.put("startTimecode", c.startTimecode)
        o.put("background", c.background)
        o.put("workAreaStart", c.workAreaStart)
        o.put("workAreaDuration", c.workAreaDuration)
        val la = JSONArray()
        for (l in c.layers) la.put(layerToJson(l))
        o.put("layers", la)
        return o
    }

    private fun layerToJson(l: Layer): JSONObject {
        val o = JSONObject()
        o.put("id", l.id)
        o.put("name", l.name)
        o.put("footageId", l.footageId)
        o.put("startTime", l.startTime)
        o.put("inPoint", l.inPoint)
        o.put("outPoint", l.outPoint)
        o.put("enabled", l.enabled)
        o.put("kind", l.kind.name)
        o.put("parentId", l.parentId)
        o.put("w", l.w)
        o.put("h", l.h)
        o.put("fill", l.fill)
        o.put("stroke", l.stroke)
        o.put("hasFill", l.hasFill)
        o.put("hasStroke", l.hasStroke)
        o.put("shapeType", l.shapeType.name)
        val po = JSONObject()
        for ((path, prop) in l.props) po.put(path, propToJson(prop))
        o.put("props", po)
        return o
    }

    private fun propToJson(p: Prop): JSONObject {
        val o = JSONObject()
        o.put("value", numsToJson(p.value))
        o.put("spatial", p.spatial)
        val ka = JSONArray()
        for (k in p.keys) {
            val ko = JSONObject()
            ko.put("time", k.time)
            ko.put("value", numsToJson(k.value))
            ko.put("interp", k.interp.name)
            ko.put("ease", numsToJson(k.ease))
            ko.put("smooth", k.smooth.name)
            ko.put("pathType", k.pathType.name)
            ko.put("inTan", numsToJson(k.inTan))
            ko.put("outTan", numsToJson(k.outTan))
            ko.put("rove", k.rove)
            ka.put(ko)
        }
        o.put("keys", ka)
        return o
    }

    // ---------- reading ----------

    private fun readNums(a: JSONArray?): List<Double> {
        val out = ArrayList<Double>()
        if (a != null) {
            for (i in 0 until a.length()) out.add(a.getDouble(i))
        }
        return out
    }

    private fun kindOf(s: String): FootageKind {
        return try {
            FootageKind.valueOf(s)
        } catch (e: Exception) {
            FootageKind.VIDEO
        }
    }

    private fun interpOf(s: String): Interp {
        return try {
            Interp.valueOf(s)
        } catch (e: Exception) {
            Interp.LINEAR
        }
    }

    // older project files have no "smooth": those keyframes are plain Bezier
    private fun smoothOf(s: String): Smooth {
        return try {
            Smooth.valueOf(s)
        } catch (e: Exception) {
            Smooth.NONE
        }
    }

    // older project files have no "pathType": the motion path is Auto Bezier, like After Effects
    private fun pathTypeOf(s: String): PathType {
        return try {
            PathType.valueOf(s)
        } catch (e: Exception) {
            PathType.AUTO
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, def: E): E {
        return try {
            java.lang.Enum.valueOf(E::class.java, name)
        } catch (e: Exception) {
            def
        }
    }

    private fun footageFromJson(o: JSONObject): Footage {
        return Footage(
            o.getLong("id"),
            o.optString("name", "media"),
            o.getString("uri"),
            kindOf(o.optString("kind", "VIDEO")),
            o.optInt("width", 0),
            o.optInt("height", 0),
            o.optLong("durationTicks", 0L)
        )
    }

    private fun compFromJson(o: JSONObject): Comp {
        val layers = ArrayList<Layer>()
        val la = o.optJSONArray("layers")
        if (la != null) {
            for (i in 0 until la.length()) layers.add(layerFromJson(la.getJSONObject(i)))
        }
        val duration = o.optLong("durationTicks", 0L)
        return Comp(
            id = o.getLong("id"),
            name = o.optString("name", "Comp"),
            width = o.optInt("width", 1080),
            height = o.optInt("height", 1920),
            pixelAspect = o.optDouble("pixelAspect", 1.0),
            frameRate = FrameRate(o.optInt("frameRateNum", 30), o.optInt("frameRateDen", 1)),
            durationTicks = duration,
            startTimecode = o.optLong("startTimecode", 0L),
            background = o.optInt("background", 0xFF000000.toInt()),
            workAreaStart = o.optLong("workAreaStart", 0L),
            workAreaDuration = o.optLong("workAreaDuration", duration),
            layers = layers
        )
    }

    private fun layerFromJson(o: JSONObject): Layer {
        val props = LinkedHashMap<String, Prop>()
        val po = o.optJSONObject("props")
        if (po != null) {
            for (key in po.keys()) props[key] = propFromJson(po.getJSONObject(key), key == Path.POSITION || key == Path.ANCHOR)
        }
        return Layer(
            id = o.getLong("id"),
            name = o.optString("name", "Layer"),
            footageId = o.getLong("footageId"),
            startTime = o.optLong("startTime", 0L),
            inPoint = o.optLong("inPoint", 0L),
            outPoint = o.optLong("outPoint", 0L),
            props = props,
            enabled = o.optBoolean("enabled", true),
            kind = enumOr(o.optString("kind", "FOOTAGE"), LayerKind.FOOTAGE),
            parentId = o.optLong("parentId", -1L),
            w = o.optInt("w", 0),
            h = o.optInt("h", 0),
            fill = o.optInt("fill", 0xFFFFFFFF.toInt()),
            stroke = o.optInt("stroke", 0xFFFFFFFF.toInt()),
            hasFill = o.optBoolean("hasFill", true),
            hasStroke = o.optBoolean("hasStroke", false),
            shapeType = enumOr(o.optString("shapeType", "RECT"), ShapeType.RECT)
        )
    }

    private fun propFromJson(o: JSONObject, defaultSpatial: Boolean): Prop {
        val keys = ArrayList<Keyframe>()
        val ka = o.optJSONArray("keys")
        if (ka != null) {
            for (i in 0 until ka.length()) {
                val k = ka.getJSONObject(i)
                val ease = readNums(k.optJSONArray("ease"))
                keys.add(
                    Keyframe(
                        k.getLong("time"),
                        readNums(k.optJSONArray("value")),
                        interpOf(k.optString("interp", "LINEAR")),
                        if (ease.size == 4) ease else listOf(0.33, 0.0, 0.67, 1.0),
                        smoothOf(k.optString("smooth", "NONE")),
                        pathTypeOf(k.optString("pathType", "AUTO")),
                        readNums(k.optJSONArray("inTan")),
                        readNums(k.optJSONArray("outTan")),
                        k.optBoolean("rove", false)
                    )
                )
            }
        }
        return Prop(readNums(o.optJSONArray("value")), keys, o.optBoolean("spatial", defaultSpatial))
    }
}

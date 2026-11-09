package com.motion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs

// Effects, like After Effects' Effect menu. An effect is NOT written in Kotlin: it is two kinds of files in the
// folder app/src/main/assets/effects/ :
//   <name>.json   what the effect is called, its settings, and the passes it runs
//   <name>.frag   the shader (GLSL) of a pass
// Drop in a new .json and .frag and the effect shows up in Layer > Add Effect, no Kotlin to change.
//
// The .json:
//   { "id": "tint", "name": "Tint",
//     "params": [ { "key": "amount", "label": "Amount", "dims": 1, "default": [100], "min": 0, "max": 100, "step": 0.5 } ],
//     "pad": "0",                       how many pixels the effect spreads beyond the layer (an expression)
//     "passes": [ { "shader": "tint.frag", "when": "amount > 0",
//                   "inputs": ["last"],  "prev" = what the effect started with, "last" = the pass before (default)
//                   "uniforms": { "uAmount": ["amount / 100"] } } ] }
// Colours are 3 settings (red, green, blue, 0 to 255); a checkbox is 0 or 1.
// Expressions can use: the settings (a setting with more numbers: center[0], center[1]), layerW, layerH (the layer's size
// in pixels), workW, workH (the size of the area the effect works in), pad, pi, the operators + - * / % < > <= >= == != && || !
// and ?:, and the functions min max abs sin cos tan sqrt floor ceil round pow radians degrees clamp mix.
//
// Every shader gets these for free: varying vUv, sampler2D uTex (and uTex2 for a second input), vec2 uWork (the work
// area in layer pixels), float uPad, vec2 uLayer (the layer's size), vec4 uBox (the layer's rectangle in vUv).
// Pictures are premultiplied (rgb already times alpha). vUv (0, 0) is the top left of the work area.
// The effect settings are normal properties of the layer, paths like "fx/12/blurriness", so they get the stopwatch,
// keyframes, the graph editor and everything else. The effects run in the order of the list. The picture is made in
// CompGLView (the preview). The export does not have effects yet.

// ---------- what an effect file describes ----------

class ParamDef(
    val key: String,
    val label: String,
    val dims: Int,
    val default: List<String>, // numbers or expressions (layerW and layerH can be used), one per number
    val lo: Double?,
    val hi: Double?,
    val unitsPerDp: Double
)

class PassDef(
    val shader: String,
    val whenExpr: String?,
    val inputs: List<String>,
    val uniforms: List<Pair<String, List<String>>>
)

class EffectDef(
    val id: String,
    val name: String,
    val params: List<ParamDef>,
    val pad: String,
    val passes: List<PassDef>,
    val shaders: Map<String, String> // file name -> source
)

// ---------- loading the effects folder ----------

object EffectRegistry {
    private val defs = LinkedHashMap<String, EffectDef>()
    val errors = ArrayList<String>() // files that could not be read, shown in the self-test

    // Reads every .json in app/src/main/assets/effects, and in any folder inside it (so it still works if GitHub
    // put the files in effects/effects). A shader is looked for next to the .json that names it.
    fun load(context: Context) {
        defs.clear()
        errors.clear()
        val am = context.assets
        val found = ArrayList<String>()
        fun walk(dir: String) {
            val names = try {
                am.list(dir)
            } catch (e: Exception) {
                null
            } ?: return
            for (n in names) {
                val path = "$dir/$n"
                if (n.endsWith(".json")) found.add(path) else if (!n.contains('.')) walk(path)
            }
        }
        walk("effects")
        if (found.isEmpty()) {
            errors.add("No effect .json files found in app/src/main/assets/effects")
            return
        }
        for (path in found.sorted()) {
            try {
                val dir = path.substringBeforeLast('/')
                val text = am.open(path).bufferedReader().use { it.readText() }
                val def = parse(text) { name -> am.open("$dir/$name").bufferedReader().use { it.readText() } }
                defs[def.id] = def
            } catch (e: Exception) {
                errors.add("$path: ${e.message}")
            }
        }
    }

    fun all(): List<EffectDef> = defs.values.toList()

    fun get(id: String): EffectDef? = defs[id]

    // reads one effect .json (readShader gives the text of a .frag file by its name)
    fun parse(text: String, readShader: (String) -> String): EffectDef {
        val o = JSONObject(text)
        val params = ArrayList<ParamDef>()
        val pa = o.optJSONArray("params") ?: JSONArray()
        for (i in 0 until pa.length()) {
            val p = pa.getJSONObject(i)
            val dims = p.optInt("dims", 1).coerceIn(1, 4)
            val da = p.optJSONArray("default")
            val default = ArrayList<String>()
            for (d in 0 until dims) default.add(if (da != null && d < da.length()) da.get(d).toString() else "0")
            params.add(
                ParamDef(
                    p.getString("key"), p.optString("label", p.getString("key")), dims, default,
                    if (p.has("min")) p.getDouble("min") else null,
                    if (p.has("max")) p.getDouble("max") else null,
                    p.optDouble("step", 1.0)
                )
            )
        }
        val shaders = LinkedHashMap<String, String>()
        val passes = ArrayList<PassDef>()
        val ps = o.optJSONArray("passes") ?: JSONArray()
        for (i in 0 until ps.length()) {
            val p = ps.getJSONObject(i)
            val shader = p.getString("shader")
            if (!shaders.containsKey(shader)) shaders[shader] = readShader(shader)
            val inputs = ArrayList<String>()
            val ia = p.optJSONArray("inputs")
            if (ia != null) {
                for (k in 0 until ia.length()) inputs.add(ia.getString(k))
            } else {
                inputs.add("last")
            }
            val uniforms = ArrayList<Pair<String, List<String>>>()
            val uo = p.optJSONObject("uniforms")
            if (uo != null) {
                val names = uo.keys()
                while (names.hasNext()) {
                    val n = names.next()
                    val a = uo.getJSONArray(n)
                    val exprs = ArrayList<String>()
                    for (k in 0 until a.length()) exprs.add(a.get(k).toString())
                    uniforms.add(Pair(n, exprs))
                }
            }
            passes.add(PassDef(shader, if (p.has("when")) p.getString("when") else null, inputs, uniforms))
        }
        return EffectDef(o.getString("id"), o.optString("name", o.getString("id")), params, o.optString("pad", "0"), passes, shaders)
    }
}

// ---------- the little expression language of the effect files ----------

private sealed class Node
private class Num(val v: Double) : Node()
private class Ref(val name: String, val index: Node?) : Node()
private class Neg(val a: Node) : Node()
private class Not(val a: Node) : Node()
private class Bin(val op: String, val a: Node, val b: Node) : Node()
private class Fn(val name: String, val args: List<Node>) : Node()
private class Cond(val c: Node, val a: Node, val b: Node) : Node()

private class Parser(val s: String) {
    var i = 0

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun eat(t: String): Boolean {
        ws()
        if (s.startsWith(t, i)) {
            i += t.length
            return true
        }
        return false
    }

    fun parseAll(): Node {
        val n = cond()
        ws()
        if (i < s.length) throw IllegalArgumentException("unexpected '${s[i]}' in: $s")
        return n
    }

    private fun cond(): Node {
        val c = or()
        if (eat("?")) {
            val a = cond()
            if (!eat(":")) throw IllegalArgumentException("missing ':' in: $s")
            val b = cond()
            return Cond(c, a, b)
        }
        return c
    }

    private fun or(): Node {
        var n = and()
        while (eat("||")) n = Bin("||", n, and())
        return n
    }

    private fun and(): Node {
        var n = cmp()
        while (eat("&&")) n = Bin("&&", n, cmp())
        return n
    }

    private fun cmp(): Node {
        var n = add()
        while (true) {
            val op = listOf("<=", ">=", "==", "!=", "<", ">").firstOrNull { eat(it) } ?: return n
            n = Bin(op, n, add())
        }
    }

    private fun add(): Node {
        var n = mul()
        while (true) {
            ws()
            n = if (eat("+")) Bin("+", n, mul()) else if (eat("-")) Bin("-", n, mul()) else return n
        }
    }

    private fun mul(): Node {
        var n = unary()
        while (true) {
            ws()
            n = if (eat("*")) Bin("*", n, unary()) else if (eat("/")) Bin("/", n, unary()) else if (eat("%")) Bin("%", n, unary()) else return n
        }
    }

    private fun unary(): Node {
        ws()
        if (eat("-")) return Neg(unary())
        if (i < s.length && s[i] == '!' && !s.startsWith("!=", i)) {
            i++
            return Not(unary())
        }
        return primary()
    }

    private fun primary(): Node {
        ws()
        if (i >= s.length) throw IllegalArgumentException("unfinished: $s")
        val c = s[i]
        if (c == '(') {
            i++
            val n = cond()
            if (!eat(")")) throw IllegalArgumentException("missing ')' in: $s")
            return n
        }
        if (c.isDigit() || c == '.') {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return Num(s.substring(st, i).toDouble())
        }
        if (c.isLetter() || c == '_') {
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            val name = s.substring(st, i)
            if (eat("(")) {
                val args = ArrayList<Node>()
                if (!eat(")")) {
                    do {
                        args.add(cond())
                    } while (eat(","))
                    if (!eat(")")) throw IllegalArgumentException("missing ')' in: $s")
                }
                return Fn(name, args)
            }
            if (eat("[")) {
                val idx = cond()
                if (!eat("]")) throw IllegalArgumentException("missing ']' in: $s")
                return Ref(name, idx)
            }
            return Ref(name, null)
        }
        throw IllegalArgumentException("unexpected '$c' in: $s")
    }
}

private fun truth(b: Boolean): Double = if (b) 1.0 else 0.0

private fun ev(n: Node, env: (String, Int) -> Double): Double {
    return when (n) {
        is Num -> n.v
        is Ref -> if (n.name == "pi") PI else env(n.name, if (n.index == null) 0 else ev(n.index, env).toInt())
        is Neg -> -ev(n.a, env)
        is Not -> truth(ev(n.a, env) == 0.0)
        is Cond -> if (ev(n.c, env) != 0.0) ev(n.a, env) else ev(n.b, env)
        is Bin -> {
            val a = ev(n.a, env)
            when (n.op) {
                "&&" -> truth(a != 0.0 && ev(n.b, env) != 0.0)
                "||" -> truth(a != 0.0 || ev(n.b, env) != 0.0)
                else -> {
                    val b = ev(n.b, env)
                    when (n.op) {
                        "+" -> a + b
                        "-" -> a - b
                        "*" -> a * b
                        "/" -> if (b == 0.0) 0.0 else a / b
                        "%" -> if (b == 0.0) 0.0 else a % b
                        "<" -> truth(a < b)
                        ">" -> truth(a > b)
                        "<=" -> truth(a <= b)
                        ">=" -> truth(a >= b)
                        "==" -> truth(a == b)
                        else -> truth(a != b)
                    }
                }
            }
        }
        is Fn -> {
            val v = n.args.map { ev(it, env) }
            val x = v.getOrElse(0) { 0.0 }
            when (n.name) {
                "min" -> v.minOrNull() ?: 0.0
                "max" -> v.maxOrNull() ?: 0.0
                "abs" -> abs(x)
                "sin" -> Math.sin(x)
                "cos" -> Math.cos(x)
                "tan" -> Math.tan(x)
                "sqrt" -> Math.sqrt(maxOf(0.0, x))
                "floor" -> Math.floor(x)
                "ceil" -> Math.ceil(x)
                "round" -> Math.round(x).toDouble()
                "pow" -> Math.pow(x, v.getOrElse(1) { 1.0 })
                "radians" -> Math.toRadians(x)
                "degrees" -> Math.toDegrees(x)
                "clamp" -> x.coerceIn(v.getOrElse(1) { 0.0 }, maxOf(v.getOrElse(1) { 0.0 }, v.getOrElse(2) { 1.0 }))
                "mix" -> x + (v.getOrElse(1) { 0.0 } - x) * v.getOrElse(2) { 0.0 }
                else -> 0.0
            }
        }
    }
}

private val parsed = HashMap<String, Node?>()

// Works out an expression of an effect file. env gives a name (and a number index) its value.
// A mistake in the expression gives 0 (the effect just does nothing).
fun evalExpr(src: String, env: (String, Int) -> Double): Double {
    val node = synchronized(parsed) {
        if (!parsed.containsKey(src)) {
            parsed[src] = try {
                Parser(src).parseAll()
            } catch (e: Exception) {
                null
            }
        }
        parsed[src]
    } ?: return 0.0
    return try {
        ev(node, env)
    } catch (e: Exception) {
        0.0
    }
}

// ---------- settings of effects on layers ----------

fun fxPath(effectId: Long, key: String): String = "fx/$effectId/$key"

// "fx/12/blurriness" -> the name of that setting
fun fxLabel(path: String): String {
    val key = path.substringAfterLast('/')
    for (d in EffectRegistry.all()) {
        val p = d.params.firstOrNull { it.key == key }
        if (p != null) return p.label
    }
    return path
}

// the starting numbers of a setting (layerW and layerH are the layer's size)
fun ParamDef.startValue(layerW: Double, layerH: Double): List<Double> = default.map { e ->
    evalExpr(e) { name, _ -> if (name == "layerW") layerW else if (name == "layerH") layerH else 0.0 }
}

// the value a setting starts with (what Reset puts back), or null if the path is not an effect setting
fun fxDefaultFor(layer: Layer, path: String): List<Double>? {
    if (!path.startsWith("fx/")) return null
    val id = path.split('/').getOrNull(1)?.toLongOrNull() ?: return null
    val e = layer.effects.firstOrNull { it.id == id } ?: return null
    val def = EffectRegistry.get(e.type) ?: return null
    val size = fxLayerSize(layer)
    return def.params.firstOrNull { it.key == path.substringAfterLast('/') }?.startValue(size.first, size.second)
}

// size of a solid, a null or a shape (a shape: its Size now)
fun fxLayerSize(l: Layer): Pair<Double, Double> {
    if (l.kind == LayerKind.SHAPE) {
        val s = l.props[Path.SHAPE_SIZE]?.value ?: return Pair(0.0, 0.0)
        return Pair(s.getOrElse(0) { 0.0 }, s.getOrElse(1) { 0.0 })
    }
    return Pair(l.w.toDouble(), l.h.toDouble())
}

// The size of a layer in its own pixels (a footage layer needs the project for that).
private fun layerSizeOf(p: Project, l: Layer): Pair<Double, Double> {
    if (l.kind == LayerKind.FOOTAGE) {
        val f = p.footage.firstOrNull { it.id == l.footageId }
        return if (f != null) Pair(f.width.toDouble(), f.height.toDouble()) else Pair(0.0, 0.0)
    }
    return fxLayerSize(l)
}

// Adds an effect at the end of the layer's effects (below the others, like After Effects).
fun Project.withEffectAdded(compId: Long, layerId: Long, typeId: String): Project {
    val def = EffectRegistry.get(typeId) ?: return this
    val c = comps.firstOrNull { it.id == compId } ?: return this
    val l = c.layers.firstOrNull { it.id == layerId } ?: return this
    if (l.kind == LayerKind.NULL) return this // a null shows nothing
    val id = nextId
    val size = layerSizeOf(this, l)
    var props = l.props
    for (d in def.params) props = props + (fxPath(id, d.key) to Prop(d.startValue(size.first, size.second)))
    val updated = l.copy(effects = l.effects + EffectInstance(id, typeId), props = props)
    return copy(
        comps = comps.map { cc -> if (cc.id == compId) cc.copy(layers = cc.layers.map { if (it.id == layerId) updated else it }) else cc },
        nextId = nextId + 1
    )
}

// Deletes an effect and its settings (and their keyframes).
fun Project.withEffectRemoved(compId: Long, layerId: Long, effectId: Long): Project = mapComp(compId) { c ->
    c.copy(layers = c.layers.map { l ->
        if (l.id != layerId) {
            l
        } else {
            val prefix = "fx/$effectId/"
            l.copy(effects = l.effects.filter { it.id != effectId }, props = l.props.filterKeys { !it.startsWith(prefix) })
        }
    })
}

// The effect switch: off keeps the settings but the effect does nothing.
fun Project.withEffectEnabled(compId: Long, layerId: Long, effectId: Long, enabled: Boolean): Project = mapComp(compId) { c ->
    c.copy(layers = c.layers.map { l ->
        if (l.id != layerId) l else l.copy(effects = l.effects.map { if (it.id == effectId) it.copy(enabled = enabled) else it })
    })
}

object EffectsTest {
    fun run(): List<String> {
        val out = ArrayList<String>()
        fun check(name: String, ok: Boolean) {
            out.add((if (ok) "PASS " else "FAIL ") + name)
        }
        fun calc(e: String, vararg vars: Pair<String, Double>): Double =
            evalExpr(e) { n, _ -> vars.firstOrNull { it.first == n }?.second ?: 0.0 }
        check("expressions: arithmetic and precedence", calc("1 + 2 * 3") == 7.0 && calc("(1 + 2) * 3") == 9.0 && calc("10 / 4") == 2.5)
        check("expressions: functions", calc("max(1, 5, 3)") == 5.0 && calc("abs(-2)") == 2.0 && Math.abs(calc("radians(180)") - Math.PI) < 1e-9 && calc("clamp(5, 0, 3)") == 3.0)
        check("expressions: comparing and choosing", calc("x > 2 ? 10 : 20", Pair("x", 3.0)) == 10.0 && calc("x > 2 && x < 4", Pair("x", 3.0)) == 1.0 && calc("!(x > 2)", Pair("x", 3.0)) == 0.0)
        check("expressions: a mistake gives 0", calc("1 +") == 0.0 && calc("unknownName") == 0.0)
        check("expressions: a setting with several numbers", evalExpr("c[1] * 2") { n, i -> if (n == "c") (if (i == 1) 7.0 else 1.0) else 0.0 } == 14.0)

        val defs = EffectRegistry.all()
        check("the effects folder is loaded (app/src/main/assets/effects): ${defs.size} effects", defs.isNotEmpty())
        for (e in EffectRegistry.errors) out.add("FAIL effect file: $e")
        if (defs.isEmpty()) return out
        for (d in defs) {
            check("effect ${d.id}: every pass has its shader", d.passes.isNotEmpty() && d.passes.all { d.shaders.containsKey(it.shader) })
        }

        val r = Project().withNewComp("T", 1000, 800, FrameRate.FPS_30, secondsToTicks(10.0), 0xFF000000.toInt())
        val cid = r.second
        var p = r.first.withNewSolid(cid, null, 0xFFFF0000.toInt(), 400, 200)
        val solid = p.comps[0].layers[0]
        val first = defs.first()
        p = p.withEffectAdded(cid, solid.id, first.id)
        p = p.withEffectAdded(cid, solid.id, defs.last().id)
        val l1 = p.comps[0].layers[0]
        check("effects are added in order", l1.effects.size == 2 && l1.effects[0].type == first.id && l1.effects[1].type == defs.last().id)
        val fx = l1.effects.first()
        check("their settings are properties of the layer", first.params.all { l1.props.containsKey(fxPath(fx.id, it.key)) })
        val key = first.params.first().key
        val keyed = p.withLayerProp(cid, solid.id, fxPath(fx.id, key)) { toggleAnimated(it, 0L, it.value) }
        check("a setting can be animated like any property", keyed.comps[0].layers[0].props[fxPath(fx.id, key)]!!.animated)
        check("reset gives back the starting value", fxDefaultFor(l1, fxPath(fx.id, key)) == l1.props[fxPath(fx.id, key)]!!.value)
        val off = p.withEffectEnabled(cid, solid.id, fx.id, false)
        check("an effect can be switched off", !off.comps[0].layers[0].effects.first().enabled)
        check("it survives save and load", ProjectJson.decode(ProjectJson.encode(off)).project == off)
        val gone = p.withEffectRemoved(cid, solid.id, fx.id).comps[0].layers[0]
        check("deleting an effect deletes its settings", gone.effects.size == 1 && gone.props.keys.none { it.startsWith("fx/${fx.id}/") })
        val withNull = p.withNewNull(cid)
        val nul = withNull.comps[0].layers[0]
        check("a null object takes no effects", withNull.withEffectAdded(cid, nul.id, first.id).comps[0].layers[0].effects.isEmpty())
        if (defs.any { it.id == "motion_tile" }) {
            val tp = p.withEffectAdded(cid, solid.id, "motion_tile").comps[0].layers[0]
            val te = tp.effects.last()
            check("motion tile starts with its centre in the middle of the layer", tp.props[fxPath(te.id, "center")]?.value == listOf(200.0, 100.0))
        }
        return out
    }
}

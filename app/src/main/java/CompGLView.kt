package com.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewConfiguration
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// The preview. Draws the composition with OpenGL: the background, then each layer from the
// bottom to the top. A layer is a quad in its own pixels, moved into composition pixels by
// its transform matrix (the same layerMatrix() the export uses), with opacity.
// Images are textures. Videos play in up to 3 MediaPlayers (phones decode only a few at once).
//
// Motion path: when the selected layer has a curved (spatial) Position with 2 or more keyframes,
// its path is drawn over the preview, like After Effects: the path, a dot for every frame (close
// together = slow), the keyframes, and the handles of the segment the playhead is in. Drag a
// keyframe or a handle to reshape the path. It is hidden while playing.
class CompGLView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {

    // motion path editing, all in composition pixels
    var onPathEditStart: (() -> Unit)? = null
    var onPathKey: ((Int, Double, Double) -> Unit)? = null // key index, x, y
    var onPathTangent: ((Int, Boolean, List<Double>) -> Unit)? = null // key index, outgoing handle?, offset from the keyframe
    var onPathEditEnd: (() -> Unit)? = null

    // Motion Sketch: while sketchMode is on, a finger drag on the preview draws a motion path (composition pixels)
    var sketchMode = false
    var onSketchStart: ((Double, Double) -> Unit)? = null
    var onSketchPoint: ((Double, Double) -> Unit)? = null
    var onSketchEnd: (() -> Unit)? = null

    private class Slot {
        @Volatile var surface: Surface? = null
        var texId = 0
        var st: SurfaceTexture? = null
        var player: MediaPlayer? = null
        var layerId = -1L
        @Volatile var frameAvailable = false
        var hasFrame = false
        @Volatile var prepared = false
        var wantPlay = false
        @Volatile var desiredMs = 0L
        var lastSeekMs = -1_000_000L
        var lastSeekAt = 0L
        val stMatrix = FloatArray(16)
    }

    // a rectangle or an ellipse, in the layer's own pixels, centred on 0, 0
    private class ShapeDraw(
        val ellipse: Boolean,
        val w: Float,
        val h: Float,
        val round: Float,
        val strokeW: Float,
        val fill: Int,
        val stroke: Int,
        val hasFill: Boolean,
        val hasStroke: Boolean
    )

    // an effect on a layer, with its settings worked out for this moment
    private class FxDraw(val def: EffectDef, val values: Map<String, List<Double>>)

    private class DrawLayer(
        val kind: FootageKind,
        val footageId: Long,
        val slot: Int,
        val w: Int,
        val h: Int,
        val m: Affine,
        val opacity: Float,
        val selected: Boolean,
        val lk: LayerKind = LayerKind.FOOTAGE,
        val color: Int = 0, // solid colour (ARGB)
        val shape: ShapeDraw? = null,
        val fx: List<FxDraw> = emptyList(),
        // the layer's rectangle in its own pixels (where effects work): top left and size
        val bx: Float = 0f,
        val by: Float = 0f,
        val bw: Float = 0f,
        val bh: Float = 0f
    )

    private class Scene(
        val compW: Int,
        val compH: Int,
        val background: Int,
        val layers: List<DrawLayer>
    )

    // a handle of the motion path: which keyframe, which side, and where it is (composition pixels)
    private class PathHandle(val keyIndex: Int, val isOut: Boolean, val hx: Float, val hy: Float)

    private class PathData(
        val compW: Int,
        val compH: Int,
        val line: FloatArray, // x, y pairs
        val dots: FloatArray, // x, y pairs, one per frame
        val keys: FloatArray, // x, y pairs, one per keyframe
        val seg: Int, // the segment the playhead is in: its two keyframes are white and show handles
        val handles: List<PathHandle>,
        val nowX: Float,
        val nowY: Float,
        val keysLocal: FloatArray, // the keyframe positions as the layer stores them (before the parent's space)
        val inv: Affine // from composition pixels back to the space the layer's values are written in
    )

    private val OES = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
    private val density = resources.displayMetrics.density
    private val slots = Array(3) { Slot() }
    private val bitmaps = ConcurrentHashMap<Long, Bitmap>()
    private val loadingImages = ConcurrentHashMap.newKeySet<Long>()
    private val imageTextures = HashMap<Long, Int>()

    @Volatile private var scene: Scene? = null
    @Volatile private var pathData: PathData? = null
    private var progImage = 0
    private var progVideo = 0
    private var progFlat = 0
    @Volatile private var surfaceW = 1
    @Volatile private var surfaceH = 1

    private val quad = floatBuf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private val outline = floatBuf(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))

    // scratch space for drawing the motion path (surface pixels), reused every frame
    private val ovArr = FloatArray(32768)
    private val ovBuf: FloatBuffer =
        ByteBuffer.allocateDirect(32768 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private val vertexSrc = """
        attribute vec2 aPos;
        uniform vec2 uSize;
        uniform vec4 uAB;
        uniform vec2 uEF;
        uniform vec2 uComp;
        varying vec2 vUv;
        void main() {
            vec2 p = aPos * uSize;
            float x = uAB.x * p.x + uAB.z * p.y + uEF.x;
            float y = uAB.y * p.x + uAB.w * p.y + uEF.y;
            gl_Position = vec4(2.0 * x / uComp.x - 1.0, 1.0 - 2.0 * y / uComp.y, 0.0, 1.0);
            vUv = aPos;
        }
    """

    private val imageFragSrc = """
        precision mediump float;
        varying vec2 vUv;
        uniform sampler2D uTex;
        uniform float uOpacity;
        void main() {
            gl_FragColor = texture2D(uTex, vUv) * uOpacity;
        }
    """

    private val videoFragSrc = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vUv;
        uniform samplerExternalOES uTex;
        uniform mat4 uSTMatrix;
        uniform float uOpacity;
        void main() {
            vec2 t = (uSTMatrix * vec4(vUv.x, 1.0 - vUv.y, 0.0, 1.0)).xy;
            vec4 c = texture2D(uTex, t);
            gl_FragColor = vec4(c.rgb, 1.0) * uOpacity;
        }
    """

    // one flat colour (premultiplied), used for the layer outline and the motion path
    private val flatFragSrc = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """

    private fun floatBuf(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(a).also { it.position(0) }

    init {
        setEGLContextClientVersion(2)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    // ---------- called from the main thread ----------

    fun setData(p: Project, c: Comp?, t: Long, selectedId: Long, playing: Boolean) {
        if (c == null) {
            scene = null
            pathData = null
            val none = HashSet<Long>()
            for (s in slots) {
                if (s.layerId >= 0L && s.layerId !in none) releasePlayer(s)
            }
            return
        }
        // video layers that are visible now, bottom to top
        val visible = ArrayList<Pair<Layer, Footage>>()
        for (i in c.layers.indices.reversed()) {
            val l = c.layers[i]
            if (!Eval.isActive(l, t)) continue
            val f = p.footage.firstOrNull { it.id == l.footageId } ?: continue
            if (f.kind == FootageKind.VIDEO) visible.add(Pair(l, f))
        }
        val slotOf = assignSlots(visible, t, playing)

        val draw = ArrayList<DrawLayer>()
        for (i in c.layers.indices.reversed()) {
            val l = c.layers[i]
            if (!Eval.isActive(l, t)) continue
            // the layer's transform, with all its parents
            val wm = worldMatrix(c.layers, l, t)
            val opacity = layerOpacity(l, t).toFloat()
            val sel = l.id == selectedId
            val fxList = fxOf(l, t)
            if (l.kind == LayerKind.FOOTAGE) {
                val f = p.footage.firstOrNull { it.id == l.footageId } ?: continue
                var slot = -1
                if (f.kind == FootageKind.VIDEO) {
                    slot = slotOf[l.id] ?: -1
                } else {
                    ensureBitmap(f)
                }
                draw.add(
                    DrawLayer(
                        f.kind, f.id, slot, f.width, f.height, wm, opacity, sel,
                        LayerKind.FOOTAGE, 0, null, fxList, 0f, 0f, f.width.toFloat(), f.height.toFloat()
                    )
                )
            } else if (l.kind == LayerKind.SOLID) {
                draw.add(
                    DrawLayer(
                        FootageKind.IMAGE, -1L, -1, l.w, l.h, wm, opacity, sel, LayerKind.SOLID, l.fill,
                        null, fxList, 0f, 0f, l.w.toFloat(), l.h.toFloat()
                    )
                )
            } else if (l.kind == LayerKind.NULL) {
                draw.add(DrawLayer(FootageKind.IMAGE, -1L, -1, l.w, l.h, wm, opacity, sel, LayerKind.NULL))
            } else {
                val lt = t - l.startTime
                val size = Eval.layerValue(l, Path.SHAPE_SIZE, lt)
                val shape = ShapeDraw(
                    l.shapeType == ShapeType.ELLIPSE,
                    size.getOrElse(0) { 0.0 }.toFloat(), size.getOrElse(1) { 0.0 }.toFloat(),
                    Eval.layerValue(l, Path.SHAPE_ROUND, lt).getOrElse(0) { 0.0 }.toFloat(),
                    Eval.layerValue(l, Path.STROKE_WIDTH, lt).getOrElse(0) { 0.0 }.toFloat(),
                    l.fill, l.stroke, l.hasFill, l.hasStroke
                )
                val sbw = shape.w + (if (shape.hasStroke) shape.strokeW else 0f)
                val sbh = shape.h + (if (shape.hasStroke) shape.strokeW else 0f)
                draw.add(
                    DrawLayer(
                        FootageKind.IMAGE, -1L, -1, 1, 1, wm, opacity, sel, LayerKind.SHAPE, 0, shape,
                        fxList, -sbw / 2f, -sbh / 2f, sbw, sbh
                    )
                )
            }
        }
        scene = Scene(c.width, c.height, c.background, draw)
        pathData = if (playing) null else buildPath(c, c.layers.firstOrNull { it.id == selectedId }, t)
    }

    fun pauseAll() {
        for (s in slots) {
            s.wantPlay = false
            val p = s.player ?: continue
            try {
                if (s.prepared && p.isPlaying) p.pause()
            } catch (e: Exception) {
            }
        }
    }

    fun releaseAll() {
        for (s in slots) releasePlayer(s)
    }

    // ---------- motion path ----------

    // The motion path of the layer's Position, or null when there is none to show.
    private fun buildPath(c: Comp, l: Layer?, t: Long): PathData? {
        if (l == null) return null
        val pr = l.props[Path.POSITION] ?: return null
        val keys = pr.keys
        if (!pr.spatial || keys.size < 2 || keys[0].value.size < 2) return null
        val lt = t - l.startTime

        // the shape of the path: every segment, by distance along it (so it does not depend on the timing)
        val line = ArrayList<Float>()
        for (i in 0 until keys.size - 1) {
            val a = keys[i].value
            val b = keys[i + 1].value
            val steps = if (spatialPoint(keys, i, 0.5) == null) 1 else 40
            for (j in 0..steps) {
                if (i > 0 && j == 0) continue
                val f = j.toDouble() / steps
                val pt = spatialPoint(keys, i, f)
                if (pt != null) {
                    line.add(pt[0].toFloat())
                    line.add(pt[1].toFloat())
                } else {
                    line.add((a[0] + (b[0] - a[0]) * f).toFloat())
                    line.add((a[1] + (b[1] - a[1]) * f).toFloat())
                }
            }
        }

        // a dot for every frame
        val dots = ArrayList<Float>()
        val first = keys.first().time
        val last = keys.last().time
        val stepTicks = max(c.frameRate.ticksPerFrame, (last - first) / 400L)
        var tt = first
        while (tt <= last) {
            val v = Eval.valueAt(pr, tt)
            dots.add(v[0].toFloat())
            dots.add(v[1].toFloat())
            tt += stepTicks
        }

        val keyXY = FloatArray(keys.size * 2)
        for ((i, k) in keys.withIndex()) {
            keyXY[i * 2] = k.value[0].toFloat()
            keyXY[i * 2 + 1] = k.value[1].toFloat()
        }

        // the segment the playhead is in
        var seg = 0
        while (seg < keys.size - 2 && lt >= keys[seg + 1].time) seg++

        // handles of its two keyframes (none on a Linear keyframe, none where an Auto handle has no length)
        val handles = ArrayList<PathHandle>()
        for (ki in intArrayOf(seg, seg + 1)) {
            val k = keys[ki]
            if (k.pathType == PathType.LINEAR) continue
            val tan = spatialTangents(keys, ki)
            for (isOut in booleanArrayOf(false, true)) {
                val v = if (isOut) tan.second else tan.first
                val len = Math.hypot(v[0], v[1])
                if (len < 1e-6 && k.pathType == PathType.AUTO) continue
                handles.add(PathHandle(ki, isOut, (k.value[0] + v[0]).toFloat(), (k.value[1] + v[1]).toFloat()))
            }
        }

        val now = Eval.valueAt(pr, lt)
        // a parented layer's values are in its parent's space: bring everything into composition pixels
        val space = parentSpace(c.layers, l, t)
        fun mapped(a: FloatArray): FloatArray {
            val o = FloatArray(a.size)
            var i = 0
            while (i + 1 < a.size) {
                val q = space.apply(a[i].toDouble(), a[i + 1].toDouble())
                o[i] = q.first.toFloat()
                o[i + 1] = q.second.toFloat()
                i += 2
            }
            return o
        }
        val nowC = space.apply(now[0], now[1])
        val handlesC = handles.map {
            val q = space.apply(it.hx.toDouble(), it.hy.toDouble())
            PathHandle(it.keyIndex, it.isOut, q.first.toFloat(), q.second.toFloat())
        }
        return PathData(
            c.width, c.height, mapped(line.toFloatArray()), mapped(dots.toFloatArray()), mapped(keyXY), seg, handlesC,
            nowC.first.toFloat(), nowC.second.toFloat(), keyXY, space.inverse()
        )
    }

    // where the composition sits on the screen: scale, left, top (same maths as onDrawFrame)
    private fun fit(compW: Int, compH: Int): FloatArray {
        val pad = 10f * density
        val availW = width - 2f * pad
        val availH = height - 2f * pad
        val k = min(availW / compW, availH / compH)
        val vw = (compW * k).toInt()
        val vh = (compH * k).toInt()
        return floatArrayOf(k, ((width - vw) / 2).toFloat(), ((height - vh) / 2).toFloat())
    }

    private var dragKey = -1
    private var dragHandle: PathHandle? = null
    private var dragStarted = false
    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (sketchMode) {
            val sc = scene
            if (sc == null || width <= 1) return true
            val f = fit(sc.compW, sc.compH)
            val cx = ((e.x - f[1]) / f[0]).toDouble()
            val cy = ((e.y - f[2]) / f[0]).toDouble()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onSketchStart?.invoke(cx, cy)
                }
                MotionEvent.ACTION_MOVE -> onSketchPoint?.invoke(cx, cy)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onSketchEnd?.invoke()
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragKey = -1
                dragHandle = null
                dragStarted = false
                val pd = pathData ?: return super.onTouchEvent(e)
                if (surfaceW <= 1 || pd.compW <= 0 || pd.compH <= 0) return super.onTouchEvent(e)
                val f = fit(pd.compW, pd.compH)
                val reach = 26f * density
                var best = Float.MAX_VALUE
                for (h in pd.handles) {
                    val d = abs(e.x - (f[1] + h.hx * f[0])) + abs(e.y - (f[2] + h.hy * f[0]))
                    if (d < best && d <= reach) {
                        best = d
                        dragHandle = h
                    }
                }
                if (dragHandle == null) {
                    for (i in 0 until pd.keys.size / 2) {
                        val d = abs(e.x - (f[1] + pd.keys[i * 2] * f[0])) + abs(e.y - (f[2] + pd.keys[i * 2 + 1] * f[0]))
                        if (d < best && d <= reach) {
                            best = d
                            dragKey = i
                        }
                    }
                }
                if (dragHandle == null && dragKey < 0) return super.onTouchEvent(e)
                downX = e.x
                downY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragKey < 0 && dragHandle == null) return super.onTouchEvent(e)
                val pd = pathData ?: return true
                if (!dragStarted) {
                    if (abs(e.x - downX) + abs(e.y - downY) < ViewConfiguration.get(context).scaledTouchSlop) return true
                    dragStarted = true
                    onPathEditStart?.invoke()
                }
                val f = fit(pd.compW, pd.compH)
                // finger position in composition pixels, then in the space the layer's values are written in
                val local = pd.inv.apply(((e.x - f[1]) / f[0]).toDouble(), ((e.y - f[2]) / f[0]).toDouble())
                val cx = local.first
                val cy = local.second
                val h = dragHandle
                if (h != null) {
                    // the handle is an offset from its keyframe
                    val kx = pd.keysLocal[h.keyIndex * 2].toDouble()
                    val ky = pd.keysLocal[h.keyIndex * 2 + 1].toDouble()
                    onPathTangent?.invoke(h.keyIndex, h.isOut, listOf(cx - kx, cy - ky))
                } else {
                    onPathKey?.invoke(dragKey, cx, cy)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val was = dragKey >= 0 || dragHandle != null
                if (dragStarted) onPathEditEnd?.invoke()
                dragKey = -1
                dragHandle = null
                dragStarted = false
                if (!was) return super.onTouchEvent(e)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun ensureBitmap(f: Footage) {
        if (bitmaps.containsKey(f.id) || loadingImages.contains(f.id)) return
        loadingImages.add(f.id)
        val uri = Uri.parse(f.uri)
        val id = f.id
        thread {
            val bmp = decode(uri)
            if (bmp != null) bitmaps[id] = bmp
            loadingImages.remove(id)
        }
    }

    // Decodes at most about 2048 pixels on the long side, to keep memory small.
    private fun decode(uri: Uri): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            var sample = 1
            val longest = max(opts.outWidth, opts.outHeight)
            while (longest / sample > 2048) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, o2)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- video players ----------

    private fun seekExact(mp: MediaPlayer, ms: Long) {
        if (Build.VERSION.SDK_INT >= 26) {
            mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        } else {
            mp.seekTo(ms.toInt())
        }
    }

    private fun releasePlayer(s: Slot) {
        try {
            s.player?.release()
        } catch (e: Exception) {
        }
        s.player = null
        s.layerId = -1L
        s.prepared = false
        s.hasFrame = false
        s.wantPlay = false
        s.lastSeekMs = -1_000_000L
    }

    private fun startPlayer(s: Slot, l: Layer, f: Footage): Boolean {
        val sf = s.surface ?: return false
        s.layerId = l.id
        s.prepared = false
        s.hasFrame = false
        s.desiredMs = 0L
        s.lastSeekMs = -1_000_000L
        val mp = MediaPlayer()
        try {
            mp.setDataSource(context, Uri.parse(f.uri))
            mp.setSurface(sf)
            mp.setVolume(0f, 0f)
            mp.isLooping = false
            mp.setOnPreparedListener { m ->
                s.prepared = true
                seekExact(m, s.desiredMs)
                s.lastSeekMs = s.desiredMs
                s.lastSeekAt = SystemClock.elapsedRealtime()
                if (s.wantPlay) m.start()
            }
            mp.prepareAsync()
            s.player = mp
        } catch (e: Exception) {
            try {
                mp.release()
            } catch (e2: Exception) {
            }
            s.player = null
        }
        return true
    }

    private fun drive(s: Slot, l: Layer, t: Long, playing: Boolean) {
        val desired = max(0L, (t - l.startTime) / TICKS_PER_MS)
        s.desiredMs = desired
        s.wantPlay = playing
        val p = s.player ?: return
        if (!s.prepared) return
        val now = SystemClock.elapsedRealtime()
        try {
            if (playing) {
                if (!p.isPlaying) {
                    seekExact(p, desired)
                    s.lastSeekMs = desired
                    s.lastSeekAt = now
                    p.start()
                } else if (abs(p.currentPosition - desired) > 300L && now - s.lastSeekAt > 800L) {
                    seekExact(p, desired)
                    s.lastSeekAt = now
                }
            } else {
                if (p.isPlaying) p.pause()
                if (abs(desired - s.lastSeekMs) > 15L && now - s.lastSeekAt > 40L) {
                    seekExact(p, desired)
                    s.lastSeekMs = desired
                    s.lastSeekAt = now
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun assignSlots(
        visible: List<Pair<Layer, Footage>>,
        t: Long,
        playing: Boolean
    ): Map<Long, Int> {
        val result = HashMap<Long, Int>()
        val wanted = HashSet<Long>()
        for (v in visible) wanted.add(v.first.id)
        for (s in slots) {
            if (s.layerId >= 0L && s.layerId !in wanted) releasePlayer(s)
        }
        for (v in visible) {
            val l = v.first
            val f = v.second
            var idx = slots.indexOfFirst { it.layerId == l.id }
            if (idx < 0) {
                idx = slots.indexOfFirst { it.layerId < 0L }
                if (idx < 0) continue
                if (!startPlayer(slots[idx], l, f)) continue
            }
            result[l.id] = idx
            drive(slots[idx], l, t, playing)
        }
        return result
    }

    // ---------- OpenGL ----------

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException(GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        return p
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // a new OpenGL context: everything made in the old one is gone
        fxPrograms.clear()
        java.util.Arrays.fill(fxTex, 0)
        java.util.Arrays.fill(fxFbo, 0)
        fxW = 0
        fxH = 0
        progImage = buildProgram(vertexSrc, imageFragSrc)
        progVideo = buildProgram(vertexSrc, videoFragSrc)
        progFlat = buildProgram(vertexSrc, flatFragSrc)
        imageTextures.clear()
        for (s in slots) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            s.texId = t[0]
            GLES20.glBindTexture(OES, s.texId)
            GLES20.glTexParameteri(OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            s.hasFrame = false
            s.frameAvailable = false
            val oldSt = s.st
            val oldSurface = s.surface
            val st = SurfaceTexture(s.texId)
            st.setOnFrameAvailableListener { s.frameAvailable = true }
            val sf = Surface(st)
            s.st = st
            s.surface = sf
            try {
                s.player?.setSurface(sf)
            } catch (e: Exception) {
            }
            try {
                oldSurface?.release()
            } catch (e: Exception) {
            }
            try {
                oldSt?.release()
            } catch (e: Exception) {
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
    }

    private fun imageTexture(id: Long): Int {
        val have = imageTextures[id]
        if (have != null) return have
        val bmp = bitmaps[id] ?: return 0
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        imageTextures[id] = t[0]
        return t[0]
    }

    private fun loc(prog: Int, name: String): Int = GLES20.glGetUniformLocation(prog, name)

    private fun setTransform(prog: Int, sc: Scene, dl: DrawLayer, geometry: FloatBuffer) {
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, geometry)
        GLES20.glUniform2f(loc(prog, "uSize"), dl.w.toFloat(), dl.h.toFloat())
        GLES20.glUniform4f(
            loc(prog, "uAB"),
            dl.m.a.toFloat(), dl.m.b.toFloat(), dl.m.c.toFloat(), dl.m.d.toFloat()
        )
        GLES20.glUniform2f(loc(prog, "uEF"), dl.m.e.toFloat(), dl.m.f.toFloat())
        GLES20.glUniform2f(loc(prog, "uComp"), sc.compW.toFloat(), sc.compH.toFloat())
    }

    // premultiplied colour for the flat program: ARGB colour times the layer's opacity
    private fun setFlatColor(argb: Int, opacity: Float) {
        val a = ((argb ushr 24) and 255) / 255f * opacity
        GLES20.glUniform4f(
            loc(progFlat, "uColor"),
            ((argb shr 16) and 255) / 255f * a, ((argb shr 8) and 255) / 255f * a, (argb and 255) / 255f * a, a
        )
    }

    private fun drawSolid(sc: Scene, dl: DrawLayer) {
        GLES20.glUseProgram(progFlat)
        setTransform(progFlat, sc, dl, quad)
        setFlatColor(dl.color, dl.opacity)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // the outline of a shape as x, y pairs around it (ellipse: 72 points; rectangle: 4 corners, or rounded corners)
    private fun outlinePoints(s: ShapeDraw): FloatArray {
        val hw = s.w / 2f
        val hh = s.h / 2f
        if (s.ellipse) {
            val n = 72
            val out = FloatArray(n * 2)
            for (i in 0 until n) {
                val a = (2.0 * PI * i / n)
                out[i * 2] = (cos(a) * hw).toFloat()
                out[i * 2 + 1] = (sin(a) * hh).toFloat()
            }
            return out
        }
        val r = min(s.round, min(hw, hh)).coerceAtLeast(0f)
        if (r < 0.5f) return floatArrayOf(-hw, -hh, hw, -hh, hw, hh, -hw, hh)
        val seg = 8
        val pts = ArrayList<Float>()
        // centre of each rounded corner and the angle its arc starts at (y points down, so -90 is up)
        val corners = arrayOf(
            floatArrayOf(hw - r, -hh + r, -90f), floatArrayOf(hw - r, hh - r, 0f),
            floatArrayOf(-hw + r, hh - r, 90f), floatArrayOf(-hw + r, -hh + r, 180f)
        )
        for (c in corners) {
            for (j in 0..seg) {
                val a = Math.toRadians(c[2] + 90.0 * j / seg)
                pts.add(c[0] + (r * cos(a)).toFloat())
                pts.add(c[1] + (r * sin(a)).toFloat())
            }
        }
        return pts.toFloatArray()
    }

    // draws the first n floats of ovArr (points in the layer's own pixels) with the layer's transform
    private fun drawLayerGeometry(sc: Scene, dl: DrawLayer, mode: Int, n: Int, argb: Int) {
        if (n < 6) return
        ovBuf.clear()
        ovBuf.put(ovArr, 0, n)
        ovBuf.position(0)
        GLES20.glUseProgram(progFlat)
        setTransform(progFlat, sc, dl, ovBuf)
        setFlatColor(argb, dl.opacity)
        GLES20.glDrawArrays(mode, 0, n / 2)
    }

    private fun drawShape(sc: Scene, dl: DrawLayer) {
        val s = dl.shape ?: return
        if (s.w <= 0f || s.h <= 0f) return
        val ring = outlinePoints(s)
        val n = ring.size / 2
        if (s.hasFill) {
            var k = 0
            ovArr[k++] = 0f
            ovArr[k++] = 0f
            for (i in 0 until n) {
                ovArr[k++] = ring[i * 2]
                ovArr[k++] = ring[i * 2 + 1]
            }
            ovArr[k++] = ring[0]
            ovArr[k++] = ring[1]
            drawLayerGeometry(sc, dl, GLES20.GL_TRIANGLE_FAN, k, s.fill)
        }
        if (s.hasStroke && s.strokeW > 0f) {
            // a strip along the outline, half the width on each side, mitred at the corners
            val half = s.strokeW / 2f
            var k = 0
            for (i in 0..n) {
                val idx = i % n
                val prev = (idx - 1 + n) % n
                val next = (idx + 1) % n
                var e1x = ring[idx * 2] - ring[prev * 2]
                var e1y = ring[idx * 2 + 1] - ring[prev * 2 + 1]
                var e2x = ring[next * 2] - ring[idx * 2]
                var e2y = ring[next * 2 + 1] - ring[idx * 2 + 1]
                val l1 = sqrt(e1x * e1x + e1y * e1y)
                val l2 = sqrt(e2x * e2x + e2y * e2y)
                if (l1 < 1e-6f) {
                    e1x = e2x
                    e1y = e2y
                } else {
                    e1x /= l1
                    e1y /= l1
                }
                if (l2 < 1e-6f) {
                    e2x = e1x
                    e2y = e1y
                } else {
                    e2x /= l2
                    e2y /= l2
                }
                var nx = -e1y + -e2y
                var ny = e1x + e2x
                val nl = sqrt(nx * nx + ny * ny)
                if (nl < 1e-6f) {
                    nx = -e1y
                    ny = e1x
                } else {
                    nx /= nl
                    ny /= nl
                }
                val cosHalf = max(0.3f, nx * -e1y + ny * e1x)
                val ox = nx * half / cosHalf
                val oy = ny * half / cosHalf
                if (k + 4 > ovArr.size) break
                ovArr[k++] = ring[idx * 2] + ox
                ovArr[k++] = ring[idx * 2 + 1] + oy
                ovArr[k++] = ring[idx * 2] - ox
                ovArr[k++] = ring[idx * 2 + 1] - oy
            }
            drawLayerGeometry(sc, dl, GLES20.GL_TRIANGLE_STRIP, k, s.stroke)
        }
    }

    private fun drawLayer(sc: Scene, dl: DrawLayer) {
        when (dl.lk) {
            LayerKind.SOLID -> {
                drawSolid(sc, dl)
                return
            }
            LayerKind.NULL -> return
            LayerKind.SHAPE -> {
                drawShape(sc, dl)
                return
            }
            LayerKind.FOOTAGE -> {}
        }
        if (dl.kind == FootageKind.IMAGE) {
            val tex = imageTexture(dl.footageId)
            if (tex == 0) return
            GLES20.glUseProgram(progImage)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glUniform1i(loc(progImage, "uTex"), 0)
            GLES20.glUniform1f(loc(progImage, "uOpacity"), dl.opacity)
            setTransform(progImage, sc, dl, quad)
        } else {
            if (dl.slot < 0) return
            val s = slots[dl.slot]
            if (!s.hasFrame) return
            GLES20.glUseProgram(progVideo)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(OES, s.texId)
            GLES20.glUniform1i(loc(progVideo, "uTex"), 0)
            GLES20.glUniformMatrix4fv(loc(progVideo, "uSTMatrix"), 1, false, s.stMatrix, 0)
            GLES20.glUniform1f(loc(progVideo, "uOpacity"), dl.opacity)
            setTransform(progVideo, sc, dl, quad)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawOutline(sc: Scene, dl: DrawLayer, r: Float, g: Float, b: Float) {
        GLES20.glUseProgram(progFlat)
        GLES20.glUniform4f(loc(progFlat, "uColor"), r, g, b, 1.0f)
        setTransform(progFlat, sc, dl, outline)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
    }

    // ---------- effects (the .json and .frag files in assets/effects) ----------
    // A layer with effects is drawn in steps: the layer itself into a picture (a little bigger than the layer,
    // so a blur or a shadow has room), then each effect's passes one after the other, then that picture is put
    // into the composition with the layer's transform and opacity.

    private val fxTex = IntArray(3)
    private val fxFbo = IntArray(3)
    private var fxW = 0
    private var fxH = 0
    private val fxPrograms = HashMap<String, Int>()
    private val fxReported = HashSet<String>()

    // called on the main thread when an effect's shader does not compile
    var onFxError: ((String) -> Unit)? = null

    private val fxVertexSrc = """
        attribute vec2 aPos;
        varying vec2 vUv;
        void main() {
            vUv = aPos;
            gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);
        }
    """

    // what every effect shader gets for free (see Effects.kt)
    private val fxHeader = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        varying vec2 vUv;
        uniform sampler2D uTex;
        uniform sampler2D uTex2;
        uniform vec2 uWork;
        uniform float uPad;
        uniform vec2 uLayer;
        uniform vec4 uBox;
    """

    // the effects of a layer, with their settings at this moment (only the ones that are on)
    private fun fxOf(l: Layer, t: Long): List<FxDraw> {
        if (l.effects.isEmpty() || l.kind == LayerKind.NULL) return emptyList()
        val lt = t - l.startTime
        val out = ArrayList<FxDraw>()
        for (e in l.effects) {
            if (!e.enabled) continue
            val def = EffectRegistry.get(e.type) ?: continue
            val vals = HashMap<String, List<Double>>()
            for (p in def.params) vals[p.key] = Eval.layerValue(l, fxPath(e.id, p.key), lt)
            out.add(FxDraw(def, vals))
        }
        return out
    }

    private fun fxEnv(fx: FxDraw, bw: Float, bh: Float, workW: Double, workH: Double, pad: Double): (String, Int) -> Double =
        { name, i ->
            when (name) {
                "layerW" -> bw.toDouble()
                "layerH" -> bh.toDouble()
                "workW" -> workW
                "workH" -> workH
                "pad" -> pad
                else -> fx.values[name]?.getOrElse(i) { 0.0 } ?: 0.0
            }
        }

    private fun fxProgram(def: EffectDef, ps: PassDef): Int {
        val key = def.id + "/" + ps.shader
        val have = fxPrograms[key]
        if (have != null) return have
        var prog = -1
        try {
            prog = buildProgram(fxVertexSrc, fxHeader + (def.shaders[ps.shader] ?: ""))
        } catch (e: Exception) {
            if (fxReported.add(key)) {
                val msg = "The shader " + ps.shader + " of " + def.name + " has an error:\n" + (e.message ?: "")
                post { onFxError?.invoke(msg) }
            }
        }
        fxPrograms[key] = prog
        return prog
    }

    // three pictures to work in (the one being read, the one being written, and a spare)
    private fun ensureFx(w: Int, h: Int): Boolean {
        if (fxW == w && fxH == h && fxFbo[0] != 0) return true
        if (fxFbo[0] != 0) {
            GLES20.glDeleteFramebuffers(3, fxFbo, 0)
            GLES20.glDeleteTextures(3, fxTex, 0)
        }
        GLES20.glGenTextures(3, fxTex, 0)
        GLES20.glGenFramebuffers(3, fxFbo, 0)
        for (i in 0 until 3) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fxTex[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fxFbo[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fxTex[i], 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                fxW = 0
                fxH = 0
                return false
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        fxW = w
        fxH = h
        return true
    }

    // a picture put into the composition as a rectangle w x h (the picture's top left is the rectangle's top left)
    private fun drawTexQuad(sc: Scene, tex: Int, w: Float, h: Float, m: Affine, opacity: Float) {
        GLES20.glUseProgram(progImage)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(loc(progImage, "uTex"), 0)
        GLES20.glUniform1f(loc(progImage, "uOpacity"), opacity)
        val aPos = GLES20.glGetAttribLocation(progImage, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniform2f(loc(progImage, "uSize"), w, h)
        GLES20.glUniform4f(loc(progImage, "uAB"), m.a.toFloat(), m.b.toFloat(), m.c.toFloat(), m.d.toFloat())
        GLES20.glUniform2f(loc(progImage, "uEF"), m.e.toFloat(), m.f.toFloat())
        GLES20.glUniform2f(loc(progImage, "uComp"), sc.compW.toFloat(), sc.compH.toFloat())
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    // runs the passes of one effect. input = the picture it starts from, gives back the picture it ends with.
    private fun runEffect(
        fx: FxDraw, input: Int, texW: Int, texH: Int,
        workW: Double, workH: Double, pad: Double, bw: Float, bh: Float
    ): Int {
        var last = input
        val env = fxEnv(fx, bw, bh, workW, workH, pad)
        for (ps in fx.def.passes) {
            if (ps.whenExpr != null && evalExpr(ps.whenExpr, env) == 0.0) continue
            val prog = fxProgram(fx.def, ps)
            if (prog <= 0) continue
            // "prev" is what the effect started with, "last" is what the pass before made
            val ins = ps.inputs.map { if (it == "prev") input else last }
            var out = 0
            while (out == input || out == last) out++
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fxFbo[out])
            GLES20.glViewport(0, 0, texW, texH)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(prog)
            for (k in ins.indices) {
                if (k > 1) break
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + k)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fxTex[ins[k]])
            }
            GLES20.glUniform1i(loc(prog, "uTex"), 0)
            GLES20.glUniform1i(loc(prog, "uTex2"), 1)
            GLES20.glUniform2f(loc(prog, "uWork"), workW.toFloat(), workH.toFloat())
            GLES20.glUniform1f(loc(prog, "uPad"), pad.toFloat())
            GLES20.glUniform2f(loc(prog, "uLayer"), bw, bh)
            GLES20.glUniform4f(
                loc(prog, "uBox"),
                (pad / workW).toFloat(), (pad / workH).toFloat(), ((pad + bw) / workW).toFloat(), ((pad + bh) / workH).toFloat()
            )
            for (u in ps.uniforms) {
                val v = u.second.map { evalExpr(it, env).toFloat() }
                val l = loc(prog, u.first)
                when (v.size) {
                    1 -> GLES20.glUniform1f(l, v[0])
                    2 -> GLES20.glUniform2f(l, v[0], v[1])
                    3 -> GLES20.glUniform3f(l, v[0], v[1], v[2])
                    4 -> GLES20.glUniform4f(l, v[0], v[1], v[2], v[3])
                }
            }
            val aPos = GLES20.glGetAttribLocation(prog, "aPos")
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            last = out
        }
        return last
    }

    private fun drawWithEffects(sc: Scene, dl: DrawLayer, vx: Int, vy: Int, vw: Int, vh: Int) {
        val bw = dl.bw
        val bh = dl.bh
        if (bw < 1f || bh < 1f) {
            drawLayer(sc, dl)
            return
        }
        // how far the effects spread beyond the layer's edges (in steps of 32 so the pictures are not made again every frame)
        var padRaw = 0.0
        for (fx in dl.fx) padRaw += max(0.0, evalExpr(fx.def.pad, fxEnv(fx, bw, bh, 0.0, 0.0, 0.0)))
        val pad = min(1024.0, ceil(padRaw / 32.0) * 32.0)
        val workW = bw + 2.0 * pad
        val workH = bh + 2.0 * pad
        // big layers are worked on a little smaller (2048 pixels at most)
        val s = min(1.0, min(2048.0 / workW, 2048.0 / workH))
        val texW = max(1, ceil(workW * s).toInt())
        val texH = max(1, ceil(workH * s).toInt())
        if (!ensureFx(texW, texH)) {
            drawLayer(sc, dl)
            return
        }

        // step 1: the layer itself into picture 0. The matrix turns it so that the picture's top is the layer's top.
        val stub = Scene(texW, texH, 0, emptyList())
        val m0 = Affine(s, 0.0, 0.0, -s, s * (pad - dl.bx), texH - s * (pad - dl.by))
        val dl0 = DrawLayer(dl.kind, dl.footageId, dl.slot, dl.w, dl.h, m0, 1f, false, dl.lk, dl.color, dl.shape)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fxFbo[0])
        GLES20.glViewport(0, 0, texW, texH)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawLayer(stub, dl0)

        // step 2: the effects, in order
        var cur = 0
        for (fx in dl.fx) cur = runEffect(fx, cur, texW, texH, workW, workH, pad, bw, bh)

        // step 3: back to the composition
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(vx, vy, vw, vh)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(vx, vy, vw, vh)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val m = dl.m.times(Affine.translate(dl.bx - pad, dl.by - pad))
        drawTexQuad(sc, fxTex[cur], workW.toFloat(), workH.toFloat(), m, dl.opacity)
    }

    // ---------- motion path drawing (surface pixels) ----------

    // draws the first n floats of ovArr (x, y pairs in surface pixels) in one colour
    private fun drawOverlay(mode: Int, n: Int, r: Float, g: Float, b: Float, a: Float) {
        if (n < 4) return
        ovBuf.clear()
        ovBuf.put(ovArr, 0, n)
        ovBuf.position(0)
        GLES20.glUseProgram(progFlat)
        val aPos = GLES20.glGetAttribLocation(progFlat, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, ovBuf)
        GLES20.glUniform2f(loc(progFlat, "uSize"), 1f, 1f)
        GLES20.glUniform4f(loc(progFlat, "uAB"), 1f, 0f, 0f, 1f)
        GLES20.glUniform2f(loc(progFlat, "uEF"), 0f, 0f)
        GLES20.glUniform2f(loc(progFlat, "uComp"), surfaceW.toFloat(), surfaceH.toFloat())
        GLES20.glUniform4f(loc(progFlat, "uColor"), r * a, g * a, b * a, a)
        GLES20.glDrawArrays(mode, 0, n / 2)
    }

    // adds a square (two triangles) centred on cx, cy, returns the new float count
    private fun addQuad(n: Int, cx: Float, cy: Float, h: Float): Int {
        if (n + 12 > ovArr.size) return n
        val x0 = cx - h
        val y0 = cy - h
        val x1 = cx + h
        val y1 = cy + h
        ovArr[n] = x0; ovArr[n + 1] = y0
        ovArr[n + 2] = x1; ovArr[n + 3] = y0
        ovArr[n + 4] = x0; ovArr[n + 5] = y1
        ovArr[n + 6] = x1; ovArr[n + 7] = y0
        ovArr[n + 8] = x1; ovArr[n + 9] = y1
        ovArr[n + 10] = x0; ovArr[n + 11] = y1
        return n + 12
    }

    private fun drawPath(pd: PathData, k: Float, vx: Float, vy: Float) {
        fun sx(x: Float): Float = vx + x * k
        fun sy(y: Float): Float = vy + y * k

        // the path
        var n = 0
        var i = 0
        while (i + 1 < pd.line.size && n + 2 <= ovArr.size) {
            ovArr[n++] = sx(pd.line[i])
            ovArr[n++] = sy(pd.line[i + 1])
            i += 2
        }
        GLES20.glLineWidth(1.6f * density)
        drawOverlay(GLES20.GL_LINE_STRIP, n, 0.49f, 0.72f, 1f, 1f)

        // a dot for every frame: close together = slow, far apart = fast
        n = 0
        i = 0
        while (i + 1 < pd.dots.size) {
            n = addQuad(n, sx(pd.dots[i]), sy(pd.dots[i + 1]), 1.6f * density)
            i += 2
        }
        drawOverlay(GLES20.GL_TRIANGLES, n, 1f, 1f, 1f, 0.6f)

        // handle lines and handles
        n = 0
        for (h in pd.handles) {
            if (n + 4 > ovArr.size) break
            ovArr[n++] = sx(pd.keys[h.keyIndex * 2])
            ovArr[n++] = sy(pd.keys[h.keyIndex * 2 + 1])
            ovArr[n++] = sx(h.hx)
            ovArr[n++] = sy(h.hy)
        }
        GLES20.glLineWidth(1.4f * density)
        drawOverlay(GLES20.GL_LINES, n, 1f, 0.84f, 0.29f, 1f)
        n = 0
        for (h in pd.handles) n = addQuad(n, sx(h.hx), sy(h.hy), 5.5f * density)
        drawOverlay(GLES20.GL_TRIANGLES, n, 1f, 0.84f, 0.29f, 1f)

        // keyframes: grey, the two of the selected segment white
        n = 0
        for (j in 0 until pd.keys.size / 2) {
            if (j == pd.seg || j == pd.seg + 1) continue
            n = addQuad(n, sx(pd.keys[j * 2]), sy(pd.keys[j * 2 + 1]), 4.5f * density)
        }
        drawOverlay(GLES20.GL_TRIANGLES, n, 0.54f, 0.56f, 0.62f, 1f)
        n = 0
        for (j in intArrayOf(pd.seg, pd.seg + 1)) {
            if (j * 2 + 1 >= pd.keys.size) continue
            n = addQuad(n, sx(pd.keys[j * 2]), sy(pd.keys[j * 2 + 1]), 4.5f * density)
        }
        drawOverlay(GLES20.GL_TRIANGLES, n, 1f, 1f, 1f, 1f)

        // where the layer is now
        n = addQuad(0, sx(pd.nowX), sy(pd.nowY), 4f * density)
        drawOverlay(GLES20.GL_TRIANGLES, n, 1f, 0.62f, 0.26f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        for (s in slots) {
            val st = s.st ?: continue
            if (s.frameAvailable) {
                s.frameAvailable = false
                st.updateTexImage()
                st.getTransformMatrix(s.stMatrix)
                s.hasFrame = true
            }
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
        GLES20.glClearColor(0.04f, 0.04f, 0.055f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val sc = scene ?: return
        if (progImage == 0) return
        val pad = 10f * density
        val availW = surfaceW - 2f * pad
        val availH = surfaceH - 2f * pad
        if (availW <= 0f || availH <= 0f) return
        val k = min(availW / sc.compW, availH / sc.compH)
        val vw = (sc.compW * k).toInt()
        val vh = (sc.compH * k).toInt()
        val vx = (surfaceW - vw) / 2
        val vy = (surfaceH - vh) / 2
        GLES20.glViewport(vx, vy, vw, vh)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(vx, vy, vw, vh)
        val bg = sc.background
        GLES20.glClearColor(
            ((bg shr 16) and 255) / 255f,
            ((bg shr 8) and 255) / 255f,
            (bg and 255) / 255f,
            1f
        )
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        for (dl in sc.layers) {
            if (dl.fx.isNotEmpty()) drawWithEffects(sc, dl, vx, vy, vw, vh) else drawLayer(sc, dl)
        }
        GLES20.glLineWidth(2f * density)
        for (dl in sc.layers) {
            if (dl.lk == LayerKind.SHAPE) continue
            // a null object shows as a box, in red (yellow when it is selected), like After Effects
            if (dl.selected) {
                drawOutline(sc, dl, 1.0f, 0.85f, 0.2f)
            } else if (dl.lk == LayerKind.NULL) {
                drawOutline(sc, dl, 0.9f, 0.25f, 0.25f)
            }
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

        // the motion path goes over everything, also outside the composition frame
        val pd = pathData
        if (pd != null) {
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            drawPath(pd, k, vx.toFloat(), vy.toFloat())
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }
}

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
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// The preview. Draws the composition with OpenGL: the background, then each layer from the
// bottom to the top. A layer is a quad in its own pixels, moved into composition pixels by
// its transform matrix (the same layerMatrix() the export uses), with opacity.
// Images are textures. Videos play in up to 3 MediaPlayers (phones decode only a few at once).
class CompGLView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {

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

    private class DrawLayer(
        val kind: FootageKind,
        val footageId: Long,
        val slot: Int,
        val w: Int,
        val h: Int,
        val m: Affine,
        val opacity: Float,
        val selected: Boolean
    )

    private class Scene(
        val compW: Int,
        val compH: Int,
        val background: Int,
        val layers: List<DrawLayer>
    )

    private val OES = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
    private val density = resources.displayMetrics.density
    private val slots = Array(3) { Slot() }
    private val bitmaps = ConcurrentHashMap<Long, Bitmap>()
    private val loadingImages = ConcurrentHashMap.newKeySet<Long>()
    private val imageTextures = HashMap<Long, Int>()
    @Volatile private var scene: Scene? = null

    private var progImage = 0
    private var progVideo = 0
    private var progFlat = 0
    private var surfaceW = 1
    private var surfaceH = 1

    private val quad = floatBuf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
    private val outline = floatBuf(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))

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

    private val flatFragSrc = """
        precision mediump float;
        void main() {
            gl_FragColor = vec4(1.0, 0.85, 0.2, 1.0);
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
            val f = p.footage.firstOrNull { it.id == l.footageId } ?: continue
            var slot = -1
            if (f.kind == FootageKind.VIDEO) {
                slot = slotOf[l.id] ?: -1
            } else {
                ensureBitmap(f)
            }
            draw.add(
                DrawLayer(
                    f.kind, f.id, slot, f.width, f.height,
                    layerMatrix(l, t), layerOpacity(l, t).toFloat(), l.id == selectedId
                )
            )
        }
        scene = Scene(c.width, c.height, c.background, draw)
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

    private fun drawLayer(sc: Scene, dl: DrawLayer) {
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

    private fun drawOutline(sc: Scene, dl: DrawLayer) {
        GLES20.glUseProgram(progFlat)
        setTransform(progFlat, sc, dl, outline)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
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
        for (dl in sc.layers) drawLayer(sc, dl)
        GLES20.glLineWidth(2f * density)
        for (dl in sc.layers) {
            if (dl.selected) drawOutline(sc, dl)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }
}

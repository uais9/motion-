package com.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.view.View
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// The preview. Draws the composition background, then each layer from the bottom
// to the top, each with its transform matrix and opacity.
// Images are drawn for real. Video layers show a placeholder box for now.
class CompView(context: Context) : View(context) {
    private var project: Project = Project()
    private var comp: Comp? = null
    private var timeTicks = 0L
    private var selectedLayerId = -1L

    private val bitmaps = HashMap<Long, Bitmap>()
    private val loading = HashSet<Long>()

    private val density = resources.displayMetrics.density
    private val bitmapPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val fillPaint = Paint()
    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
    }
    private val drawMatrix = Matrix()
    private val values = FloatArray(9)

    fun setData(p: Project, c: Comp?, t: Long, selectedId: Long) {
        project = p
        comp = c
        timeTicks = t
        selectedLayerId = selectedId
        if (c != null) ensureBitmaps(c)
        invalidate()
    }

    private fun setMatrix(m: Affine) {
        values[0] = m.a.toFloat()
        values[1] = m.c.toFloat()
        values[2] = m.e.toFloat()
        values[3] = m.b.toFloat()
        values[4] = m.d.toFloat()
        values[5] = m.f.toFloat()
        values[6] = 0f
        values[7] = 0f
        values[8] = 1f
        drawMatrix.setValues(values)
    }

    private fun ensureBitmaps(c: Comp) {
        for (layer in c.layers) {
            val f = project.footage.firstOrNull { it.id == layer.footageId } ?: continue
            if (f.kind != FootageKind.IMAGE) continue
            if (bitmaps.containsKey(f.id) || loading.contains(f.id)) continue
            loading.add(f.id)
            val uri = Uri.parse(f.uri)
            val id = f.id
            thread {
                val bmp = decode(uri)
                post {
                    loading.remove(id)
                    if (bmp != null) bitmaps[id] = bmp
                    invalidate()
                }
            }
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#0B0B0E"))
        val c = comp
        if (c == null) {
            textPaint.textSize = 14f * density
            canvas.drawText("No composition yet", 16f * density, 32f * density, textPaint)
            return
        }

        val pad = 10f * density
        val availW = width - 2f * pad
        val availH = height - 2f * pad
        if (availW <= 0f || availH <= 0f) return
        val s = min(availW / c.width, availH / c.height)
        val ox = (width - c.width * s) / 2f
        val oy = (height - c.height * s) / 2f

        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        canvas.clipRect(0f, 0f, c.width.toFloat(), c.height.toFloat())
        canvas.drawColor(c.background)

        // layers[0] is the top layer, so draw from the last one up to the first
        for (i in c.layers.indices.reversed()) {
            val layer = c.layers[i]
            if (!Eval.isActive(layer, timeTicks)) continue
            val f = project.footage.firstOrNull { it.id == layer.footageId } ?: continue
            setMatrix(layerMatrix(layer, timeTicks))
            val opacity = layerOpacity(layer, timeTicks)

            canvas.save()
            canvas.concat(drawMatrix)
            val bmp = bitmaps[f.id]
            if (f.kind == FootageKind.IMAGE && bmp != null) {
                bitmapPaint.alpha = (opacity * 255).toInt().coerceIn(0, 255)
                canvas.scale(f.width.toFloat() / bmp.width, f.height.toFloat() / bmp.height)
                canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
            } else {
                fillPaint.color = Color.argb((opacity * 140).toInt(), 90, 110, 160)
                canvas.drawRect(0f, 0f, f.width.toFloat(), f.height.toFloat(), fillPaint)
                val label = if (f.kind == FootageKind.IMAGE) {
                    "loading image..."
                } else {
                    "video preview comes in step 3"
                }
                textPaint.textSize = max(f.width, f.height) / 18f
                canvas.drawText(label, textPaint.textSize, textPaint.textSize * 2f, textPaint)
            }
            canvas.restore()
        }

        // yellow outline around the selected layer
        val sel = c.layers.firstOrNull { it.id == selectedLayerId }
        if (sel != null && Eval.isActive(sel, timeTicks)) {
            val f = project.footage.firstOrNull { it.id == sel.footageId }
            if (f != null) {
                val m = layerMatrix(sel, timeTicks)
                setMatrix(m)
                val det = abs(m.a * m.d - m.b * m.c)
                val k = (s * sqrt(det)).toFloat()
                strokePaint.color = Color.YELLOW
                strokePaint.strokeWidth = if (k > 0.0001f) 2f * density / k else 1f
                canvas.save()
                canvas.concat(drawMatrix)
                canvas.drawRect(0f, 0f, f.width.toFloat(), f.height.toFloat(), strokePaint)
                canvas.restore()
            }
        }
        canvas.restore()

        // border around the composition frame
        strokePaint.color = Color.parseColor("#66FFFFFF")
        strokePaint.strokeWidth = density
        canvas.drawRect(ox, oy, ox + c.width * s, oy + c.height * s, strokePaint)
    }
}

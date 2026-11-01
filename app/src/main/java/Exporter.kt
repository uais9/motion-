package com.motion

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.max

// Exports the composition to an MP4 with Media3 Transformer.
//  - a solid picture in the composition's background colour runs for the whole duration (the base)
//  - every visible layer is its own sequence, stacked on top in layer order
//  - each layer's frames are moved by its transform (anchor, position, scale, rotation) through a
//    MatrixTransformation that reads the same layerMatrix() the preview uses, so keyframes animate
//  - opacity (and its keyframes) is applied by the compositor
@OptIn(UnstableApi::class)
class Exporter(private val activity: Activity) {
    private val progressHolder = ProgressHolder()
    private var transformer: Transformer? = null

    var running = false
        private set

    fun progressText(): String {
        val tr = transformer ?: return "Exporting..."
        val state = tr.getProgress(progressHolder)
        return if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
            "Exporting... ${progressHolder.progress}%  (keep the app open)"
        } else {
            "Exporting..."
        }
    }

    fun cancel() {
        try {
            transformer?.cancel()
        } catch (e: Exception) {
        }
        transformer = null
        running = false
    }

    private fun seqOf(item: EditedMediaItem, gapUs: Long): EditedMediaItemSequence {
        val b = EditedMediaItemSequence.Builder(emptyList<EditedMediaItem>())
        if (gapUs > 0L) b.addGap(gapUs)
        b.addItem(item)
        b.experimentalSetForceVideoTrack(true)
        b.experimentalSetForceAudioTrack(true)
        return b.build()
    }

    // The layer's transform, written for the frame's own coordinates (x and y from -1 to 1).
    // itemUs is the time since the layer's first visible frame.
    private fun matrixAt(l: Layer, f: Footage, w: Int, h: Int, itemUs: Long): android.graphics.Matrix {
        val compTime = l.inPoint + itemUs * TICKS_PER_US
        val m = layerMatrix(l, compTime)
        val fw = max(f.width, 1).toDouble()
        val fh = max(f.height, 1).toDouble()
        // frame coordinates (-1..1) -> the layer's own pixels
        val toPixels = Affine(fw / 2.0, 0.0, 0.0, -fh / 2.0, fw / 2.0, fh / 2.0)
        // composition pixels -> frame coordinates (-1..1)
        val toFrame = Affine(2.0 / w, 0.0, 0.0, -2.0 / h, -1.0, 1.0)
        val a = toFrame.times(m).times(toPixels)
        val out = android.graphics.Matrix()
        out.setValues(
            floatArrayOf(
                a.a.toFloat(), a.c.toFloat(), a.e.toFloat(),
                a.b.toFloat(), a.d.toFloat(), a.f.toFloat(),
                0f, 0f, 1f
            )
        )
        return out
    }

    private fun layerItem(l: Layer, f: Footage, w: Int, h: Int, fps: Int): EditedMediaItem? {
        val uri = Uri.parse(f.uri)
        val effects = Effects(
            emptyList(),
            listOf<Effect>(
                Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_STRETCH_TO_FIT),
                MatrixTransformation { us -> matrixAt(l, f, w, h, us) }
            )
        )
        if (f.kind == FootageKind.VIDEO) {
            val startMs = (l.inPoint - l.startTime) / TICKS_PER_MS
            val endMs = (l.outPoint - l.startTime) / TICKS_PER_MS
            if (endMs <= startMs) return null
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()
            val mi = MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(clipping)
                .build()
            return EditedMediaItem.Builder(mi).setEffects(effects).build()
        }
        val durationUs = ticksToUs(l.outPoint - l.inPoint)
        if (durationUs <= 0L) return null
        val mime = activity.contentResolver.getType(uri) ?: "image/jpeg"
        val mi = MediaItem.Builder().setUri(uri).setMimeType(mime).build()
        return EditedMediaItem.Builder(mi)
            .setDurationUs(durationUs)
            .setFrameRate(fps)
            .setEffects(effects)
            .build()
    }

    // Writes an error and the exceptions behind it, so we can see the real cause.
    private fun describe(e: Throwable?): String {
        var s = ""
        var c: Throwable? = e
        var n = 0
        while (c != null && n < 4) {
            if (s.isNotEmpty()) s += "  <-  "
            s += c.javaClass.simpleName + ": " + (c.message ?: "")
            c = c.cause
            n++
        }
        return s
    }

    fun start(project: Project, comp: Comp, onFinished: (String, Boolean) -> Unit) {
        runExport(project, comp, true, "", onFinished)
    }

    // useBase = true: a solid background picture is the bottom of the stack.
    // If that fails it is tried again without it (the bottom layer then ignores its opacity).
    private fun runExport(
        project: Project,
        comp: Comp,
        useBase: Boolean,
        firstError: String,
        onFinished: (String, Boolean) -> Unit
    ) {
        try {
            val w = comp.width and 1.inv()
            val h = comp.height and 1.inv()
            val fps = Math.round(comp.frameRate.fps).toInt().coerceIn(1, 60)

            val sequences = ArrayList<EditedMediaItemSequence>()
            if (useBase) {
                val baseFile = File(activity.cacheDir, "base_background.png")
                val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(comp.background or 0xFF000000.toInt())
                FileOutputStream(baseFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val baseItem = EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(Uri.fromFile(baseFile)).setMimeType("image/png").build()
                )
                    .setDurationUs(ticksToUs(comp.durationTicks))
                    .setFrameRate(fps)
                    .setEffects(
                        Effects(
                            emptyList(),
                            listOf<Effect>(
                                Presentation.createForWidthAndHeight(
                                    w, h, Presentation.LAYOUT_STRETCH_TO_FIT
                                )
                            )
                        )
                    )
                    .build()
                sequences.add(seqOf(baseItem, 0L))
            }

            val used = ArrayList<Layer>()
            // bottom layer first, so the top layer ends up on top
            for (l in comp.layers.filter { it.enabled }.reversed()) {
                val f = project.footage.firstOrNull { it.id == l.footageId } ?: continue
                val item = layerItem(l, f, w, h, fps) ?: continue
                sequences.add(seqOf(item, ticksToUs(l.inPoint)))
                used.add(l)
            }
            if (used.isEmpty()) {
                onFinished("Nothing to export", false)
                return
            }

            val offset = if (useBase) 1 else 0
            val builder = Composition.Builder(sequences)
            if (sequences.size > 1) {
                builder.setVideoCompositorSettings(object : VideoCompositorSettings {
                    override fun getOutputSize(inputSizes: List<Size>): Size = Size(w, h)

                    override fun getOverlaySettings(
                        inputId: Int,
                        presentationTimeUs: Long
                    ): OverlaySettings {
                        val idx = inputId - offset
                        var alpha = 1f
                        if (idx >= 0 && idx < used.size) {
                            val l = used[idx]
                            val t = presentationTimeUs * TICKS_PER_US
                            alpha = if (t < l.inPoint || t >= l.outPoint) {
                                0f
                            } else {
                                layerOpacity(l, t).toFloat()
                            }
                        }
                        return StaticOverlaySettings.Builder().setAlphaScale(alpha).build()
                    }
                })
            }
            val composition = builder.build()

            val out = File(activity.cacheDir, "export_" + System.currentTimeMillis() + ".mp4")
            if (out.exists()) out.delete()

            val t = Transformer.Builder(activity)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        running = false
                        transformer = null
                        thread {
                            val where = try {
                                saveToGallery(out)
                            } catch (e: Exception) {
                                ""
                            }
                            out.delete()
                            activity.runOnUiThread {
                                if (where.isEmpty()) {
                                    onFinished("Export finished but saving failed", false)
                                } else {
                                    val note = if (useBase) "" else "  (this time without the background base)"
                                    onFinished("Saved to $where$note", true)
                                }
                            }
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        running = false
                        transformer = null
                        val why = "code ${exportException.errorCode}: " + describe(exportException)
                        if (useBase) {
                            runExport(project, comp, false, why, onFinished)
                        } else {
                            onFinished(
                                "Export failed.\n\nWith the base:\n$firstError\n\nWithout the base:\n$why",
                                false
                            )
                        }
                    }
                })
                .build()
            transformer = t
            running = true
            t.start(composition, out.absolutePath)
        } catch (e: Exception) {
            running = false
            transformer = null
            onFinished("Export setup failed: " + describe(e), false)
        }
    }

    private fun saveToGallery(src: File): String {
        val name = "Motion_" + System.currentTimeMillis() + ".mp4"
        val resolver = activity.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Motion")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return ""
            resolver.openOutputStream(uri)?.use { o ->
                src.inputStream().use { it.copyTo(o) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Movies/Motion (open your Gallery)"
        } else {
            val dir = activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: activity.filesDir
            val dest = File(dir, name)
            src.copyTo(dest, true)
            return dest.absolutePath
        }
    }
}

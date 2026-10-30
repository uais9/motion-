package com.motion

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var session: Session
    private lateinit var report: TextView
    private lateinit var compView: CompView
    private lateinit var timeBar: SeekBar
    private lateinit var timeLabel: TextView

    private var currentTime = 0L
    private var selectedLayerId = -1L

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun btn(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 12f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setPadding(0, dp(10), 0, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = Session(File(filesDir, "project.motion.json"))
        session.load()

        val title = TextView(this).apply {
            text = "Motion"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(2))
        }

        compView = CompView(this)

        timeLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(12), dp(4), dp(12), 0)
        }
        timeBar = SeekBar(this).apply {
            max = 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val c = session.currentComp() ?: return
                    currentTime = ticksOfFrame(progress.toLong(), c.frameRate)
                    updateTimeUi()
                    pushToView()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("New comp") { showNewComp() })
            addView(btn("Import") { pickMedia() })
            addView(btn("Add layer") { showAddLayer() })
            addView(btn("Layer") { showLayerSettings() })
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Undo") {
                if (!session.undo()) toast("Nothing to undo")
                refresh()
            })
            addView(btn("Redo") {
                if (!session.redo()) toast("Nothing to redo")
                refresh()
            })
            addView(btn("Self-test") { showSelfTest() })
        }
        report = TextView(this).apply {
            setTextColor(Color.parseColor("#D8D8E0"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(report) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101014"))
            fitsSystemWindows = true
            addView(title)
            addView(compView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.4f))
            addView(timeLabel)
            addView(timeBar)
            addView(row1)
            addView(row2)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        refresh()
    }

    // ---------- preview and time ----------

    private fun pushToView() {
        compView.setData(session.project, session.currentComp(), currentTime, selectedLayerId)
    }

    private fun updateTimeUi() {
        val c = session.currentComp()
        if (c == null) {
            timeLabel.text = "No composition"
        } else {
            timeLabel.text = "${timecode(currentTime, c.frameRate)}  /  ${timecode(c.durationTicks, c.frameRate)}"
        }
    }

    // ---------- new composition ----------

    private fun showNewComp() {
        val presets = listOf(
            "1080 x 1920 (vertical)",
            "1920 x 1080 (landscape)",
            "1080 x 1080 (square)",
            "720 x 1280",
            "3840 x 2160 (4K)",
            "Custom"
        )
        val sizes = listOf(
            Pair(1080, 1920), Pair(1920, 1080), Pair(1080, 1080),
            Pair(720, 1280), Pair(3840, 2160)
        )
        val rates = FrameRate.ALL

        val nameEdit = EditText(this).apply {
            setText("Comp ${session.project.comps.size + 1}")
        }
        val wEdit = EditText(this).apply {
            setText("1080")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val hEdit = EditText(this).apply {
            setText("1920")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val durEdit = EditText(this).apply {
            setText("10")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val presetSpinner = Spinner(this)
        presetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, presets
        )
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (position < sizes.size) {
                    wEdit.setText(sizes[position].first.toString())
                    hEdit.setText(sizes[position].second.toString())
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val rateSpinner = Spinner(this)
        rateSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, rates.map { it.label() }
        )
        rateSpinner.setSelection(4)

        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(wEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(hEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(label("Name"))
            addView(nameEdit)
            addView(label("Size preset"))
            addView(presetSpinner)
            addView(label("Width x Height (pixels)"))
            addView(sizeRow)
            addView(label("Frame rate"))
            addView(rateSpinner)
            addView(label("Duration (seconds)"))
            addView(durEdit)
        }
        val scroll = ScrollView(this).apply { addView(form) }

        AlertDialog.Builder(this)
            .setTitle("New composition")
            .setView(scroll)
            .setPositiveButton("Create") { _, _ ->
                val w = wEdit.text.toString().toIntOrNull() ?: 0
                val h = hEdit.text.toString().toIntOrNull() ?: 0
                val dur = durEdit.text.toString().toDoubleOrNull() ?: 0.0
                if (w < 16 || h < 16 || w > 8192 || h > 8192 || dur <= 0.0) {
                    toast("Check the size and the duration")
                } else {
                    val name = nameEdit.text.toString().ifBlank { "Comp" }
                    val fr = rates[rateSpinner.selectedItemPosition]
                    session.createComp(name, w, h, fr, secondsToTicks(dur))
                    currentTime = 0L
                    selectedLayerId = -1L
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- importing media ----------

    private fun pickMedia() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(i, 1)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            val cd = data.clipData
            if (cd != null) {
                for (i in 0 until cd.itemCount) {
                    uris.add(cd.getItemAt(i).uri)
                }
            } else {
                data.data?.let { uris.add(it) }
            }
            importUris(uris)
        }
    }

    private fun displayName(u: Uri): String {
        try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (n != null) return n
                }
            }
        } catch (e: Exception) {
        }
        return u.lastPathSegment ?: "media"
    }

    private fun importUris(uris: List<Uri>) {
        thread {
            val items = ArrayList<Footage>()
            for (u in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                }
                val mime = contentResolver.getType(u) ?: ""
                val name = displayName(u)
                try {
                    if (mime.startsWith("video/")) {
                        val r = MediaMetadataRetriever()
                        r.setDataSource(this, u)
                        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            ?.toIntOrNull() ?: 0
                        var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            ?.toIntOrNull() ?: 0
                        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                            ?.toIntOrNull() ?: 0
                        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                        r.release()
                        if (rot == 90 || rot == 270) {
                            val t = w
                            w = h
                            h = t
                        }
                        if (w > 0 && h > 0 && ms > 0L) {
                            items.add(
                                Footage(0L, name, u.toString(), FootageKind.VIDEO, w, h, msToTicks(ms))
                            )
                        }
                    } else if (mime.startsWith("image/")) {
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        contentResolver.openInputStream(u)?.use {
                            BitmapFactory.decodeStream(it, null, opts)
                        }
                        if (opts.outWidth > 0 && opts.outHeight > 0) {
                            items.add(
                                Footage(
                                    0L, name, u.toString(), FootageKind.IMAGE,
                                    opts.outWidth, opts.outHeight, 0L
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                }
            }
            runOnUiThread {
                if (items.isEmpty()) {
                    toast("Nothing could be imported")
                } else {
                    session.edit { p -> p.withFootage(items) }
                    refresh()
                    toast("Imported ${items.size}")
                }
            }
        }
    }

    // ---------- layers ----------

    private fun showAddLayer() {
        val comp = session.currentComp()
        if (comp == null) {
            toast("Create a composition first")
            return
        }
        val f = session.project.footage
        if (f.isEmpty()) {
            toast("Import media first")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Add layer")
            .setItems(f.map { it.name }.toTypedArray()) { _, which ->
                session.edit { p -> p.withLayer(comp.id, f[which].id) }
                selectedLayerId = session.currentComp()?.layers?.firstOrNull()?.id ?: -1L
                refresh()
            }
            .show()
    }

    private fun fmtNum(v: Double): String {
        val s = String.format(Locale.US, "%.3f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    private fun numEdit(v: Double): EditText {
        return EditText(this).apply {
            setText(fmtNum(v))
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
        }
    }

    private fun formRow(text: String, vararg edits: EditText): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(label(text))
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (e in edits) {
            line.addView(e, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        row.addView(line)
        return row
    }

    private fun showLayerSettings() {
        val comp = session.currentComp()
        if (comp == null || comp.layers.isEmpty()) {
            toast("Add a layer first")
            return
        }
        val names = comp.layers.mapIndexed { i, l -> "${i + 1}. ${l.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Which layer?")
            .setItems(names) { _, which ->
                val layer = comp.layers[which]
                selectedLayerId = layer.id
                pushToView()
                showLayerForm(comp, layer)
            }
            .show()
    }

    private fun showLayerForm(comp: Comp, layer: Layer) {
        val lt = currentTime - layer.startTime
        fun cur(path: String, i: Int, def: Double): Double =
            Eval.layerValue(layer, path, lt).getOrNull(i) ?: def

        val posX = numEdit(cur(Path.POSITION, 0, 0.0))
        val posY = numEdit(cur(Path.POSITION, 1, 0.0))
        val sclX = numEdit(cur(Path.SCALE, 0, 100.0))
        val sclY = numEdit(cur(Path.SCALE, 1, 100.0))
        val rot = numEdit(cur(Path.ROTATION, 0, 0.0))
        val opa = numEdit(cur(Path.OPACITY, 0, 100.0))
        val ancX = numEdit(cur(Path.ANCHOR, 0, 0.0))
        val ancY = numEdit(cur(Path.ANCHOR, 1, 0.0))

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(formRow("Position X, Y (pixels)", posX, posY))
            addView(formRow("Scale X, Y (percent)", sclX, sclY))
            addView(formRow("Rotation (degrees, clockwise)", rot))
            addView(formRow("Opacity (percent)", opa))
            addView(formRow("Anchor point X, Y (pixels in the picture)", ancX, ancY))
        }
        val scroll = ScrollView(this).apply { addView(form) }

        AlertDialog.Builder(this)
            .setTitle(layer.name)
            .setView(scroll)
            .setPositiveButton("Apply") { _, _ ->
                val parsed = listOf(posX, posY, sclX, sclY, rot, opa, ancX, ancY)
                    .map { it.text.toString().toDoubleOrNull() }
                if (parsed.any { it == null }) {
                    toast("Check the numbers")
                } else {
                    val n = parsed.map { it ?: 0.0 }
                    val values = mapOf(
                        Path.POSITION to listOf(n[0], n[1]),
                        Path.SCALE to listOf(n[2], n[3]),
                        Path.ROTATION to listOf(n[4]),
                        Path.OPACITY to listOf(n[5].coerceIn(0.0, 100.0)),
                        Path.ANCHOR to listOf(n[6], n[7])
                    )
                    session.edit { p -> p.withLayerValues(comp.id, layer.id, values) }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSelfTest() {
        val lines = SelfTest.run() + GeometryTest.run()
        AlertDialog.Builder(this)
            .setTitle("Self-test")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------- the report on screen ----------

    private fun fmtNums(l: List<Double>): String =
        l.joinToString(", ") { String.format(Locale.US, "%.1f", it) }

    private fun refresh() {
        val p = session.project
        val sb = StringBuilder()
        sb.append("PROJECT  ${p.settings.name}   (schema ${ProjectJson.SCHEMA})\n")
        val warn = session.loadWarning
        if (warn != null) sb.append("! $warn\n")
        sb.append("Autosaved after every edit.\n")
        sb.append("Undo steps: ${session.undoCount}   Redo steps: ${session.redoCount}\n\n")

        val c = session.currentComp()
        if (c == null) {
            sb.append("No composition yet. Tap New comp.\n")
        } else {
            val bg = String.format(Locale.US, "#%06X", c.background and 0xFFFFFF)
            sb.append("COMP  \"${c.name}\"\n")
            sb.append("  size         ${c.width} x ${c.height}   pixel aspect ${c.pixelAspect}\n")
            sb.append("  frame rate   ${c.frameRate.label()}   (${c.frameRate.ticksPerFrame} ticks per frame)\n")
            sb.append("  duration     ${timecode(c.durationTicks, c.frameRate)}   (${secondsLabel(c.durationTicks)})\n")
            sb.append("  start        ${timecode(c.startTimecode, c.frameRate)}\n")
            sb.append("  background   $bg\n")
            sb.append(
                "  work area    ${secondsLabel(c.workAreaStart)} to " +
                    "${secondsLabel(c.workAreaStart + c.workAreaDuration)}\n"
            )
        }

        sb.append("\nFOOTAGE (${p.footage.size})\n")
        for (f in p.footage) {
            val dur = if (f.kind == FootageKind.VIDEO) secondsLabel(f.durationTicks) else "still"
            sb.append("  #${f.id} ${f.kind.name.lowercase()}  ${f.name}  ${f.width}x${f.height}  $dur\n")
        }

        if (c != null) {
            sb.append("\nLAYERS, top first (${c.layers.size})\n")
            for ((i, l) in c.layers.withIndex()) {
                val t = currentTime - l.startTime
                sb.append("  ${i + 1}. ${l.name}\n")
                sb.append("     in ${timecode(l.inPoint, c.frameRate)}   out ${timecode(l.outPoint, c.frameRate)}\n")
                sb.append("     position (${fmtNums(Eval.layerValue(l, Path.POSITION, t))})   anchor (${fmtNums(Eval.layerValue(l, Path.ANCHOR, t))})\n")
                sb.append("     scale (${fmtNums(Eval.layerValue(l, Path.SCALE, t))})%   rotation ${fmtNums(Eval.layerValue(l, Path.ROTATION, t))}   opacity ${fmtNums(Eval.layerValue(l, Path.OPACITY, t))}%\n")
            }
        }
        report.text = sb.toString()

        if (c != null) {
            val frames = (c.durationTicks / c.frameRate.ticksPerFrame).toInt().coerceAtLeast(1)
            if (currentTime > c.durationTicks) currentTime = 0L
            timeBar.max = frames
            timeBar.progress = frameOf(currentTime, c.frameRate).toInt().coerceIn(0, frames)
        } else {
            currentTime = 0L
            timeBar.max = 1
            timeBar.progress = 0
        }
        updateTimeUi()
        pushToView()
    }
}

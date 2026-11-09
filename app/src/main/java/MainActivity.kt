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
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var session: Session
    private lateinit var report: TextView
    private lateinit var compView: CompGLView
    private lateinit var layerTimeline: LayerTimelineView
    private lateinit var timeLabel: TextView
    private lateinit var panel: PropertyPanelView
    private lateinit var graph: GraphEditorView
    private lateinit var graphBtn: Button
    private var graphMode = false
    // views that are hidden on the graph screen, so the graph can be big
    private var chromeViews: List<View> = emptyList()
    // keyframes selected on the timeline (layer times) and the copied ones, by property
    private var selectedKeys: Set<Long> = emptySet()
    private var keyClip: Map<String, List<Keyframe>> = emptyMap()
    // layers selected together (Multi-select on), in the order they were tapped
    private val multiIds = LinkedHashSet<Long>()
    private var multiMode = false
    // Motion Sketch: the finger path being drawn (layer time, [x, y]) and the Smoother tolerance to use after
    private val sketchSamples = ArrayList<Pair<Long, List<Double>>>()
    private var sketchActive = false
    private var sketchSmoothing = 1.0
    private lateinit var playBtn: Button
    private lateinit var loopBtn: Button
    private lateinit var exporter: Exporter

    private var currentTime = 0L
    private var selectedLayerId = -1L

    // playback: time follows the real clock and skips frames if the phone is slow
    private var playing = false
    private var loop = false
    private var playStartNanos = 0L
    private var playStartTicks = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!playing) return
            val c = session.currentComp()
            if (c == null) {
                stopPlay()
                return
            }
            val tpf = c.frameRate.ticksPerFrame
            val lastFrame = maxOf(0L, c.durationTicks - tpf)
            val elapsedMs = (frameTimeNanos - playStartNanos) / 1_000_000L
            var t = playStartTicks + elapsedMs * TICKS_PER_MS
            if (t >= c.durationTicks) {
                if (loop) {
                    playStartNanos = frameTimeNanos
                    playStartTicks = 0L
                    t = 0L
                } else {
                    currentTime = lastFrame
                    stopPlay()
                    updateTimeUi()
                    pushToView()
                    return
                }
            }
            currentTime = snapFrame(t, c.frameRate).coerceAtMost(lastFrame)
            updateTimeUi()
            pushToView()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val exportWatcher = object : Runnable {
        override fun run() {
            if (exporter.running) {
                timeLabel.text = exporter.progressText()
                timeLabel.postDelayed(this, 300)
            } else {
                updateTimeUi()
            }
        }
    }

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
        EffectRegistry.load(this) // the effects in app/src/main/assets/effects
        session = Session(File(filesDir, "project.motion.json"))
        session.load()
        exporter = Exporter(this)

        val title = TextView(this).apply {
            text = "Motion"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(2))
        }
        compView = CompGLView(this).apply {
            // dragging the motion path on the preview: no undo step until you let go
            onPathEditStart = {
                stopPlay()
                session.beginGroup()
            }
            onPathKey = { idx, x, y -> moveKeyOnPath(idx, x, y) }
            onPathTangent = { idx, isOut, vec -> moveTangentLive(Path.POSITION, idx, isOut, vec) }
            onPathEditEnd = { endScrub() }
            onSketchStart = { x, y -> sketchStart(x, y) }
            onSketchPoint = { x, y -> sketchPoint(x, y) }
            onSketchEnd = { sketchEnd() }
            onFxError = { msg -> showFxError(msg) }
        }
        timeLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#4DA3FF"))
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setPadding(dp(12), dp(4), dp(12), dp(2))
        }
        layerTimeline = LayerTimelineView(this).apply {
            onScrub = { t -> setTime(t) }
            onSelect = { id -> selectLayer(id) }
            onMove = { id, delta, row -> moveLayer(id, delta, row) }
            onTrim = { id, edge, target -> trimLayer(id, edge, target) }
            onKeySelect = { t, toggle -> selectKey(t, toggle) }
            onKeysMove = { d -> moveSelectedKeys(d) }
            onKeysScale = { anchor, factor -> scaleSelectedKeys(anchor, factor) }
        }
        panel = PropertyPanelView(this).apply {
            onScrubStart = {
                stopPlay()
                session.beginGroup()
            }
            onScrub = { path, v -> scrubValue(path, v) }
            onScrubEnd = { endScrub() }
            onTapValue = { path -> showValueDialog(path) }
            onReset = { path -> resetValue(path) }
            onToggle = { path -> toggleAnim(path) }
            onKey = { path -> keyAction(path) }
            onEffectTap = { id -> showEffectMenu(id) }
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("New comp") { showNewComp() })
            addView(btn("Import") { pickMedia() })
            addView(btn("Layer") { showLayerMenu() })
            addView(btn("Info") { showInfo() })
        }
        graph = GraphEditorView(this).apply {
            visibility = View.GONE
            onEaseStart = {
                stopPlay()
                session.beginGroup()
            }
            onEase = { path, idx, interp, ease -> easeLive(path, idx, interp, ease) }
            onEaseEnd = { endScrub() }
            onPreset = { path, idx, interp, ease -> applyPreset(path, idx, interp, ease) }
            onKeyMove = { path, idx, t, v -> moveKeyLive(path, idx, t, v) }
            onSmooth = { path, idx, sm -> applySmooth(path, idx, sm) }
            onTangent = { path, idx, isOut, vec -> moveTangentLive(path, idx, isOut, vec) }
            onPathType = { path, seg, type -> applyPathType(path, seg, type) }
        }
        graphBtn = btn("Graph") { toggleGraph() }
        playBtn = btn("Play") { togglePlay() }
        loopBtn = btn("Loop") { toggleLoop() }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(playBtn)
            addView(loopBtn)
            addView(btn("Split") { splitSelected() })
            addView(btn("Delete") { deleteSelected() })
            addView(btn("Hide") { toggleHidden() })
        }
        val rowKeys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Easy Ease") { keyEase() })
            addView(btn("Hold") { keyHold() })
            addView(btn("Copy") { keyCopy() })
            addView(btn("Paste") { keyPaste() })
            addView(btn("Del key") { keyDelete() })
            addView(btn("Animation") { showAnimationMenu() })
        }
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Undo") {
                if (!session.undo()) toast("Nothing to undo")
                refresh()
            })
            addView(btn("Redo") {
                if (!session.redo()) toast("Nothing to redo")
                refresh()
            })
            addView(graphBtn)
            addView(btn("Self-test") { showSelfTest() })
            addView(btn("Export") { startExport() })
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
            setBackgroundColor(Color.parseColor("#232323"))
            fitsSystemWindows = true
            addView(title)
            addView(compView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.4f))
            addView(timeLabel)
            addView(layerTimeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(172)))
            addView(
                FrameLayout(this@MainActivity).apply {
                    addView(
                        panel,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    addView(
                        graph,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(184))
            )
            addView(row1)
            addView(row2)
            addView(rowKeys)
            addView(row3)
        }
        setContentView(root)
        chromeViews = listOf(title, row1, row2, rowKeys)
        refresh()
    }

    // ---------- preview and time ----------

    private fun pushToView() {
        val c = session.currentComp()
        compView.setData(session.project, c, currentTime, selectedLayerId, playing)
        pruneKeys(c)
        multiIds.retainAll((c?.layers ?: emptyList()).map { it.id }.toSet())
        layerTimeline.setData(session.project, c, currentTime, selectedLayerId, selectedKeys, multiIds)
        updatePanel()
    }

    private fun updateTimeUi() {
        val c = session.currentComp()
        if (c == null) {
            timeLabel.text = "No composition"
        } else {
            timeLabel.text = "${timecode(currentTime, c.frameRate)} / ${timecode(c.durationTicks, c.frameRate)}"
        }
    }

    private fun setTime(t: Long) {
        stopPlay()
        val c = session.currentComp() ?: return
        currentTime = t.coerceIn(0L, c.durationTicks)
        refresh()
    }

    private fun selectLayer(id: Long) {
        if (id != selectedLayerId) selectedKeys = emptySet()
        // with Multi-select on, tapping a layer adds it to the group (tap again to take it out)
        if (multiMode && id >= 0L) {
            if (!multiIds.remove(id)) multiIds.add(id)
        } else {
            multiIds.clear()
        }
        selectedLayerId = id
        refresh()
    }

    // ---------- play, pause, loop ----------

    private fun togglePlay() {
        if (playing) stopPlay() else startPlay()
    }

    private fun toggleLoop() {
        loop = !loop
        updatePlayButtons()
    }

    private fun startPlay() {
        val c = session.currentComp()
        if (c == null || c.layers.isEmpty()) {
            toast("Add a layer first")
            return
        }
        if (currentTime >= c.durationTicks - c.frameRate.ticksPerFrame) currentTime = 0L
        playing = true
        playStartNanos = System.nanoTime()
        playStartTicks = currentTime
        updatePlayButtons()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopPlay() {
        playing = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        compView.pauseAll()
        updatePlayButtons()
    }

    private fun updatePlayButtons() {
        playBtn.text = if (playing) "Pause" else "Play"
        loopBtn.text = if (loop) "Loop ON" else "Loop"
    }

    override fun onPause() {
        stopPlay()
        compView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        compView.onResume()
    }

    override fun onDestroy() {
        exporter.cancel()
        compView.releaseAll()
        super.onDestroy()
    }

    // ---------- export ----------

    private fun startExport() {
        if (exporter.running) {
            toast("Export already running")
            return
        }
        val c = session.currentComp()
        if (c == null || c.layers.none { it.enabled }) {
            toast("Add a layer first")
            return
        }
        stopPlay()
        if (c.layers.any { l -> l.effects.any { it.enabled } }) toast("Effects are not in the exported video yet")
        exporter.start(session.project, c) { msg, ok ->
            if (ok) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                timeLabel.text = msg
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Export problem")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
        if (exporter.running) timeLabel.post(exportWatcher)
    }

    // ---------- timeline edits ----------

    private fun moveLayer(id: Long, delta: Long, row: Int) {
        val c = session.currentComp() ?: return
        val idx = c.layers.indexOfFirst { it.id == id }
        if (idx < 0) return
        if (delta == 0L && idx == row) return
        session.edit { p -> p.withLayerMoved(c.id, id, delta, row) }
        refresh()
    }

    private fun trimLayer(id: Long, edge: Int, target: Long) {
        val c = session.currentComp() ?: return
        val l = c.layers.firstOrNull { it.id == id } ?: return
        val newIn = if (edge == 1) target else l.inPoint
        val newOut = if (edge == 2) target else l.outPoint
        if (newIn == l.inPoint && newOut == l.outPoint) return
        if (newOut <= newIn) return
        session.edit { p -> p.withLayerTiming(c.id, id, newIn, newOut) }
        refresh()
    }

    private fun splitSelected() {
        val c = session.currentComp()
        val l = c?.layers?.firstOrNull { it.id == selectedLayerId }
        if (c == null || l == null) {
            toast("Tap a layer bar first")
            return
        }
        val tpf = c.frameRate.ticksPerFrame
        val t = snapFrame(currentTime, c.frameRate)
        if (t < l.inPoint + tpf || t > l.outPoint - tpf) {
            toast("Move the playhead inside the layer")
            return
        }
        session.edit { p -> p.withLayerSplit(c.id, l.id, t) }
        refresh()
    }

    private fun deleteSelected() {
        val c = session.currentComp()
        val l = c?.layers?.firstOrNull { it.id == selectedLayerId }
        if (c == null || l == null) {
            toast("Tap a layer bar first")
            return
        }
        session.edit { p -> p.withLayerRemoved(c.id, l.id) }
        selectedLayerId = -1L
        refresh()
    }

    private fun toggleHidden() {
        val c = session.currentComp()
        val l = c?.layers?.firstOrNull { it.id == selectedLayerId }
        if (c == null || l == null) {
            toast("Tap a layer bar first")
            return
        }
        session.edit { p -> p.withLayerEnabled(c.id, l.id, !l.enabled) }
        refresh()
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

    // ---------- property panel and keyframes ----------

    private fun selectedPair(): Pair<Comp, Layer>? {
        val c = session.currentComp() ?: return null
        val l = c.layers.firstOrNull { it.id == selectedLayerId } ?: return null
        return Pair(c, l)
    }

    // Keyframe times are measured from the layer's start (layer time), on whole frames.
    private fun layerTimeNow(c: Comp, l: Layer): Long =
        snapFrame(currentTime, c.frameRate) - l.startTime

    private fun updatePanel() {
        val sel = selectedPair()
        if (sel == null) {
            panel.setData(null, 0L, emptyMap())
            graph.setData(null, 0L)
        } else {
            val lt = layerTimeNow(sel.first, sel.second)
            panel.setData(sel.second, lt, emptyMap())
            graph.setData(sel.second, lt)
        }
    }

    private fun scrubValue(path: String, v: List<Double>) {
        val sel = selectedPair() ?: return
        val c = sel.first
        val l = sel.second
        val lt = layerTimeNow(c, l)
        session.modify { p -> p.withLayerProp(c.id, l.id, path) { pr -> setPropAt(pr, lt, v) } }
        pushToView()
    }

    private fun endScrub() {
        session.save()
        refresh()
    }

    private fun showValueDialog(path: String) {
        val sel = selectedPair() ?: return
        val c = sel.first
        val l = sel.second
        val lt = layerTimeNow(c, l)
        val cur = Eval.layerValue(l, path, lt)
        if (cur.isEmpty()) return
        val edits = cur.map { numEdit(it) }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(formRow(PropertyPanelView.labelOf(path), *edits.toTypedArray()))
        }
        AlertDialog.Builder(this)
            .setTitle(l.name)
            .setView(form)
            .setPositiveButton("Apply") { _, _ ->
                val nums = edits.map { it.text.toString().toDoubleOrNull() }
                if (nums.any { it == null }) {
                    toast("Check the numbers")
                } else {
                    val vals = nums.map { it ?: 0.0 }
                    val fixed = if (path == Path.OPACITY) vals.map { it.coerceIn(0.0, 100.0) } else vals
                    session.edit { p -> p.withLayerProp(c.id, l.id, path) { pr -> setPropAt(pr, lt, fixed) } }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetValue(path: String) {
        val sel = selectedPair() ?: return
        val c = sel.first
        val l = sel.second
        val defaults = layerDefaults(session.project, c, l)
        val d = defaults[path]?.value
            ?: when (path) {
                Path.POSITION_X -> defaults[Path.POSITION]?.value?.take(1)
                Path.POSITION_Y -> defaults[Path.POSITION]?.value?.drop(1)?.take(1)
                else -> fxDefaultFor(l, path)
            }
            ?: return
        val lt = layerTimeNow(c, l)
        session.edit { p -> p.withLayerProp(c.id, l.id, path) { pr -> setPropAt(pr, lt, d) } }
        refresh()
    }

    private fun toggleAnim(path: String) {
        val sel = selectedPair() ?: return
        val c = sel.first
        val l = sel.second
        val pr = l.props[path] ?: return
        val lt = layerTimeNow(c, l)
        val cur = Eval.valueAt(pr, lt)
        session.edit { p -> p.withLayerProp(c.id, l.id, path) { x -> toggleAnimated(x, lt, cur) } }
        refresh()
    }

    private fun keyAction(path: String) {
        val sel = selectedPair() ?: return
        val c = sel.first
        val l = sel.second
        val pr = l.props[path] ?: return
        if (!pr.animated) {
            toast("Tap the stopwatch first")
            return
        }
        val lt = layerTimeNow(c, l)
        val has = pr.keys.any { it.time == lt }
        val cur = Eval.valueAt(pr, lt)
        session.edit { p ->
            p.withLayerProp(c.id, l.id, path) { x ->
                if (has) deleteKeyAt(x, lt) else setPropAt(x, lt, cur)
            }
        }
        refresh()
    }

    // The graph gets its own screen: the title and the button rows are hidden,
    // the graph takes most of the height and the preview stays on top.
    private fun toggleGraph() {
        graphMode = !graphMode
        panel.visibility = if (graphMode) View.GONE else View.VISIBLE
        graph.visibility = if (graphMode) View.VISIBLE else View.GONE
        graphBtn.text = if (graphMode) "Props" else "Graph"
        for (v in chromeViews) v.visibility = if (graphMode) View.GONE else View.VISIBLE
        (graph.parent as View).layoutParams = if (graphMode) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(184))
        }
        // like After Effects: preview on top, then a compact layer list, then the graph editor panel
        (compView.layoutParams as LinearLayout.LayoutParams).weight = if (graphMode) 0.9f else 1.4f
        compView.requestLayout()
        layerTimeline.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, if (graphMode) dp(96) else dp(172)
        )
    }

    private fun easeLive(path: String, idx: Int, interp: Interp, ease: List<Double>) {
        val sel = selectedPair() ?: return
        val c = sel.first
        val l = sel.second
        session.modify { p ->
            p.withLayerProp(c.id, l.id, path) { pr -> withKeyEase(pr, idx, interp, ease) }
        }
        pushToView()
    }

    private fun applyPreset(path: String, idx: Int, interp: Interp, ease: List<Double>) {
        val sel = selectedPair() ?: return
        val c = sel.first
        val l = sel.second
        session.edit { p ->
            p.withLayerProp(c.id, l.id, path) { pr -> withKeyEase(pr, idx, interp, ease) }
        }
        refresh()
    }

    // dragging a keyframe in the graph: time snaps to whole frames, no undo step until you let go
    private fun moveKeyLive(path: String, idx: Int, t: Long, v: List<Double>) {
        val sel = selectedPair() ?: return
        val c = sel.first
        val l = sel.second
        val tpf = c.frameRate.ticksPerFrame
        val lt = snapFrame(t + l.startTime, c.frameRate) - l.startTime
        val vals = if (path == Path.OPACITY) v.map { it.coerceIn(0.0, 100.0) } else v
        session.modify { p ->
            p.withLayerProp(c.id, l.id, path) { pr -> moveKey(pr, idx, lt, vals, tpf) }
        }
        pushToView()
    }

    // dragging a motion path handle: no undo step until you let go
    private fun moveTangentLive(path: String, idx: Int, isOut: Boolean, vec: List<Double>) {
        val sel = selectedPair() ?: return
        val c = sel.first
        val l = sel.second
        session.modify { p ->
            p.withLayerProp(c.id, l.id, path) { pr -> withKeyTangent(pr, idx, isOut, vec) }
        }
        pushToView()
    }

    private fun applyPathType(path: String, seg: Int, type: PathType) {
        val sel = selectedPair() ?: return
        val c = sel.first
        val l = sel.second
        session.edit { p ->
            p.withLayerProp(c.id, l.id, path) { pr -> withSegmentPathType(pr, seg, type) }
        }
        refresh()
    }

    // dragging a Position keyframe on the preview: x and y change, its time stays
    private fun moveKeyOnPath(idx: Int, x: Double, y: Double) {
        val sel = selectedPair() ?: return
        val pr = sel.second.props[Path.POSITION] ?: return
        val k = pr.keys.getOrNull(idx) ?: return
        val v = k.value.toMutableList()
        if (v.size < 2) return
        v[0] = x
        v[1] = y
        moveKeyLive(Path.POSITION, idx, k.time, v)
    }

    private fun applySmooth(path: String, idx: Int, smooth: Smooth) {
        val sel = selectedPair() ?: return
        val c = sel.first
        val l = sel.second
        session.edit { p ->
            p.withLayerProp(c.id, l.id, path) { pr -> withKeySmooth(pr, idx, smooth) }
        }
        refresh()
    }

    // ---------- keyframes selected on the timeline ----------

    // forgets selected keyframes that no longer exist
    private fun pruneKeys(c: Comp?) {
        val l = c?.layers?.firstOrNull { it.id == selectedLayerId }
        if (l == null) {
            selectedKeys = emptySet()
        } else {
            val all = l.props.values.flatMap { pr -> pr.keys.map { it.time } }.toSet()
            selectedKeys = selectedKeys.filter { it in all }.toSet()
        }
    }

    private fun selectKey(t: Long, toggle: Boolean) {
        selectedKeys = when {
            !toggle -> setOf(t)
            t in selectedKeys -> selectedKeys - t
            else -> selectedKeys + t
        }
        pushToView()
    }

    private fun moveSelectedKeys(delta: Long) {
        val sel = selectedPair() ?: return
        val old = selectedKeys
        session.edit { p ->
            p.withLayerAllProps(sel.first.id, sel.second.id) { _, pr -> withKeysMoved(pr, old, delta) }
        }
        selectedKeys = old.map { it + delta }.toSet()
        refresh()
    }

    // runs an edit on the selected keyframes of the selected layer
    private fun keyEdit(change: (Prop) -> Prop) {
        val sel = selectedPair()
        if (sel == null || selectedKeys.isEmpty()) {
            toast("Tap a keyframe diamond on the layer first")
            return
        }
        session.edit { p -> p.withLayerAllProps(sel.first.id, sel.second.id) { _, pr -> change(pr) } }
        refresh()
    }

    private fun keyEase() = keyEdit { pr -> withEasyEase(pr, selectedKeys) }

    private fun keyHold() = keyEdit { pr -> withToggleHold(pr, selectedKeys) }

    private fun keyDelete() {
        keyEdit { pr -> withKeysDeleted(pr, selectedKeys) }
        selectedKeys = emptySet()
        refresh()
    }

    private fun keyCopy() {
        val sel = selectedPair()
        if (sel == null || selectedKeys.isEmpty()) {
            toast("Tap a keyframe diamond on the layer first")
            return
        }
        val copied = HashMap<String, List<Keyframe>>()
        for ((path, pr) in sel.second.props) {
            val ks = keysAtTimes(pr, selectedKeys)
            if (ks.isNotEmpty()) copied[path] = ks
        }
        keyClip = copied
        toast("Copied ${copied.values.sumOf { it.size }} keyframes")
    }

    // pastes at the playhead, onto the same properties of the selected layer
    private fun keyPaste() {
        if (keyClip.isEmpty()) {
            toast("Copy keyframes first")
            return
        }
        val sel = selectedPair()
        if (sel == null) {
            toast("Tap a layer bar first")
            return
        }
        val c = sel.first
        val l = sel.second
        val lt = layerTimeNow(c, l)
        if (lt < 0L) {
            toast("Move the playhead inside the layer")
            return
        }
        val first = keyClip.values.flatten().minOf { it.time }
        session.edit { p ->
            p.withLayerAllProps(c.id, l.id) { path, pr ->
                val ks = keyClip[path]
                if (ks == null) pr else withKeysPasted(pr, ks, lt)
            }
        }
        selectedKeys = keyClip.values.flatten().map { lt + (it.time - first) }.toSet()
        refresh()
    }

    // Animation > Keyframe Interpolation: temporal, spatial (Position and Anchor only) and roving,
    // for the keyframes selected on the timeline
    private fun showInterpolationDialog() {
        val sel = selectedPair()
        if (sel == null || selectedKeys.isEmpty()) {
            toast("Tap a keyframe diamond on the layer first")
            return
        }
        val hasSpatial = sel.second.props.values.any { pr -> pr.spatial && pr.keys.any { it.time in selectedKeys } }
        fun spinner(items: Array<String>): Spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
        }
        val temporal = spinner(arrayOf("Current Settings", "Linear", "Bezier", "Continuous Bezier", "Auto Bezier", "Hold"))
        val spatial = spinner(arrayOf("Current Settings", "Linear", "Bezier", "Continuous Bezier", "Auto Bezier"))
        val roving = spinner(arrayOf("Current Settings", "Rove Across Time", "Lock To Time"))
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(label("Temporal Interpolation"))
            addView(temporal)
            if (hasSpatial) {
                addView(label("Spatial Interpolation"))
                addView(spatial)
                addView(label("Roving"))
                addView(roving)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Keyframe Interpolation")
            .setView(form)
            .setPositiveButton("OK") { _, _ ->
                applyInterpolation(
                    temporal.selectedItemPosition,
                    if (hasSpatial) spatial.selectedItemPosition else 0,
                    if (hasSpatial) roving.selectedItemPosition else 0
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // t: 0 = current, then Linear, Bezier, Continuous Bezier, Auto Bezier, Hold
    // s: 0 = current, then Linear, Bezier, Continuous Bezier, Auto Bezier
    // r: 0 = current, 1 = Rove Across Time, 2 = Lock To Time
    private fun applyInterpolation(t: Int, s: Int, r: Int) {
        val sel = selectedPair() ?: return
        val times = selectedKeys
        val spatialTypes = arrayOf(PathType.LINEAR, PathType.BEZIER, PathType.CONTINUOUS, PathType.AUTO)
        session.edit { p ->
            p.withLayerAllProps(sel.first.id, sel.second.id) { _, pr ->
                var x = pr
                if (t > 0) x = withTemporalType(x, times, TemporalType.values()[t - 1])
                if (s > 0 && x.spatial) x = withSpatialType(x, times, spatialTypes[s - 1])
                if (r > 0 && x.spatial) x = withRoving(x, times, r == 1)
                x
            }
        }
        // roving keyframes change their time, so the selection is let go
        if (r == 1) selectedKeys = emptySet()
        refresh()
    }

    // ---------- Animation menu (like After Effects' Animation menu) ----------

    private fun showAnimationMenu() {
        val items = arrayOf(
            "Keyframe Interpolation…", "Rove Across Time", "Toggle Hold Keyframe",
            "Easy Ease", "Easy Ease In", "Easy Ease Out",
            "Time-Reverse Keyframes", "Scale Keyframes in Time…", "Exponential Scale",
            "Smoother…", "Wiggler…", "Motion Sketch…",
            "Select All Keyframes", "Select Keyframes After", "Select Keyframes Before",
            "Separate Dimensions (Position)"
        )
        AlertDialog.Builder(this)
            .setTitle("Animation")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showInterpolationDialog()
                    1 -> toggleRove()
                    2 -> keyHold()
                    3 -> keyEase()
                    4 -> keyEdit { pr -> withEasyEase(pr, selectedKeys, 1) }
                    5 -> keyEdit { pr -> withEasyEase(pr, selectedKeys, 2) }
                    6 -> keyEdit { pr -> withKeysReversed(pr, selectedKeys) }
                    7 -> showScaleKeysDialog()
                    8 -> exponentialScale()
                    9 -> showSmootherDialog()
                    10 -> showWigglerDialog()
                    11 -> showMotionSketchDialog()
                    12 -> selectKeysWhere { _ -> true }
                    13 -> selectKeysWhere { t -> t > (selectedKeys.maxOrNull() ?: Long.MAX_VALUE) }
                    14 -> selectKeysWhere { t -> t < (selectedKeys.minOrNull() ?: Long.MIN_VALUE) }
                    else -> toggleSeparate()
                }
            }
            .show()
    }

    // Animation > Separate Dimensions: Position <-> X Position and Y Position
    private fun toggleSeparate() {
        val sel = selectedPair()
        if (sel == null) {
            toast("Tap a layer bar first")
            return
        }
        val separated = sel.second.props.containsKey(Path.POSITION_X)
        session.edit { p -> p.withSeparatedDimensions(sel.first.id, sel.second.id, !separated) }
        selectedKeys = emptySet()
        toast(if (separated) "Position is one property again" else "Position is now X Position and Y Position")
        refresh()
    }

    private fun allKeyTimes(): Set<Long> {
        val l = selectedPair()?.second ?: return emptySet()
        return l.props.values.flatMap { pr -> pr.keys.map { it.time } }.toSet()
    }

    private fun selectKeysWhere(keep: (Long) -> Boolean) {
        if (selectedPair() == null) {
            toast("Tap a layer bar first")
            return
        }
        selectedKeys = allKeyTimes().filter(keep).toSet()
        pushToView()
    }

    // Animation > Rove Across Time: toggles roving on the selected keyframes of Position and Anchor
    private fun toggleRove() {
        val sel = selectedPair()
        if (sel == null || selectedKeys.isEmpty()) {
            toast("Tap a keyframe diamond on the layer first")
            return
        }
        val anyRove = sel.second.props.values.any { pr -> pr.spatial && pr.keys.any { it.time in selectedKeys && it.rove } }
        applyInterpolation(0, 0, if (anyRove) 2 else 1)
    }

    // stretches or squeezes the selected keyframes in time around anchor (layer time)
    private fun scaleSelectedKeys(anchor: Long, factor: Double) {
        val sel = selectedPair() ?: return
        val old = selectedKeys
        val tpf = sel.first.frameRate.ticksPerFrame
        session.edit { p ->
            p.withLayerAllProps(sel.first.id, sel.second.id) { _, pr -> withKeysScaled(pr, old, anchor, factor, tpf) }
        }
        selectedKeys = scaledTimes(old.sorted(), anchor, factor, tpf).toSet()
        refresh()
    }

    // the same thing from a menu: scale around the first selected keyframe
    private fun showScaleKeysDialog() {
        if (selectedPair() == null || selectedKeys.size < 2) {
            toast("Select two or more keyframes first")
            return
        }
        val pct = numEdit(100.0)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(formRow("Time scale (%), around the first selected keyframe", pct))
        }
        AlertDialog.Builder(this)
            .setTitle("Scale Keyframes in Time")
            .setView(form)
            .setPositiveButton("OK") { _, _ ->
                val v = pct.text.toString().toDoubleOrNull()
                if (v == null || v <= 0.0) toast("Check the number") else scaleSelectedKeys(selectedKeys.minOrNull() ?: 0L, v / 100.0)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Exponential Scale: the Scale keyframes between the first and last selected one become exponential
    private fun exponentialScale() {
        val sel = selectedPair()
        val pr = sel?.second?.props?.get(Path.SCALE)
        if (sel == null || pr == null || pr.keys.count { it.time in selectedKeys } < 2) {
            toast("Select a start and an end Scale keyframe first")
            return
        }
        val tpf = sel.first.frameRate.ticksPerFrame
        session.edit { p ->
            p.withLayerProp(sel.first.id, sel.second.id, Path.SCALE) { x -> withExponentialScale(x, selectedKeys, tpf) }
        }
        refresh()
    }

    private fun spinnerOf(items: Array<String>): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    // Window > Smoother
    private fun showSmootherDialog() {
        val sel = selectedPair()
        if (sel == null || selectedKeys.size < 2) {
            toast("Select two or more keyframes first")
            return
        }
        val applyTo = spinnerOf(arrayOf("Spatial Path", "Temporal Graph"))
        val tolerance = numEdit(5.0)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(label("Apply To"))
            addView(applyTo)
            addView(formRow("Tolerance", tolerance))
        }
        AlertDialog.Builder(this)
            .setTitle("Smoother")
            .setView(form)
            .setPositiveButton("Apply") { _, _ ->
                val tol = tolerance.text.toString().toDoubleOrNull()
                if (tol == null || tol < 0.0) {
                    toast("Check the number")
                } else {
                    val spatial = applyTo.selectedItemPosition == 0
                    val times = selectedKeys
                    session.edit { p ->
                        p.withLayerAllProps(sel.first.id, sel.second.id) { _, pr -> withSmoothed(pr, times, tol, spatial) }
                    }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Window > Wiggler
    private fun showWigglerDialog() {
        val sel = selectedPair()
        if (sel == null || selectedKeys.size < 2) {
            toast("Select two or more keyframes first")
            return
        }
        val noise = spinnerOf(arrayOf("Smooth", "Jagged"))
        val dims = spinnerOf(arrayOf("All The Same", "All Independent", "X", "Y"))
        val frequency = numEdit(5.0)
        val magnitude = numEdit(1.0)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(label("Noise Type"))
            addView(noise)
            addView(label("Dimensions"))
            addView(dims)
            addView(formRow("Frequency (per second)", frequency))
            addView(formRow("Magnitude", magnitude))
        }
        AlertDialog.Builder(this)
            .setTitle("Wiggler")
            .setView(form)
            .setPositiveButton("Apply") { _, _ ->
                val fq = frequency.text.toString().toDoubleOrNull()
                val mg = magnitude.text.toString().toDoubleOrNull()
                if (fq == null || mg == null || fq <= 0.0) {
                    toast("Check the numbers")
                } else {
                    val tpf = sel.first.frameRate.ticksPerFrame
                    val times = selectedKeys
                    val seed = System.nanoTime()
                    session.edit { p ->
                        p.withLayerAllProps(sel.first.id, sel.second.id) { _, pr ->
                            withWiggle(pr, times, noise.selectedItemPosition == 0, dims.selectedItemPosition, fq, mg, tpf, seed)
                        }
                    }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Window > Motion Sketch: then touch and drag on the preview, time plays while you draw
    private fun showMotionSketchDialog() {
        val sel = selectedPair()
        if (sel == null || sel.second.props[Path.POSITION] == null) {
            toast("Tap a layer bar first")
            return
        }
        val smoothing = numEdit(sketchSmoothing)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = "Sketches the Position of the selected layer. Press Start, then touch and drag on the preview: time plays at normal speed while you draw. Let go to stop."
            })
            addView(formRow("Smoothing (0 = keep every point)", smoothing))
        }
        AlertDialog.Builder(this)
            .setTitle("Motion Sketch")
            .setView(form)
            .setPositiveButton("Start") { _, _ ->
                sketchSmoothing = (smoothing.text.toString().toDoubleOrNull() ?: 1.0).coerceAtLeast(0.0)
                compView.sketchMode = true
                toast("Touch and drag on the preview")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sketchStart(x: Double, y: Double) {
        val sel = selectedPair()
        if (sel == null) {
            compView.sketchMode = false
            return
        }
        sketchSamples.clear()
        sketchActive = true
        sketchSamples.add(Pair(currentTime - sel.second.startTime, listOf(x, y)))
        startPlay()
    }

    private fun sketchPoint(x: Double, y: Double) {
        if (!sketchActive) return
        val sel = selectedPair() ?: return
        sketchSamples.add(Pair(currentTime - sel.second.startTime, listOf(x, y)))
    }

    private fun sketchEnd() {
        compView.sketchMode = false
        if (!sketchActive) return
        sketchActive = false
        stopPlay()
        val sel = selectedPair() ?: return
        val tpf = sel.first.frameRate.ticksPerFrame
        val samples = ArrayList(sketchSamples)
        sketchSamples.clear()
        if (samples.size < 2) {
            toast("Nothing was sketched")
            refresh()
            return
        }
        session.edit { p ->
            p.withLayerProp(sel.first.id, sel.second.id, Path.POSITION) { pr ->
                withSketchedPath(pr, samples, tpf, sketchSmoothing)
            }
        }
        selectedKeys = emptySet()
        refresh()
    }

    // ---------- Layer menu (like After Effects' Layer menu) ----------

    private fun showLayerMenu() {
        val comp = session.currentComp()
        if (comp == null) {
            toast("Create a composition first")
            return
        }
        val items = arrayOf(
            "Add Footage Layer…", "New Solid…", "New Null Object",
            "New Shape Layer: Rectangle", "New Shape Layer: Ellipse",
            "Duplicate Layer", "Rename Layer…", "Parent…", "Edit Colors…",
            if (multiMode) "Multi-select: ON (tap to turn off)" else "Multi-select: off (tap to turn on)",
            "Sequence Layers…", "Add Effect…"
        )
        AlertDialog.Builder(this)
            .setTitle("Layer")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAddLayer()
                    1 -> showNewSolidDialog()
                    2 -> addNewLayer { p, id -> p.withNewNull(id) }
                    3 -> addNewLayer { p, id -> p.withNewShape(id, ShapeType.RECT, 0xFF4C8DFF.toInt()) }
                    4 -> addNewLayer { p, id -> p.withNewShape(id, ShapeType.ELLIPSE, 0xFFFF6B6B.toInt()) }
                    5 -> duplicateLayer()
                    6 -> showRenameDialog()
                    7 -> showParentDialog()
                    8 -> showColorsDialog()
                    9 -> toggleMulti()
                    10 -> showSequenceDialog()
                    else -> showAddEffectDialog()
                }
            }
            .show()
    }

    // makes a new layer (on top, the whole length of the composition) and selects it
    private fun addNewLayer(make: (Project, Long) -> Project) {
        val c = session.currentComp() ?: return
        session.edit { p -> make(p, c.id) }
        selectedLayerId = session.currentComp()?.layers?.firstOrNull()?.id ?: -1L
        selectedKeys = emptySet()
        multiIds.clear()
        refresh()
    }

    private fun hexOf(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    private fun parseHex(text: String): Int? {
        return try {
            Color.parseColor(text.trim())
        } catch (e: Exception) {
            null
        }
    }

    private fun intEdit(v: Int): EditText = EditText(this).apply {
        setText(v.toString())
        inputType = InputType.TYPE_CLASS_NUMBER
    }

    private fun showNewSolidDialog() {
        val c = session.currentComp() ?: return
        val name = EditText(this).apply { setText("Solid") }
        val color = EditText(this).apply { setText("#FFFFFF") }
        val w = intEdit(c.width)
        val h = intEdit(c.height)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(label("Name"))
            addView(name)
            addView(label("Colour (#RRGGBB)"))
            addView(color)
            addView(formRow("Width x Height (pixels)", w, h))
        }
        AlertDialog.Builder(this)
            .setTitle("Solid Settings")
            .setView(form)
            .setPositiveButton("OK") { _, _ ->
                val argb = parseHex(color.text.toString())
                val sw = w.text.toString().toIntOrNull() ?: 0
                val sh = h.text.toString().toIntOrNull() ?: 0
                if (argb == null || sw < 1 || sh < 1 || sw > 8192 || sh > 8192) {
                    toast("Check the colour and the size")
                } else {
                    val nm = name.text.toString().trim().ifBlank { null }
                    addNewLayer { p, id -> p.withNewSolid(id, nm, argb, sw, sh) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun duplicateLayer() {
        val sel = selectedPair()
        if (sel == null) {
            toast("Tap a layer bar first")
            return
        }
        val idx = sel.first.layers.indexOfFirst { it.id == sel.second.id }
        session.edit { p -> p.withLayerDuplicated(sel.first.id, sel.second.id) }
        selectedLayerId = session.currentComp()?.layers?.getOrNull(idx)?.id ?: selectedLayerId
        multiIds.clear()
        refresh()
    }

    private fun showRenameDialog() {
        val sel = selectedPair()
        if (sel == null) {
            toast("Tap a layer bar first")
            return
        }
        val name = EditText(this).apply { setText(sel.second.name) }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(name)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Layer")
            .setView(form)
            .setPositiveButton("OK") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) {
                    session.edit { p -> p.withLayerRenamed(sel.first.id, sel.second.id, n) }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // The Parent column: pick the layer this one follows (or None). The layer keeps looking the same. With
    // Multi-select on, all the selected layers get that parent at once.
    private fun showParentDialog() {
        val sel = selectedPair()
        if (sel == null) {
            toast("Tap a layer bar first")
            return
        }
        val c = sel.first
        val l = sel.second
        val children = if (multiIds.size > 1 && l.id in multiIds) multiIds.toList() else listOf(l.id)
        val targets = c.layers.filter { it.id !in children }
        val names = arrayOf("None") + targets.map { it.name }
        AlertDialog.Builder(this)
            .setTitle(if (children.size > 1) "Parent of ${children.size} layers" else "Parent of ${l.name}")
            .setItems(names) { _, which ->
                val pid = if (which == 0) -1L else targets[which - 1].id
                val t = snapFrame(currentTime, c.frameRate)
                session.edit { p ->
                    var q = p
                    for (id in children) q = q.withLayerParent(c.id, id, pid, t)
                    q
                }
                refresh()
            }
            .show()
    }

    // colour of a solid; fill and stroke of a shape layer
    private fun showColorsDialog() {
        val sel = selectedPair()
        val l = sel?.second
        if (sel == null || l == null || (l.kind != LayerKind.SOLID && l.kind != LayerKind.SHAPE)) {
            toast("Select a solid or a shape layer first")
            return
        }
        val fill = EditText(this).apply { setText(hexOf(l.fill)) }
        val stroke = EditText(this).apply { setText(hexOf(l.stroke)) }
        val fillOn = CheckBox(this).apply {
            text = "Fill"
            isChecked = l.hasFill
        }
        val strokeOn = CheckBox(this).apply {
            text = "Stroke"
            isChecked = l.hasStroke
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(label(if (l.kind == LayerKind.SOLID) "Colour (#RRGGBB)" else "Fill colour (#RRGGBB)"))
            addView(fill)
            if (l.kind == LayerKind.SHAPE) {
                addView(fillOn)
                addView(label("Stroke colour (#RRGGBB)"))
                addView(stroke)
                addView(strokeOn)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Colours")
            .setView(form)
            .setPositiveButton("OK") { _, _ ->
                val f = parseHex(fill.text.toString())
                val s = parseHex(stroke.text.toString())
                if (f == null || s == null) {
                    toast("Check the colours")
                } else {
                    session.edit { p ->
                        p.withLayerColors(sel.first.id, l.id, f, s, if (l.kind == LayerKind.SHAPE) fillOn.isChecked else true, strokeOn.isChecked && l.kind == LayerKind.SHAPE)
                    }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleMulti() {
        multiMode = !multiMode
        multiIds.clear()
        if (multiMode && selectedLayerId >= 0L) multiIds.add(selectedLayerId)
        toast(if (multiMode) "Tap layers to add them to the group" else "Multi-select is off")
        pushToView()
    }

    // Layer > Keyframe Assistant > Sequence Layers: the selected layers one after another, in the order you tapped them
    private fun showSequenceDialog() {
        val c = session.currentComp() ?: return
        if (multiIds.size < 2) {
            toast("Turn on Multi-select and tap two or more layers first")
            return
        }
        val overlap = numEdit(0.0)
        val transition = spinnerOf(arrayOf("Off", "Cross Dissolve"))
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(formRow("Overlap (seconds)", overlap))
            addView(label("Transition"))
            addView(transition)
        }
        AlertDialog.Builder(this)
            .setTitle("Sequence Layers")
            .setView(form)
            .setPositiveButton("OK") { _, _ ->
                val ov = overlap.text.toString().toDoubleOrNull()
                if (ov == null || ov < 0.0) {
                    toast("Check the number")
                } else {
                    val ticks = snapFrame(secondsToTicks(ov), c.frameRate)
                    val ids = multiIds.toList()
                    session.edit { p -> p.withLayersSequenced(c.id, ids, ticks, transition.selectedItemPosition == 1) }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- effects ----------

    // Layer > Add Effect: the effects found in app/src/main/assets/effects
    private fun showAddEffectDialog() {
        val sel = selectedPair()
        if (sel == null) {
            toast("Tap a layer bar first")
            return
        }
        val defs = EffectRegistry.all()
        if (defs.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("No effects found")
                .setMessage("Put the effect .json and .frag files in app/src/main/assets/effects\n\n" + EffectRegistry.errors.joinToString("\n"))
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Add Effect")
            .setItems(defs.map { it.name }.toTypedArray()) { _, which ->
                session.edit { p -> p.withEffectAdded(sel.first.id, sel.second.id, defs[which].id) }
                refresh()
            }
            .show()
    }

    // tapping the title row of an effect in the panel: switch it off or on, or delete it
    private fun showEffectMenu(effectId: Long) {
        val sel = selectedPair() ?: return
        val e = sel.second.effects.firstOrNull { it.id == effectId } ?: return
        val items = arrayOf(if (e.enabled) "Turn off" else "Turn on", "Delete")
        AlertDialog.Builder(this)
            .setTitle(EffectRegistry.get(e.type)?.name ?: e.type)
            .setItems(items) { _, which ->
                session.edit { p ->
                    if (which == 0) {
                        p.withEffectEnabled(sel.first.id, sel.second.id, effectId, !e.enabled)
                    } else {
                        p.withEffectRemoved(sel.first.id, sel.second.id, effectId)
                    }
                }
                refresh()
            }
            .show()
    }

    // an effect's shader did not compile (the message says which file and what is wrong)
    private fun showFxError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle("Effect problem")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showInfo() {
        AlertDialog.Builder(this)
            .setTitle("Project info")
            .setMessage(report.text.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showSelfTest() {
        val lines = SelfTest.run() + GeometryTest.run() + OpsTest.run() + KeyframeTest.run() + GraphTest.run() + LayersTest.run() + EffectsTest.run()
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
        if (playing) stopPlay()
        val p = session.project
        val sb = StringBuilder()
        sb.append("PROJECT ${p.settings.name} (schema ${ProjectJson.SCHEMA})\n")
        val warn = session.loadWarning
        if (warn != null) sb.append("! $warn\n")
        sb.append("Autosaved after every edit.\n")
        sb.append("Undo steps: ${session.undoCount} Redo steps: ${session.redoCount}\n\n")
        val c = session.currentComp()
        if (c == null) {
            sb.append("No composition yet. Tap New comp.\n")
        } else {
            val bg = String.format(Locale.US, "#%06X", c.background and 0xFFFFFF)
            sb.append("COMP \"${c.name}\"\n")
            sb.append("  size ${c.width} x ${c.height}  pixel aspect ${c.pixelAspect}\n")
            sb.append("  frame rate ${c.frameRate.label()} (${c.frameRate.ticksPerFrame} ticks per frame)\n")
            sb.append("  duration ${timecode(c.durationTicks, c.frameRate)} (${secondsLabel(c.durationTicks)})\n")
            sb.append("  start ${timecode(c.startTimecode, c.frameRate)}\n")
            sb.append("  background $bg\n")
            sb.append(
                "  work area ${secondsLabel(c.workAreaStart)} to " +
                    "${secondsLabel(c.workAreaStart + c.workAreaDuration)}\n"
            )
        }
        sb.append("\nFOOTAGE (${p.footage.size})\n")
        for (f in p.footage) {
            val dur = if (f.kind == FootageKind.VIDEO) secondsLabel(f.durationTicks) else "still"
            sb.append("  #${f.id} ${f.kind.name.lowercase()} ${f.name} ${f.width}x${f.height} $dur\n")
        }
        if (c != null) {
            sb.append("\nLAYERS, top first (${c.layers.size})\n")
            for ((i, l) in c.layers.withIndex()) {
                val t = currentTime - l.startTime
                val state = if (l.enabled) "" else " (hidden)"
                val kindTxt = if (l.kind == LayerKind.FOOTAGE) "" else " [${l.kind.name.lowercase()}]"
                val parentTxt = c.layers.firstOrNull { it.id == l.parentId }?.let { " parent: ${it.name}" } ?: ""
                sb.append("  ${i + 1}. ${l.name}$kindTxt$state$parentTxt\n")
                sb.append("     in ${timecode(l.inPoint, c.frameRate)}  out ${timecode(l.outPoint, c.frameRate)}\n")
                sb.append("     position (${fmtNums(Eval.layerValue(l, Path.POSITION, t))})  anchor (${fmtNums(Eval.layerValue(l, Path.ANCHOR, t))})\n")
                sb.append("     scale (${fmtNums(Eval.layerValue(l, Path.SCALE, t))})%  rotation ${fmtNums(Eval.layerValue(l, Path.ROTATION, t))}  opacity ${fmtNums(Eval.layerValue(l, Path.OPACITY, t))}%\n")
            }
        }
        report.text = sb.toString()
        if (c != null) {
            if (currentTime > c.durationTicks) currentTime = 0L
        } else {
            currentTime = 0L
        }
        updateTimeUi()
        pushToView()
    }
}

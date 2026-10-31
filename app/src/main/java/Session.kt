package com.motion

import java.io.File

// Holds the current project, the undo/redo history, and saves after every edit.
class Session(private val file: File) {
    var project: Project = Project()
        private set
    var currentCompId: Long = -1L
    var loadWarning: String? = null

    private val undoStack = ArrayList<Project>()
    private val redoStack = ArrayList<Project>()

    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    fun load() {
        if (!file.exists()) return
        try {
            val loaded = ProjectJson.decode(file.readText())
            project = loaded.project
            loadWarning = loaded.warning
        } catch (e: Exception) {
            loadWarning = "Could not read the saved project: ${e.message}"
        }
        fixCurrent()
    }

    fun save() {
        try {
            file.writeText(ProjectJson.encode(project))
        } catch (e: Exception) {
        }
    }

    private fun fixCurrent() {
        if (project.comps.none { it.id == currentCompId }) {
            currentCompId = project.comps.firstOrNull()?.id ?: -1L
        }
    }

    fun currentComp(): Comp? = project.comps.firstOrNull { it.id == currentCompId }

    // Every change goes through here, so undo/redo and saving always work.
    fun edit(change: (Project) -> Project) {
        undoStack.add(project)
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
        project = change(project)
        fixCurrent()
        save()
    }

    // Starts ONE undo step for a drag. Then call modify() as often as needed, and save() at the end.
    fun beginGroup() {
        undoStack.add(project)
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun modify(change: (Project) -> Project) {
        project = change(project)
        fixCurrent()
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.add(project)
        project = undoStack.removeAt(undoStack.size - 1)
        fixCurrent()
        save()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.add(project)
        project = redoStack.removeAt(redoStack.size - 1)
        fixCurrent()
        save()
        return true
    }

    fun createComp(name: String, width: Int, height: Int, fr: FrameRate, durationTicks: Long) {
        var newId = -1L
        edit { p ->
            val r = p.withNewComp(name, width, height, fr, durationTicks, 0xFF000000.toInt())
            newId = r.second
            r.first
        }
        if (newId >= 0L) currentCompId = newId
    }
}

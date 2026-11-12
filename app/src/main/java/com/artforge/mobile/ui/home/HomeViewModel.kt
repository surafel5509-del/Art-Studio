package com.artforge.mobile.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.artforge.mobile.ArtForgeApplication
import com.artforge.mobile.core.model.ProjectKind
import com.artforge.mobile.core.model.ProjectSummary
import com.artforge.mobile.data.db.RecoveryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Home screen state: recent projects, favourites and recovery entries. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ArtForgeApplication
    private val repo = app.projectRepository

    private val _recents = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val recents: StateFlow<List<ProjectSummary>> = _recents.asStateFlow()

    private val _recoveries = MutableStateFlow<List<RecoveryEntity>>(emptyList())
    val recoveries: StateFlow<List<RecoveryEntity>> = _recoveries.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeRecent().collect { _recents.value = it }
        }
        viewModelScope.launch {
            repo.observeRecoveries().collect { _recoveries.value = it }
        }
    }

    fun createProject(
        kind: ProjectKind,
        title: String,
        width: Int,
        height: Int,
        fps: Int,
        frameCount: Int,
        transparent: Boolean,
        backgroundColor: Int,
        onCreated: (String) -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = true
            val doc = repo.createDocument(kind, title, width, height, fps, frameCount, transparent, backgroundColor)
            val prepared = prepareFirstFrame(doc)
            repo.ensureFrames(prepared)
            repo.saveProject(prepared)
            _busy.value = false
            onCreated(prepared.id)
        }
    }

    private fun prepareFirstFrame(doc: com.artforge.mobile.core.model.ProjectDocument): com.artforge.mobile.core.model.ProjectDocument {
        repo.registerProject(doc)
        val frameId = doc.animation.frames.firstOrNull()?.id
            ?: doc.layers.firstOrNull()?.activeFrameId
            ?: java.util.UUID.randomUUID().toString()
        val layers = doc.layers.map { layer ->
            if (layer.frames.isEmpty() && layer.activeFrameId == null) {
                layer.copy(
                    activeFrameId = frameId,
                    frames = listOf(com.artforge.mobile.core.model.FrameRef(frameId, 0.0)),
                )
            } else {
                layer
            }
        }
        return doc.copy(layers = layers, activeLayerId = doc.activeLayerId ?: layers.firstOrNull()?.id)
    }

    fun deleteProject(id: String) {
        viewModelScope.launch {
            repo.deleteProject(id)
            _message.value = "Project deleted"
        }
    }

    fun duplicateProject(id: String) {
        viewModelScope.launch {
            _busy.value = true
            val doc = repo.loadProject(id)
            if (doc != null) repo.duplicateProject(doc, "${doc.title} copy")
            _busy.value = false
            _message.value = "Project duplicated"
        }
    }

    fun importProject(uri: android.net.Uri, onImported: (String?) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            val doc = repo.importProject(uri)
            _busy.value = false
            _message.value = if (doc != null) "Project imported" else "Could not import that file"
            onImported(doc?.id)
        }
    }

    fun toggleFavorite(id: String, favorite: Boolean) {
        viewModelScope.launch { repo.projectDao.setFavorite(id, favorite) }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun restoreRecovery(entity: RecoveryEntity, onRestored: (String) -> Unit) {
        viewModelScope.launch {
            val doc = repo.recoveryFor(entity.projectId)
            if (doc != null) {
                repo.saveProject(doc.document)
                onRestored(entity.projectId)
            } else {
                _message.value = "Recovery data unreadable"
            }
        }
    }

    fun discardRecovery(projectId: String) {
        viewModelScope.launch { repo.discardRecovery(projectId) }
    }
}

package it.objectfinder.app.ui.objects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.objectfinder.app.data.ObjectDraft
import it.objectfinder.app.data.ObjectRepository
import it.objectfinder.app.data.local.ObjectStatus
import it.objectfinder.app.location.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorState(
    val isNew: Boolean,
    val loaded: Boolean = false,
    val notFound: Boolean = false,
    val name: String = "",
    val category: String = "general",
    val notes: String = "",
    val status: ObjectStatus = ObjectStatus.ACTIVE,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val locating: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val nameTouched: Boolean = false,
    /** true quando il salvataggio è completato e la schermata può chiudersi. */
    val finished: Boolean = false,
) {
    val canSave: Boolean get() = loaded && name.isNotBlank() && !saving && !locating
    val hasLocation: Boolean get() = latitude != null && longitude != null
    val isArchived: Boolean get() = status == ObjectStatus.ARCHIVED
}

/** Gestisce creazione e modifica: `objectId == null` → nuovo oggetto. */
class ObjectEditorViewModel(
    private val objectId: String?,
    private val repository: ObjectRepository,
    private val locationProvider: LocationProvider,
    private val onLocalChange: () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorState(isNew = objectId == null, loaded = objectId == null))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    init {
        if (objectId != null) viewModelScope.launch {
            val item = repository.get(objectId)
            _state.update {
                if (item == null) it.copy(loaded = true, notFound = true)
                else it.copy(
                    loaded = true,
                    name = item.name,
                    category = item.category,
                    notes = item.notes,
                    status = item.statusEnum,
                    latitude = item.latitude,
                    longitude = item.longitude,
                    accuracyMeters = item.accuracyMeters,
                )
            }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v, nameTouched = true) }
    fun setCategory(v: String) = _state.update { it.copy(category = v) }
    fun setNotes(v: String) = _state.update { it.copy(notes = v) }
    fun setStatus(v: ObjectStatus) = _state.update { it.copy(status = v) }
    fun clearLocation() = _state.update { it.copy(latitude = null, longitude = null, accuracyMeters = null) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Da invocare solo dopo che il permesso posizione è stato concesso. */
    fun captureLocation() {
        if (_state.value.locating) return
        _state.update { it.copy(locating = true) }
        viewModelScope.launch {
            val location = runCatching { locationProvider.currentLocation() }.getOrNull()
            _state.update {
                if (location == null) it.copy(
                    locating = false,
                    message = "Posizione non disponibile: verifica che il GPS sia attivo e riprova.",
                )
                else it.copy(
                    locating = false,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracyMeters,
                    message = "Posizione acquisita (±${location.accuracyMeters.toInt()} m)",
                )
            }
        }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val draft = ObjectDraft(s.name, s.category, s.notes, s.status, s.latitude, s.longitude, s.accuracyMeters)
            if (objectId == null) repository.create(draft) else repository.update(objectId, draft)
            onLocalChange()
            _state.update { it.copy(saving = false, finished = true) }
        }
    }

    fun toggleArchived() {
        val id = objectId ?: return
        val archive = !_state.value.isArchived
        viewModelScope.launch {
            repository.setArchived(id, archive)
            onLocalChange()
            _state.update {
                it.copy(
                    status = if (archive) ObjectStatus.ARCHIVED else ObjectStatus.ACTIVE,
                    finished = archive,
                    message = if (archive) null else "Oggetto ripristinato",
                )
            }
        }
    }
}

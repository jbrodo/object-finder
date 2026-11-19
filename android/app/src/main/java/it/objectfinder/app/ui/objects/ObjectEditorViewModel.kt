package it.objectfinder.app.ui.objects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.objectfinder.app.data.ObjectDraft
import android.net.Uri
import it.objectfinder.app.data.ObjectRepository
import it.objectfinder.app.data.AddIdentifierResult
import it.objectfinder.app.data.IdentifierRepository
import it.objectfinder.app.data.LocationHistoryRepository
import it.objectfinder.app.data.local.LocationHistoryEntity
import it.objectfinder.app.data.PhotoRepository
import it.objectfinder.app.data.local.IdentifierEntity
import it.objectfinder.app.data.local.IdentifierType
import it.objectfinder.app.qr.QrCodes
import it.objectfinder.app.data.local.PhotoEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.util.UUID
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
    // Si può salvare anche durante l'acquisizione GPS: il salvataggio ne attende la fine.
    val canSave: Boolean get() = loaded && name.isNotBlank() && !saving
    val hasLocation: Boolean get() = latitude != null && longitude != null
    val isArchived: Boolean get() = status == ObjectStatus.ARCHIVED
}

/** Gestisce creazione e modifica: `objectId == null` → nuovo oggetto. */
class ObjectEditorViewModel(
    private val objectId: String?,
    private val repository: ObjectRepository,
    private val photoRepository: PhotoRepository,
    private val identifierRepository: IdentifierRepository,
    private val historyRepository: LocationHistoryRepository,
    /** Aggiorna lo storico dal server (no-op offline). */
    private val refreshHistory: suspend (String) -> Unit,
    private val locationProvider: LocationProvider,
    private val appScope: CoroutineScope,
    private val onLocalChange: () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorState(isNew = objectId == null, loaded = objectId == null))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** Id stabile anche per un oggetto nuovo: le foto possono essere allegate prima del salvataggio. */
    private val effectiveId: String = objectId ?: UUID.randomUUID().toString()
    private var saved = false
    private var locateJob: Job? = null

    val photos: StateFlow<List<PhotoEntity>> = photoRepository.observeForObject(effectiveId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _photoBusy = MutableStateFlow(false)
    val photoBusy: StateFlow<Boolean> = _photoBusy.asStateFlow()

    val identifiers: StateFlow<List<IdentifierEntity>> = identifierRepository.observeForObject(effectiveId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Collega un codice esistente (scansionato o digitato) all'oggetto. */
    fun addCode(type: IdentifierType, raw: String) {
        if (QrCodes.parseObjectId(raw) != null) {
            _state.update { it.copy(message = "È un'etichetta Object Finder: identifica già un oggetto, non va collegata.") }
            return
        }
        viewModelScope.launch {
            val message = when (identifierRepository.add(effectiveId, type, raw)) {
                AddIdentifierResult.Added -> {
                    if (objectId != null) onLocalChange()
                    "Codice collegato"
                }
                AddIdentifierResult.Duplicate -> "Codice già collegato a questo oggetto"
                AddIdentifierResult.Invalid -> "Codice non valido"
            }
            _state.update { it.copy(message = message) }
        }
    }

    fun deleteCode(id: String) {
        viewModelScope.launch {
            identifierRepository.delete(id)
            if (objectId != null) onLocalChange()
        }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun newCameraUri(): Uri = photoRepository.newCameraUri()

    fun addPhoto(uri: Uri) {
        _photoBusy.value = true
        viewModelScope.launch {
            runCatching { photoRepository.addPhoto(effectiveId, uri) }
                .onSuccess { if (objectId != null) onLocalChange() }
                .onFailure { e -> _state.update { it.copy(message = "Foto non salvata: ${e.message ?: "errore"}") } }
            _photoBusy.value = false
        }
    }

    fun deletePhoto(photoId: String) {
        viewModelScope.launch {
            photoRepository.deletePhoto(photoId)
            if (objectId != null) onLocalChange()
        }
    }

    override fun onCleared() {
        // Editor di un oggetto nuovo chiuso senza salvare: niente foto orfane su disco.
        if (objectId == null && !saved) appScope.launch {
            photoRepository.discardAllForObject(effectiveId)
            identifierRepository.discardAllForObject(effectiveId)
        }
    }

    val history: StateFlow<List<LocationHistoryEntity>> = historyRepository.observeForObject(effectiveId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (objectId != null) viewModelScope.launch { runCatching { refreshHistory(objectId) } }
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
        locateJob = viewModelScope.launch {
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
        if (!_state.value.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            locateJob?.join()
            val s = _state.value
            val draft = ObjectDraft(s.name, s.category, s.notes, s.status, s.latitude, s.longitude, s.accuracyMeters)
            if (objectId == null) repository.create(draft, effectiveId) else repository.update(objectId, draft)
            saved = true
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

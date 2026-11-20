package it.objectfinder.app.ui.objects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.objectfinder.app.data.ObjectDraft
import android.net.Uri
import it.objectfinder.app.data.ObjectRepository
import it.objectfinder.app.data.SyncMerge
import it.objectfinder.app.data.local.ConflictKind
import it.objectfinder.app.data.local.DEFAULT_CATEGORY
import it.objectfinder.app.data.local.PLACE_MAX
import it.objectfinder.app.data.local.categoryLabel
import it.objectfinder.app.data.remote.ObjectReadDto
import it.objectfinder.app.data.remote.SupabaseException
import it.objectfinder.app.data.remote.supabaseJson
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
import it.objectfinder.app.location.DeviceLocation
import it.objectfinder.app.data.Geo
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
    val category: String = DEFAULT_CATEGORY,
    val notes: String = "",
    val place: String = "",
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
    /** Oggetto esistente: si apre in consultazione, la modifica è un'azione esplicita. */
    val editing: Boolean = isNew,
    /** Posizione del telefono, per mostrare la distanza dall'oggetto (null se non disponibile). */
    val origin: DeviceLocation? = null,
) {
    // Si può salvare anche durante l'acquisizione GPS: il salvataggio ne attende la fine.
    val canSave: Boolean get() = loaded && name.isNotBlank() && !saving
    val hasLocation: Boolean get() = latitude != null && longitude != null
    val isArchived: Boolean get() = status == ObjectStatus.ARCHIVED

    fun draft() = ObjectDraft(name, category, notes, status, latitude, longitude, accuracyMeters, place)

    /** Distanza dal telefono all'oggetto, se entrambe le posizioni sono note. */
    val distanceMeters: Double?
        get() {
            val from = origin ?: return null
            val lat = latitude ?: return null
            val lon = longitude ?: return null
            return Geo.distanceMeters(from.latitude, from.longitude, lat, lon)
        }
}

data class ConflictUi(val deletedOnServer: Boolean, val differences: List<SyncMerge.FieldDiff>)

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
    /** Valori all'apertura della modifica: per sapere se ci sono cambiamenti da non perdere. */
    private var baseline: ObjectDraft? = null

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

    /** Conflitto aperto (modifica concorrente o eliminazione sul server) con i campi diversi. */
    val conflict: StateFlow<ConflictUi?> = combine(
        objectId?.let { repository.observeConflict(it) } ?: flowOf(null),
        objectId?.let { repository.observeObject(it) } ?: flowOf(null),
    ) { conflict, local ->
        if (conflict == null || local == null) null
        else {
            val server = conflict.serverJson?.let { supabaseJson.decodeFromString(ObjectReadDto.serializer(), it) }
            ConflictUi(
                deletedOnServer = server == null || ConflictKind.fromWire(conflict.kind) == ConflictKind.DELETED,
                differences = server?.let { SyncMerge.diff(local, it) }.orEmpty(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        if (objectId != null) viewModelScope.launch { runCatching { refreshHistory(objectId) } }
        if (objectId != null) viewModelScope.launch { reload() }
        if (objectId != null) viewModelScope.launch { locateUser() }
        if (objectId != null) viewModelScope.launch { runCatching { photoRepository.ensureDownloaded(objectId) } }
    }

    /** Posizione del telefono per la distanza: subito l'ultima nota, poi un fix fresco. Mai richiesta di permesso qui. */
    private suspend fun locateUser() {
        if (!locationProvider.hasPermission()) return
        runCatching { locationProvider.lastKnownLocation() }.getOrNull()?.let { loc -> _state.update { it.copy(origin = loc) } }
        runCatching { locationProvider.currentLocation() }.getOrNull()?.let { loc -> _state.update { it.copy(origin = loc) } }
    }

    /** Da chiamare dopo che il permesso è stato concesso altrove (es. "Spostato qui"). */
    fun refreshOrigin() { viewModelScope.launch { locateUser() } }

    fun startEditing() {
        baseline = _state.value.draft()
        _state.update { it.copy(editing = true, nameTouched = false) }
    }

    /** Ci sono modifiche non salvate nel modulo (solo per un oggetto esistente in modifica). */
    val hasUnsavedChanges: Boolean
        get() = _state.value.editing && !_state.value.isNew && baseline != null && baseline != _state.value.draft()

    /** Esce dalla modifica scartando i cambiamenti: si torna ai valori salvati. */
    fun cancelEditing() {
        locateJob?.cancel()
        viewModelScope.launch {
            reload()
            _state.update { it.copy(editing = false, locating = false) }
        }
    }

    /** "Spostato qui": nuova posizione dal GPS salvata subito, senza passare dalla modifica. */
    fun moveHere() {
        val id = objectId ?: return
        if (_state.value.locating) return
        _state.update { it.copy(locating = true) }
        locateJob = viewModelScope.launch {
            val location = runCatching { locationProvider.currentLocation() }.getOrNull()
            if (location == null) {
                _state.update {
                    it.copy(locating = false, message = "Posizione non disponibile: verifica che il GPS sia attivo e riprova.")
                }
                return@launch
            }
            val current = repository.get(id) ?: run { _state.update { it.copy(locating = false) }; return@launch }
            repository.update(
                id,
                ObjectDraft(
                    current.name, current.category, current.notes, current.statusEnum,
                    location.latitude, location.longitude, location.accuracyMeters, current.place,
                ),
            )
            onLocalChange()
            reload()
            _state.update {
                it.copy(
                    locating = false,
                    origin = location,
                    message = "Posizione aggiornata (±${location.accuracyMeters.toInt()} m)",
                )
            }
        }
    }

    private suspend fun reload() {
        val item = objectId?.let { repository.get(it) }
        _state.update {
            if (item == null) it.copy(loaded = true, notFound = true)
            else it.copy(
                loaded = true,
                name = item.name,
                category = categoryLabel(item.category),
                notes = item.notes,
                place = item.place,
                status = item.statusEnum,
                latitude = item.latitude,
                longitude = item.longitude,
                accuracyMeters = item.accuracyMeters,
            )
        }
    }

    fun keepMine() {
        val id = objectId ?: return
        viewModelScope.launch {
            // Prima si salvano le eventuali modifiche in corso nel form, poi si sceglie la propria versione.
            val s = _state.value
            repository.update(id, s.draft())
            repository.keepMine(id)
            onLocalChange()
            _state.update { it.copy(message = "Tenuta la tua versione: verrà inviata al server") }
        }
    }

    fun useServer() {
        val id = objectId ?: return
        viewModelScope.launch {
            val deleted = conflict.value?.deletedOnServer == true
            repository.useServer(id)
            if (deleted) _state.update { it.copy(finished = true) }
            else {
                reload()
                _state.update { it.copy(message = "Ripristinata la versione del server") }
            }
        }
    }

    fun deletePermanently() {
        val id = objectId ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            runCatching { repository.deletePermanently(id) }
                .onSuccess {
                    saved = true
                    _state.update { it.copy(saving = false, finished = true) }
                }
                .onFailure { e ->
                    val text = when (e) {
                        is SupabaseException.NotAuthenticated -> "Accedi per eliminare un oggetto condiviso"
                        is java.io.IOException -> "Serve una connessione per eliminare un oggetto già sincronizzato"
                        else -> e.message ?: "Eliminazione non riuscita"
                    }
                    _state.update { it.copy(saving = false, message = text) }
                }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v, nameTouched = true) }
    fun setCategory(v: String) = _state.update { it.copy(category = v) }
    fun setNotes(v: String) = _state.update { it.copy(notes = v) }
    fun setPlace(v: String) = _state.update { it.copy(place = v.take(PLACE_MAX)) }
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
            val draft = _state.value.draft()
            saved = true
            if (objectId == null) {
                repository.create(draft, effectiveId)
                onLocalChange()
                _state.update { it.copy(saving = false, finished = true) }
            } else {
                // Oggetto esistente: si torna alla scheda, dove si vede subito il risultato.
                repository.update(objectId, draft)
                onLocalChange()
                reload()
                _state.update { it.copy(saving = false, editing = false, message = "Modifiche salvate") }
            }
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

package it.objectfinder.app.ui.objects

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import it.objectfinder.app.location.FixPolicy
import it.objectfinder.app.data.local.APPROXIMATE_ACCURACY_METERS
import it.objectfinder.app.data.local.LocationSource
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
    val locationSource: LocationSource = LocationSource.GPS,
    val locating: Boolean = false,
    /** Incertezza del miglior fix raccolto finora durante il rilevamento. */
    val liveAccuracy: Float? = null,
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

    fun draft() = ObjectDraft(name, category, notes, status, latitude, longitude, accuracyMeters, place, locationSource)
    val isManualLocation: Boolean get() = locationSource == LocationSource.MANUAL
    val isApproximate: Boolean
        get() = hasLocation && !isManualLocation && (accuracyMeters ?: 0f) > APPROXIMATE_ACCURACY_METERS

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
private const val NO_FIX_MESSAGE = "Posizione non disponibile: verifica che il GPS sia attivo, o indicala sulla mappa."

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
    /** Foto da allegare subito a un oggetto nuovo (scattata per il riconoscimento). */
    initialPhoto: Uri? = null,
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
        if (objectId == null && initialPhoto != null) addPhoto(initialPhoto)
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
    /** "L'ho spostato qui": miglior posizione GPS dei prossimi secondi, salvata subito. */
    fun moveHere() {
        val id = objectId ?: return
        acquire { location ->
            if (location == null) {
                _state.update { it.copy(message = NO_FIX_MESSAGE) }
                return@acquire
            }
            persistLocation(id, location.latitude, location.longitude, location.accuracyMeters, LocationSource.GPS)
            _state.update { it.copy(origin = location, message = fixMessage(location, saved = true)) }
        }
    }

    /** Punto scelto sulla mappa dalla scheda: salvato subito come posizione indicata a mano. */
    fun saveManualLocation(latitude: Double, longitude: Double) {
        val id = objectId ?: return
        viewModelScope.launch {
            persistLocation(id, latitude, longitude, null, LocationSource.MANUAL)
            _state.update { it.copy(message = "Posizione corretta sulla mappa") }
        }
    }

    /** Punto scelto sulla mappa nel modulo (nuovo oggetto o modifica): si salva con il resto. */
    fun setManualLocation(latitude: Double, longitude: Double) {
        locateJob?.cancel()
        _state.update {
            it.copy(
                latitude = latitude, longitude = longitude, accuracyMeters = null,
                locationSource = LocationSource.MANUAL, locating = false, liveAccuracy = null,
            )
        }
    }

    private suspend fun persistLocation(
        id: String, latitude: Double, longitude: Double, accuracy: Float?, source: LocationSource,
    ) {
        val current = repository.get(id) ?: return
        repository.update(
            id,
            ObjectDraft(
                current.name, current.category, current.notes, current.statusEnum,
                latitude, longitude, accuracy, current.place, source,
            ),
        )
        onLocalChange()
        reload()
    }

    // ---- Rilevamento GPS: il fix migliore entro pochi secondi ----

    private var bestSoFar: DeviceLocation? = null
    private var onFix: (suspend (DeviceLocation?) -> Unit)? = null

    /**
     * Raccoglie fix per al massimo [FixPolicy.MAX_WAIT_MILLIS], tiene il più preciso e si ferma appena
     * è buono; l'incertezza attuale è in [EditorState.liveAccuracy]. Senza fix ripiega sull'ultima nota.
     */
    private fun acquire(onDone: suspend (DeviceLocation?) -> Unit) {
        if (_state.value.locating) return
        bestSoFar = null
        onFix = onDone
        _state.update { it.copy(locating = true, liveAccuracy = null) }
        locateJob = viewModelScope.launch {
            withTimeoutOrNull(FixPolicy.MAX_WAIT_MILLIS) {
                locationProvider.fixes().first { fix ->
                    val best = FixPolicy.better(bestSoFar, fix).also { bestSoFar = it }
                    _state.update { it.copy(liveAccuracy = best.accuracyMeters) }
                    FixPolicy.isGoodEnough(best)
                }
            }
            finishAcquire(bestSoFar ?: runCatching { locationProvider.currentLocation() }.getOrNull())
        }
    }

    /** "Usa comunque": si accetta subito il fix migliore raccolto finora. */
    fun useBestFixNow() {
        val best = bestSoFar ?: return
        val running = locateJob
        locateJob = viewModelScope.launch {
            running?.cancelAndJoin()
            finishAcquire(best)
        }
    }

    private suspend fun finishAcquire(location: DeviceLocation?) {
        val callback = onFix ?: return
        onFix = null
        _state.update { it.copy(locating = false, liveAccuracy = null) }
        callback(location)
    }

    private fun fixMessage(location: DeviceLocation, saved: Boolean): String {
        val accuracy = location.accuracyMeters.toInt()
        return if (location.accuracyMeters > APPROXIMATE_ACCURACY_METERS)
            "Posizione approssimata (±$accuracy m): puoi correggerla sulla mappa"
        else if (saved) "Posizione aggiornata (±$accuracy m)" else "Posizione acquisita (±$accuracy m)"
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
                locationSource = LocationSource.fromWire(item.locationSource),
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
    fun clearLocation() = _state.update {
        it.copy(latitude = null, longitude = null, accuracyMeters = null, locationSource = LocationSource.GPS)
    }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Da invocare solo dopo che il permesso posizione è stato concesso. */
    fun captureLocation() = acquire { location ->
        _state.update {
            if (location == null) it.copy(message = NO_FIX_MESSAGE)
            else it.copy(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracyMeters,
                locationSource = LocationSource.GPS,
                message = fixMessage(location, saved = false),
            )
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

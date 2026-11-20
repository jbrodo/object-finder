package it.objectfinder.app.ui.objects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.objectfinder.app.data.Geo
import it.objectfinder.app.data.IdentifierRepository
import it.objectfinder.app.location.DeviceLocation
import it.objectfinder.app.data.ObjectRepository
import it.objectfinder.app.data.PhotoRepository
import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.ObjectStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class ObjectFilter(val label: String) { ACTIVE("Attuali"), ARCHIVED("Archiviati"), ALL("Tutti") }

data class ObjectListState(
    val items: List<ObjectEntity> = emptyList(),
    val totalInProject: Int = 0,
    val query: String = "",
    val filter: ObjectFilter = ObjectFilter.ACTIVE,
    val loading: Boolean = true,
    /** objectId → path della foto di copertina. */
    val covers: Map<String, String> = emptyMap(),
    /** Posizione del telefono se l'ordinamento "vicino a me" è attivo. */
    val origin: DeviceLocation? = null,
) {
    fun distanceTo(item: ObjectEntity): Double? {
        val from = origin ?: return null
        val lat = item.latitude ?: return null
        val lon = item.longitude ?: return null
        return Geo.distanceMeters(from.latitude, from.longitude, lat, lon)
    }
}

class ObjectListViewModel(
    repository: ObjectRepository,
    photos: PhotoRepository,
    identifiers: IdentifierRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(ObjectFilter.ACTIVE)
    private val origin = MutableStateFlow<DeviceLocation?>(null)

    val state: StateFlow<ObjectListState> =
        combine(
            repository.observeObjects(), query, filter, photos.observeCovers(), identifiers.observeValuesByObject(),
        ) { all, q, f, covers, codes ->
            ObjectListState(
                items = filterObjects(all, q, f, codes),
                totalInProject = all.size,
                query = q,
                filter = f,
                loading = false,
                covers = covers,
            )
        }.combine(origin) { state, from ->
            if (from == null) state
            else state.copy(items = sortByDistance(state.items, from.latitude, from.longitude), origin = from)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObjectListState())

    /** Attiva (posizione) o disattiva (null) l'ordinamento per distanza. */
    fun setOrigin(location: DeviceLocation?) { origin.value = location }

    fun setQuery(value: String) { query.value = value }
    fun setFilter(value: ObjectFilter) { filter.value = value }
}

internal fun filterObjects(
    all: List<ObjectEntity>,
    query: String,
    filter: ObjectFilter,
    codes: Map<String, List<String>> = emptyMap(),
): List<ObjectEntity> {
    val q = query.trim()
    return all.filter { item ->
        val archived = item.statusEnum == ObjectStatus.ARCHIVED
        val statusOk = when (filter) {
            ObjectFilter.ACTIVE -> !archived
            ObjectFilter.ARCHIVED -> archived
            ObjectFilter.ALL -> true
        }
        statusOk && (q.isEmpty() ||
            item.name.contains(q, ignoreCase = true) ||
            item.category.contains(q, ignoreCase = true) ||
            item.notes.contains(q, ignoreCase = true) ||
            item.place.contains(q, ignoreCase = true) ||
            codes[item.id].orEmpty().any { it.contains(q, ignoreCase = true) })
    }
}

/** Più vicini prima; gli oggetti senza coordinate in fondo, in ordine invariato. */
internal fun sortByDistance(items: List<ObjectEntity>, latitude: Double, longitude: Double): List<ObjectEntity> =
    items.sortedWith(compareBy(nullsLast()) { item ->
        val lat = item.latitude
        val lon = item.longitude
        if (lat == null || lon == null) null else Geo.distanceMeters(latitude, longitude, lat, lon)
    })

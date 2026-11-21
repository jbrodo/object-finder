package it.objectfinder.app.ui.objects

import kotlinx.coroutines.flow.flowOf
import it.objectfinder.app.data.PlaceTree
import it.objectfinder.app.data.PlaceRepository
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
import kotlinx.coroutines.launch

enum class ObjectFilter(val label: String) {
    ACTIVE("Attuali"), LOANED("In prestito"), NO_LOCATION("Senza posizione"), ARCHIVED("Archiviati"), ALL("Tutti")
}

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
    val places: PlaceTree = PlaceTree(emptyList()),
    /** Filtro per luogo (incluso tutto quello che contiene); null = tutti. */
    val placeFilter: String? = null,
) {
    fun placePath(item: ObjectEntity): String = places.path(item.placeId)

    fun distanceTo(item: ObjectEntity): Double? {
        val from = origin ?: return null
        val lat = item.latitude ?: return null
        val lon = item.longitude ?: return null
        return Geo.distanceMeters(from.latitude, from.longitude, lat, lon)
    }
}

class ObjectListViewModel(
    private val repository: ObjectRepository,
    photos: PhotoRepository,
    identifiers: IdentifierRepository,
    places: PlaceRepository? = null,
) : ViewModel() {
    private val placeFilter = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(ObjectFilter.ACTIVE)
    private val origin = MutableStateFlow<DeviceLocation?>(null)

    val state: StateFlow<ObjectListState> =
        combine(
            repository.observeObjects(), query, filter, photos.observeCovers(), identifiers.observeValuesByObject(),
        ) { all, q, f, covers, codes -> Raw(all, q, f, covers, codes) }
            .combine(combine(places?.observeTree() ?: flowOf(PlaceTree(emptyList())), placeFilter, ::Pair)) { raw, (tree, within) ->
                // Un luogo cancellato nel frattempo non lascia la lista vuota.
                val active = within?.takeIf { tree.get(it) != null }
                ObjectListState(
                    items = filterObjects(
                        raw.all, raw.query, raw.filter, raw.codes,
                        placePaths = raw.all.mapNotNull { o -> o.placeId?.let { it to tree.path(it) } }.toMap(),
                        within = active?.let(tree::subtree),
                    ),
                    totalInProject = raw.all.size,
                    query = raw.query,
                    filter = raw.filter,
                    loading = false,
                    covers = raw.covers,
                    places = tree,
                    placeFilter = active,
                )
            }.combine(origin) { state, from ->
            if (from == null) state
            else state.copy(items = sortByDistance(state.items, from.latitude, from.longitude), origin = from)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObjectListState())

    /** Attiva (posizione) o disattiva (null) l'ordinamento per distanza. */
    fun setOrigin(location: DeviceLocation?) { origin.value = location }

    fun setQuery(value: String) { query.value = value }

    fun setPlaceFilter(id: String?) { placeFilter.value = id }

    private class Raw(
        val all: List<ObjectEntity>, val query: String, val filter: ObjectFilter,
        val covers: Map<String, String>, val codes: Map<String, List<String>>,
    )

    /** Archivia o ripristina più oggetti insieme (selezione multipla). */
    fun setArchived(ids: Collection<String>, archived: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            ids.forEach { repository.setArchived(it, archived) }
            onDone()
        }
    }
    fun setFilter(value: ObjectFilter) { filter.value = value }
}

internal fun filterObjects(
    all: List<ObjectEntity>,
    query: String,
    filter: ObjectFilter,
    codes: Map<String, List<String>> = emptyMap(),
    /** placeId → "Casa › Garage": la ricerca trova anche per luogo. */
    placePaths: Map<String, String> = emptyMap(),
    /** Solo oggetti in questi luoghi (luogo scelto + sotto-luoghi); null = nessun filtro. */
    within: Set<String>? = null,
): List<ObjectEntity> {
    val q = query.trim()
    return all.filter { item ->
        if (within != null && item.placeId !in within) return@filter false
        val archived = item.statusEnum == ObjectStatus.ARCHIVED
        val statusOk = when (filter) {
            ObjectFilter.ACTIVE -> !archived
            ObjectFilter.LOANED -> !archived && item.isLoaned
            // Quelli che la mappa non può mostrare: da qui si aggiunge la posizione.
            ObjectFilter.NO_LOCATION -> !archived && !item.hasLocation
            ObjectFilter.ARCHIVED -> archived
            ObjectFilter.ALL -> true
        }
        statusOk && (q.isEmpty() ||
            item.name.contains(q, ignoreCase = true) ||
            item.category.contains(q, ignoreCase = true) ||
            item.notes.contains(q, ignoreCase = true) ||
            item.place.contains(q, ignoreCase = true) ||
            placePaths[item.placeId].orEmpty().contains(q, ignoreCase = true) ||
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

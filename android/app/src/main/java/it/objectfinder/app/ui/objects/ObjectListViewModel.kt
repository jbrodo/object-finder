package it.objectfinder.app.ui.objects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.objectfinder.app.data.IdentifierRepository
import it.objectfinder.app.data.ObjectRepository
import it.objectfinder.app.data.PhotoRepository
import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.ObjectStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class ObjectFilter(val label: String) { ACTIVE("In uso"), ARCHIVED("Archiviati"), ALL("Tutti") }

data class ObjectListState(
    val items: List<ObjectEntity> = emptyList(),
    val totalInProject: Int = 0,
    val query: String = "",
    val filter: ObjectFilter = ObjectFilter.ACTIVE,
    val loading: Boolean = true,
    /** objectId → path della foto di copertina. */
    val covers: Map<String, String> = emptyMap(),
)

class ObjectListViewModel(
    repository: ObjectRepository,
    photos: PhotoRepository,
    identifiers: IdentifierRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(ObjectFilter.ACTIVE)

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
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObjectListState())

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
            codes[item.id].orEmpty().any { it.contains(q, ignoreCase = true) })
    }
}

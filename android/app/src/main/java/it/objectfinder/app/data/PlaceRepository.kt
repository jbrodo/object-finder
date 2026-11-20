package it.objectfinder.app.data

import it.objectfinder.app.data.local.LOCAL_PROJECT_ID
import it.objectfinder.app.data.local.ObjectDao
import it.objectfinder.app.data.local.PlaceDao
import it.objectfinder.app.data.local.PlaceEntity
import it.objectfinder.app.data.local.PlaceSync
import it.objectfinder.app.data.remote.PlaceRowDto
import it.objectfinder.app.data.remote.SupabaseException
import it.objectfinder.app.data.remote.SupabaseRestApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Albero dei luoghi del progetto attivo; offline-first, sincronizzato con la tabella `places`. */
class PlaceRepository(
    private val dao: PlaceDao,
    private val objects: ObjectDao,
    private val api: SupabaseRestApi,
    private val settings: SettingsStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val currentProject: String get() = settings.activeProject.value?.id ?: LOCAL_PROJECT_ID

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTree(): Flow<PlaceTree> =
        settings.activeProject.map { it?.id ?: LOCAL_PROJECT_ID }
            .flatMapLatest { dao.observeByProject(it) }
            .map(::PlaceTree)

    suspend fun create(name: String, parentId: String?): PlaceEntity {
        val clean = name.trim().take(PlaceTree.NAME_MAX)
        require(clean.isNotEmpty()) { "Scrivi il nome del luogo" }
        return PlaceEntity(UUID.randomUUID().toString(), currentProject, parentId, clean, PlaceSync.PENDING, now())
            .also { dao.upsert(it) }
    }

    suspend fun rename(id: String, name: String) {
        val place = dao.getById(id) ?: return
        val clean = name.trim().take(PlaceTree.NAME_MAX)
        require(clean.isNotEmpty()) { "Scrivi il nome del luogo" }
        dao.upsert(place.copy(name = clean, syncStatus = PlaceSync.PENDING, updatedAt = now()))
    }

    /** Cancella il luogo: sotto-luoghi al livello superiore, oggetti senza luogo (nulla va perso). */
    suspend fun delete(id: String) {
        val place = dao.getById(id) ?: return
        dao.detachChildren(id)
        objects.clearPlace(id)
        if (place.syncStatus == PlaceSync.PENDING && place.projectId == LOCAL_PROJECT_ID) dao.delete(id)
        else dao.upsert(place.copy(syncStatus = PlaceSync.PENDING_DELETE, updatedAt = now()))
    }

    /**
     * Sync: cancellazioni, poi inserimenti/aggiornamenti in ordine (genitori prima dei figli), poi
     * l'elenco completo dal server (pochi luoghi per progetto: niente cursori né tombstone).
     * Da chiamare prima del push degli oggetti, che possono riferirsi a luoghi nuovi.
     */
    suspend fun sync(projectId: String, canWrite: Boolean): Int {
        var changes = 0
        if (canWrite) {
            dao.byStatus(projectId, PlaceSync.PENDING_DELETE).forEach { place ->
                runCatching { api.deletePlace(place.id) }
                    .onFailure { if (it !is SupabaseException.Http || it.status !in 400..499) throw it }
                dao.delete(place.id)
                changes++
            }
            val pending = dao.byStatus(projectId, PlaceSync.PENDING)
            PlaceTree.parentsFirst(pending).forEach { place ->
                api.upsertPlace(PlaceRowDto(place.id, place.projectId, place.parentId, place.name))
                dao.upsert(place.copy(syncStatus = PlaceSync.SYNCED))
                changes++
            }
        }
        val remote = api.listPlaces(projectId)
        val local = dao.allByProject(projectId).associateBy { it.id }
        remote.forEach { row ->
            val mine = local[row.id]
            if (mine == null || mine.syncStatus == PlaceSync.SYNCED) {
                dao.upsert(PlaceEntity(row.id, row.projectId, row.parentId, row.name, PlaceSync.SYNCED, mine?.updatedAt ?: now()))
            }
        }
        dao.deleteSyncedExcept(projectId, remote.map { it.id }.ifEmpty { listOf("") })
        return changes
    }
}

/** Vista ad albero dei luoghi (pura, testabile): percorsi, discendenti, ordinamento. */
class PlaceTree(val places: List<PlaceEntity>) {
    private val byId = places.associateBy { it.id }
    private val children = places.groupBy { it.parentId }

    fun get(id: String?): PlaceEntity? = id?.let(byId::get)

    /** "Casa › Garage › Scaffale"; vuoto se il luogo non esiste (più). */
    fun path(id: String?): String = chain(id).joinToString(SEPARATOR) { it.name }

    /** Dal luogo principale a [id]; si ferma su cicli o genitori mancanti. */
    fun chain(id: String?): List<PlaceEntity> {
        val out = ArrayList<PlaceEntity>()
        var current = get(id)
        val seen = HashSet<String>()
        while (current != null && seen.add(current.id)) {
            out.add(0, current)
            current = get(current.parentId)
        }
        return out
    }

    /** Il luogo e tutti i suoi sotto-luoghi (per il filtro "cosa c'è in Garage"). */
    fun subtree(id: String): Set<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(id))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (out.add(next)) children[next].orEmpty().forEach { queue.add(it.id) }
        }
        return out
    }

    /** Elenco piatto in ordine di albero con la profondità, per il selettore. */
    fun flattened(): List<Pair<PlaceEntity, Int>> {
        val out = ArrayList<Pair<PlaceEntity, Int>>()
        fun visit(parent: String?, depth: Int, seen: Set<String>) {
            children[parent].orEmpty().sortedBy { it.name.lowercase() }.forEach {
                if (it.id in seen) return@forEach
                out += it to depth
                visit(it.id, depth + 1, seen + it.id)
            }
        }
        visit(null, 0, emptySet())
        // Luoghi con un genitore sparito (cancellato altrove) restano visibili al primo livello.
        places.filter { it.parentId != null && it.parentId !in byId && out.none { o -> o.first.id == it.id } }
            .forEach { out += it to 0 }
        return out
    }

    /** Si può spostare [id] sotto [newParent]? No se diventerebbe figlio di sé stesso. */
    fun canMove(id: String, newParent: String?): Boolean = newParent == null || newParent !in subtree(id)

    companion object {
        const val SEPARATOR = " › "
        const val NAME_MAX = 80

        fun parentsFirst(places: List<PlaceEntity>): List<PlaceEntity> {
            val ids = places.map { it.id }.toSet()
            val done = LinkedHashSet<String>()
            val byId = places.associateBy { it.id }
            fun add(p: PlaceEntity, guard: Int = 0) {
                if (p.id in done || guard > 16) return
                p.parentId?.takeIf { it in ids }?.let { byId[it]?.let { parent -> add(parent, guard + 1) } }
                done += p.id
            }
            places.forEach { add(it) }
            return done.mapNotNull(byId::get)
        }
    }
}

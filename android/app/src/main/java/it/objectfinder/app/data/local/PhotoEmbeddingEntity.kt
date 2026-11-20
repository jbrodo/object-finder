package it.objectfinder.app.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Impronta numerica (embedding) di una foto, per riconoscere gli oggetti inquadrandoli.
 * Calcolata sul telefono e condivisa sul server (migration 016): un telefono nuovo la scarica
 * invece di scaricare tutte le foto per ricalcolarla.
 */
@Entity(tableName = "photo_embeddings")
data class PhotoEmbeddingEntity(
    @PrimaryKey val photoId: String,
    val objectId: String,
    /** Vettore float32 little-endian, normalizzato L2. */
    val vector: ByteArray,
    /** Modello che l'ha prodotta: cambiando modello le impronte vecchie si ricalcolano. */
    val model: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Già sul server (scaricata da lì o inviata). */
    val uploaded: Boolean = false,
) {
    override fun equals(other: Any?) = other is PhotoEmbeddingEntity && other.photoId == photoId && other.model == model
    override fun hashCode() = photoId.hashCode()
}

@Dao
interface PhotoEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PhotoEmbeddingEntity)

    /** Impronte delle foto ancora visibili, di oggetti non archiviati del progetto. */
    @Query(
        "SELECT e.* FROM photo_embeddings e " +
            "JOIN object_photos p ON p.id = e.photoId AND p.syncStatus != 'pending_delete' " +
            "JOIN objects o ON o.id = e.objectId " +
            "WHERE e.model = :model AND o.projectId = :projectId AND o.status != 'archived'"
    )
    suspend fun forProject(projectId: String, model: String): List<PhotoEmbeddingEntity>

    /**
     * Foto senza impronta (o con un modello vecchio): sul telefono o solo sul server (da scaricare
     * temporaneamente). Prima quelle locali, che non costano rete.
     */
    @Query(
        "SELECT p.* FROM object_photos p " +
            "LEFT JOIN photo_embeddings e ON e.photoId = p.id AND e.model = :model " +
            "WHERE (p.localPath IS NOT NULL OR p.storagePath IS NOT NULL) " +
            "AND p.syncStatus != 'pending_delete' AND e.photoId IS NULL " +
            "ORDER BY (p.localPath IS NULL), p.createdAt"
    )
    suspend fun photosWithoutEmbedding(model: String): List<PhotoEntity>

    /** Impronte da inviare: foto già sul server, oggetti del progetto indicato. */
    @Query(
        "SELECT e.* FROM photo_embeddings e " +
            "JOIN object_photos p ON p.id = e.photoId AND p.syncStatus = 'synced' " +
            "JOIN objects o ON o.id = e.objectId " +
            "WHERE e.model = :model AND e.uploaded = 0 AND o.projectId = :projectId"
    )
    suspend fun notUploaded(projectId: String, model: String): List<PhotoEmbeddingEntity>

    @Query("UPDATE photo_embeddings SET uploaded = 1 WHERE model = :model AND photoId IN (:photoIds)")
    suspend fun markUploaded(photoIds: List<String>, model: String)

    /** Impronte di foto non più esistenti o di un modello precedente. */
    @Query(
        "DELETE FROM photo_embeddings WHERE model != :model " +
            "OR photoId NOT IN (SELECT id FROM object_photos WHERE syncStatus != 'pending_delete')"
    )
    suspend fun deleteStale(model: String): Int
}

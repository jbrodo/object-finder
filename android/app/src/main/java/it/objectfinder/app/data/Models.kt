package it.objectfinder.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Entity(tableName = "objects")
data class ObjectEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val name: String,
    val category: String,
    val notes: String = "",
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val status: String = "active",
    val syncStatus: String = "pending",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class ObjectDto(
    val id: String,
    @SerialName("project_id") val projectId: String,
    val name: String,
    val category: String,
    val notes: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("accuracy_meters") val accuracyMeters: Float? = null,
    val status: String = "active",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

fun ObjectEntity.toDto() = ObjectDto(
    id = id, projectId = projectId, name = name, category = category,
    notes = notes, latitude = latitude, longitude = longitude,
    accuracyMeters = accuracyMeters, status = status
)

package it.objectfinder.app.data.remote

import it.objectfinder.app.data.SyncMerge

import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.builtins.serializer

@Serializable
data class ProjectDto(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("owner_id") val ownerId: String,
)

@Serializable
data class MembershipRoleDto(val role: String)

/** Progetto con il ruolo dell'utente corrente (embed inner su project_members). */
@Serializable
data class ProjectWithRoleDto(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("owner_id") val ownerId: String,
    @SerialName("project_members") val memberships: List<MembershipRoleDto> = emptyList(),
)

@Serializable
data class ProjectMemberDto(
    @SerialName("user_id") val userId: String,
    val email: String? = null,
    val role: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
private data class ProjectArg(@SerialName("p_project") val project: String)

@Serializable
private data class AddMemberArgs(
    @SerialName("p_project") val project: String,
    @SerialName("p_email") val email: String,
    @SerialName("p_role") val role: String,
)

@Serializable
private data class RemoveMemberArgs(
    @SerialName("p_project") val project: String,
    @SerialName("p_user") val user: String,
)

@Serializable
data class PhotoRowDto(
    val id: String,
    @SerialName("object_id") val objectId: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("photo_type") val photoType: String = "reference",
    @SerialName("captured_at") val capturedAt: String? = null,
)

@Serializable
data class IdentifierRowDto(
    val id: String,
    @SerialName("object_id") val objectId: String,
    @SerialName("identifier_type") val type: String,
    @SerialName("identifier_value") val value: String,
)

@Serializable
data class DeletedObjectDto(
    @SerialName("object_id") val objectId: String,
    @SerialName("deleted_at") val deletedAt: String,
)

@Serializable
data class LocationRowDto(
    val id: String,
    @SerialName("object_id") val objectId: String,
    val source: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_meters") val accuracyMeters: Float?,
    @SerialName("recorded_at") val recordedAt: String,
)

@Serializable
data class InviteDto(
    val id: String,
    @SerialName("project_id") val projectId: String,
    val email: String,
    val role: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("accepted_at") val acceptedAt: String? = null,
)

@Serializable
private data class InviteArgs(
    @SerialName("p_project") val project: String,
    @SerialName("p_email") val email: String,
    @SerialName("p_role") val role: String,
)

@Serializable
private data class InviteEmailArgs(@SerialName("project_id") val projectId: String, val email: String)

@Serializable
private class NoArgs

@Serializable
private data class StoragePrefixes(val prefixes: List<String>)

/** Payload di scrittura: niente timestamp/version, gestiti dai trigger della migration 001. */
@Serializable
data class ObjectWriteDto(
    val id: String,
    @SerialName("project_id") val projectId: String,
    val name: String,
    val category: String,
    val notes: String,
    val place: String,
    val status: String,
    val latitude: Double?,
    val longitude: Double?,
    @SerialName("accuracy_meters") val accuracyMeters: Float?,
    @SerialName("location_source") val locationSource: String = "gps",
)

@Serializable
data class ObjectReadDto(
    val id: String,
    @SerialName("project_id") val projectId: String,
    val name: String,
    val category: String = "general",
    val notes: String = "",
    val place: String = "",
    val status: String = "active",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("accuracy_meters") val accuracyMeters: Float? = null,
    @SerialName("location_source") val locationSource: String = "gps",
    val version: Int = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

/** Fornisce un access token valido (refresh incluso) per le chiamate PostgREST. */
interface AccessTokenProvider {
    suspend fun validAccessToken(): String
    suspend fun forceRefresh(): String
}

class SupabaseRestApi(
    private val http: SupabaseHttp,
    private val tokens: AccessTokenProvider,
) {
    private val rest get() = "${http.baseUrl}/rest/v1"

    /** Progetti dell'utente con il suo ruolo (RLS restituisce solo quelli di cui è membro). */
    suspend fun listProjects(userId: String): List<ProjectWithRoleDto> = authorized { token ->
        http.client.get("$rest/projects") {
            auth(token)
            parameter("select", "id,name,description,owner_id,project_members!inner(role)")
            parameter("project_members.user_id", "eq.$userId")
            parameter("order", "created_at.asc")
        }
    }.body()

    suspend fun listMembers(projectId: String): List<ProjectMemberDto> = rpc("list_project_members", ProjectArg(projectId)).body()

    suspend fun addMember(projectId: String, email: String, role: String) {
        rpc("add_project_member", AddMemberArgs(projectId, email, role))
    }

    suspend fun removeMember(projectId: String, userId: String) {
        rpc("remove_project_member", RemoveMemberArgs(projectId, userId))
    }

    private suspend inline fun <reified T : Any> rpc(function: String, args: T): HttpResponse = authorized { token ->
        http.client.post("$rest/rpc/$function") {
            auth(token)
            contentType(ContentType.Application.Json)
            setBody(args)
        }
    }

    /**
     * L'id è generato dal client e la risposta è `return=minimal`: la policy SELECT su projects
     * dipende dalla membership creata dal trigger AFTER INSERT, non visibile nel RETURNING.
     */
    suspend fun createProject(project: ProjectDto) {
        authorized { token ->
            http.client.post("$rest/projects") {
                auth(token)
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(project)
            }
        }
    }

    /** Inserimento di un oggetto mai sincronizzato. Un 409 (già esistente) va gestito dal chiamante. */
    suspend fun insertObject(item: ObjectWriteDto): ObjectReadDto = authorized { token ->
        http.client.post("$rest/objects") {
            auth(token)
            parameter("select", OBJECT_COLUMNS)
            header("Prefer", "return=representation")
            header("Accept", "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(item)
        }
    }.body()

    /**
     * Concorrenza ottimistica: aggiorna solo se sul server c'è ancora la [expectedVersion] nota.
     * null = nessuna riga aggiornata (modificato da altri, eliminato o senza permesso).
     */
    suspend fun updateObjectIfVersion(item: ObjectWriteDto, expectedVersion: Int): ObjectReadDto? =
        authorized { token ->
            http.client.patch("$rest/objects") {
                auth(token)
                parameter("id", "eq.${item.id}")
                parameter("version", "eq.$expectedVersion")
                parameter("select", OBJECT_COLUMNS)
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                setBody(item)
            }
        }.body<List<ObjectReadDto>>().firstOrNull()

    suspend fun getObject(id: String): ObjectReadDto? = authorized { token ->
        http.client.get("$rest/objects") {
            auth(token)
            parameter("id", "eq.$id")
            parameter("select", OBJECT_COLUMNS)
        }
    }.body<List<ObjectReadDto>>().firstOrNull()

    /** Eliminazione definitiva (RLS: owner/admin). false = nessuna riga eliminata (permessi). */
    suspend fun deleteObject(id: String): Boolean = authorized { token ->
        http.client.delete("$rest/objects") {
            auth(token)
            parameter("id", "eq.$id")
            parameter("select", "id")
            header("Prefer", "return=representation")
        }
    }.body<List<JsonObject>>().isNotEmpty()

    /** Eliminazioni avvenute nel progetto dopo il cursore (migration 009). */
    suspend fun deletedObjectsSince(projectId: String, cursor: String?): List<DeletedObjectDto> = authorized { token ->
        http.client.get("$rest/deleted_objects") {
            auth(token)
            parameter("select", "object_id,deleted_at")
            parameter("project_id", "eq.$projectId")
            if (cursor != null) parameter("deleted_at", "gt.$cursor")
            parameter("order", "deleted_at.asc")
            parameter("limit", 1000)
        }
    }.body()

    suspend fun photoPathsForObject(objectId: String): List<String> = authorized { token ->
        http.client.get("$rest/object_photos") {
            auth(token)
            parameter("select", "storage_path")
            parameter("object_id", "eq.$objectId")
        }
    }.body<List<JsonObject>>().mapNotNull { it["storage_path"]?.jsonPrimitive?.contentOrNull }

    /** Pull incrementale con keyset `(updated_at, id)` esclusivo: nessuna riga saltata tra le pagine. */
    suspend fun objectsUpdatedSince(
        projectId: String,
        cursor: SyncMerge.PullCursor?,
        limit: Int = 500,
    ): List<ObjectReadDto> =
        authorized { token ->
            http.client.get("$rest/objects") {
                auth(token)
                parameter("select", OBJECT_COLUMNS)
                parameter("project_id", "eq.$projectId")
                if (cursor != null) parameter("or", keysetAfter(cursor))
                parameter("order", "updated_at.asc,id.asc")
                parameter("limit", limit)
            }
        }.body()

    // ---- Foto: righe object_photos ----

    /** Tutte le foto degli oggetti del progetto (embed inner per filtrare per progetto). */
    suspend fun listPhotos(projectId: String): List<PhotoRowDto> = authorized { token ->
        http.client.get("$rest/object_photos") {
            auth(token)
            parameter("select", "id,object_id,storage_path,photo_type,captured_at,objects!inner(project_id)")
            parameter("objects.project_id", "eq.$projectId")
            parameter("order", "created_at.asc")
            parameter("limit", 5000)
        }
    }.body()

    /** Inserimento idempotente: se la riga esiste già (retry dopo errore di rete) viene ignorata. */
    suspend fun insertPhotoRow(row: PhotoRowDto) {
        authorized { token ->
            http.client.post("$rest/object_photos") {
                auth(token)
                parameter("on_conflict", "id")
                header("Prefer", "resolution=ignore-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(row)
            }
        }
    }

    suspend fun deletePhotoRow(id: String) {
        authorized { token ->
            http.client.delete("$rest/object_photos") {
                auth(token)
                parameter("id", "eq.$id")
            }
        }
    }

    // ---- Codici collegati (object_identifiers) ----

    suspend fun listIdentifiers(projectId: String): List<IdentifierRowDto> = authorized { token ->
        http.client.get("$rest/object_identifiers") {
            auth(token)
            parameter("select", "id,object_id,identifier_type,identifier_value,objects!inner(project_id)")
            parameter("objects.project_id", "eq.$projectId")
            parameter("order", "created_at.asc")
            parameter("limit", 10000)
        }
    }.body()

    suspend fun insertIdentifier(row: IdentifierRowDto) {
        authorized { token ->
            http.client.post("$rest/object_identifiers") {
                auth(token)
                parameter("on_conflict", "id")
                header("Prefer", "resolution=ignore-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(row)
            }
        }
    }

    suspend fun deleteIdentifier(id: String) {
        authorized { token ->
            http.client.delete("$rest/object_identifiers") {
                auth(token)
                parameter("id", "eq.$id")
            }
        }
    }

    // ---- Storico posizioni (tabella locations, migration 008) ----

    /** Inserimento idempotente (stesso id → ignorato): i retry dopo errori di rete sono sicuri. */
    suspend fun insertLocation(row: LocationRowDto) {
        authorized { token ->
            http.client.post("$rest/locations") {
                auth(token)
                parameter("on_conflict", "id")
                header("Prefer", "resolution=ignore-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(row)
            }
        }
    }

    suspend fun locationsForObject(objectId: String, limit: Int = 50): List<LocationRowDto> = authorized { token ->
        http.client.get("$rest/locations") {
            auth(token)
            parameter("select", "id,object_id,source,latitude,longitude,accuracy_meters,recorded_at")
            parameter("object_id", "eq.$objectId")
            parameter("order", "recorded_at.desc")
            parameter("limit", limit)
        }
    }.body()

    // ---- Inviti (migration 007) ----

    /** 'added' se l'account esiste (aggiunto subito), 'invited' se è stato creato un invito. */
    suspend fun inviteMember(projectId: String, email: String, role: String): String =
        // PostgREST restituisce uno scalare JSON ("invited"): body<String>() darebbe il testo grezzo con le virgolette.
        supabaseJson.decodeFromString(String.serializer(), rpc("invite_project_member", InviteArgs(projectId, email, role)).bodyAsText())

    /** Chiede alla Edge Function `send-invite` di mandare l'email all'invitato (indirizzo letto dal DB). */
    suspend fun sendInviteEmail(projectId: String, email: String) {
        authorized { token ->
            http.client.post("${http.baseUrl}/functions/v1/send-invite") {
                auth(token)
                contentType(ContentType.Application.Json)
                setBody(InviteEmailArgs(projectId, email))
            }
        }
    }

    suspend fun acceptMyInvites(): Int = rpc("accept_my_invites", NoArgs()).body<Int>()

    suspend fun listPendingInvites(projectId: String): List<InviteDto> = authorized { token ->
        http.client.get("$rest/project_invites") {
            auth(token)
            parameter("select", "id,project_id,email,role,created_at,accepted_at")
            parameter("project_id", "eq.$projectId")
            parameter("accepted_at", "is.null")
            parameter("order", "created_at.desc")
        }
    }.body()

    suspend fun revokeInvite(id: String) {
        authorized { token ->
            http.client.delete("$rest/project_invites") {
                auth(token)
                parameter("id", "eq.$id")
            }
        }
    }

    // ---- Foto: file su Storage (bucket privato) ----

    suspend fun uploadPhoto(path: String, jpeg: ByteArray) {
        http.requireConfigured()
        val upload: suspend (String) -> HttpResponse = { token ->
            http.client.post("${http.baseUrl}/storage/v1/object/$PHOTO_BUCKET/$path") {
                auth(token)
                header("x-upsert", "false")
                contentType(ContentType.Image.JPEG)
                setBody(jpeg)
            }
        }
        var response = upload(tokens.validAccessToken())
        if (response.status == HttpStatusCode.Unauthorized) response = upload(tokens.forceRefresh())
        // Un retry dopo un upload riuscito ma non confermato riceve "already exists": va bene.
        if (response.status == HttpStatusCode.Conflict || isDuplicate(response)) return
        response.ensureSuccess()
    }

    suspend fun downloadPhoto(path: String): ByteArray = authorized { token ->
        http.client.get("${http.baseUrl}/storage/v1/object/authenticated/$PHOTO_BUCKET/$path") { auth(token) }
    }.body()

    suspend fun deletePhotoFile(path: String) {
        authorized { token ->
            http.client.delete("${http.baseUrl}/storage/v1/object/$PHOTO_BUCKET") {
                auth(token)
                contentType(ContentType.Application.Json)
                setBody(StoragePrefixes(listOf(path)))
            }
        }
    }

    private suspend fun isDuplicate(response: HttpResponse): Boolean {
        if (response.status != HttpStatusCode.BadRequest) return false
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        return body.contains("already exists", ignoreCase = true) || body.contains("\"409\"")
    }

    private fun HttpRequestBuilder.auth(token: String) {
        header("apikey", http.anonKey)
        header("Authorization", "Bearer $token")
    }

    /** Esegue la richiesta; su 401 forza un refresh del token e ritenta una sola volta. */
    private suspend fun authorized(block: suspend (String) -> HttpResponse): HttpResponse {
        http.requireConfigured()
        val first = block(tokens.validAccessToken())
        if (first.status != HttpStatusCode.Unauthorized) return first.ensureSuccess()
        return block(tokens.forceRefresh()).ensureSuccess()
    }

    companion object {
        /** Filtro PostgREST "dopo il cursore": updated_at maggiore, oppure uguale con id maggiore. */
        fun keysetAfter(cursor: SyncMerge.PullCursor): String {
            val at = "\"${cursor.updatedAt}\""
            return if (cursor.id.isEmpty()) "(updated_at.gt.$at)"
            else "(updated_at.gt.$at,and(updated_at.eq.$at,id.gt.${cursor.id}))"
        }

        const val PHOTO_BUCKET = "object-photos"
        private const val OBJECT_COLUMNS =
            "id,project_id,name,category,notes,place,status,latitude,longitude,accuracy_meters,location_source,version,created_at,updated_at"
    }
}

package it.objectfinder.app.data.remote

import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

/** Payload di scrittura: niente timestamp/version, gestiti dai trigger della migration 001. */
@Serializable
data class ObjectWriteDto(
    val id: String,
    @SerialName("project_id") val projectId: String,
    val name: String,
    val category: String,
    val notes: String,
    val status: String,
    val latitude: Double?,
    val longitude: Double?,
    @SerialName("accuracy_meters") val accuracyMeters: Float?,
)

@Serializable
data class ObjectReadDto(
    val id: String,
    @SerialName("project_id") val projectId: String,
    val name: String,
    val category: String = "general",
    val notes: String = "",
    val status: String = "active",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("accuracy_meters") val accuracyMeters: Float? = null,
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

    suspend fun upsertObject(item: ObjectWriteDto): ObjectReadDto = authorized { token ->
        http.client.post("$rest/objects") {
            auth(token)
            parameter("on_conflict", "id")
            parameter("select", OBJECT_COLUMNS)
            header("Prefer", "resolution=merge-duplicates,return=representation")
            header("Accept", "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(item)
        }
    }.body()

    /** Pull incrementale ordinato per `updated_at` (cursore esclusivo). */
    suspend fun objectsUpdatedSince(projectId: String, cursor: String?, limit: Int = 500): List<ObjectReadDto> =
        authorized { token ->
            http.client.get("$rest/objects") {
                auth(token)
                parameter("select", OBJECT_COLUMNS)
                parameter("project_id", "eq.$projectId")
                if (cursor != null) parameter("updated_at", "gt.$cursor")
                parameter("order", "updated_at.asc")
                parameter("limit", limit)
            }
        }.body()

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

    private companion object {
        const val OBJECT_COLUMNS =
            "id,project_id,name,category,notes,status,latitude,longitude,accuracy_meters,version,created_at,updated_at"
    }
}

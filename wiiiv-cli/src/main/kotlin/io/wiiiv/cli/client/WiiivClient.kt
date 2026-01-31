package io.wiiiv.cli.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.wiiiv.api.dto.common.ApiResponse
import io.wiiiv.api.dto.decision.*
import io.wiiiv.api.dto.blueprint.*
import io.wiiiv.api.dto.execution.*
import io.wiiiv.api.dto.auth.*
import io.wiiiv.api.dto.system.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * wiiiv API 클라이언트
 *
 * CLI에서 wiiiv-api를 호출하기 위한 HTTP 클라이언트
 */
class WiiivClient(
    private val baseUrl: String = "http://localhost:8235",
    private var token: String? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    // ==================== Auth ====================

    suspend fun autoLogin(): String {
        val response = client.get("$baseUrl/api/v2/auth/auto-login")
        val body = response.bodyAsText()
        val jsonObj = json.parseToJsonElement(body).jsonObject
        val accessToken = jsonObj["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content
            ?: throw RuntimeException("Failed to get access token")
        token = accessToken
        return accessToken
    }

    suspend fun login(username: String, password: String): String {
        val response = client.post("$baseUrl/api/v2/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }

        if (response.status != HttpStatusCode.OK) {
            throw RuntimeException("Login failed: ${response.status}")
        }

        val body = response.bodyAsText()
        val jsonObj = json.parseToJsonElement(body).jsonObject
        val accessToken = jsonObj["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content
            ?: throw RuntimeException("Failed to get access token")
        token = accessToken
        return accessToken
    }

    // ==================== Decision ====================

    suspend fun createDecision(intent: String, constraints: List<String>? = null): JsonObject {
        ensureToken()
        val response = client.post("$baseUrl/api/v2/decisions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(DecisionRequest(
                spec = SpecInput(intent = intent, constraints = constraints)
            ))
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun getDecision(id: String): JsonObject {
        ensureToken()
        val response = client.get("$baseUrl/api/v2/decisions/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun approveDecision(id: String): JsonObject {
        ensureToken()
        val response = client.post("$baseUrl/api/v2/decisions/$id/approve") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun rejectDecision(id: String): JsonObject {
        ensureToken()
        val response = client.post("$baseUrl/api/v2/decisions/$id/reject") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    // ==================== Blueprint ====================

    suspend fun listBlueprints(page: Int = 1, pageSize: Int = 20): JsonObject {
        ensureToken()
        val response = client.get("$baseUrl/api/v2/blueprints") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("page", page)
            parameter("pageSize", pageSize)
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun getBlueprint(id: String): JsonObject {
        ensureToken()
        val response = client.get("$baseUrl/api/v2/blueprints/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun validateBlueprint(id: String): JsonObject {
        ensureToken()
        val response = client.post("$baseUrl/api/v2/blueprints/$id/validate") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    // ==================== Execution ====================

    suspend fun createExecution(blueprintId: String, dryRun: Boolean = false): JsonObject {
        ensureToken()
        val response = client.post("$baseUrl/api/v2/executions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(ExecutionRequest(
                blueprintId = blueprintId,
                options = ExecutionOptions(dryRun = dryRun)
            ))
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun listExecutions(blueprintId: String? = null, page: Int = 1, pageSize: Int = 20): JsonObject {
        ensureToken()
        val response = client.get("$baseUrl/api/v2/executions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("page", page)
            parameter("pageSize", pageSize)
            blueprintId?.let { parameter("blueprintId", it) }
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun getExecution(id: String): JsonObject {
        ensureToken()
        val response = client.get("$baseUrl/api/v2/executions/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun cancelExecution(id: String): JsonObject {
        ensureToken()
        val response = client.post("$baseUrl/api/v2/executions/$id/cancel") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun getExecutionLogs(id: String): JsonObject {
        ensureToken()
        val response = client.get("$baseUrl/api/v2/executions/$id/logs") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    // ==================== System ====================

    suspend fun health(): JsonObject {
        val response = client.get("$baseUrl/api/v2/system/health")
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun info(): JsonObject {
        val response = client.get("$baseUrl/api/v2/system/info")
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun listExecutors(): JsonObject {
        ensureToken()
        val response = client.get("$baseUrl/api/v2/system/executors") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun listGates(): JsonObject {
        ensureToken()
        val response = client.get("$baseUrl/api/v2/system/gates") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun listPersonas(): JsonObject {
        ensureToken()
        val response = client.get("$baseUrl/api/v2/system/personas") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    // ==================== RAG ====================

    suspend fun ragIngest(content: String, title: String? = null, documentId: String? = null): JsonObject {
        ensureToken()
        val response = client.post("$baseUrl/api/v2/rag/ingest") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(buildJsonObject {
                put("content", content)
                title?.let { put("title", it) }
                documentId?.let { put("documentId", it) }
            })
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun ragSearch(query: String, topK: Int = 5, minScore: Float = 0.0f): JsonObject {
        ensureToken()
        val response = client.post("$baseUrl/api/v2/rag/search") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(buildJsonObject {
                put("query", query)
                put("topK", topK)
                put("minScore", minScore)
            })
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun ragSize(): JsonObject {
        ensureToken()
        val response = client.get("$baseUrl/api/v2/rag/size") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun ragDocuments(): JsonObject {
        ensureToken()
        val response = client.get("$baseUrl/api/v2/rag/documents") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun ragDelete(documentId: String): JsonObject {
        ensureToken()
        val response = client.delete("$baseUrl/api/v2/rag/$documentId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun ragClear(): JsonObject {
        ensureToken()
        val response = client.delete("$baseUrl/api/v2/rag") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    // ==================== Utilities ====================

    private suspend fun ensureToken() {
        if (token == null) {
            autoLogin()
        }
    }

    fun setToken(newToken: String) {
        token = newToken
    }

    fun close() {
        client.close()
    }

    // Generic request methods
    suspend fun get(path: String): JsonObject {
        ensureToken()
        val response = client.get("$baseUrl$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun post(path: String, body: Any? = null): JsonObject {
        ensureToken()
        val response = client.post("$baseUrl$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }
}

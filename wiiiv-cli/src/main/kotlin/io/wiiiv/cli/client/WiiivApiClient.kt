package io.wiiiv.cli.client

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.request.forms.*
import io.ktor.utils.io.*
import io.wiiiv.cli.model.LocalImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * wiiiv Server API Client
 *
 * HTTP/SSE를 통해 wiiiv-server와 통신한다.
 * core 의존성 없이 서버 API만으로 모든 기능을 수행한다.
 */
class WiiivApiClient(
    private val baseUrl: String = "http://localhost:8235",
    httpClient: HttpClient? = null
) {
    internal val jsonParser = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val httpClient: HttpClient = httpClient ?: HttpClient(CIO) {
        install(ContentNegotiation) {
            json(jsonParser)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000  // 5분 — LLM 응답 대기
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 300_000
        }
    }

    private var token: String? = null

    /** 저장된 토큰을 복원한다. */
    fun setToken(savedToken: String) {
        token = savedToken
    }

    /** 현재 토큰을 반환한다 (저장용). */
    fun getToken(): String? = token

    /** GET /auth/me 로 토큰 유효성을 검증한다. */
    suspend fun validateToken(): Boolean {
        return try {
            val response = httpClient.get("$baseUrl/api/v2/auth/me") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            response.status == HttpStatusCode.OK
        } catch (_: Exception) {
            false
        }
    }

    // === Auth ===

    suspend fun autoLogin(): String {
        val response = httpClient.get("$baseUrl/api/v2/auth/auto-login")
        val body = jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
            ?: throw RuntimeException("Auto-login failed: ${response.bodyAsText()}")
        val accessToken = data["accessToken"]?.jsonPrimitive?.content
            ?: throw RuntimeException("No accessToken in response")
        token = accessToken
        return accessToken
    }

    suspend fun login(username: String, password: String): String {
        val response = httpClient.post("$baseUrl/api/v2/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        val body = jsonParser.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
            ?: throw RuntimeException("Login failed: ${response.bodyAsText()}")
        val accessToken = data["accessToken"]?.jsonPrimitive?.content
            ?: throw RuntimeException("No accessToken in response")
        token = accessToken
        return accessToken
    }

    // === Session Lifecycle ===

    suspend fun createSession(workspace: String? = null): SessionResponse {
        val response = httpClient.post("$baseUrl/api/v2/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(workspace))
        }
        return parseData(response)
    }

    suspend fun deleteSession(sessionId: String) {
        httpClient.delete("$baseUrl/api/v2/sessions/$sessionId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    // === Chat (SSE Streaming) ===

    suspend fun chat(
        sessionId: String,
        message: String,
        images: List<LocalImage>? = null,
        autoContinue: Boolean = true,
        maxContinue: Int = 10,
        onProgress: (ProgressEventDto) -> Unit,
        onResponse: (ChatResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        val imageData = images?.map { ImageData(it.base64, it.mimeType) }
        val request = ChatRequest(
            message = message,
            images = imageData,
            autoContinue = autoContinue,
            maxContinue = maxContinue
        )

        httpClient.preparePost("$baseUrl/api/v2/sessions/$sessionId/chat") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.execute { response ->
            val channel: ByteReadChannel = response.bodyAsChannel()
            val reader = StringBuilder()

            var currentEvent = ""
            var currentData = ""

            while (!channel.isClosedForRead) {
                val line = try {
                    channel.readUTF8Line()
                } catch (_: Exception) {
                    null
                } ?: break

                if (line.startsWith("event: ")) {
                    currentEvent = line.removePrefix("event: ").trim()
                } else if (line.startsWith("data: ")) {
                    currentData = line.removePrefix("data: ").trim()
                } else if (line.isBlank() && currentEvent.isNotEmpty()) {
                    // Process complete event
                    try {
                        when (currentEvent) {
                            "progress" -> {
                                val event = jsonParser.decodeFromString<ProgressEventDto>(currentData)
                                onProgress(event)
                            }
                            "response" -> {
                                val chatResp = jsonParser.decodeFromString<ChatResponse>(currentData)
                                onResponse(chatResp)
                            }
                            "error" -> {
                                val errorObj = jsonParser.parseToJsonElement(currentData).jsonObject
                                val msg = errorObj["message"]?.jsonPrimitive?.content ?: currentData
                                onError(msg)
                            }
                            "done" -> {
                                // Stream complete
                            }
                        }
                    } catch (e: Exception) {
                        onError("SSE parse error: ${e.message}")
                    }
                    currentEvent = ""
                    currentData = ""
                }
            }
        }
    }

    // === Session State (New Endpoints) ===

    suspend fun getSessionState(sessionId: String): SessionStateResponse {
        val response = httpClient.get("$baseUrl/api/v2/sessions/$sessionId/state") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return parseData(response)
    }

    suspend fun getHistory(sessionId: String, count: Int = 20): HistoryResponse {
        val response = httpClient.get("$baseUrl/api/v2/sessions/$sessionId/history") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("count", count)
        }
        return parseData(response)
    }

    suspend fun controlSession(sessionId: String, request: ControlRequest): ControlResponse {
        val response = httpClient.post("$baseUrl/api/v2/sessions/$sessionId/control") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return parseData(response)
    }

    // === RAG ===

    suspend fun ragSize(): Int {
        val response = httpClient.get("$baseUrl/api/v2/rag/size") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val data: SizeResponse = parseData(response)
        return data.size
    }

    suspend fun ragDocuments(): DocumentListResponse {
        val response = httpClient.get("$baseUrl/api/v2/rag/documents") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return parseData(response)
    }

    suspend fun ragSearch(query: String, topK: Int = 5): SearchResponse {
        val response = httpClient.post("$baseUrl/api/v2/rag/search") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(SearchRequest(query, topK))
        }
        return parseData(response)
    }

    suspend fun ragIngest(content: String, title: String): IngestResponse {
        val response = httpClient.post("$baseUrl/api/v2/rag/ingest") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(IngestRequest(content, mapOf("title" to title)))
        }
        return parseData(response)
    }

    suspend fun ragIngestFile(file: java.io.File): IngestResponse {
        val response = httpClient.submitFormWithBinaryData(
            url = "$baseUrl/api/v2/rag/ingest/file",
            formData = formData {
                append("file", file.readBytes(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                    append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                })
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return parseData(response)
    }

    suspend fun ragDelete(documentId: String): Int {
        val response = httpClient.delete("$baseUrl/api/v2/rag/$documentId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val data: DeleteResponse = parseData(response)
        return data.deleted
    }

    // === HLX Workflows ===

    suspend fun hlxCreate(workflowJson: String): HlxWorkflowDto {
        val response = httpClient.post("$baseUrl/api/v2/workflows") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"workflow":${jsonParser.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), kotlinx.serialization.json.JsonPrimitive(workflowJson))}}""")
        }
        return parseData(response)
    }

    suspend fun hlxList(): HlxWorkflowListDto {
        val response = httpClient.get("$baseUrl/api/v2/workflows") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return parseData(response)
    }

    suspend fun hlxGet(id: String): HlxWorkflowDetailDto {
        val response = httpClient.get("$baseUrl/api/v2/workflows/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return parseData(response)
    }

    suspend fun hlxValidate(id: String): HlxValidateDto {
        val response = httpClient.post("$baseUrl/api/v2/workflows/$id/validate") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return parseData(response)
    }

    suspend fun hlxDelete(id: String): HlxDeleteDto {
        val response = httpClient.delete("$baseUrl/api/v2/workflows/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return parseData(response)
    }

    suspend fun hlxExecute(id: String, variables: Map<String, String> = emptyMap()): HlxExecutionDto {
        val varsJson = variables.entries.joinToString(",") { (k, v) ->
            "\"$k\":\"$v\""
        }
        val response = httpClient.post("$baseUrl/api/v2/workflows/$id/execute") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"variables":{$varsJson}}""")
        }
        return parseData(response)
    }

    suspend fun hlxExecutions(workflowId: String): HlxExecutionListDto {
        val response = httpClient.get("$baseUrl/api/v2/workflows/$workflowId/executions") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return parseData(response)
    }

    suspend fun hlxExecution(executionId: String): HlxExecutionDto {
        val response = httpClient.get("$baseUrl/api/v2/workflows/executions/$executionId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return parseData(response)
    }

    // === Health ===

    suspend fun healthCheck(): Boolean {
        return try {
            val response = httpClient.get("$baseUrl/api/v2/system/health")
            response.status == HttpStatusCode.OK
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getServerVersion(): String? {
        return try {
            val response = httpClient.get("$baseUrl/api/v2/system/info")
            val info: SystemInfoDto = parseData(response)
            info.version
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        httpClient.close()
    }

    // === Helpers ===

    private suspend inline fun <reified T> parseData(response: HttpResponse): T {
        val bodyText = response.bodyAsText()
        val apiResp = jsonParser.decodeFromString<ApiResponse<T>>(bodyText)
        if (!apiResp.success || apiResp.data == null) {
            throw RuntimeException(apiResp.error?.message ?: "API error: $bodyText")
        }
        return apiResp.data
    }
}

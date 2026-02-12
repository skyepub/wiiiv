package io.wiiiv.cli

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.wiiiv.cli.client.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WiiivApiClient 단위 테스트 — Ktor MockEngine 사용
 */
class WiiivApiClientTest {

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private fun createMockClient(handler: MockRequestHandler): WiiivApiClient {
        val mockEngine = MockEngine(handler)
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(jsonParser)
            }
        }
        return WiiivApiClient("http://localhost:8235", httpClient)
    }

    // === Auth ===

    @Test
    fun `autoLogin should extract and store token`() = runBlocking {
        val client = createMockClient { request ->
            assertEquals("/api/v2/auth/auto-login", request.url.encodedPath)
            respond(
                content = """{"success":true,"data":{"accessToken":"test-token-123","userId":"dev-user"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val token = client.autoLogin()
        assertEquals("test-token-123", token)
    }

    @Test
    fun `login should extract and store token`() = runBlocking {
        val client = createMockClient { request ->
            assertEquals("/api/v2/auth/login", request.url.encodedPath)
            respond(
                content = """{"success":true,"data":{"accessToken":"admin-token","userId":"admin"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val token = client.login("admin", "admin123")
        assertEquals("admin-token", token)
    }

    // === Session ===

    @Test
    fun `createSession should return session response`() = runBlocking {
        var callCount = 0
        val client = createMockClient { request ->
            callCount++
            when {
                request.url.encodedPath == "/api/v2/auth/auto-login" ->
                    respond(
                        content = """{"success":true,"data":{"accessToken":"tok","userId":"u1"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                request.url.encodedPath == "/api/v2/sessions" && request.method == HttpMethod.Post ->
                    respond(
                        content = """{"success":true,"data":{"sessionId":"sess-1","userId":"u1","createdAt":"2025-01-01T00:00:00Z"}}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                else -> error("Unexpected: ${request.url.encodedPath}")
            }
        }

        client.autoLogin()
        val session = client.createSession("/tmp/workspace")
        assertEquals("sess-1", session.sessionId)
    }

    // === Session State ===

    @Test
    fun `getSessionState should parse full response`() = runBlocking {
        val client = createMockClient { request ->
            when {
                request.url.encodedPath == "/api/v2/auth/auto-login" ->
                    respond(
                        content = """{"success":true,"data":{"accessToken":"tok","userId":"u1"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                request.url.encodedPath.endsWith("/state") ->
                    respond(
                        content = """{
                            "success":true,
                            "data":{
                                "sessionId":"sess-1",
                                "createdAt":1700000000000,
                                "turnCount":3,
                                "spec":null,
                                "activeTask":null,
                                "tasks":[],
                                "declaredWriteIntent":null,
                                "workspace":"/tmp",
                                "serverInfo":{
                                    "modelName":"gpt-4o-mini",
                                    "dacsTypeName":"HybridDACS",
                                    "llmAvailable":true,
                                    "ragAvailable":true
                                }
                            }
                        }""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                else -> error("Unexpected: ${request.url.encodedPath}")
            }
        }

        client.autoLogin()
        val state = client.getSessionState("sess-1")
        assertEquals("sess-1", state.sessionId)
        assertEquals(3, state.turnCount)
        assertEquals("HybridDACS", state.serverInfo.dacsTypeName)
        assertTrue(state.serverInfo.llmAvailable)
        assertTrue(state.tasks.isEmpty())
    }

    // === History ===

    @Test
    fun `getHistory should parse messages`() = runBlocking {
        val client = createMockClient { request ->
            when {
                request.url.encodedPath == "/api/v2/auth/auto-login" ->
                    respond(
                        content = """{"success":true,"data":{"accessToken":"tok","userId":"u1"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                request.url.encodedPath.endsWith("/history") ->
                    respond(
                        content = """{
                            "success":true,
                            "data":{
                                "messages":[
                                    {"role":"USER","content":"hello","timestamp":1700000000000},
                                    {"role":"GOVERNOR","content":"Hi!","timestamp":1700000001000}
                                ],
                                "total":2
                            }
                        }""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                else -> error("Unexpected: ${request.url.encodedPath}")
            }
        }

        client.autoLogin()
        val history = client.getHistory("sess-1", 10)
        assertEquals(2, history.total)
        assertEquals(2, history.messages.size)
        assertEquals("USER", history.messages[0].role)
        assertEquals("hello", history.messages[0].content)
    }

    // === Control ===

    @Test
    fun `controlSession should parse response`() = runBlocking {
        val client = createMockClient { request ->
            when {
                request.url.encodedPath == "/api/v2/auth/auto-login" ->
                    respond(
                        content = """{"success":true,"data":{"accessToken":"tok","userId":"u1"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                request.url.encodedPath.endsWith("/control") ->
                    respond(
                        content = """{"success":true,"data":{"success":true,"message":"Current spec cleared"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                else -> error("Unexpected: ${request.url.encodedPath}")
            }
        }

        client.autoLogin()
        val result = client.controlSession("sess-1", ControlRequest("resetSpec"))
        assertTrue(result.success)
        assertEquals("Current spec cleared", result.message)
    }

    // === RAG ===

    @Test
    fun `ragSize should return chunk count`() = runBlocking {
        val client = createMockClient { request ->
            when {
                request.url.encodedPath == "/api/v2/auth/auto-login" ->
                    respond(
                        content = """{"success":true,"data":{"accessToken":"tok","userId":"u1"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                request.url.encodedPath == "/api/v2/rag/size" ->
                    respond(
                        content = """{"success":true,"data":{"size":42}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                else -> error("Unexpected: ${request.url.encodedPath}")
            }
        }

        client.autoLogin()
        assertEquals(42, client.ragSize())
    }

    @Test
    fun `ragDocuments should return document list`() = runBlocking {
        val client = createMockClient { request ->
            when {
                request.url.encodedPath == "/api/v2/auth/auto-login" ->
                    respond(
                        content = """{"success":true,"data":{"accessToken":"tok","userId":"u1"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                request.url.encodedPath == "/api/v2/rag/documents" ->
                    respond(
                        content = """{
                            "success":true,
                            "data":{
                                "documents":[
                                    {"documentId":"doc-1","chunkCount":5,"metadata":{"title":"test.txt"}}
                                ],
                                "total":1
                            }
                        }""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                else -> error("Unexpected: ${request.url.encodedPath}")
            }
        }

        client.autoLogin()
        val docs = client.ragDocuments()
        assertEquals(1, docs.total)
        assertEquals("doc-1", docs.documents[0].documentId)
        assertEquals(5, docs.documents[0].chunkCount)
    }

    @Test
    fun `ragSearch should return search results`() = runBlocking {
        val client = createMockClient { request ->
            when {
                request.url.encodedPath == "/api/v2/auth/auto-login" ->
                    respond(
                        content = """{"success":true,"data":{"accessToken":"tok","userId":"u1"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                request.url.encodedPath == "/api/v2/rag/search" ->
                    respond(
                        content = """{
                            "success":true,
                            "data":{
                                "results":[
                                    {"content":"hello world","score":0.95,"sourceId":"doc-1","chunkIndex":0}
                                ],
                                "totalFound":1
                            }
                        }""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                else -> error("Unexpected: ${request.url.encodedPath}")
            }
        }

        client.autoLogin()
        val result = client.ragSearch("hello", topK = 5)
        assertEquals(1, result.totalFound)
        assertEquals(0.95, result.results[0].score, 0.01)
    }

    // === Health ===

    @Test
    fun `healthCheck should return true on 200`() = runBlocking {
        val client = createMockClient { request ->
            respond(
                content = """{"status":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        assertTrue(client.healthCheck())
    }

    // === Token management ===

    @Test
    fun `setToken and getToken should work`() {
        val client = createMockClient { request ->
            respond("", HttpStatusCode.OK)
        }

        assertNull(client.getToken())
        client.setToken("my-saved-token")
        assertEquals("my-saved-token", client.getToken())
    }

    @Test
    fun `validateToken should return true on 200`() = runBlocking {
        val client = createMockClient { request ->
            assertEquals("/api/v2/auth/me", request.url.encodedPath)
            respond(
                content = """{"success":true,"data":{"userId":"admin","username":"admin"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        client.setToken("valid-token")
        assertTrue(client.validateToken())
    }

    @Test
    fun `validateToken should return false on 401`() = runBlocking {
        val client = createMockClient { request ->
            respond(
                content = """{"error":"Unauthorized"}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        client.setToken("expired-token")
        assertFalse(client.validateToken())
    }

    // === Error handling ===

    @Test
    fun `API error should throw RuntimeException`() = runBlocking {
        val client = createMockClient { request ->
            when {
                request.url.encodedPath == "/api/v2/auth/auto-login" ->
                    respond(
                        content = """{"success":true,"data":{"accessToken":"tok","userId":"u1"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                else ->
                    respond(
                        content = """{"success":false,"error":{"code":"NOT_FOUND","message":"Session not found"}}""",
                        status = HttpStatusCode.NotFound,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
            }
        }

        client.autoLogin()
        val ex = assertThrows<RuntimeException> {
            client.getSessionState("non-existent")
        }
        assertTrue(ex.message?.contains("Session not found") == true)
    }
}

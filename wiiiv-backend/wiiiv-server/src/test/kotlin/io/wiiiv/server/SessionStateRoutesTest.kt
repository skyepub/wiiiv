package io.wiiiv.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Session State/History/Control 엔드포인트 통합 테스트
 *
 * Phase 3에서 추가된 3개 엔드포인트를 검증한다:
 * - GET /sessions/{id}/state
 * - GET /sessions/{id}/history
 * - POST /sessions/{id}/control
 */
class SessionStateRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ApplicationTestBuilder.getToken(): String {
        val loginResponse = client.get("/api/v2/auth/auto-login")
        val loginBody = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
        return loginBody["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content
            ?: error("Failed to get token")
    }

    private suspend fun ApplicationTestBuilder.getOtherUserToken(): String {
        val loginResponse = client.post("/api/v2/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username": "admin", "password": "admin123"}""")
        }
        val loginBody = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
        return loginBody["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content
            ?: error("Failed to get token")
    }

    private suspend fun ApplicationTestBuilder.createSession(token: String): String {
        val response = client.post("/api/v2/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["data"]?.jsonObject?.get("sessionId")?.jsonPrimitive?.content
            ?: error("Failed to create session")
    }

    // === GET /sessions/{id}/state ===

    @Test
    fun `GET state should return session state`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.get("/api/v2/sessions/$sessionId/state") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(sessionId, data["sessionId"]?.jsonPrimitive?.content)
        assertNotNull(data["createdAt"]?.jsonPrimitive?.long)
        assertNotNull(data["turnCount"]?.jsonPrimitive?.int)
        assertNotNull(data["tasks"]?.jsonArray)
        assertNotNull(data["serverInfo"]?.jsonObject)

        // ServerInfo 필드 확인
        val serverInfo = data["serverInfo"]?.jsonObject
        assertNotNull(serverInfo)
        assertNotNull(serverInfo["dacsTypeName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET state without token should return 401`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.get("/api/v2/sessions/$sessionId/state")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET state of other user session should return 403`() = testApplication {
        application { module() }
        val token1 = getToken()
        val token2 = getOtherUserToken()
        val sessionId = createSession(token1)

        val response = client.get("/api/v2/sessions/$sessionId/state") {
            header(HttpHeaders.Authorization, "Bearer $token2")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET state of non-existent session should return 403 or 404`() = testApplication {
        application { module() }
        val token = getToken()

        val response = client.get("/api/v2/sessions/non-existent/state") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertTrue(
            response.status == HttpStatusCode.NotFound ||
            response.status == HttpStatusCode.Forbidden
        )
    }

    // === GET /sessions/{id}/history ===

    @Test
    fun `GET history should return empty history for new session`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.get("/api/v2/sessions/$sessionId/history") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data)
        assertNotNull(data["messages"]?.jsonArray)
        assertNotNull(data["total"]?.jsonPrimitive?.int)
    }

    @Test
    fun `GET history with count parameter should work`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.get("/api/v2/sessions/$sessionId/history?count=5") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `GET history without token should return 401`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.get("/api/v2/sessions/$sessionId/history")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET history of other user session should return 403`() = testApplication {
        application { module() }
        val token1 = getToken()
        val token2 = getOtherUserToken()
        val sessionId = createSession(token1)

        val response = client.get("/api/v2/sessions/$sessionId/history") {
            header(HttpHeaders.Authorization, "Bearer $token2")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // === POST /sessions/{id}/control ===

    @Test
    fun `POST control resetSpec should succeed`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.post("/api/v2/sessions/$sessionId/control") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"action": "resetSpec"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(true, data["success"]?.jsonPrimitive?.boolean)
        assertNotNull(data["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST control cancel should succeed`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.post("/api/v2/sessions/$sessionId/control") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"action": "cancel"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `POST control cancelAll should succeed`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.post("/api/v2/sessions/$sessionId/control") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"action": "cancelAll"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `POST control setWorkspace should succeed`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.post("/api/v2/sessions/$sessionId/control") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"action": "setWorkspace", "workspace": "/tmp/test-workspace"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `POST control without action should return 400`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.post("/api/v2/sessions/$sessionId/control") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST control with unknown action should return 400`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.post("/api/v2/sessions/$sessionId/control") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"action": "unknownAction"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `POST control without token should return 401`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.post("/api/v2/sessions/$sessionId/control") {
            contentType(ContentType.Application.Json)
            setBody("""{"action": "cancel"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST control on other user session should return 403`() = testApplication {
        application { module() }
        val token1 = getToken()
        val token2 = getOtherUserToken()
        val sessionId = createSession(token1)

        val response = client.post("/api/v2/sessions/$sessionId/control") {
            header(HttpHeaders.Authorization, "Bearer $token2")
            contentType(ContentType.Application.Json)
            setBody("""{"action": "cancel"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // === state after chat ===

    @Test
    fun `GET state after chat should include turn count`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        // 채팅 한번 수행
        client.post("/api/v2/sessions/$sessionId/chat") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"message": "hello"}""")
        }

        // state 확인
        val response = client.get("/api/v2/sessions/$sessionId/state") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
        assertNotNull(data)
        assertTrue(data["turnCount"]?.jsonPrimitive?.int!! >= 1)
    }

    @Test
    fun `GET history after chat should include messages`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        // 채팅 한번 수행
        client.post("/api/v2/sessions/$sessionId/chat") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"message": "hello"}""")
        }

        // history 확인
        val response = client.get("/api/v2/sessions/$sessionId/history") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
        assertNotNull(data)

        val messages = data["messages"]?.jsonArray
        assertNotNull(messages)
        assertTrue(messages.size >= 1, "Should have at least 1 message after chat")
    }
}

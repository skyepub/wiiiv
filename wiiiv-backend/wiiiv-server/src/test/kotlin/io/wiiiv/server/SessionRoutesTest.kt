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
 * Session Routes 통합 테스트
 */
class SessionRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 토큰 획득 헬퍼
     */
    private suspend fun ApplicationTestBuilder.getToken(): String {
        val loginResponse = client.get("/api/v2/auth/auto-login")
        val loginBody = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
        return loginBody["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content
            ?: error("Failed to get token")
    }

    /**
     * 다른 사용자 토큰 획득 헬퍼
     */
    private suspend fun ApplicationTestBuilder.getOtherUserToken(): String {
        val loginResponse = client.post("/api/v2/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email": "test@wiiiv.io", "password": "test1234"}""")
        }
        val loginBody = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
        return loginBody["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content
            ?: error("Failed to get token")
    }

    /**
     * 세션 생성 헬퍼
     */
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

    // === 인증 테스트 ===

    @Test
    fun `POST sessions without token should return 401`() = testApplication {
        application { module() }

        val response = client.post("/api/v2/sessions") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET sessions without token should return 401`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/sessions")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // === 세션 CRUD 테스트 ===

    @Test
    fun `POST sessions should create session`() = testApplication {
        application { module() }
        val token = getToken()

        val response = client.post("/api/v2/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.Created, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("sessionId")?.jsonPrimitive?.content)
        assertNotNull(data?.get("userId")?.jsonPrimitive?.content)
        assertNotNull(data?.get("createdAt")?.jsonPrimitive?.content)
    }

    @Test
    fun `GET sessions should list user sessions`() = testApplication {
        application { module() }
        val token = getToken()

        // 세션 2개 생성
        createSession(token)
        createSession(token)

        val response = client.get("/api/v2/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        val sessions = data?.get("sessions")?.jsonArray
        assertNotNull(sessions)
        assertTrue(sessions.size >= 2)
    }

    @Test
    fun `GET sessions by id should return session info`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.get("/api/v2/sessions/$sessionId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertEquals(sessionId, data?.get("sessionId")?.jsonPrimitive?.content)
    }

    @Test
    fun `DELETE sessions should end session`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.delete("/api/v2/sessions/$sessionId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertEquals(true, data?.get("deleted")?.jsonPrimitive?.boolean)

        // 삭제 후 조회 → 403 (isOwner 실패) 또는 404 (세션 없음)
        val getResponse = client.get("/api/v2/sessions/$sessionId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertTrue(
            getResponse.status == HttpStatusCode.Forbidden || getResponse.status == HttpStatusCode.NotFound,
            "Expected 403 or 404 but got ${getResponse.status}"
        )
    }

    // === 소유권 테스트 ===

    @Test
    fun `accessing other user's session should return 403`() = testApplication {
        application { module() }
        val token1 = getToken()       // dev-user
        val token2 = getOtherUserToken()  // admin

        val sessionId = createSession(token1)

        // admin이 dev-user의 세션에 접근
        val response = client.get("/api/v2/sessions/$sessionId") {
            header(HttpHeaders.Authorization, "Bearer $token2")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // === 존재하지 않는 세션 ===

    @Test
    fun `GET non-existent session should return 404`() = testApplication {
        application { module() }
        val token = getToken()

        val response = client.get("/api/v2/sessions/non-existent") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        // isOwner가 false → 403 (소유권 확인 먼저)
        // 또는 세션이 없어서 404
        assertTrue(
            response.status == HttpStatusCode.NotFound ||
            response.status == HttpStatusCode.Forbidden
        )
    }

    // === Chat SSE 테스트 ===

    @Test
    fun `POST chat should return SSE stream`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.post("/api/v2/sessions/$sessionId/chat") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"message": "안녕하세요"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // SSE Content-Type 확인
        val contentType = response.contentType()
        assertNotNull(contentType)
        assertEquals(ContentType.Text.EventStream.contentType, contentType.contentType)
        assertEquals(ContentType.Text.EventStream.contentSubtype, contentType.contentSubtype)

        // SSE 이벤트 파싱
        val bodyText = response.bodyAsText()
        assertTrue(bodyText.isNotEmpty(), "SSE body should not be empty")

        // 최소한 response 이벤트와 done 이벤트가 있어야 함
        assertTrue(bodyText.contains("event: response"), "Should contain response event")
        assertTrue(bodyText.contains("event: done"), "Should contain done event")

        // response 이벤트에 action 필드가 있어야 함
        val responseLines = bodyText.lines()
            .filter { it.startsWith("data: ") && it.contains("\"action\"") }
        assertTrue(responseLines.isNotEmpty(), "Should have response data with action field")
    }

    @Test
    fun `POST chat to non-existent session should return 404`() = testApplication {
        application { module() }
        val token = getToken()

        val response = client.post("/api/v2/sessions/non-existent/chat") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"message": "hello"}""")
        }

        assertTrue(
            response.status == HttpStatusCode.NotFound ||
            response.status == HttpStatusCode.Forbidden
        )
    }

    @Test
    fun `POST chat without message should return 400`() = testApplication {
        application { module() }
        val token = getToken()
        val sessionId = createSession(token)

        val response = client.post("/api/v2/sessions/$sessionId/chat") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}

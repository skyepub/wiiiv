package io.wiiiv.cli

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.wiiiv.api.module
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Auth Command 통합 테스트
 *
 * CLI auth 명령어가 API와 올바르게 통신하는지 검증
 */
class AuthCommandTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `auto-login returns valid token`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/auth/auto-login")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("accessToken")?.jsonPrimitive?.content)
        assertEquals("Bearer", data?.get("tokenType")?.jsonPrimitive?.content)
        assertNotNull(data?.get("expiresIn"))
    }

    @Test
    fun `manual login with valid credentials`() = testApplication {
        application { module() }

        val response = client.post("/api/v2/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username": "admin", "password": "admin123"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)
        assertNotNull(body["data"]?.jsonObject?.get("accessToken"))
    }

    @Test
    fun `manual login with invalid credentials returns 401`() = testApplication {
        application { module() }

        val response = client.post("/api/v2/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username": "admin", "password": "wrong"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `whoami returns user info with valid token`() = testApplication {
        application { module() }

        // Get token
        val loginResponse = client.get("/api/v2/auth/auto-login")
        val loginBody = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
        val token = loginBody["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content

        // Get user info
        val response = client.get("/api/v2/auth/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("userId"))
        assertNotNull(data?.get("username"))
        assertNotNull(data?.get("roles"))
    }

    @Test
    fun `whoami without token returns 401`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/auth/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `whoami with invalid token returns 401`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/auth/me") {
            header(HttpHeaders.Authorization, "Bearer invalid-token-here")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `token contains expected claims`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/auth/auto-login")
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject

        // Verify token structure
        val token = data?.get("accessToken")?.jsonPrimitive?.content
        assertNotNull(token)
        assertTrue(token.split(".").size == 3) // JWT has 3 parts
    }
}

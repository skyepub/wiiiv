package io.wiiiv.api

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
 * Auth Routes 테스트
 */
class AuthRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `auto-login should return JWT token in dev mode`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/auth/auto-login")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data)
        assertNotNull(data["accessToken"]?.jsonPrimitive?.content)
        assertEquals("Bearer", data["tokenType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `login with valid credentials should return token`() = testApplication {
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
    fun `login with invalid credentials should return 401`() = testApplication {
        application { module() }

        val response = client.post("/api/v2/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username": "admin", "password": "wrongpassword"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, body["success"]?.jsonPrimitive?.boolean)
        // StatusPages intercepts 401 responses and returns UNAUTHORIZED
        assertEquals("UNAUTHORIZED", body["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `me endpoint without token should return 401`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/auth/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `me endpoint with valid token should return user info`() = testApplication {
        application { module() }

        // Get token first
        val loginResponse = client.get("/api/v2/auth/auto-login")
        val loginBody = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
        val token = loginBody["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content

        // Use token to get user info
        val response = client.get("/api/v2/auth/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("userId"))
        assertNotNull(data?.get("roles"))
    }

    @Test
    fun `me endpoint with invalid token should return 401`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/auth/me") {
            header(HttpHeaders.Authorization, "Bearer invalid-token")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}

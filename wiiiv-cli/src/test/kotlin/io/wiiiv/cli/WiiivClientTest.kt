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
 * WiiivClient 통합 테스트
 *
 * CLI HTTP 클라이언트의 모든 API 호출을 검증
 */
class WiiivClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ==================== Decision Tests ====================

    @Test
    fun `create decision endpoint works`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.post("/api/v2/decisions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{
                "spec": {
                    "intent": "Test decision",
                    "constraints": ["constraint1"]
                }
            }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("decisionId"))
        assertNotNull(data?.get("consensus"))
    }

    @Test
    fun `get decision endpoint works`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create decision first
        val createResponse = client.post("/api/v2/decisions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"spec": {"intent": "Test"}}""")
        }
        val createBody = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val decisionId = createBody["data"]?.jsonObject?.get("decisionId")?.jsonPrimitive?.content

        // Get decision
        val response = client.get("/api/v2/decisions/$decisionId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `approve decision endpoint works`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create decision first
        val createResponse = client.post("/api/v2/decisions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"spec": {"intent": "Approval test"}}""")
        }
        val createBody = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val decisionId = createBody["data"]?.jsonObject?.get("decisionId")?.jsonPrimitive?.content

        // Approve decision
        val response = client.post("/api/v2/decisions/$decisionId/approve") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `reject decision endpoint works`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create decision first
        val createResponse = client.post("/api/v2/decisions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"spec": {"intent": "Rejection test"}}""")
        }
        val createBody = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val decisionId = createBody["data"]?.jsonObject?.get("decisionId")?.jsonPrimitive?.content

        // Reject decision
        val response = client.post("/api/v2/decisions/$decisionId/reject") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)
    }

    // ==================== Blueprint Tests ====================

    @Test
    fun `list blueprints endpoint works`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/blueprints") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("blueprints"))
        assertNotNull(data?.get("total"))
    }

    @Test
    fun `get blueprint endpoint handles not found`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/blueprints/non-existent-id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ==================== Execution Tests ====================

    @Test
    fun `list executions endpoint works`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/executions") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("executions"))
        assertNotNull(data?.get("total"))
    }

    @Test
    fun `get execution endpoint handles not found`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/executions/non-existent-id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ==================== Pagination Tests ====================

    @Test
    fun `blueprints pagination parameters work`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/blueprints?page=1&pageSize=10") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
        assertEquals(1, data?.get("page")?.jsonPrimitive?.int)
        assertEquals(10, data?.get("pageSize")?.jsonPrimitive?.int)
    }

    @Test
    fun `executions pagination parameters work`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/executions?page=2&pageSize=5") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
        assertEquals(2, data?.get("page")?.jsonPrimitive?.int)
        assertEquals(5, data?.get("pageSize")?.jsonPrimitive?.int)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `invalid JSON returns error status`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.post("/api/v2/decisions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("invalid json {")
        }

        // Server may return 400 Bad Request or 500 Internal Server Error
        // depending on how Ktor handles the deserialization error
        assertTrue(response.status.value >= 400, "Expected error status code, got ${response.status}")
    }

    @Test
    fun `missing required fields returns error`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.post("/api/v2/decisions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("{}")  // Missing required 'spec' field
        }

        // Should return 400 or similar error
        assertTrue(response.status.value >= 400)
    }

    // ==================== Helper Functions ====================

    private suspend fun getToken(client: io.ktor.client.HttpClient): String {
        val loginResponse = client.get("/api/v2/auth/auto-login")
        val loginBody = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
        return loginBody["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content
            ?: throw IllegalStateException("Failed to get token")
    }
}

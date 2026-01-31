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
 * System Command 통합 테스트
 *
 * CLI system 명령어가 API와 올바르게 통신하는지 검증
 */
class SystemCommandTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `health endpoint returns OK`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/system/health")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertEquals("healthy", data?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun `info endpoint returns system info`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/system/info")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("version"))
        assertNotNull(data?.get("uptime"))
        assertNotNull(data?.get("status"))
    }

    @Test
    fun `executors endpoint returns executor list`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/system/executors") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        val executors = data?.get("executors")?.jsonArray
        assertNotNull(executors)
        assertTrue(executors.isNotEmpty())

        // Verify executor structure
        val firstExecutor = executors.first().jsonObject
        assertNotNull(firstExecutor["id"])
        assertNotNull(firstExecutor["type"])
        assertNotNull(firstExecutor["supportedStepTypes"])
    }

    @Test
    fun `gates endpoint returns gate list`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/system/gates") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        val gates = data?.get("gates")?.jsonArray
        assertNotNull(gates)
        assertTrue(gates.isNotEmpty())

        // Verify gate structure
        val firstGate = gates.first().jsonObject
        assertNotNull(firstGate["id"])
        assertNotNull(firstGate["type"])
        assertNotNull(firstGate["priority"])
    }

    @Test
    fun `personas endpoint returns persona list`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/system/personas") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        val personas = data?.get("personas")?.jsonArray
        assertNotNull(personas)
        assertTrue(personas.isNotEmpty())

        // Verify persona structure
        val firstPersona = personas.first().jsonObject
        assertNotNull(firstPersona["id"])
        assertNotNull(firstPersona["name"])
        assertNotNull(firstPersona["role"])
    }

    @Test
    fun `executors endpoint requires authentication`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/system/executors")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `gates endpoint requires authentication`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/system/gates")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `personas endpoint requires authentication`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/system/personas")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `health endpoint does not require authentication`() = testApplication {
        application { module() }

        // Health check should work without token
        val response = client.get("/api/v2/system/health")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `info endpoint does not require authentication`() = testApplication {
        application { module() }

        // Info should work without token
        val response = client.get("/api/v2/system/info")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `executor list includes RAG executor`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/system/executors") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val executors = body["data"]?.jsonObject?.get("executors")?.jsonArray

        val ragExecutor = executors?.find {
            it.jsonObject["id"]?.jsonPrimitive?.content == "rag-executor"
        }
        assertNotNull(ragExecutor, "RAG executor should be in the list")
    }

    // ==================== Helper Functions ====================

    private suspend fun getToken(client: io.ktor.client.HttpClient): String {
        val loginResponse = client.get("/api/v2/auth/auto-login")
        val loginBody = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
        return loginBody["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content
            ?: throw IllegalStateException("Failed to get token")
    }
}

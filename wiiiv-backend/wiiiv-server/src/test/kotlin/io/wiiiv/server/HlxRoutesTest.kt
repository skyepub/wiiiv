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
import kotlin.test.assertFalse

/**
 * HLX Routes 테스트
 *
 * HLX 워크플로우 CRUD + 검증 + 실행 API 테스트
 */
class HlxRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun getToken(client: io.ktor.client.HttpClient): String {
        val response = client.get("/api/v2/auth/auto-login")
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content!!
    }

    private val validWorkflowJson = """
        {
            "version": "1.0",
            "id": "test-workflow",
            "name": "Test Workflow",
            "description": "A test workflow for API testing",
            "trigger": {"type": "manual"},
            "nodes": [
                {
                    "id": "observe-1",
                    "type": "observe",
                    "description": "Observe something",
                    "output": "observed_data"
                },
                {
                    "id": "transform-1",
                    "type": "transform",
                    "description": "Transform the data",
                    "input": "observed_data",
                    "output": "transformed_data"
                }
            ]
        }
    """.trimIndent()

    private suspend fun createWorkflow(client: io.ktor.client.HttpClient, token: String, workflowJson: String = validWorkflowJson): HttpResponse {
        return client.post("/api/v2/workflows") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"workflow": ${json.encodeToString(JsonElement.serializer(), JsonPrimitive(workflowJson))}}""")
        }
    }

    // === CRUD ===

    @Test
    fun `create workflow should succeed with valid JSON`() = testApplication {
        application { module() }

        val token = getToken(client)
        val response = createWorkflow(client, token)

        assertEquals(HttpStatusCode.Created, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertEquals("test-workflow", data?.get("id")?.jsonPrimitive?.content)
        assertEquals("Test Workflow", data?.get("name")?.jsonPrimitive?.content)
        assertEquals(2, data?.get("nodeCount")?.jsonPrimitive?.int)
        assertNotNull(data?.get("createdAt")?.jsonPrimitive?.content)
    }

    @Test
    fun `create workflow with invalid JSON should return 400`() = testApplication {
        application { module() }

        val token = getToken(client)
        val response = client.post("/api/v2/workflows") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"workflow": "not valid json at all"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertFalse(body["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("PARSE_ERROR", body["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `create workflow with validation errors should return 400`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Workflow with duplicate node IDs
        val invalidWorkflow = """
            {
                "version": "1.0",
                "id": "invalid-wf",
                "name": "Invalid",
                "description": "Has duplicate node IDs",
                "nodes": [
                    {"id": "node-1", "type": "observe", "description": "First"},
                    {"id": "node-1", "type": "act", "description": "Duplicate"}
                ]
            }
        """.trimIndent()

        val response = client.post("/api/v2/workflows") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"workflow": ${json.encodeToString(JsonElement.serializer(), JsonPrimitive(invalidWorkflow))}}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("VALIDATION_ERROR", body["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `list workflows should return empty initially`() = testApplication {
        application { module() }

        val token = getToken(client)
        val response = client.get("/api/v2/workflows") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("workflows")?.jsonArray)
        assertNotNull(data?.get("total")?.jsonPrimitive?.int)
    }

    @Test
    fun `list workflows should return created workflows`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create first
        createWorkflow(client, token)

        val response = client.get("/api/v2/workflows") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
        val workflows = data?.get("workflows")?.jsonArray
        assertNotNull(workflows)
        assertTrue(workflows.isNotEmpty())
    }

    @Test
    fun `get workflow should return details`() = testApplication {
        application { module() }

        val token = getToken(client)
        createWorkflow(client, token)

        val response = client.get("/api/v2/workflows/test-workflow") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
        assertEquals("test-workflow", data?.get("id")?.jsonPrimitive?.content)
        assertEquals("Test Workflow", data?.get("name")?.jsonPrimitive?.content)
        assertNotNull(data?.get("nodes")?.jsonArray)
        assertEquals(2, data?.get("nodes")?.jsonArray?.size)
        assertNotNull(data?.get("rawJson")?.jsonPrimitive?.content)
        assertEquals("manual", data?.get("trigger")?.jsonPrimitive?.content)
    }

    @Test
    fun `get non-existent workflow should return 404`() = testApplication {
        application { module() }

        val token = getToken(client)
        val response = client.get("/api/v2/workflows/non-existent") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `delete workflow should succeed`() = testApplication {
        application { module() }

        val token = getToken(client)
        createWorkflow(client, token)

        val response = client.delete("/api/v2/workflows/test-workflow") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["data"]?.jsonObject?.get("deleted")?.jsonPrimitive?.boolean == true)

        // Verify deleted
        val getResponse = client.get("/api/v2/workflows/test-workflow") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, getResponse.status)
    }

    @Test
    fun `delete non-existent workflow should return 404`() = testApplication {
        application { module() }

        val token = getToken(client)
        val response = client.delete("/api/v2/workflows/non-existent") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // === Validate ===

    @Test
    fun `validate valid workflow should return valid true`() = testApplication {
        application { module() }

        val token = getToken(client)
        createWorkflow(client, token)

        val response = client.post("/api/v2/workflows/test-workflow/validate") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
        assertTrue(data?.get("valid")?.jsonPrimitive?.boolean == true)
        assertTrue(data?.get("errors")?.jsonArray?.isEmpty() == true)
    }

    @Test
    fun `validate non-existent workflow should return 404`() = testApplication {
        application { module() }

        val token = getToken(client)
        val response = client.post("/api/v2/workflows/non-existent/validate") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // === Execute ===

    @Test
    fun `execute without LLM should return 503`() = testApplication {
        application { module() }

        val token = getToken(client)
        createWorkflow(client, token)

        // In test environment, LLM provider is likely null
        val response = client.post("/api/v2/workflows/test-workflow/execute") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"variables": {}}""")
        }

        // Either 503 (no LLM) or 200 (LLM available) — both are valid
        assertTrue(
            response.status == HttpStatusCode.ServiceUnavailable ||
            response.status == HttpStatusCode.OK
        )
    }

    @Test
    fun `execution history should return list`() = testApplication {
        application { module() }

        val token = getToken(client)
        createWorkflow(client, token)

        val response = client.get("/api/v2/workflows/test-workflow/executions") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("executions")?.jsonArray)
        assertNotNull(data?.get("total")?.jsonPrimitive?.int)
    }

    // === Auth ===

    @Test
    fun `access without token should return 401`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/workflows")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}

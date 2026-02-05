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
 * Execution Routes 테스트
 *
 * Execution은 Blueprint를 실행하는 것이다.
 * Gate 체크 → Executor 실행 흐름을 테스트한다.
 */
class ExecutionRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun getToken(client: io.ktor.client.HttpClient): String {
        val response = client.get("/api/v2/auth/auto-login")
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content!!
    }

    private suspend fun createApprovedDecision(client: io.ktor.client.HttpClient, token: String): Pair<String, String?> {
        // Create decision
        val createResponse = client.post("/api/v2/decisions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"spec": {"intent": "Read the text file /tmp/test.txt for testing purposes"}}""")
        }
        val createBody = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val decisionId = createBody["data"]?.jsonObject?.get("decisionId")?.jsonPrimitive?.content!!
        val blueprintId = createBody["data"]?.jsonObject?.get("blueprintId")?.jsonPrimitive?.contentOrNull

        // Approve decision
        client.post("/api/v2/decisions/$decisionId/approve") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        return Pair(decisionId, blueprintId)
    }

    @Test
    fun `execute without auth should return 401`() = testApplication {
        application { module() }

        val response = client.post("/api/v2/executions") {
            contentType(ContentType.Application.Json)
            setBody("""{"blueprintId": "some-id"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `execute non-existent blueprint should return 404`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.post("/api/v2/executions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"blueprintId": "non-existent-id"}""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `execute without user approval should be denied by Gate`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create decision but do NOT approve
        val createResponse = client.post("/api/v2/decisions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"spec": {"intent": "Read the text file /tmp/test.txt without approval"}}""")
        }
        val createBody = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val blueprintId = createBody["data"]?.jsonObject?.get("blueprintId")?.jsonPrimitive?.contentOrNull

        if (blueprintId != null) {
            val response = client.post("/api/v2/executions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"blueprintId": "$blueprintId"}""")
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(false, body["success"]?.jsonPrimitive?.boolean)
            // StatusPages intercepts 403 responses and returns FORBIDDEN
            assertEquals("FORBIDDEN", body["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `execute with approval should succeed`() = testApplication {
        application { module() }

        val token = getToken(client)

        val (_, blueprintId) = createApprovedDecision(client, token)

        if (blueprintId != null) {
            val response = client.post("/api/v2/executions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"blueprintId": "$blueprintId"}""")
            }

            assertEquals(HttpStatusCode.Accepted, response.status)

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

            val data = body["data"]?.jsonObject
            assertNotNull(data?.get("executionId")?.jsonPrimitive?.content)
            assertEquals(blueprintId, data?.get("blueprintId")?.jsonPrimitive?.content)
            assertEquals("RUNNING", data?.get("status")?.jsonPrimitive?.content)
            assertNotNull(data?.get("startedAt")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `dry-run execution should complete immediately`() = testApplication {
        application { module() }

        val token = getToken(client)

        val (_, blueprintId) = createApprovedDecision(client, token)

        if (blueprintId != null) {
            val response = client.post("/api/v2/executions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"blueprintId": "$blueprintId", "options": {"dryRun": true}}""")
            }

            assertEquals(HttpStatusCode.Accepted, response.status)

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val executionId = body["data"]?.jsonObject?.get("executionId")?.jsonPrimitive?.content

            // Get execution status
            val statusResponse = client.get("/api/v2/executions/$executionId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }

            val statusBody = json.parseToJsonElement(statusResponse.bodyAsText()).jsonObject
            assertEquals("COMPLETED", statusBody["data"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `list executions should work`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/executions") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("executions")?.jsonArray)
        assertNotNull(data?.get("total")?.jsonPrimitive?.int)
    }

    @Test
    fun `get execution by id should return execution details`() = testApplication {
        application { module() }

        val token = getToken(client)

        val (_, blueprintId) = createApprovedDecision(client, token)

        if (blueprintId != null) {
            // Create execution
            val createResponse = client.post("/api/v2/executions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"blueprintId": "$blueprintId", "options": {"dryRun": true}}""")
            }

            val createBody = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
            val executionId = createBody["data"]?.jsonObject?.get("executionId")?.jsonPrimitive?.content

            // Get execution
            val response = client.get("/api/v2/executions/$executionId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

            val data = body["data"]?.jsonObject
            assertEquals(executionId, data?.get("executionId")?.jsonPrimitive?.content)
            assertEquals(blueprintId, data?.get("blueprintId")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `get non-existent execution should return 404`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/executions/non-existent-id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `cancel execution should update status`() = testApplication {
        application { module() }

        val token = getToken(client)

        val (_, blueprintId) = createApprovedDecision(client, token)

        if (blueprintId != null) {
            // Create execution
            val createResponse = client.post("/api/v2/executions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"blueprintId": "$blueprintId"}""")
            }

            val createBody = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
            val executionId = createBody["data"]?.jsonObject?.get("executionId")?.jsonPrimitive?.content

            // Cancel
            val response = client.post("/api/v2/executions/$executionId/cancel") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["success"]?.jsonPrimitive?.boolean == true)
            assertTrue(body["data"]?.jsonObject?.get("cancelled")?.jsonPrimitive?.boolean == true)
        }
    }

    @Test
    fun `get execution logs should return logs`() = testApplication {
        application { module() }

        val token = getToken(client)

        val (_, blueprintId) = createApprovedDecision(client, token)

        if (blueprintId != null) {
            // Create execution
            val createResponse = client.post("/api/v2/executions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"blueprintId": "$blueprintId", "options": {"dryRun": true}}""")
            }

            val createBody = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
            val executionId = createBody["data"]?.jsonObject?.get("executionId")?.jsonPrimitive?.content

            // Get logs
            val response = client.get("/api/v2/executions/$executionId/logs") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

            val data = body["data"]?.jsonObject
            assertEquals(executionId, data?.get("executionId")?.jsonPrimitive?.content)
            assertNotNull(data?.get("logs")?.jsonArray)
        }
    }

    @Test
    fun `list executions with blueprintId filter should work`() = testApplication {
        application { module() }

        val token = getToken(client)

        val (_, blueprintId) = createApprovedDecision(client, token)

        if (blueprintId != null) {
            // Create execution
            client.post("/api/v2/executions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"blueprintId": "$blueprintId", "options": {"dryRun": true}}""")
            }

            // List with filter
            val response = client.get("/api/v2/executions?blueprintId=$blueprintId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val executions = body["data"]?.jsonObject?.get("executions")?.jsonArray
            assertNotNull(executions)

            // All executions should have the filtered blueprintId
            executions.forEach { exec ->
                assertEquals(blueprintId, exec.jsonObject["blueprintId"]?.jsonPrimitive?.content)
            }
        }
    }
}

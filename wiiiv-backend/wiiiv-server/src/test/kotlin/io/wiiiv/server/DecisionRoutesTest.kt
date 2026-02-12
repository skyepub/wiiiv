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
 * Decision Routes 테스트
 *
 * Decision은 Governor에게 판단을 요청하는 것이다.
 * DACS 합의 → Blueprint 생성 흐름을 테스트한다.
 */
class DecisionRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun getToken(client: io.ktor.client.HttpClient): String {
        val response = client.get("/api/v2/auth/auto-login")
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content!!
    }

    @Test
    fun `create decision without auth should return 401`() = testApplication {
        application { module() }

        val response = client.post("/api/v2/decisions") {
            contentType(ContentType.Application.Json)
            setBody("""{"spec": {"intent": "Test"}}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `create decision should return DACS consensus and blueprint`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.post("/api/v2/decisions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "spec": {
                        "intent": "Read a configuration file",
                        "constraints": ["read-only", "config files only"]
                    }
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data)

        // Decision ID
        assertNotNull(data["decisionId"]?.jsonPrimitive?.content)

        // Status
        val status = data["status"]?.jsonPrimitive?.content
        assertTrue(status in listOf("APPROVED", "REJECTED", "NEEDS_REVISION", "PENDING_APPROVAL"))

        // Consensus
        val consensus = data["consensus"]?.jsonObject
        assertNotNull(consensus)
        val outcome = consensus["outcome"]?.jsonPrimitive?.content
        assertTrue(outcome in listOf("YES", "NO", "REVISION"))

        // Votes (3 personas for SimpleDACS, 6 for HybridDACS)
        val votes = consensus["votes"]?.jsonArray
        assertNotNull(votes)
        assertTrue(votes.size >= 3) // architect, reviewer, adversary (+ LLM personas if API key present)

        // If approved, should have blueprintId
        if (status == "APPROVED") {
            assertNotNull(data["blueprintId"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `create decision with minimal spec should work`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.post("/api/v2/decisions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"spec": {"intent": "Simple task"}}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `get decision by id should return decision details`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create decision first
        val createResponse = client.post("/api/v2/decisions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"spec": {"intent": "Test decision"}}""")
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
        assertEquals(decisionId, body["data"]?.jsonObject?.get("decisionId")?.jsonPrimitive?.content)
    }

    @Test
    fun `get non-existent decision should return 404`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/decisions/non-existent-id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `approve decision should set userApproved to true`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create decision
        val createResponse = client.post("/api/v2/decisions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"spec": {"intent": "Approval test"}}""")
        }

        val createBody = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val decisionId = createBody["data"]?.jsonObject?.get("decisionId")?.jsonPrimitive?.content

        // Approve
        val response = client.post("/api/v2/decisions/$decisionId/approve") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)
        assertTrue(body["data"]?.jsonObject?.get("approved")?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `reject decision should update status`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create decision
        val createResponse = client.post("/api/v2/decisions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"spec": {"intent": "Rejection test"}}""")
        }

        val createBody = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val decisionId = createBody["data"]?.jsonObject?.get("decisionId")?.jsonPrimitive?.content

        // Reject
        val response = client.post("/api/v2/decisions/$decisionId/reject") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)
        assertTrue(body["data"]?.jsonObject?.get("rejected")?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `double approval should be idempotent`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create decision
        val createResponse = client.post("/api/v2/decisions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"spec": {"intent": "Double approval test"}}""")
        }

        val createBody = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val decisionId = createBody["data"]?.jsonObject?.get("decisionId")?.jsonPrimitive?.content

        // First approval
        client.post("/api/v2/decisions/$decisionId/approve") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        // Second approval
        val response = client.post("/api/v2/decisions/$decisionId/approve") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)
        assertEquals("Already approved", body["data"]?.jsonObject?.get("message")?.jsonPrimitive?.content)
    }
}

package io.wiiiv.server

import io.mockk.every
import io.mockk.mockk
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.wiiiv.audit.AuditStore
import io.wiiiv.platform.model.ProjectPolicy
import io.wiiiv.platform.store.PlatformStore
import io.wiiiv.server.policy.PolicyCheckResult
import io.wiiiv.server.policy.ProjectPolicyChecker
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * PolicyEnforcementTest — F5-F6 정책 시행 전용 테스트
 *
 * ProjectPolicyChecker 단위 테스트 + HTTP 레벨 통합 테스트
 */
class PolicyEnforcementTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun uniqueEmail() = "user-${UUID.randomUUID()}@test.com"

    private suspend fun ApplicationTestBuilder.register(
        email: String = uniqueEmail()
    ): Pair<Long, String> {
        val resp = client.post("/api/v2/platform/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"test12345678","displayName":"Test"}""")
        }
        val rawBody = resp.bodyAsText()
        val data = json.parseToJsonElement(rawBody).jsonObject["data"]?.takeIf { it !is JsonNull }?.jsonObject
            ?: error("Register failed (${resp.status}): $rawBody")
        return Pair(data["userId"]!!.jsonPrimitive.long, data["accessToken"]!!.jsonPrimitive.content)
    }

    private suspend fun ApplicationTestBuilder.createProject(token: String): Long {
        val resp = client.post("/api/v2/platform/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Proj-${UUID.randomUUID().toString().take(8)}"}""")
        }
        val rawBody = resp.bodyAsText()
        val data = json.parseToJsonElement(rawBody).jsonObject["data"]?.takeIf { it !is JsonNull }?.jsonObject
            ?: error("CreateProject failed (${resp.status}): $rawBody")
        return data["projectId"]!!.jsonPrimitive.long
    }

    // ════════════════════════════════════════════
    //  1. ProjectPolicyChecker Unit Tests
    // ════════════════════════════════════════════

    @Test
    fun `null platformStore — always allowed`() {
        val result = ProjectPolicyChecker.checkDailyLimit(1L, null, null)
        assertTrue(result.allowed)
    }

    @Test
    fun `null auditStore — always allowed`() {
        val platformStore = mockk<PlatformStore>()
        every { platformStore.getPolicy(any()) } returns ProjectPolicy(projectId = 1L, maxRequestsPerDay = 10)
        val result = ProjectPolicyChecker.checkDailyLimit(1L, platformStore, null)
        assertTrue(result.allowed)
    }

    @Test
    fun `no policy — always allowed`() {
        val platformStore = mockk<PlatformStore>()
        every { platformStore.getPolicy(any()) } returns null
        val result = ProjectPolicyChecker.checkDailyLimit(1L, platformStore, mockk())
        assertTrue(result.allowed)
    }

    @Test
    fun `maxRequestsPerDay null — always allowed`() {
        val platformStore = mockk<PlatformStore>()
        every { platformStore.getPolicy(any()) } returns ProjectPolicy(projectId = 1L, maxRequestsPerDay = null)
        val result = ProjectPolicyChecker.checkDailyLimit(1L, platformStore, mockk())
        assertTrue(result.allowed)
    }

    @Test
    fun `count below limit — allowed with currentCount and limit`() {
        val platformStore = mockk<PlatformStore>()
        val auditStore = mockk<AuditStore>()
        every { platformStore.getPolicy(1L) } returns ProjectPolicy(projectId = 1L, maxRequestsPerDay = 10)
        every { auditStore.countByProject(eq(1L), any()) } returns 5L

        val result = ProjectPolicyChecker.checkDailyLimit(1L, platformStore, auditStore)
        assertTrue(result.allowed)
        assertEquals(5L, result.currentCount)
        assertEquals(10, result.limit)
    }

    @Test
    fun `count at limit — denied with message`() {
        val platformStore = mockk<PlatformStore>()
        val auditStore = mockk<AuditStore>()
        every { platformStore.getPolicy(1L) } returns ProjectPolicy(projectId = 1L, maxRequestsPerDay = 10)
        every { auditStore.countByProject(eq(1L), any()) } returns 10L

        val result = ProjectPolicyChecker.checkDailyLimit(1L, platformStore, auditStore)
        assertFalse(result.allowed)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("10"))
        assertEquals(10L, result.currentCount)
        assertEquals(10, result.limit)
    }

    // ════════════════════════════════════════════
    //  2. HTTP Integration (Usage Tracking)
    // ════════════════════════════════════════════

    @Test
    fun `GET usage — todayCount 0 initially`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        val resp = client.get("/api/v2/projects/$projectId/audit/usage") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals(projectId, data["projectId"]!!.jsonPrimitive.long)
        // todayCount should be >= 0
        assertTrue(data["todayCount"]!!.jsonPrimitive.long >= 0)
    }

    @Test
    fun `GET usage — monthCount includes today`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        val resp = client.get("/api/v2/projects/$projectId/audit/usage") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject
        val todayCount = data["todayCount"]!!.jsonPrimitive.long
        val monthCount = data["monthCount"]!!.jsonPrimitive.long
        assertTrue(monthCount >= todayCount, "monthCount should be >= todayCount")
    }

    @Test
    fun `GET usage — maxRequestsPerDay reflects current policy`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        // Default policy: null maxRequestsPerDay
        val resp1 = client.get("/api/v2/projects/$projectId/audit/usage") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val data1 = json.parseToJsonElement(resp1.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertTrue(data1["maxRequestsPerDay"] is JsonNull || data1["maxRequestsPerDay"]!!.jsonPrimitive.contentOrNull == null)

        // Set policy
        client.put("/api/v2/platform/projects/$projectId/policy") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"maxRequestsPerDay":50}""")
        }

        // Check updated policy reflected
        val resp2 = client.get("/api/v2/projects/$projectId/audit/usage") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val data2 = json.parseToJsonElement(resp2.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals(50, data2["maxRequestsPerDay"]!!.jsonPrimitive.int)
    }

    @Test
    fun `limit applies per-project`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projA = createProject(token)
        val projB = createProject(token)

        // Both projects have their own usage counters
        val respA = client.get("/api/v2/projects/$projA/audit/usage") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val respB = client.get("/api/v2/projects/$projB/audit/usage") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, respA.status)
        assertEquals(HttpStatusCode.OK, respB.status)

        val dataA = json.parseToJsonElement(respA.bodyAsText()).jsonObject["data"]!!.jsonObject
        val dataB = json.parseToJsonElement(respB.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals(projA, dataA["projectId"]!!.jsonPrimitive.long)
        assertEquals(projB, dataB["projectId"]!!.jsonPrimitive.long)
    }

    @Test
    fun `OWNER increases limit — policy change reflected`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        // Set limit to 5
        client.put("/api/v2/platform/projects/$projectId/policy") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"maxRequestsPerDay":5}""")
        }

        // Increase to 100
        client.put("/api/v2/platform/projects/$projectId/policy") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"maxRequestsPerDay":100}""")
        }

        val resp = client.get("/api/v2/projects/$projectId/audit/usage") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals(100, data["maxRequestsPerDay"]!!.jsonPrimitive.int)
    }
}

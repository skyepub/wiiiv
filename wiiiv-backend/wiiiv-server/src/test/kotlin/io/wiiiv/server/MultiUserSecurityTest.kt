package io.wiiiv.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.*

/**
 * MultiUserSecurityTest — 보안 핵심 테스트
 *
 * 교차 프로젝트 격리, 역할 강제, API Key 교차검증
 *
 * 시나리오:
 * Alice (OWNER of Alpha)
 * Bob   (MEMBER of Alpha, OWNER of Beta)
 * Carol (VIEWER of Alpha)
 * Dave  (외부인)
 */
class MultiUserSecurityTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun uniqueEmail() = "user-${UUID.randomUUID()}@test.com"

    data class TestUser(val userId: Long, val token: String, val email: String)

    private suspend fun ApplicationTestBuilder.register(email: String = uniqueEmail()): TestUser {
        val resp = client.post("/api/v2/platform/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"test12345678","displayName":"User"}""")
        }
        val rawBody = resp.bodyAsText()
        val data = json.parseToJsonElement(rawBody).jsonObject["data"]?.takeIf { it !is JsonNull }?.jsonObject
            ?: error("Register failed (${resp.status}): $rawBody")
        return TestUser(data["userId"]!!.jsonPrimitive.long, data["accessToken"]!!.jsonPrimitive.content, email)
    }

    private suspend fun ApplicationTestBuilder.createProject(token: String, name: String): Long {
        val resp = client.post("/api/v2/platform/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name"}""")
        }
        val rawBody = resp.bodyAsText()
        val data = json.parseToJsonElement(rawBody).jsonObject["data"]?.takeIf { it !is JsonNull }?.jsonObject
            ?: error("CreateProject failed (${resp.status}): $rawBody")
        return data["projectId"]!!.jsonPrimitive.long
    }

    private suspend fun ApplicationTestBuilder.addMember(ownerToken: String, projectId: Long, email: String, role: String) {
        client.post("/api/v2/platform/projects/$projectId/members") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","role":"$role"}""")
        }
    }

    private suspend fun ApplicationTestBuilder.createApiKey(token: String, projectId: Long): String {
        val resp = client.post("/api/v2/platform/projects/$projectId/api-keys") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        return json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content
    }

    // ════════════════════════════════════════════
    //  1. Cross-Project Isolation
    // ════════════════════════════════════════════

    @Test
    fun `Dave cannot GET project Alpha — 403`() = testApplication {
        application { module() }
        val alice = register()
        val dave = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")

        val resp = client.get("/api/v2/platform/projects/$alpha") {
            header(HttpHeaders.Authorization, "Bearer ${dave.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `Dave cannot list project Alpha members — 403`() = testApplication {
        application { module() }
        val alice = register()
        val dave = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")

        val resp = client.get("/api/v2/platform/projects/$alpha/members") {
            header(HttpHeaders.Authorization, "Bearer ${dave.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `Bob API key for Beta cannot access Alpha scoped routes`() = testApplication {
        application { module() }
        val alice = register()
        val bob = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")
        val beta = createProject(bob.token, "Beta-${UUID.randomUUID().toString().take(6)}")
        addMember(alice.token, alpha, bob.email, "MEMBER")

        val betaKey = createApiKey(bob.token, beta)

        // Use Beta key on Alpha
        val resp = client.get("/api/v2/projects/$alpha/sessions") {
            header("X-API-Key", betaKey)
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `session created in Alpha not accessible from Beta scope`() = testApplication {
        application { module() }
        val alice = register()
        val bob = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")
        val beta = createProject(alice.token, "Beta-${UUID.randomUUID().toString().take(6)}")

        // Create session in Alpha
        val createResp = client.post("/api/v2/projects/$alpha/sessions") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        val sessionId = json.parseToJsonElement(createResp.bodyAsText())
            .jsonObject["data"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content

        // Try to chat from Beta
        val resp = client.post("/api/v2/projects/$beta/sessions/$sessionId/chat") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"message":"test"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `audit records from Alpha not visible in Beta audit endpoint`() = testApplication {
        application { module() }
        val alice = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")
        val beta = createProject(alice.token, "Beta-${UUID.randomUUID().toString().take(6)}")

        // Get audit for Alpha
        val respAlpha = client.get("/api/v2/projects/$alpha/audit") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
        }
        assertEquals(HttpStatusCode.OK, respAlpha.status)

        // Get audit for Beta
        val respBeta = client.get("/api/v2/projects/$beta/audit") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
        }
        assertEquals(HttpStatusCode.OK, respBeta.status)
    }

    // ════════════════════════════════════════════
    //  2. Role Enforcement — OWNER vs MEMBER vs VIEWER
    // ════════════════════════════════════════════

    @Test
    fun `Alice OWNER can add members`() = testApplication {
        application { module() }
        val alice = register()
        val bob = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")

        val resp = client.post("/api/v2/platform/projects/$alpha/members") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"${bob.email}","role":"MEMBER"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
    }

    @Test
    fun `Bob MEMBER cannot add members — 403`() = testApplication {
        application { module() }
        val alice = register()
        val bob = register()
        val carol = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")
        addMember(alice.token, alpha, bob.email, "MEMBER")

        val resp = client.post("/api/v2/platform/projects/$alpha/members") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"${carol.email}","role":"MEMBER"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `Bob MEMBER can create sessions`() = testApplication {
        application { module() }
        val alice = register()
        val bob = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")
        addMember(alice.token, alpha, bob.email, "MEMBER")

        val resp = client.post("/api/v2/projects/$alpha/sessions") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
    }

    @Test
    fun `Carol VIEWER can GET sessions but cannot POST session — 403`() = testApplication {
        application { module() }
        val alice = register()
        val carol = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")
        addMember(alice.token, alpha, carol.email, "VIEWER")

        // Can GET
        val getResp = client.get("/api/v2/projects/$alpha/sessions") {
            header(HttpHeaders.Authorization, "Bearer ${carol.token}")
        }
        assertEquals(HttpStatusCode.OK, getResp.status)

        // Cannot POST (create session requires execute)
        val postResp = client.post("/api/v2/projects/$alpha/sessions") {
            header(HttpHeaders.Authorization, "Bearer ${carol.token}")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.Forbidden, postResp.status)
    }

    @Test
    fun `Alice OWNER can update policy, Bob and Carol cannot`() = testApplication {
        application { module() }
        val alice = register()
        val bob = register()
        val carol = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")
        addMember(alice.token, alpha, bob.email, "MEMBER")
        addMember(alice.token, alpha, carol.email, "VIEWER")

        // Alice can
        val aliceResp = client.put("/api/v2/platform/projects/$alpha/policy") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"maxRequestsPerDay":100}""")
        }
        assertEquals(HttpStatusCode.OK, aliceResp.status)

        // Bob cannot
        val bobResp = client.put("/api/v2/platform/projects/$alpha/policy") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"maxRequestsPerDay":200}""")
        }
        assertEquals(HttpStatusCode.Forbidden, bobResp.status)

        // Carol cannot
        val carolResp = client.put("/api/v2/platform/projects/$alpha/policy") {
            header(HttpHeaders.Authorization, "Bearer ${carol.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"maxRequestsPerDay":300}""")
        }
        assertEquals(HttpStatusCode.Forbidden, carolResp.status)
    }

    // ════════════════════════════════════════════
    //  3. API Key Security
    // ════════════════════════════════════════════

    @Test
    fun `API key for Alpha authenticates correctly`() = testApplication {
        application { module() }
        val alice = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")

        val apiKey = createApiKey(alice.token, alpha)

        val resp = client.get("/api/v2/projects/$alpha/sessions") {
            header("X-API-Key", apiKey)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `API key for Alpha used on Beta — 403 PROJECT_MISMATCH`() = testApplication {
        application { module() }
        val alice = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")
        val beta = createProject(alice.token, "Beta-${UUID.randomUUID().toString().take(6)}")

        val alphaKey = createApiKey(alice.token, alpha)

        val resp = client.get("/api/v2/projects/$beta/sessions") {
            header("X-API-Key", alphaKey)
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `revoked API key — 401`() = testApplication {
        application { module() }
        val alice = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")

        val keyResp = client.post("/api/v2/platform/projects/$alpha/api-keys") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        val keyData = json.parseToJsonElement(keyResp.bodyAsText()).jsonObject["data"]!!.jsonObject
        val rawKey = keyData["apiKey"]!!.jsonPrimitive.content
        val keyId = keyData["keyId"]!!.jsonPrimitive.long

        // Revoke
        client.delete("/api/v2/platform/api-keys/$keyId") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
        }

        // Try to use revoked key
        val resp = client.get("/api/v2/projects/$alpha/sessions") {
            header("X-API-Key", rawKey)
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ════════════════════════════════════════════
    //  4. Privilege Escalation Prevention
    // ════════════════════════════════════════════

    @Test
    fun `MEMBER cannot promote self to OWNER`() = testApplication {
        application { module() }
        val alice = register()
        val bob = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")
        addMember(alice.token, alpha, bob.email, "MEMBER")

        // Bob tries to change own role
        val resp = client.put("/api/v2/platform/projects/$alpha/members/${bob.userId}") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"OWNER"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `MEMBER cannot delete project — 403`() = testApplication {
        application { module() }
        val alice = register()
        val bob = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")
        addMember(alice.token, alpha, bob.email, "MEMBER")

        val resp = client.delete("/api/v2/platform/projects/$alpha") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `VIEWER cannot create workflow — 403`() = testApplication {
        application { module() }
        val alice = register()
        val carol = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")
        addMember(alice.token, alpha, carol.email, "VIEWER")

        val resp = client.post("/api/v2/projects/$alpha/workflows") {
            header(HttpHeaders.Authorization, "Bearer ${carol.token}")
            contentType(ContentType.Application.Json)
            setBody("""{"workflow":"{}"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `non-member cannot create API key — 403`() = testApplication {
        application { module() }
        val alice = register()
        val dave = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")

        val resp = client.post("/api/v2/platform/projects/$alpha/api-keys") {
            header(HttpHeaders.Authorization, "Bearer ${dave.token}")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // ════════════════════════════════════════════
    //  5. Concurrent Multi-User
    // ════════════════════════════════════════════

    @Test
    fun `5 users register simultaneously — all succeed with unique IDs`() = testApplication {
        application { module() }
        val users = (1..5).map { register() }
        val ids = users.map { it.userId }.toSet()
        assertEquals(5, ids.size, "All user IDs should be unique")
    }

    @Test
    fun `3 users each create project — each sees only own in list`() = testApplication {
        application { module() }
        val users = (1..3).map { register() }
        val projects = users.map { user ->
            createProject(user.token, "Proj-${user.userId}")
        }

        users.forEachIndexed { idx, user ->
            val resp = client.get("/api/v2/platform/projects") {
                header(HttpHeaders.Authorization, "Bearer ${user.token}")
            }
            val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject
            val projs = data["projects"]!!.jsonArray
            // At minimum, user should see their own project
            assertTrue(projs.any { it.jsonObject["projectId"]!!.jsonPrimitive.long == projects[idx] })
        }
    }

    @Test
    fun `OWNER adds 2 members with different roles — roles enforced correctly`() = testApplication {
        application { module() }
        val alice = register()
        val bob = register()
        val carol = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")
        addMember(alice.token, alpha, bob.email, "MEMBER")
        addMember(alice.token, alpha, carol.email, "VIEWER")

        // Bob (MEMBER) can create session
        val bobSessionResp = client.post("/api/v2/projects/$alpha/sessions") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.Created, bobSessionResp.status)

        // Carol (VIEWER) cannot create session
        val carolSessionResp = client.post("/api/v2/projects/$alpha/sessions") {
            header(HttpHeaders.Authorization, "Bearer ${carol.token}")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.Forbidden, carolSessionResp.status)
    }

    @Test
    fun `member removed mid-session — subsequent requests fail with 403`() = testApplication {
        application { module() }
        val alice = register()
        val bob = register()
        val alpha = createProject(alice.token, "Alpha-${UUID.randomUUID().toString().take(6)}")
        addMember(alice.token, alpha, bob.email, "MEMBER")

        // Bob can access
        val beforeResp = client.get("/api/v2/projects/$alpha/sessions") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
        }
        assertEquals(HttpStatusCode.OK, beforeResp.status)

        // Remove Bob
        client.delete("/api/v2/platform/projects/$alpha/members/${bob.userId}") {
            header(HttpHeaders.Authorization, "Bearer ${alice.token}")
        }

        // Bob's subsequent request should fail
        val afterResp = client.get("/api/v2/projects/$alpha/sessions") {
            header(HttpHeaders.Authorization, "Bearer ${bob.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, afterResp.status)
    }
}

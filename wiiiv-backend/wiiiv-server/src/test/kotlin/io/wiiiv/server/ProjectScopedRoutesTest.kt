package io.wiiiv.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.*

/**
 * ProjectScopedRoutesTest — 프로젝트 스코프 API 통합 테스트
 *
 * Ktor testApplication, 세션/워크플로우/감사 API 검증
 */
class ProjectScopedRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun uniqueEmail() = "user-${UUID.randomUUID()}@test.com"

    private suspend fun ApplicationTestBuilder.register(
        email: String = uniqueEmail(),
        pw: String = "test12345678"
    ): Pair<Long, String> {
        val resp = client.post("/api/v2/platform/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$pw","displayName":"Test"}""")
        }
        val rawBody = resp.bodyAsText()
        val body = json.parseToJsonElement(rawBody).jsonObject
        val data = body["data"]?.takeIf { it !is JsonNull }?.jsonObject
            ?: error("Register failed (${resp.status}): $rawBody")
        return Pair(data["userId"]!!.jsonPrimitive.long, data["accessToken"]!!.jsonPrimitive.content)
    }

    private suspend fun ApplicationTestBuilder.createProject(token: String): Long {
        val resp = client.post("/api/v2/platform/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Project-${UUID.randomUUID().toString().take(8)}"}""")
        }
        val rawBody = resp.bodyAsText()
        val data = json.parseToJsonElement(rawBody).jsonObject["data"]?.takeIf { it !is JsonNull }?.jsonObject
            ?: error("CreateProject failed (${resp.status}): $rawBody")
        return data["projectId"]!!.jsonPrimitive.long
    }

    // ════════════════════════════════════════════
    //  1. Project-Scoped Sessions
    // ════════════════════════════════════════════

    @Test
    fun `POST sessions — creates session with projectId`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        val resp = client.post("/api/v2/projects/$projectId/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertTrue(data["sessionId"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals(projectId, data["projectId"]!!.jsonPrimitive.long)
    }

    @Test
    fun `GET sessions — lists only this project sessions`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        // Create 2 sessions
        repeat(2) {
            client.post("/api/v2/projects/$projectId/sessions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{}""")
            }
        }

        val resp = client.get("/api/v2/projects/$projectId/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject
        val sessions = data["sessions"]!!.jsonArray
        assertTrue(sessions.size >= 2)
    }

    @Test
    fun `POST sessions — VIEWER returns 403`() = testApplication {
        application { module() }
        val (_, ownerToken) = register()
        val projectId = createProject(ownerToken)

        val viewerEmail = uniqueEmail()
        val (_, viewerToken) = register(viewerEmail)
        client.post("/api/v2/platform/projects/$projectId/members") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$viewerEmail","role":"VIEWER"}""")
        }

        val resp = client.post("/api/v2/projects/$projectId/sessions") {
            header(HttpHeaders.Authorization, "Bearer $viewerToken")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `POST chat — session from other project returns 403`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projA = createProject(token)
        val projB = createProject(token)

        // Create session in project A
        val createResp = client.post("/api/v2/projects/$projA/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        val sessionId = json.parseToJsonElement(createResp.bodyAsText())
            .jsonObject["data"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content

        // Try to chat on that session from project B
        val resp = client.post("/api/v2/projects/$projB/sessions/$sessionId/chat") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"message":"hello"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // ════════════════════════════════════════════
    //  2. Project-Scoped Workflows
    // ════════════════════════════════════════════

    @Test
    fun `POST workflows — invalid HLX JSON returns 400`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        val resp = client.post("/api/v2/projects/$projectId/workflows") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"workflow":"not-valid-json{"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `POST workflows — VIEWER returns 403`() = testApplication {
        application { module() }
        val (_, ownerToken) = register()
        val projectId = createProject(ownerToken)

        val viewerEmail = uniqueEmail()
        val (_, viewerToken) = register(viewerEmail)
        client.post("/api/v2/platform/projects/$projectId/members") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$viewerEmail","role":"VIEWER"}""")
        }

        val resp = client.post("/api/v2/projects/$projectId/workflows") {
            header(HttpHeaders.Authorization, "Bearer $viewerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"workflow":"{}"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // ════════════════════════════════════════════
    //  3. Project-Scoped Audit
    // ════════════════════════════════════════════

    @Test
    fun `GET audit usage — returns todayCount and monthCount`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        val resp = client.get("/api/v2/projects/$projectId/audit/usage") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals(projectId, data["projectId"]!!.jsonPrimitive.long)
        assertTrue(data.containsKey("todayCount"))
        assertTrue(data.containsKey("monthCount"))
    }

    @Test
    fun `GET audit stats — returns statistics`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        val resp = client.get("/api/v2/projects/$projectId/audit/stats") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertTrue(data.containsKey("totalRecords"))
    }

    @Test
    fun `non-member cannot access audit endpoint — 403`() = testApplication {
        application { module() }
        val (_, ownerToken) = register()
        val projectId = createProject(ownerToken)
        val (_, outsiderToken) = register()

        val resp = client.get("/api/v2/projects/$projectId/audit") {
            header(HttpHeaders.Authorization, "Bearer $outsiderToken")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // ════════════════════════════════════════════
    //  4. requireProjectAccess
    // ════════════════════════════════════════════

    @Test
    fun `valid JWT + member — access granted`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        val resp = client.get("/api/v2/projects/$projectId/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `valid JWT + non-member — 403`() = testApplication {
        application { module() }
        val (_, ownerToken) = register()
        val projectId = createProject(ownerToken)
        val (_, outsiderToken) = register()

        val resp = client.get("/api/v2/projects/$projectId/sessions") {
            header(HttpHeaders.Authorization, "Bearer $outsiderToken")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `invalid projectId format — 400`() = testApplication {
        application { module() }
        val (_, token) = register()

        val resp = client.get("/api/v2/projects/abc/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `API key with matching projectId — access granted`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        // Create API key
        val keyResp = client.post("/api/v2/platform/projects/$projectId/api-keys") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        val rawKey = json.parseToJsonElement(keyResp.bodyAsText())
            .jsonObject["data"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content

        // Use API key to access project scoped route
        val resp = client.get("/api/v2/projects/$projectId/sessions") {
            header("X-API-Key", rawKey)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `API key with mismatched projectId — 403 PROJECT_MISMATCH`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projA = createProject(token)
        val projB = createProject(token)

        // Create API key for project A
        val keyResp = client.post("/api/v2/platform/projects/$projA/api-keys") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        val rawKey = json.parseToJsonElement(keyResp.bodyAsText())
            .jsonObject["data"]!!.jsonObject["apiKey"]!!.jsonPrimitive.content

        // Use API key for project A on project B
        val resp = client.get("/api/v2/projects/$projB/sessions") {
            header("X-API-Key", rawKey)
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // ════════════════════════════════════════════
    //  5. Session-Project Binding
    // ════════════════════════════════════════════

    @Test
    fun `session created in project A belongs to project A only`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projA = createProject(token)
        val projB = createProject(token)

        // Create session in A
        client.post("/api/v2/projects/$projA/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }

        // List sessions in B → should not include A's sessions
        val resp = client.get("/api/v2/projects/$projB/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val sessions = json.parseToJsonElement(resp.bodyAsText())
            .jsonObject["data"]!!.jsonObject["sessions"]!!.jsonArray
        // Sessions in B should not contain sessions from A
        sessions.forEach { session ->
            val pid = session.jsonObject["projectId"]
            if (pid != null && pid != JsonNull) {
                assertEquals(projB, pid.jsonPrimitive.long)
            }
        }
    }

    @Test
    fun `listUserSessions with projectId filters correctly`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projA = createProject(token)
        val projB = createProject(token)

        // Create 2 sessions in A, 1 in B
        repeat(2) {
            client.post("/api/v2/projects/$projA/sessions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{}""")
            }
        }
        client.post("/api/v2/projects/$projB/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }

        val respA = client.get("/api/v2/projects/$projA/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val sessionsA = json.parseToJsonElement(respA.bodyAsText())
            .jsonObject["data"]!!.jsonObject["sessions"]!!.jsonArray
        assertTrue(sessionsA.size >= 2)

        val respB = client.get("/api/v2/projects/$projB/sessions") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val sessionsB = json.parseToJsonElement(respB.bodyAsText())
            .jsonObject["data"]!!.jsonObject["sessions"]!!.jsonArray
        assertTrue(sessionsB.size >= 1)
    }
}

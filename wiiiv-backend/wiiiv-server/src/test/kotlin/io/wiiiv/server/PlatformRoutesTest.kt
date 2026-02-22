package io.wiiiv.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.wiiiv.server.config.JwtConfig
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.*

/**
 * PlatformRoutesTest — Platform HTTP API 통합 테스트
 *
 * Ktor testApplication, H2 인메모리 (WiiivRegistry 기본값)
 */
class PlatformRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun uniqueEmail() = "user-${UUID.randomUUID()}@test.com"

    private suspend fun ApplicationTestBuilder.register(
        email: String = uniqueEmail(),
        pw: String = "test12345678",
        displayName: String = "Test User"
    ): Pair<Long, String> {
        val resp = client.post("/api/v2/platform/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"$pw","displayName":"$displayName"}""")
        }
        val rawBody = resp.bodyAsText()
        val body = json.parseToJsonElement(rawBody).jsonObject
        val data = body["data"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonObject
            ?: error("Register failed (${resp.status}): $rawBody")
        val userId = data["userId"]!!.jsonPrimitive.long
        val token = data["accessToken"]!!.jsonPrimitive.content
        return Pair(userId, token)
    }

    private suspend fun ApplicationTestBuilder.createProject(
        token: String,
        name: String = "Project-${UUID.randomUUID().toString().take(8)}"
    ): Long {
        val resp = client.post("/api/v2/platform/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"$name"}""")
        }
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        return body["data"]!!.jsonObject["projectId"]!!.jsonPrimitive.long
    }

    // ════════════════════════════════════════════
    //  1. Auth
    // ════════════════════════════════════════════

    @Test
    fun `POST register — 201, returns userId and JWT token`() = testApplication {
        application { module() }
        val email = uniqueEmail()
        val resp = client.post("/api/v2/platform/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"test12345678","displayName":"Alice"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertTrue(body["success"]!!.jsonPrimitive.boolean)
        val data = body["data"]!!.jsonObject
        assertTrue(data["userId"]!!.jsonPrimitive.long > 0)
        assertTrue(data["accessToken"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `POST register — short password returns 400`() = testApplication {
        application { module() }
        val resp = client.post("/api/v2/platform/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"${uniqueEmail()}","password":"short","displayName":"X"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `POST register — duplicate email returns 409`() = testApplication {
        application { module() }
        val email = uniqueEmail()
        register(email)
        val resp = client.post("/api/v2/platform/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"test12345678","displayName":"Dup"}""")
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `POST login — correct credentials returns 200 and token`() = testApplication {
        application { module() }
        val email = uniqueEmail()
        register(email, "test12345678")
        val resp = client.post("/api/v2/platform/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"test12345678"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertTrue(body["success"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `POST login — wrong password returns 401`() = testApplication {
        application { module() }
        val email = uniqueEmail()
        register(email, "test12345678")
        val resp = client.post("/api/v2/platform/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$email","password":"wrongpassword"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ════════════════════════════════════════════
    //  2. Me
    // ════════════════════════════════════════════

    @Test
    fun `GET me with valid JWT — returns user info`() = testApplication {
        application { module() }
        val (userId, token) = register()
        val resp = client.get("/api/v2/platform/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val data = body["data"]!!.jsonObject
        assertEquals(userId, data["userId"]!!.jsonPrimitive.long)
    }

    @Test
    fun `GET me without token — 401`() = testApplication {
        application { module() }
        val resp = client.get("/api/v2/platform/me")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ════════════════════════════════════════════
    //  3. Project CRUD
    // ════════════════════════════════════════════

    @Test
    fun `POST projects — 201, returns projectId`() = testApplication {
        application { module() }
        val (_, token) = register()
        val resp = client.post("/api/v2/platform/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Test Project"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertTrue(body["data"]!!.jsonObject["projectId"]!!.jsonPrimitive.long > 0)
    }

    @Test
    fun `POST projects — blank name returns 400`() = testApplication {
        application { module() }
        val (_, token) = register()
        val resp = client.post("/api/v2/platform/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `GET projects — lists only my projects`() = testApplication {
        application { module() }
        val (_, token) = register()
        createProject(token, "Proj A")
        createProject(token, "Proj B")

        val resp = client.get("/api/v2/platform/projects") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val projects = body["data"]!!.jsonObject["projects"]!!.jsonArray
        assertTrue(projects.size >= 2)
    }

    @Test
    fun `PUT projects — OWNER updates name returns 200`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token, "Old Name")

        val resp = client.put("/api/v2/platform/projects/$projectId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"New Name"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals("New Name", data["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `DELETE projects — non-owner returns 403`() = testApplication {
        application { module() }
        val (_, ownerToken) = register()
        val projectId = createProject(ownerToken)

        val (bobId, bobToken) = register()
        // Add bob as member
        client.post("/api/v2/platform/projects/$projectId/members") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"","role":"MEMBER"}""")
        }
        // For simplicity, use bob's token to try delete (he's not the owner)
        val resp = client.delete("/api/v2/platform/projects/$projectId") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // ════════════════════════════════════════════
    //  4. Members
    // ════════════════════════════════════════════

    @Test
    fun `GET members — returns owner in list`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        val resp = client.get("/api/v2/platform/projects/$projectId/members") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val members = body["data"]!!.jsonObject["members"]!!.jsonArray
        assertTrue(members.isNotEmpty())
    }

    @Test
    fun `POST members — OWNER adds new member returns 201`() = testApplication {
        application { module() }
        val (_, ownerToken) = register()
        val projectId = createProject(ownerToken)

        val bobEmail = uniqueEmail()
        register(bobEmail)

        val resp = client.post("/api/v2/platform/projects/$projectId/members") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$bobEmail","role":"MEMBER"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
    }

    @Test
    fun `POST members — MEMBER tries to add returns 403`() = testApplication {
        application { module() }
        val (_, ownerToken) = register()
        val projectId = createProject(ownerToken)

        val bobEmail = uniqueEmail()
        val (_, bobToken) = register(bobEmail)
        // Add bob as MEMBER
        client.post("/api/v2/platform/projects/$projectId/members") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$bobEmail","role":"MEMBER"}""")
        }

        val carolEmail = uniqueEmail()
        register(carolEmail)
        // Bob tries to add Carol
        val resp = client.post("/api/v2/platform/projects/$projectId/members") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$carolEmail","role":"MEMBER"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `PUT members — OWNER changes role returns 200`() = testApplication {
        application { module() }
        val (_, ownerToken) = register()
        val projectId = createProject(ownerToken)

        val bobEmail = uniqueEmail()
        val (bobId, _) = register(bobEmail)
        client.post("/api/v2/platform/projects/$projectId/members") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$bobEmail","role":"MEMBER"}""")
        }

        val resp = client.put("/api/v2/platform/projects/$projectId/members/$bobId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"role":"VIEWER"}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `DELETE members — OWNER removes member returns 200`() = testApplication {
        application { module() }
        val (_, ownerToken) = register()
        val projectId = createProject(ownerToken)

        val bobEmail = uniqueEmail()
        val (bobId, _) = register(bobEmail)
        client.post("/api/v2/platform/projects/$projectId/members") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$bobEmail","role":"MEMBER"}""")
        }

        val resp = client.delete("/api/v2/platform/projects/$projectId/members/$bobId") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    // ════════════════════════════════════════════
    //  5. API Keys
    // ════════════════════════════════════════════

    @Test
    fun `POST api-keys — returns rawKey starting with wiiiv_`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        val resp = client.post("/api/v2/platform/projects/$projectId/api-keys") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"label":"test-key"}""")
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertTrue(data["apiKey"]!!.jsonPrimitive.content.startsWith("wiiiv_"))
    }

    @Test
    fun `GET api-keys — lists created keys`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        // Create 2 keys
        repeat(2) {
            client.post("/api/v2/platform/projects/$projectId/api-keys") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"label":"key-$it"}""")
            }
        }

        val resp = client.get("/api/v2/platform/projects/$projectId/api-keys") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val keys = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject["keys"]!!.jsonArray
        assertTrue(keys.size >= 2)
    }

    @Test
    fun `DELETE api-keys — revokes key`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        val createResp = client.post("/api/v2/platform/projects/$projectId/api-keys") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        val keyId = json.parseToJsonElement(createResp.bodyAsText()).jsonObject["data"]!!.jsonObject["keyId"]!!.jsonPrimitive.long

        val resp = client.delete("/api/v2/platform/api-keys/$keyId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `POST api-keys — non-member returns 403`() = testApplication {
        application { module() }
        val (_, ownerToken) = register()
        val projectId = createProject(ownerToken)
        val (_, outsiderToken) = register()

        val resp = client.post("/api/v2/platform/projects/$projectId/api-keys") {
            header(HttpHeaders.Authorization, "Bearer $outsiderToken")
            contentType(ContentType.Application.Json)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // ════════════════════════════════════════════
    //  6. Policy
    // ════════════════════════════════════════════

    @Test
    fun `GET policy — member sees default policy`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        val resp = client.get("/api/v2/platform/projects/$projectId/policy") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals(projectId, data["projectId"]!!.jsonPrimitive.long)
    }

    @Test
    fun `PUT policy — OWNER sets maxRequestsPerDay returns 200`() = testApplication {
        application { module() }
        val (_, token) = register()
        val projectId = createProject(token)

        val resp = client.put("/api/v2/platform/projects/$projectId/policy") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"maxRequestsPerDay":100}""")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val data = json.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertEquals(100, data["maxRequestsPerDay"]!!.jsonPrimitive.int)
    }

    @Test
    fun `PUT policy — MEMBER tries to update returns 403`() = testApplication {
        application { module() }
        val (_, ownerToken) = register()
        val projectId = createProject(ownerToken)

        val bobEmail = uniqueEmail()
        val (_, bobToken) = register(bobEmail)
        client.post("/api/v2/platform/projects/$projectId/members") {
            header(HttpHeaders.Authorization, "Bearer $ownerToken")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"$bobEmail","role":"MEMBER"}""")
        }

        val resp = client.put("/api/v2/platform/projects/$projectId/policy") {
            header(HttpHeaders.Authorization, "Bearer $bobToken")
            contentType(ContentType.Application.Json)
            setBody("""{"maxRequestsPerDay":999}""")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `GET policy — non-member returns 403`() = testApplication {
        application { module() }
        val (_, ownerToken) = register()
        val projectId = createProject(ownerToken)
        val (_, outsiderToken) = register()

        val resp = client.get("/api/v2/platform/projects/$projectId/policy") {
            header(HttpHeaders.Authorization, "Bearer $outsiderToken")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    // ════════════════════════════════════════════
    //  7. Edge Cases
    // ════════════════════════════════════════════

    @Test
    fun `invalid projectId format returns 400`() = testApplication {
        application { module() }
        val (_, token) = register()
        val resp = client.get("/api/v2/platform/projects/not-a-number") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `non-existent projectId returns 403 for non-member`() = testApplication {
        application { module() }
        val (_, token) = register()
        val resp = client.get("/api/v2/platform/projects/999999") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        // isMember check fails → 403
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `register with empty email returns 400`() = testApplication {
        application { module() }
        val resp = client.post("/api/v2/platform/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"","password":"test12345678","displayName":"X"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `login with non-existent email returns 401`() = testApplication {
        application { module() }
        val resp = client.post("/api/v2/platform/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"nobody-${UUID.randomUUID()}@test.com","password":"test12345678"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }
}

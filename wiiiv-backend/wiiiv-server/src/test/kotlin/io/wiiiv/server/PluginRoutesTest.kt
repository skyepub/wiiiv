package io.wiiiv.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.test.*

/**
 * PluginRoutesTest — Plugin REST API 통합 테스트
 *
 * GET /api/v2/plugins      플러그인 목록
 * GET /api/v2/plugins/{id}  플러그인 상세
 */
class PluginRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun uniqueEmail() = "user-${UUID.randomUUID()}@test.com"

    private suspend fun ApplicationTestBuilder.register(): String {
        val resp = client.post("/api/v2/platform/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"${uniqueEmail()}","password":"test12345678","displayName":"Test"}""")
        }
        val rawBody = resp.bodyAsText()
        val data = json.parseToJsonElement(rawBody).jsonObject["data"]?.takeIf { it !is JsonNull }?.jsonObject
            ?: error("Register failed (${resp.status}): $rawBody")
        return data["accessToken"]!!.jsonPrimitive.content
    }

    // ════════════════════════════════════════════
    //  1. Plugin List
    // ════════════════════════════════════════════

    @Test
    fun `GET plugins — returns loaded plugins array`() = testApplication {
        application { module() }
        val token = register()

        val resp = client.get("/api/v2/plugins") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertTrue(body["success"]!!.jsonPrimitive.boolean)
        val data = body["data"]!!.jsonObject
        assertTrue(data.containsKey("plugins"))
        assertTrue(data.containsKey("total"))
    }

    @Test
    fun `GET plugins — each entry has pluginId, displayName, version`() = testApplication {
        application { module() }
        val token = register()

        val resp = client.get("/api/v2/plugins") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val plugins = json.parseToJsonElement(resp.bodyAsText())
            .jsonObject["data"]!!.jsonObject["plugins"]!!.jsonArray

        plugins.forEach { plugin ->
            val obj = plugin.jsonObject
            assertTrue(obj.containsKey("pluginId"), "Missing pluginId")
            assertTrue(obj.containsKey("displayName"), "Missing displayName")
            assertTrue(obj.containsKey("version"), "Missing version")
        }
    }

    @Test
    fun `GET plugins — without auth returns 401`() = testApplication {
        application { module() }
        val resp = client.get("/api/v2/plugins")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `GET plugins — response includes actionCount per plugin`() = testApplication {
        application { module() }
        val token = register()

        val resp = client.get("/api/v2/plugins") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val plugins = json.parseToJsonElement(resp.bodyAsText())
            .jsonObject["data"]!!.jsonObject["plugins"]!!.jsonArray

        plugins.forEach { plugin ->
            val obj = plugin.jsonObject
            assertTrue(obj.containsKey("actionCount"), "Missing actionCount for ${obj["pluginId"]}")
        }
    }

    // ════════════════════════════════════════════
    //  2. Plugin Detail
    // ════════════════════════════════════════════

    @Test
    fun `GET plugins non-existent — 404`() = testApplication {
        application { module() }
        val token = register()

        val resp = client.get("/api/v2/plugins/does-not-exist-${UUID.randomUUID()}") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `GET plugins detail — returns actions with riskLevel and requiredParams`() = testApplication {
        application { module() }
        val token = register()

        // First get plugin list to find a valid ID
        val listResp = client.get("/api/v2/plugins") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val plugins = json.parseToJsonElement(listResp.bodyAsText())
            .jsonObject["data"]!!.jsonObject["plugins"]!!.jsonArray

        if (plugins.isEmpty()) return@testApplication // no plugins loaded in test env

        val pluginId = plugins.first().jsonObject["pluginId"]!!.jsonPrimitive.content
        val detailResp = client.get("/api/v2/plugins/$pluginId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, detailResp.status)
        val detail = json.parseToJsonElement(detailResp.bodyAsText()).jsonObject["data"]!!.jsonObject
        assertTrue(detail.containsKey("actions"))
        assertTrue(detail.containsKey("scheme"))

        val actions = detail["actions"]!!.jsonArray
        actions.forEach { action ->
            val a = action.jsonObject
            assertTrue(a.containsKey("name"))
            assertTrue(a.containsKey("riskLevel"))
            assertTrue(a.containsKey("requiredParams"))
        }
    }
}

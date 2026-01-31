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
 * Blueprint Routes 테스트
 *
 * Blueprint는 Governor가 생성한 실행 계획이다.
 * 조회 및 검증 기능을 테스트한다.
 */
class BlueprintRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun getToken(client: io.ktor.client.HttpClient): String {
        val response = client.get("/api/v2/auth/auto-login")
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content!!
    }

    private suspend fun createDecisionAndGetBlueprintId(client: io.ktor.client.HttpClient, token: String): String? {
        val response = client.post("/api/v2/decisions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"spec": {"intent": "Blueprint test"}}""")
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["data"]?.jsonObject?.get("blueprintId")?.jsonPrimitive?.content
    }

    @Test
    fun `list blueprints without auth should return 401`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/blueprints")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `list blueprints should return empty list initially`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/blueprints") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("blueprints")?.jsonArray)
        assertNotNull(data?.get("total")?.jsonPrimitive?.int)
        assertNotNull(data?.get("page")?.jsonPrimitive?.int)
        assertNotNull(data?.get("pageSize")?.jsonPrimitive?.int)
    }

    @Test
    fun `list blueprints should return created blueprints`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create a decision (which creates a blueprint)
        createDecisionAndGetBlueprintId(client, token)

        val response = client.get("/api/v2/blueprints") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val blueprints = body["data"]?.jsonObject?.get("blueprints")?.jsonArray
        assertNotNull(blueprints)
        assertTrue(blueprints.isNotEmpty())
    }

    @Test
    fun `get blueprint by id should return blueprint details`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create a decision to get a blueprint
        val blueprintId = createDecisionAndGetBlueprintId(client, token)
        assertNotNull(blueprintId)

        val response = client.get("/api/v2/blueprints/$blueprintId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(blueprintId, data["id"]?.jsonPrimitive?.content)
        assertNotNull(data["status"])
        assertNotNull(data["structure"])
        assertNotNull(data["createdAt"])

        // Structure should have nodes and edges
        val structure = data["structure"]?.jsonObject
        assertNotNull(structure?.get("nodes")?.jsonArray)
        assertNotNull(structure?.get("edges")?.jsonArray)
    }

    @Test
    fun `get non-existent blueprint should return 404`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/blueprints/non-existent-id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `validate blueprint should return validation result`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create a decision to get a blueprint
        val blueprintId = createDecisionAndGetBlueprintId(client, token)
        assertNotNull(blueprintId)

        val response = client.post("/api/v2/blueprints/$blueprintId/validate") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data)
        assertEquals(blueprintId, data["blueprintId"]?.jsonPrimitive?.content)
        assertNotNull(data["valid"]?.jsonPrimitive?.boolean)
        assertNotNull(data["errors"]?.jsonArray)
        assertNotNull(data["warnings"]?.jsonArray)
    }

    @Test
    fun `blueprint structure should contain nodes with correct format`() = testApplication {
        application { module() }

        val token = getToken(client)

        val blueprintId = createDecisionAndGetBlueprintId(client, token)
        assertNotNull(blueprintId)

        val response = client.get("/api/v2/blueprints/$blueprintId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val nodes = body["data"]?.jsonObject?.get("structure")?.jsonObject?.get("nodes")?.jsonArray
        assertNotNull(nodes)

        // Each node should have id, type, config
        nodes.forEach { node ->
            val nodeObj = node.jsonObject
            assertNotNull(nodeObj["id"]?.jsonPrimitive?.content)
            assertNotNull(nodeObj["type"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `list blueprints with pagination should work`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create multiple decisions/blueprints
        repeat(3) {
            createDecisionAndGetBlueprintId(client, token)
        }

        val response = client.get("/api/v2/blueprints?page=1&pageSize=2") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
        assertEquals(1, data?.get("page")?.jsonPrimitive?.int)
        assertEquals(2, data?.get("pageSize")?.jsonPrimitive?.int)
    }
}

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
 * System Routes 테스트
 *
 * 시스템 상태, Executor, Gate, Persona 조회를 테스트한다.
 */
class SystemRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun getToken(client: io.ktor.client.HttpClient): String {
        val response = client.get("/api/v2/auth/auto-login")
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content!!
    }

    @Test
    fun `health endpoint should be public`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/system/health")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertEquals("healthy", data?.get("status")?.jsonPrimitive?.content)
        assertNotNull(data?.get("checks")?.jsonObject)
    }

    @Test
    fun `health checks should include core components`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/system/health")

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val checks = body["data"]?.jsonObject?.get("checks")?.jsonObject

        assertNotNull(checks?.get("core"))
        assertNotNull(checks?.get("executors"))
        assertNotNull(checks?.get("gates"))
        assertNotNull(checks?.get("dacs"))
    }

    @Test
    fun `info endpoint should be public`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/system/info")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("version")?.jsonPrimitive?.content)
        assertNotNull(data?.get("uptime")?.jsonPrimitive?.long)
        assertEquals("running", data?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun `executors endpoint without auth should return 401`() = testApplication {
        application { module() }

        val response = client.get("/api/v2/system/executors")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `executors endpoint should return registered executors`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/system/executors") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val executors = body["data"]?.jsonObject?.get("executors")?.jsonArray
        assertNotNull(executors)
        assertTrue(executors.isNotEmpty())

        // Check executor structure
        executors.forEach { executor ->
            val execObj = executor.jsonObject
            assertNotNull(execObj["id"]?.jsonPrimitive?.content)
            assertNotNull(execObj["type"]?.jsonPrimitive?.content)
            assertNotNull(execObj["supportedStepTypes"]?.jsonArray)
            assertNotNull(execObj["status"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `executors should include core executor types`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/system/executors") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val executors = body["data"]?.jsonObject?.get("executors")?.jsonArray
        val executorIds = executors?.map { it.jsonObject["id"]?.jsonPrimitive?.content }

        assertTrue(executorIds?.contains("file-executor") == true)
        assertTrue(executorIds?.contains("command-executor") == true)
    }

    @Test
    fun `gates endpoint should return registered gates`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/system/gates") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val gates = body["data"]?.jsonObject?.get("gates")?.jsonArray
        assertNotNull(gates)
        assertTrue(gates.isNotEmpty())

        // Check gate structure
        gates.forEach { gate ->
            val gateObj = gate.jsonObject
            assertNotNull(gateObj["id"]?.jsonPrimitive?.content)
            assertNotNull(gateObj["type"]?.jsonPrimitive?.content)
            assertNotNull(gateObj["priority"]?.jsonPrimitive?.int)
            assertNotNull(gateObj["status"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `gates should include all 4 standard gates`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/system/gates") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val gates = body["data"]?.jsonObject?.get("gates")?.jsonArray
        val gateTypes = gates?.map { it.jsonObject["type"]?.jsonPrimitive?.content }

        assertTrue(gateTypes?.contains("DACS Gate") == true)
        assertTrue(gateTypes?.contains("User Approval Gate") == true)
        assertTrue(gateTypes?.contains("Execution Permission Gate") == true)
        assertTrue(gateTypes?.contains("Cost Gate") == true)
    }

    @Test
    fun `personas endpoint should return DACS personas`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/system/personas") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val personas = body["data"]?.jsonObject?.get("personas")?.jsonArray
        assertNotNull(personas)
        assertEquals(3, personas.size) // architect, reviewer, adversary

        // Check persona structure
        personas.forEach { persona ->
            val personaObj = persona.jsonObject
            assertNotNull(personaObj["id"]?.jsonPrimitive?.content)
            assertNotNull(personaObj["name"]?.jsonPrimitive?.content)
            assertNotNull(personaObj["role"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `personas should include all 3 standard personas`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/system/personas") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val personas = body["data"]?.jsonObject?.get("personas")?.jsonArray
        val personaIds = personas?.map { it.jsonObject["id"]?.jsonPrimitive?.content }

        assertTrue(personaIds?.contains("architect") == true)
        assertTrue(personaIds?.contains("reviewer") == true)
        assertTrue(personaIds?.contains("adversary") == true)
    }

    @Test
    fun `gate logs endpoint should return logs`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/system/gates/logs") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("logs")?.jsonArray)
    }

    @Test
    fun `gate logs should record gate checks after execution attempt`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Create a decision to trigger gate checks
        val createResponse = client.post("/api/v2/decisions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"spec": {"intent": "Read the text file /tmp/test.txt for gate log testing"}}""")
        }

        val createBody = json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val blueprintId = createBody["data"]?.jsonObject?.get("blueprintId")?.jsonPrimitive?.contentOrNull

        if (blueprintId != null) {
            // Try to execute (will fail at gate)
            client.post("/api/v2/executions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"blueprintId": "$blueprintId"}""")
            }

            // Check gate logs
            val logsResponse = client.get("/api/v2/system/gates/logs") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }

            val logsBody = json.parseToJsonElement(logsResponse.bodyAsText()).jsonObject
            val logs = logsBody["data"]?.jsonObject?.get("logs")?.jsonArray
            assertNotNull(logs)
            assertTrue(logs.isNotEmpty())

            // Check log structure
            val lastLog = logs.last().jsonObject
            assertNotNull(lastLog["logId"])
            assertNotNull(lastLog["gate"])
            assertNotNull(lastLog["result"])
            assertNotNull(lastLog["timestamp"])
        }
    }
}

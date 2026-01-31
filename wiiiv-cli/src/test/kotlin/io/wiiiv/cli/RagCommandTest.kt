package io.wiiiv.cli

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.wiiiv.api.module
import io.wiiiv.cli.client.WiiivClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RAG Command 통합 테스트
 *
 * wiiiv-api 서버를 테스트 모드로 실행하고
 * CLI 클라이언트가 올바르게 동작하는지 검증
 */
class RagCommandTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ==================== API Integration Tests ====================

    @Test
    fun `RAG ingest endpoint should accept document`() = testApplication {
        application { module() }

        // Get token
        val token = getToken(client)

        // Ingest document
        val response = client.post("/api/v2/rag/ingest") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"content": "This is test content for RAG.", "title": "Test Doc"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("documentId"))
        assertEquals("Test Doc", data?.get("title")?.jsonPrimitive?.content)
        assertTrue((data?.get("chunkCount")?.jsonPrimitive?.int ?: 0) > 0)
    }

    @Test
    fun `RAG search endpoint should return results`() = testApplication {
        application { module() }

        val token = getToken(client)

        // First ingest a document
        client.post("/api/v2/rag/ingest") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"content": "Kotlin is a modern programming language.", "title": "Kotlin Guide"}""")
        }

        // Then search
        val response = client.post("/api/v2/rag/search") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"query": "programming language", "topK": 5}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertEquals("programming language", data?.get("query")?.jsonPrimitive?.content)
        assertNotNull(data?.get("results"))
    }

    @Test
    fun `RAG size endpoint should return store size`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/rag/size") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("size"))
        assertNotNull(data?.get("storeId"))
    }

    @Test
    fun `RAG documents endpoint should list documents`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.get("/api/v2/rag/documents") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertNotNull(data?.get("documents"))
        assertNotNull(data?.get("total"))
    }

    @Test
    fun `RAG delete endpoint should remove document`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Ingest first
        val ingestResponse = client.post("/api/v2/rag/ingest") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"content": "Document to delete", "documentId": "delete-me"}""")
        }

        val ingestBody = json.parseToJsonElement(ingestResponse.bodyAsText()).jsonObject
        val documentId = ingestBody["data"]?.jsonObject?.get("documentId")?.jsonPrimitive?.content

        // Delete
        val response = client.delete("/api/v2/rag/$documentId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertEquals(documentId, data?.get("documentId")?.jsonPrimitive?.content)
    }

    @Test
    fun `RAG clear endpoint should empty store`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Ingest some documents
        client.post("/api/v2/rag/ingest") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"content": "Doc 1"}""")
        }

        // Clear
        val response = client.delete("/api/v2/rag") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertTrue(data?.get("cleared")?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `RAG batch ingest should accept multiple documents`() = testApplication {
        application { module() }

        val token = getToken(client)

        val response = client.post("/api/v2/rag/ingest/batch") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{
                "documents": [
                    {"content": "First document", "title": "Doc 1"},
                    {"content": "Second document", "title": "Doc 2"},
                    {"content": "Third document", "title": "Doc 3"}
                ]
            }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["success"]?.jsonPrimitive?.boolean == true)

        val data = body["data"]?.jsonObject
        assertEquals(3, data?.get("totalDocuments")?.jsonPrimitive?.int)
        assertEquals(3, data?.get("successCount")?.jsonPrimitive?.int)
        assertEquals(0, data?.get("failureCount")?.jsonPrimitive?.int)
    }

    @Test
    fun `RAG endpoints require authentication`() = testApplication {
        application { module() }

        // Try without token
        val ingestResponse = client.post("/api/v2/rag/ingest") {
            contentType(ContentType.Application.Json)
            setBody("""{"content": "test"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, ingestResponse.status)

        val searchResponse = client.post("/api/v2/rag/search") {
            contentType(ContentType.Application.Json)
            setBody("""{"query": "test"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, searchResponse.status)

        val sizeResponse = client.get("/api/v2/rag/size")
        assertEquals(HttpStatusCode.Unauthorized, sizeResponse.status)
    }

    @Test
    fun `RAG search with minScore filter`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Ingest document
        client.post("/api/v2/rag/ingest") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"content": "Machine learning and artificial intelligence"}""")
        }

        // Search with high minScore
        val response = client.post("/api/v2/rag/search") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"query": "completely unrelated topic", "topK": 10, "minScore": 0.9}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body["data"]?.jsonObject
        // With high minScore, likely no results (or filtered)
        assertNotNull(data?.get("results"))
    }

    // ==================== Helper Functions ====================

    private suspend fun getToken(client: io.ktor.client.HttpClient): String {
        val loginResponse = client.get("/api/v2/auth/auto-login")
        val loginBody = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
        return loginBody["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content
            ?: throw IllegalStateException("Failed to get token")
    }
}

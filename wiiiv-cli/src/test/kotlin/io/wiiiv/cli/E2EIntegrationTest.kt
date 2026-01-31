package io.wiiiv.cli

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.wiiiv.api.module
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E 통합 테스트 - CLI → API → Core 전체 흐름
 *
 * 실제 사용 시나리오를 테스트:
 * 1. 인증 → 판단 요청 → Blueprint 생성 → 실행
 * 2. RAG: 문서 수집 → 검색 → 삭제
 * 3. 시스템 상태 조회 및 모니터링
 *
 * ## 테스트 원칙
 * - 실제 사용자 워크플로우 시뮬레이션
 * - API 응답 구조 검증
 * - 상태 전이 검증
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class E2EIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ==================== Scenario 1: Decision → Blueprint → Execution ====================

    @Test
    @Order(1)
    fun `E2E - complete decision to execution workflow`() = testApplication {
        application { module() }

        // Step 1: 인증
        val token = getToken(client)
        assertNotNull(token, "Token should be obtained")

        // Step 2: 판단 요청 (Decision)
        val decisionResponse = client.post("/api/v2/decisions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{
                "spec": {
                    "intent": "Read configuration file from /tmp",
                    "constraints": ["read-only", "no-sensitive-data"]
                }
            }""")
        }

        assertEquals(HttpStatusCode.Created, decisionResponse.status)

        val decisionBody = json.parseToJsonElement(decisionResponse.bodyAsText()).jsonObject
        assertTrue(decisionBody["success"]?.jsonPrimitive?.boolean == true)

        val decisionData = decisionBody["data"]?.jsonObject
        val decisionId = decisionData?.get("decisionId")?.jsonPrimitive?.content
        val status = decisionData?.get("status")?.jsonPrimitive?.content
        val consensusOutcome = decisionData?.get("consensus")?.jsonObject?.get("outcome")?.jsonPrimitive?.content

        assertNotNull(decisionId, "Decision ID should be present")
        assertNotNull(status, "Status should be present")
        assertNotNull(consensusOutcome, "Consensus outcome should be present")

        println("Decision created: $decisionId, Status: $status, Consensus: $consensusOutcome")

        // Step 3: 판단 결과 조회
        val getDecisionResponse = client.get("/api/v2/decisions/$decisionId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, getDecisionResponse.status)

        // Step 4: 사용자 승인
        val approveResponse = client.post("/api/v2/decisions/$decisionId/approve") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, approveResponse.status)

        val approveBody = json.parseToJsonElement(approveResponse.bodyAsText()).jsonObject
        assertTrue(approveBody["data"]?.jsonObject?.get("approved")?.jsonPrimitive?.boolean == true)

        println("Decision approved: $decisionId")

        // Step 5: Blueprint 확인 (APPROVED 상태인 경우)
        if (status == "APPROVED") {
            val blueprintId = decisionData?.get("blueprintId")?.jsonPrimitive?.content
            if (blueprintId != null) {
                val blueprintResponse = client.get("/api/v2/blueprints/$blueprintId") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

                assertEquals(HttpStatusCode.OK, blueprintResponse.status)
                println("Blueprint retrieved: $blueprintId")
            }
        }
    }

    @Test
    @Order(2)
    fun `E2E - decision rejection workflow`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Step 1: 판단 요청
        val decisionResponse = client.post("/api/v2/decisions") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{
                "spec": {
                    "intent": "Delete all system files",
                    "constraints": ["destructive-operation"]
                }
            }""")
        }

        assertEquals(HttpStatusCode.Created, decisionResponse.status)

        val decisionBody = json.parseToJsonElement(decisionResponse.bodyAsText()).jsonObject
        val decisionId = decisionBody["data"]?.jsonObject?.get("decisionId")?.jsonPrimitive?.content

        assertNotNull(decisionId)

        // Step 2: 사용자 거부
        val rejectResponse = client.post("/api/v2/decisions/$decisionId/reject") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, rejectResponse.status)

        val rejectBody = json.parseToJsonElement(rejectResponse.bodyAsText()).jsonObject
        assertTrue(rejectBody["data"]?.jsonObject?.get("rejected")?.jsonPrimitive?.boolean == true)

        println("Decision rejected: $decisionId")
    }

    // ==================== Scenario 2: RAG Workflow ====================

    @Test
    @Order(3)
    fun `E2E - complete RAG workflow - ingest, search, delete`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Step 1: 문서 수집 (Ingest)
        val doc1Response = client.post("/api/v2/rag/ingest") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{
                "content": "Kotlin is a modern programming language that runs on the JVM. It is concise, safe, and interoperable with Java.",
                "title": "Kotlin Introduction",
                "documentId": "e2e-doc-kotlin"
            }""")
        }

        assertEquals(HttpStatusCode.Created, doc1Response.status)

        val doc1Body = json.parseToJsonElement(doc1Response.bodyAsText()).jsonObject
        assertTrue(doc1Body["success"]?.jsonPrimitive?.boolean == true)
        val kotlinDocId = doc1Body["data"]?.jsonObject?.get("documentId")?.jsonPrimitive?.content
        val kotlinChunks = doc1Body["data"]?.jsonObject?.get("chunkCount")?.jsonPrimitive?.int

        assertNotNull(kotlinDocId)
        assertTrue((kotlinChunks ?: 0) > 0, "Should have at least 1 chunk")

        println("Ingested document: $kotlinDocId ($kotlinChunks chunks)")

        // Step 2: 추가 문서 수집
        val doc2Response = client.post("/api/v2/rag/ingest") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{
                "content": "Python is a versatile programming language known for its readability and extensive libraries for data science and machine learning.",
                "title": "Python Introduction",
                "documentId": "e2e-doc-python"
            }""")
        }

        assertEquals(HttpStatusCode.Created, doc2Response.status)

        // Step 3: 저장소 크기 확인
        val sizeResponse = client.get("/api/v2/rag/size") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, sizeResponse.status)

        val sizeBody = json.parseToJsonElement(sizeResponse.bodyAsText()).jsonObject
        val storeSize = sizeBody["data"]?.jsonObject?.get("size")?.jsonPrimitive?.int

        assertTrue((storeSize ?: 0) >= 2, "Store should have at least 2 chunks")
        println("Store size: $storeSize chunks")

        // Step 4: 문서 목록 조회
        val docsResponse = client.get("/api/v2/rag/documents") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, docsResponse.status)

        val docsBody = json.parseToJsonElement(docsResponse.bodyAsText()).jsonObject
        val documents = docsBody["data"]?.jsonObject?.get("documents")?.jsonArray

        assertNotNull(documents)
        assertTrue(documents.size >= 2, "Should have at least 2 documents")
        println("Documents in store: ${documents.size}")

        // Step 5: 검색 - "programming language"
        val searchResponse = client.post("/api/v2/rag/search") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{
                "query": "programming language JVM",
                "topK": 5,
                "minScore": 0.0
            }""")
        }

        assertEquals(HttpStatusCode.OK, searchResponse.status)

        val searchBody = json.parseToJsonElement(searchResponse.bodyAsText()).jsonObject
        val searchResults = searchBody["data"]?.jsonObject?.get("results")?.jsonArray
        val totalFound = searchBody["data"]?.jsonObject?.get("totalFound")?.jsonPrimitive?.int

        assertNotNull(searchResults)
        assertTrue((totalFound ?: 0) > 0, "Should find at least 1 result")
        println("Search found: $totalFound results")

        // Step 6: 단일 문서 삭제
        val deleteResponse = client.delete("/api/v2/rag/e2e-doc-python") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, deleteResponse.status)

        val deleteBody = json.parseToJsonElement(deleteResponse.bodyAsText()).jsonObject
        val deletedChunks = deleteBody["data"]?.jsonObject?.get("deletedChunks")?.jsonPrimitive?.int

        assertTrue((deletedChunks ?: 0) > 0, "Should have deleted chunks")
        println("Deleted document: e2e-doc-python ($deletedChunks chunks)")

        // Step 7: 저장소 초기화
        val clearResponse = client.delete("/api/v2/rag") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, clearResponse.status)

        // Step 8: 초기화 확인
        val finalSizeResponse = client.get("/api/v2/rag/size") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        val finalSizeBody = json.parseToJsonElement(finalSizeResponse.bodyAsText()).jsonObject
        val finalSize = finalSizeBody["data"]?.jsonObject?.get("size")?.jsonPrimitive?.int

        assertEquals(0, finalSize, "Store should be empty after clear")
        println("Store cleared successfully")
    }

    @Test
    @Order(4)
    fun `E2E - RAG batch ingest and search`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Step 1: 배치 수집
        val batchResponse = client.post("/api/v2/rag/ingest/batch") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{
                "documents": [
                    {"content": "Machine learning is a subset of artificial intelligence.", "title": "ML Intro"},
                    {"content": "Deep learning uses neural networks with many layers.", "title": "DL Intro"},
                    {"content": "Natural language processing enables computers to understand human language.", "title": "NLP Intro"},
                    {"content": "Computer vision allows machines to interpret visual information.", "title": "CV Intro"}
                ]
            }""")
        }

        assertEquals(HttpStatusCode.Created, batchResponse.status)

        val batchBody = json.parseToJsonElement(batchResponse.bodyAsText()).jsonObject
        val totalDocs = batchBody["data"]?.jsonObject?.get("totalDocuments")?.jsonPrimitive?.int
        val successCount = batchBody["data"]?.jsonObject?.get("successCount")?.jsonPrimitive?.int

        assertEquals(4, totalDocs)
        assertEquals(4, successCount)
        println("Batch ingested: $successCount/$totalDocs documents")

        // Step 2: 검색
        val searchResponse = client.post("/api/v2/rag/search") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"query": "neural networks deep learning", "topK": 3}""")
        }

        assertEquals(HttpStatusCode.OK, searchResponse.status)

        val searchBody = json.parseToJsonElement(searchResponse.bodyAsText()).jsonObject
        val results = searchBody["data"]?.jsonObject?.get("results")?.jsonArray

        assertNotNull(results)
        assertTrue(results.isNotEmpty(), "Should find results for neural networks")

        // Cleanup
        client.delete("/api/v2/rag") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    // ==================== Scenario 3: System Monitoring ====================

    @Test
    @Order(5)
    fun `E2E - system health and introspection`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Step 1: Health check (no auth required)
        val healthResponse = client.get("/api/v2/system/health")

        assertEquals(HttpStatusCode.OK, healthResponse.status)

        val healthBody = json.parseToJsonElement(healthResponse.bodyAsText()).jsonObject
        assertEquals("healthy", healthBody["data"]?.jsonObject?.get("status")?.jsonPrimitive?.content)

        val checks = healthBody["data"]?.jsonObject?.get("checks")?.jsonObject
        assertNotNull(checks?.get("core"))
        assertNotNull(checks?.get("executors"))
        assertNotNull(checks?.get("gates"))
        assertNotNull(checks?.get("dacs"))

        println("Health: OK")

        // Step 2: System info (no auth required)
        val infoResponse = client.get("/api/v2/system/info")

        assertEquals(HttpStatusCode.OK, infoResponse.status)

        val infoBody = json.parseToJsonElement(infoResponse.bodyAsText()).jsonObject
        val version = infoBody["data"]?.jsonObject?.get("version")?.jsonPrimitive?.content

        assertNotNull(version)
        assertTrue(version.startsWith("2."), "Version should be 2.x")
        println("Version: $version")

        // Step 3: List executors (auth required)
        val executorsResponse = client.get("/api/v2/system/executors") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, executorsResponse.status)

        val executorsBody = json.parseToJsonElement(executorsResponse.bodyAsText()).jsonObject
        val executors = executorsBody["data"]?.jsonObject?.get("executors")?.jsonArray

        assertNotNull(executors)
        assertTrue(executors.size >= 5, "Should have at least 5 executors")

        // Verify RAG executor is registered
        val ragExecutor = executors.find {
            it.jsonObject["id"]?.jsonPrimitive?.content == "rag-executor"
        }
        assertNotNull(ragExecutor, "RAG executor should be registered")

        println("Executors: ${executors.size} registered")

        // Step 4: List gates (auth required)
        val gatesResponse = client.get("/api/v2/system/gates") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, gatesResponse.status)

        val gatesBody = json.parseToJsonElement(gatesResponse.bodyAsText()).jsonObject
        val gates = gatesBody["data"]?.jsonObject?.get("gates")?.jsonArray

        assertNotNull(gates)
        assertTrue(gates.isNotEmpty(), "Should have gates")

        println("Gates: ${gates.size} registered")

        // Step 5: List personas (auth required)
        val personasResponse = client.get("/api/v2/system/personas") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, personasResponse.status)

        val personasBody = json.parseToJsonElement(personasResponse.bodyAsText()).jsonObject
        val personas = personasBody["data"]?.jsonObject?.get("personas")?.jsonArray

        assertNotNull(personas)
        assertTrue(personas.isNotEmpty(), "Should have DACS personas")

        // Verify standard personas
        val personaIds = personas.map { it.jsonObject["id"]?.jsonPrimitive?.content }
        assertTrue(personaIds.contains("architect"), "Should have architect persona")
        assertTrue(personaIds.contains("reviewer"), "Should have reviewer persona")
        assertTrue(personaIds.contains("adversary"), "Should have adversary persona")

        println("DACS Personas: ${personas.size} registered")
    }

    // ==================== Scenario 4: Authentication Lifecycle ====================

    @Test
    @Order(6)
    fun `E2E - authentication lifecycle`() = testApplication {
        application { module() }

        // Step 1: Auto-login
        val autoLoginResponse = client.get("/api/v2/auth/auto-login")

        assertEquals(HttpStatusCode.OK, autoLoginResponse.status)

        val autoLoginBody = json.parseToJsonElement(autoLoginResponse.bodyAsText()).jsonObject
        val token = autoLoginBody["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content

        assertNotNull(token)
        println("Auto-login successful")

        // Step 2: Get user info
        val meResponse = client.get("/api/v2/auth/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, meResponse.status)

        val meBody = json.parseToJsonElement(meResponse.bodyAsText()).jsonObject
        val userId = meBody["data"]?.jsonObject?.get("userId")?.jsonPrimitive?.content
        val roles = meBody["data"]?.jsonObject?.get("roles")?.jsonArray

        assertNotNull(userId)
        assertNotNull(roles)
        assertTrue(roles.isNotEmpty(), "User should have roles")

        println("User: $userId, Roles: ${roles.map { it.jsonPrimitive.content }}")

        // Step 3: Manual login
        val loginResponse = client.post("/api/v2/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username": "admin", "password": "admin123"}""")
        }

        assertEquals(HttpStatusCode.OK, loginResponse.status)

        val loginBody = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
        val manualToken = loginBody["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content

        assertNotNull(manualToken)
        println("Manual login successful")

        // Step 4: Access protected endpoint with new token
        val executorsResponse = client.get("/api/v2/system/executors") {
            header(HttpHeaders.Authorization, "Bearer $manualToken")
        }

        assertEquals(HttpStatusCode.OK, executorsResponse.status)
        println("Protected endpoint accessed successfully")

        // Step 5: Invalid token should fail
        val invalidResponse = client.get("/api/v2/system/executors") {
            header(HttpHeaders.Authorization, "Bearer invalid-token")
        }

        assertEquals(HttpStatusCode.Unauthorized, invalidResponse.status)
        println("Invalid token correctly rejected")
    }

    // ==================== Scenario 5: Error Handling ====================

    @Test
    @Order(7)
    fun `E2E - error handling across layers`() = testApplication {
        application { module() }

        val token = getToken(client)

        // Step 1: 존재하지 않는 리소스 조회
        val notFoundDecision = client.get("/api/v2/decisions/non-existent-id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, notFoundDecision.status)
        println("Not found error handled correctly")

        // Step 2: 존재하지 않는 Blueprint 조회
        val notFoundBlueprint = client.get("/api/v2/blueprints/non-existent-id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, notFoundBlueprint.status)

        // Step 3: 존재하지 않는 Execution 조회
        val notFoundExecution = client.get("/api/v2/executions/non-existent-id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, notFoundExecution.status)

        // Step 4: 존재하지 않는 문서 삭제
        val deleteNonExistent = client.delete("/api/v2/rag/non-existent-doc") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, deleteNonExistent.status)
        val deleteBody = json.parseToJsonElement(deleteNonExistent.bodyAsText()).jsonObject
        assertEquals(0, deleteBody["data"]?.jsonObject?.get("deletedChunks")?.jsonPrimitive?.int)

        println("Error handling verified across all layers")
    }

    // ==================== Scenario 6: Concurrent Operations ====================

    @Test
    @Order(8)
    fun `E2E - multiple decisions workflow`() = testApplication {
        application { module() }

        val token = getToken(client)

        // 여러 판단 요청 생성
        val decisionIds = mutableListOf<String>()

        repeat(3) { i ->
            val response = client.post("/api/v2/decisions") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody("""{
                    "spec": {
                        "intent": "Test operation $i",
                        "constraints": ["test-constraint-$i"]
                    }
                }""")
            }

            assertEquals(HttpStatusCode.Created, response.status)

            val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val decisionId = body["data"]?.jsonObject?.get("decisionId")?.jsonPrimitive?.content
            assertNotNull(decisionId)
            decisionIds.add(decisionId)
        }

        println("Created ${decisionIds.size} decisions")

        // 모든 결정 조회
        decisionIds.forEach { id ->
            val response = client.get("/api/v2/decisions/$id") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }

        println("All decisions retrieved successfully")

        // Blueprint 목록 조회
        val blueprintsResponse = client.get("/api/v2/blueprints") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, blueprintsResponse.status)
        println("Blueprints list retrieved")
    }

    // ==================== Helper Functions ====================

    private suspend fun getToken(client: io.ktor.client.HttpClient): String {
        val loginResponse = client.get("/api/v2/auth/auto-login")
        val loginBody = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
        return loginBody["data"]?.jsonObject?.get("accessToken")?.jsonPrimitive?.content
            ?: throw IllegalStateException("Failed to get token")
    }
}

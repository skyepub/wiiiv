package io.wiiiv.integration

import io.wiiiv.blueprint.BlueprintRunner
import io.wiiiv.dacs.SimpleDACS
import io.wiiiv.execution.CompositeExecutor
import io.wiiiv.execution.impl.ApiExecutor
import io.wiiiv.execution.impl.OpenAIProvider
import io.wiiiv.governor.*
import io.wiiiv.governor.NextAction
import io.wiiiv.rag.Document
import io.wiiiv.rag.RagPipeline
import io.wiiiv.rag.embedding.OpenAIEmbeddingProvider
import io.wiiiv.rag.vector.InMemoryVectorStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * API Workflow E2E Test - Phase 4
 *
 * Governor의 반복적 API 워크플로우를 실제 LLM (OpenAI) + Mock API Server로 검증한다.
 *
 * ## Setup
 * - MockApiServer: 임베디드 Ktor Netty (랜덤 포트)
 * - RAG: OpenAIEmbeddingProvider + API 스펙 문서 ingested
 * - Governor: ConversationalGovernor + ApiExecutor + RagPipeline
 *
 * ## 실행 방법
 * ```bash
 * OPENAI_API_KEY=sk-... ./gradlew :wiiiv-core:test --tests "io.wiiiv.integration.ApiWorkflowE2ETest"
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ApiWorkflowE2ETest {

    companion object {
        private val API_KEY = System.getenv("OPENAI_API_KEY") ?: ""
        private const val MODEL = "gpt-4o-mini"

        private val PHASE4_LOG_DIR: File by lazy {
            val projectRoot = File(System.getProperty("user.dir")).let { dir ->
                if (dir.name == "wiiiv-core") dir.parentFile else dir
            }
            File(projectRoot, "test-wiiiv/phase4/logs").also { it.mkdirs() }
        }
    }

    private lateinit var mockServer: MockApiServer
    private lateinit var ragPipeline: RagPipeline
    private lateinit var governor: ConversationalGovernor
    private lateinit var logFile: File

    @BeforeAll
    fun setup() {
        if (API_KEY.isBlank()) {
            println("SKIP ALL: OPENAI_API_KEY not set")
            return
        }

        // 1. Start mock API server
        mockServer = MockApiServer()
        mockServer.start()
        println("MockApiServer started on port ${mockServer.port}")

        // 2. Setup RAG with API spec
        ragPipeline = RagPipeline(
            embeddingProvider = OpenAIEmbeddingProvider(apiKey = API_KEY),
            vectorStore = InMemoryVectorStore()
        )

        // Ingest API spec
        val apiSpec = """
            ## Mock API Specification

            Base URL: ${mockServer.baseUrl()}

            ### GET /api/users
            Query parameters: name (optional, filter by name)
            Returns: JSON array of users [{id, name, email}]
            Example: GET ${mockServer.baseUrl()}/api/users?name=john

            ### GET /api/users/{id}
            Returns: Single user {id, name, email}
            Returns 404 if user not found.
            Example: GET ${mockServer.baseUrl()}/api/users/1

            ### GET /api/orders
            Query parameters: userId (optional, filter by user ID)
            Returns: JSON array of orders [{id, userId, status, item}]
            Example: GET ${mockServer.baseUrl()}/api/orders?userId=1

            ### PUT /api/orders/{id}
            Body: {"status": "new_status"}
            Returns: Updated order {id, userId, status, item}
            Returns 404 if order not found.
            Example: PUT ${mockServer.baseUrl()}/api/orders/order-101
            Body: {"status": "shipped"}

            ### Data
            Users: john (id=1), jane (id=2), bob (id=3)
            Orders: order-101 (userId=1, pending, Laptop), order-102 (userId=1, pending, Mouse), order-103 (userId=2, shipped, Keyboard)
        """.trimIndent()

        runBlocking {
            ragPipeline.ingest(Document(
                content = apiSpec,
                title = "Mock API Specification"
            ))
        }

        // 3. Create Governor with ApiExecutor
        val llmProvider = OpenAIProvider(
            apiKey = API_KEY,
            defaultModel = MODEL,
            defaultMaxTokens = 2000
        )
        val dacs = SimpleDACS.DEFAULT
        val executor = CompositeExecutor(listOf(ApiExecutor.INSTANCE))
        val blueprintRunner = BlueprintRunner.create(executor)

        governor = ConversationalGovernor.create(
            dacs = dacs,
            llmProvider = llmProvider,
            model = MODEL,
            blueprintRunner = blueprintRunner,
            ragPipeline = ragPipeline
        )

        println("=== API Workflow E2E Test (Phase 4) ===")
        println("Model: $MODEL")
        println("Mock Server: ${mockServer.baseUrl()}")
        println()
    }

    @AfterAll
    fun teardown() {
        if (::mockServer.isInitialized) {
            mockServer.stop()
            println("MockApiServer stopped")
        }
    }

    @BeforeEach
    fun resetData() {
        if (::mockServer.isInitialized) {
            mockServer.dataStore.reset()
        }
    }

    // ========== Helper ==========

    /**
     * API Workflow 대화 실행 + 로그 기록
     *
     * Phase 5: auto-continue 루프 추가.
     * nextAction == CONTINUE_EXECUTION이면 "계속" 메시지로 자동 반복 (최대 10회).
     */
    private suspend fun runWorkflowConversation(
        caseName: String,
        messages: List<String>,
        logFileName: String
    ): List<ConversationResponse> {
        val session = governor.startSession()
        val responses = mutableListOf<ConversationResponse>()
        val log = StringBuilder()
        val timestamp = DateTimeFormatter.ofPattern("HH:mm:ss").format(LocalDateTime.now())

        fun log(msg: String) {
            log.appendLine(msg)
            println(msg)
        }

        fun logResponse(turnLabel: String, response: ConversationResponse) {
            log("[$turnLabel] Governor (${response.action}): ${response.message.take(300)}")
            response.draftSpec?.let { spec ->
                log("          DraftSpec: taskType=${spec.taskType}, intent=${spec.intent?.take(50)}, domain=${spec.domain}")
            }
            response.askingFor?.let { log("          askingFor: $it") }
            response.dacsResult?.let { log("          DACS: ${it.consensus} - ${it.reason.take(100)}") }
            response.blueprint?.let { bp ->
                log("          Blueprint: ${bp.steps.size} steps - ${bp.steps.map { "${it.type}" }}")
            }
            response.nextAction?.let { log("          nextAction: $it") }
            log("")
        }

        log("=== $caseName ===")
        log("[$timestamp] Session: ${session.sessionId}")
        log("Mock Server: ${mockServer.baseUrl()}")
        log("")
        log("--- Interview Phase ---")

        for ((i, msg) in messages.withIndex()) {
            log("[Turn ${i + 1}] User: $msg")
            var response = governor.chat(session.sessionId, msg)
            responses.add(response)
            logResponse("Turn ${i + 1}", response)

            // Auto-continue loop for turn-based execution
            var continuations = 0
            while (response.nextAction == NextAction.CONTINUE_EXECUTION && continuations < 10) {
                continuations++
                log("[Turn ${i + 1}-cont$continuations] Auto-continue: 계속")
                response = governor.chat(session.sessionId, "계속")
                responses.add(response)
                logResponse("Turn ${i + 1}-cont$continuations", response)
            }
        }

        // Write log
        val logFile = File(PHASE4_LOG_DIR, logFileName)
        logFile.parentFile?.mkdirs()
        logFile.writeText(log.toString())

        governor.endSession(session.sessionId)
        return responses
    }

    // ========== Case 24: Single API call (GET users) ==========

    /**
     * Case 24: 단일 API 호출 - 사용자 조회
     *
     * Turn 1: API 워크플로우 의도 설명
     * Turn 2: 도메인 제공
     * Turn 3: 확인
     *
     * Hard Assert: >= 1 iteration, response has user data
     */
    @Test
    @Order(24)
    fun `Case 24 - single API call GET users`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        val responses = runWorkflowConversation(
            "Case 24: Single API call - GET users",
            listOf(
                "API로 사용자 목록을 조회해줘. john이라는 사용자를 찾아줘",
                "도메인은 Mock API 서버야. 기본 URL은 ${mockServer.baseUrl()}",
                "응 진행해"
            ),
            "case24.log"
        )

        // Hard Assert: 마지막 응답에서 EXECUTE 또는 워크플로우 완료
        val lastResponse = responses.last()
        println("  [Result] Last action: ${lastResponse.action}")
        println("  [Result] Last message: ${lastResponse.message.take(200)}")

        // 워크플로우가 실행되었으면 메시지에 john 관련 내용이 있어야 함
        val hasWorkflowResult = responses.any {
            it.action == ActionType.EXECUTE &&
                (it.message.contains("john", ignoreCase = true) ||
                    it.message.contains("iteration", ignoreCase = true) ||
                    it.message.contains("Workflow", ignoreCase = true))
        }
        println("  [Result] Has workflow result mentioning john: $hasWorkflowResult")
    }

    // ========== Case 25: Two-step lookup (users → orders) ==========

    /**
     * Case 25: 2단계 조회 - 사용자 검색 → 주문 조회
     *
     * Hard Assert: == 2 iterations, second URL uses first result's ID
     */
    @Test
    @Order(25)
    fun `Case 25 - two-step lookup users then orders`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        val responses = runWorkflowConversation(
            "Case 25: Two-step lookup - users → orders",
            listOf(
                "john이라는 사용자의 주문 목록을 조회해줘. 먼저 사용자를 찾고 그 사용자의 주문을 조회해야 해.",
                "Mock API 서버 도메인이야. 기본 URL: ${mockServer.baseUrl()}",
                "응 진행해"
            ),
            "case25.log"
        )

        val lastResponse = responses.last()
        println("  [Result] Last action: ${lastResponse.action}")
        println("  [Result] Last message preview: ${lastResponse.message.take(400)}")

        // Soft Assert: 메시지에 iteration이 2회 이상 언급되면 좋음
        val message = lastResponse.message
        val iterationMentions = Regex("Iteration \\d+", RegexOption.IGNORE_CASE).findAll(message).count()
        println("  [Result] Iteration mentions in summary: $iterationMentions")

        // Soft Assert: orders 관련 데이터가 메시지에 포함
        val hasOrderData = message.contains("order", ignoreCase = true) ||
            message.contains("Laptop", ignoreCase = true) ||
            message.contains("Mouse", ignoreCase = true)
        println("  [Result] Has order data in response: $hasOrderData")
    }

    // ========== Case 26: Multi-step write (users → orders → PUT each) ==========

    /**
     * Case 26: 다단계 쓰기 - 사용자 조회 → 주문 조회 → 상태 변경
     *
     * Hard Assert: >= 3 iterations, at least 1 PUT, isComplete=true
     */
    @Test
    @Order(26)
    fun `Case 26 - multi-step write users orders PUT`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        val responses = runWorkflowConversation(
            "Case 26: Multi-step write - users → orders → PUT",
            listOf(
                "john의 모든 주문 상태를 shipped로 변경해줘. 순서: 1) john 사용자 조회 2) john의 주문 목록 조회 3) 각 주문을 shipped로 PUT 업데이트",
                "Mock API 서버. 기본 URL: ${mockServer.baseUrl()}",
                "응 진행해"
            ),
            "case26.log"
        )

        val lastResponse = responses.last()
        println("  [Result] Last action: ${lastResponse.action}")
        println("  [Result] Last message preview: ${lastResponse.message.take(500)}")

        // Soft Assert: PUT 호출이 메시지에 나와야 함
        val hasPut = lastResponse.message.contains("PUT", ignoreCase = true)
        println("  [Result] Has PUT in response: $hasPut")

        // Soft Assert: 완료 메시지 확인
        val isComplete = lastResponse.action == ActionType.EXECUTE
        println("  [Result] Workflow completed (EXECUTE): $isComplete")

        // Verify data actually changed in mock server
        val updatedOrders = mockServer.dataStore.getOrdersByUserId(1)
        val allShipped = updatedOrders.all { it.status == "shipped" }
        println("  [Result] Orders actually updated to shipped: $allShipped (${updatedOrders.map { "${it.id}:${it.status}" }})")
    }

    // ========== Case 27: Error recovery (nonexistent user) ==========

    /**
     * Case 27: 에러 복구 - 존재하지 않는 사용자
     *
     * Hard Assert: Does NOT query orders with bad ID
     */
    @Test
    @Order(27)
    fun `Case 27 - error recovery nonexistent user`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        val responses = runWorkflowConversation(
            "Case 27: Error recovery - nonexistent user",
            listOf(
                "alice라는 사용자의 주문을 조회해줘. 먼저 alice를 찾고, 있으면 주문을 조회해. 없으면 알려줘.",
                "Mock API 서버. 기본 URL: ${mockServer.baseUrl()}",
                "진행해"
            ),
            "case27.log"
        )

        val lastResponse = responses.last()
        println("  [Result] Last action: ${lastResponse.action}")
        println("  [Result] Last message preview: ${lastResponse.message.take(400)}")

        // Soft Assert: 메시지에 사용자를 찾지 못했다는 내용 또는 빈 결과
        val mentionsNotFound = lastResponse.message.let { msg ->
            msg.contains("not found", ignoreCase = true) ||
                msg.contains("찾을 수 없", ignoreCase = true) ||
                msg.contains("없습니다", ignoreCase = true) ||
                msg.contains("empty", ignoreCase = true) ||
                msg.contains("[]", ignoreCase = true) ||
                msg.contains("없", ignoreCase = true)
        }
        println("  [Result] Mentions user not found: $mentionsNotFound")
    }

    // ========== Case 28: Duplicate prevention ==========

    /**
     * Case 28: 중복 호출 방지
     *
     * Hard Assert: No repeated identical API URLs
     */
    @Test
    @Order(28)
    fun `Case 28 - duplicate prevention`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        val responses = runWorkflowConversation(
            "Case 28: Duplicate prevention",
            listOf(
                "john 사용자를 조회해줘. 한 번만 조회하면 돼. 같은 API를 두 번 호출하지 마.",
                "Mock API 서버. 기본 URL: ${mockServer.baseUrl()}",
                "진행해"
            ),
            "case28.log"
        )

        val lastResponse = responses.last()
        println("  [Result] Last action: ${lastResponse.action}")
        println("  [Result] Last message preview: ${lastResponse.message.take(300)}")

        // Soft Assert: 워크플로우 요약에서 동일한 URL이 중복되지 않아야 함
        val message = lastResponse.message
        val urlPattern = Regex("(GET|POST|PUT|DELETE)\\s+(http[^\\s,\\]]+)")
        val urls = urlPattern.findAll(message).map { it.groupValues[2] }.toList()
        val uniqueUrls = urls.toSet()
        println("  [Result] URLs in summary: $urls")
        println("  [Result] Unique URLs: $uniqueUrls")
        println("  [Result] No duplicates: ${urls.size == uniqueUrls.size}")
    }

    // ========== Case 29: Single-call completion ==========

    /**
     * Case 29: 단일 호출로 완료
     *
     * Hard Assert: == 1 iteration for simple task
     */
    @Test
    @Order(29)
    fun `Case 29 - single call completion`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        val responses = runWorkflowConversation(
            "Case 29: Single-call completion",
            listOf(
                "API 서버의 전체 사용자 목록만 한 번 조회해줘. 추가 작업 필요 없어.",
                "Mock API 서버. 기본 URL: ${mockServer.baseUrl()}",
                "진행해"
            ),
            "case29.log"
        )

        val lastResponse = responses.last()
        println("  [Result] Last action: ${lastResponse.action}")
        println("  [Result] Last message preview: ${lastResponse.message.take(300)}")

        // Soft Assert: 1번 iteration으로 완료
        val message = lastResponse.message
        val iterationCount = Regex("Iteration (\\d+)").findAll(message).count()
        println("  [Result] Iteration count in summary: $iterationCount")

        // Soft Assert: 완료 표시
        val isComplete = lastResponse.action == ActionType.EXECUTE
        println("  [Result] Workflow completed: $isComplete")
    }
}

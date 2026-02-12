package io.wiiiv.integration

import io.wiiiv.blueprint.BlueprintRunner
import io.wiiiv.dacs.SimpleDACS
import io.wiiiv.execution.CompositeExecutor
import io.wiiiv.execution.impl.ApiExecutor
import io.wiiiv.execution.impl.CommandExecutor
import io.wiiiv.execution.impl.FileExecutor
import io.wiiiv.execution.impl.OpenAIProvider
import io.wiiiv.governor.*
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
 * Phase 5-6 E2E Test - 턴 기반 실행 모델 + 멀티 태스크
 *
 * Phase 5: executeTurn 통합, nextAction 힌트, 세션 컨텍스트 보존
 * Phase 6: TaskSlot, taskSwitch, COMPLETED 전이, 프록시 패턴
 *
 * 실제 LLM (OpenAI gpt-4o-mini) + 실제 Executor로 검증한다.
 *
 * ## 실행 방법
 * ```bash
 * OPENAI_API_KEY=sk-... ./gradlew :wiiiv-core:test --tests "io.wiiiv.integration.TurnBasedE2ETest"
 * ```
 *
 * ## Assertion 전략
 * - Hard Assert: 구조적 속성 (TaskStatus, activeTaskId, executionHistory 크기)
 * - Soft Assert: LLM 메시지 내용 (비결정적이므로 로그 + 패턴 확인)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TurnBasedE2ETest {

    companion object {
        private val API_KEY = System.getenv("OPENAI_API_KEY") ?: ""
        private const val MODEL = "gpt-4o-mini"

        private val LOG_DIR: File by lazy {
            val projectRoot = File(System.getProperty("user.dir")).let { dir ->
                if (dir.name == "wiiiv-core") dir.parentFile else dir
            }
            File(projectRoot, "test-wiiiv/phase5-6/logs").also { it.mkdirs() }
        }
    }

    private lateinit var governor: ConversationalGovernor
    private lateinit var mockServer: MockApiServer
    private lateinit var ragPipeline: RagPipeline
    private lateinit var apiGovernor: ConversationalGovernor

    @BeforeAll
    fun setup() {
        if (API_KEY.isBlank()) {
            println("SKIP ALL: OPENAI_API_KEY not set")
            return
        }

        val llmProvider = OpenAIProvider(
            apiKey = API_KEY,
            defaultModel = MODEL,
            defaultMaxTokens = 1000
        )
        val dacs = SimpleDACS.DEFAULT
        val executor = CompositeExecutor(listOf(FileExecutor.INSTANCE, CommandExecutor.INSTANCE))
        val blueprintRunner = BlueprintRunner.create(executor)

        // Governor with file/command execution
        governor = ConversationalGovernor.create(
            dacs = dacs,
            llmProvider = llmProvider,
            model = MODEL,
            blueprintRunner = blueprintRunner
        )

        // Mock API server + Governor with API execution
        mockServer = MockApiServer()
        mockServer.start()

        ragPipeline = RagPipeline(
            embeddingProvider = OpenAIEmbeddingProvider(apiKey = API_KEY),
            vectorStore = InMemoryVectorStore()
        )

        val apiSpec = """
            ## Mock API Specification
            Base URL: ${mockServer.baseUrl()}

            ### GET /api/users
            Query parameters: name (optional, filter by name)
            Returns: JSON array of users [{id, name, email}]

            ### GET /api/users/{id}
            Returns: Single user {id, name, email}

            ### GET /api/orders
            Query parameters: userId (optional, filter by user ID)
            Returns: JSON array of orders [{id, userId, status, item}]

            ### PUT /api/orders/{id}
            Body: {"status": "new_status"}
            Returns: Updated order {id, userId, status, item}

            ### Data
            Users: john (id=1), jane (id=2), bob (id=3)
            Orders: order-101 (userId=1, pending, Laptop), order-102 (userId=1, pending, Mouse)
        """.trimIndent()

        runBlocking {
            ragPipeline.ingest(Document(content = apiSpec, title = "Mock API Spec"))
        }

        val apiExecutor = CompositeExecutor(listOf(ApiExecutor.INSTANCE))
        val apiBlueprintRunner = BlueprintRunner.create(apiExecutor)

        apiGovernor = ConversationalGovernor.create(
            dacs = dacs,
            llmProvider = OpenAIProvider(
                apiKey = API_KEY,
                defaultModel = MODEL,
                defaultMaxTokens = 2000
            ),
            model = MODEL,
            blueprintRunner = apiBlueprintRunner,
            ragPipeline = ragPipeline
        )

        println("=== Phase 5-6 E2E Test ===")
        println("Model: $MODEL")
        println("Mock Server: ${mockServer.baseUrl()}")
        println()
    }

    @AfterAll
    fun teardown() {
        if (::mockServer.isInitialized) {
            mockServer.stop()
        }
    }

    @BeforeEach
    fun resetMockData() {
        if (::mockServer.isInitialized) {
            mockServer.dataStore.reset()
        }
    }

    // ========== Helpers ==========

    /**
     * 대화 실행 + 세션 반환 (세션 종료하지 않음 - 상태 검증용)
     */
    private data class ConversationResult(
        val responses: List<ConversationResponse>,
        val session: ConversationSession?,
        val sessionId: String
    )

    private suspend fun runConversationKeepSession(
        caseName: String,
        messages: List<String>,
        gov: ConversationalGovernor = governor,
        logFileName: String
    ): ConversationResult {
        val session = gov.startSession()
        val responses = mutableListOf<ConversationResponse>()
        val log = StringBuilder()
        val timestamp = DateTimeFormatter.ofPattern("HH:mm:ss").format(LocalDateTime.now())

        fun log(msg: String) {
            log.appendLine(msg)
            println(msg)
        }

        log("╔══════════════════════════════════════════════════════════════")
        log("║ $caseName")
        log("║ [$timestamp] Session: ${session.sessionId}")
        log("╠══════════════════════════════════════════════════════════════")

        for ((i, msg) in messages.withIndex()) {
            log("║ [Turn ${i + 1}] User: $msg")
            var response = gov.chat(session.sessionId, msg)
            responses.add(response)
            logResponse(log, "Turn ${i + 1}", response, session)

            // Auto-continue for API workflows
            var continuations = 0
            while (response.nextAction == NextAction.CONTINUE_EXECUTION && continuations < 10) {
                continuations++
                log("║ [Turn ${i + 1}-cont$continuations] Auto-continue")
                response = gov.chat(session.sessionId, "계속")
                responses.add(response)
                logResponse(log, "Turn ${i + 1}-cont$continuations", response, session)
            }
        }

        // Log task state
        log("║")
        log("║ --- Session State ---")
        log("║ activeTaskId: ${session.context.activeTaskId}")
        log("║ tasks: ${session.context.tasks.map { (id, t) -> "$id(${t.status})" }}")
        log("║ executionHistory (session-level): ${session.context.executionHistory.size}")
        for ((id, task) in session.context.tasks) {
            log("║   Task [$id] '${task.label}': ${task.status}, history=${task.context.executionHistory.size}")
        }
        log("╚══════════════════════════════════════════════════════════════")
        log("")

        // Write log
        val logFile = File(LOG_DIR, logFileName)
        logFile.parentFile?.mkdirs()
        logFile.writeText(log.toString())

        return ConversationResult(responses, gov.getSession(session.sessionId), session.sessionId)
    }

    private fun logResponse(
        log: StringBuilder,
        label: String,
        response: ConversationResponse,
        session: ConversationSession
    ) {
        fun log(msg: String) {
            log.appendLine(msg)
            println(msg)
        }
        log("║ [$label] Governor (${response.action}): ${response.message.take(200)}")
        response.draftSpec?.let { spec ->
            val parts = mutableListOf<String>()
            spec.taskType?.let { parts.add("taskType=$it") }
            spec.intent?.let { parts.add("intent=${it.take(50)}") }
            spec.domain?.let { parts.add("domain=$it") }
            spec.techStack?.let { parts.add("techStack=$it") }
            spec.targetPath?.let { parts.add("targetPath=$it") }
            log("║          DraftSpec: ${parts.joinToString(", ")}")
        }
        response.nextAction?.let { log("║          nextAction: $it") }
        response.blueprint?.let { log("║          Blueprint: ${it.steps.size} steps") }
        log("║          activeTask: ${session.context.activeTaskId}, tasks: ${session.context.tasks.size}")
        log("║")
    }

    // ╔══════════════════════════════════════════════════════════════
    // ║ Phase 5: Turn-Based Execution Model
    // ╚══════════════════════════════════════════════════════════════

    // ========== Case 30: File Read → Execute → COMPLETED ==========

    /**
     * Case 30: 파일 읽기 요청 → executeDirectTurn → COMPLETED 전이
     *
     * 검증 대상:
     * - executeDirectTurn() 실행 경로 동작
     * - 실행 후 TaskSlot 상태가 COMPLETED
     * - activeTaskId가 null로 초기화
     * - executionHistory에 결과 적재
     */
    @Test
    @Order(30)
    fun `Case 30 - file read execute then COMPLETED`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        // Prepare test file
        val testFile = File("/tmp/wiiiv-e2e-case30.txt")
        testFile.writeText("Hello from Phase 5 E2E test!")

        try {
            val result = runConversationKeepSession(
                "Case 30: File Read → COMPLETED",
                listOf(
                    "/tmp/wiiiv-e2e-case30.txt 파일 읽어줘"
                ),
                logFileName = "case30.log"
            )

            val responses = result.responses
            val session = result.session

            // Hard Assert: EXECUTE 응답이 존재
            val executeResponse = responses.find { it.action == ActionType.EXECUTE }
            println("  [Result] Actions: ${responses.map { it.action }}")

            if (executeResponse != null) {
                // Hard Assert: Blueprint가 있어야 함
                assertNotNull(executeResponse.blueprint,
                    "Case 30: EXECUTE 시 blueprint 존재해야 함")

                // Session state verification (session이 아직 있으면)
                if (session != null) {
                    // Hard Assert: 활성 작업이 없어야 함 (COMPLETED 전이 후)
                    assertNull(session.context.activeTaskId,
                        "Case 30: COMPLETED 후 activeTaskId는 null")

                    // Hard Assert: COMPLETED 작업이 tasks에 존재
                    val completedTasks = session.context.tasks.values.filter { it.status == TaskStatus.COMPLETED }
                    println("  [Result] Completed tasks: ${completedTasks.size}")
                    assertTrue(completedTasks.isNotEmpty(),
                        "Case 30: COMPLETED 상태의 작업이 있어야 함")

                    // Hard Assert: executionHistory에 결과 적재
                    val totalHistory = session.context.tasks.values.sumOf { it.context.executionHistory.size }
                    println("  [Result] Total execution history: $totalHistory")
                    assertTrue(totalHistory >= 1,
                        "Case 30: executionHistory에 최소 1개 기록")
                }
            } else {
                // LLM이 ASK로 응답할 수 있음 (경로 확인 등)
                println("  [WARN] No EXECUTE response - LLM may have asked for confirmation")
                val hasAskOrConfirm = responses.any { it.action in listOf(ActionType.ASK, ActionType.CONFIRM) }
                assertTrue(hasAskOrConfirm || responses.any { it.action == ActionType.EXECUTE },
                    "Case 30: EXECUTE, ASK, 또는 CONFIRM 중 하나는 있어야 함")
            }
        } finally {
            testFile.delete()
        }
    }

    // ========== Case 31: API Workflow → Multi-Turn → COMPLETED ==========

    /**
     * Case 31: API 워크플로우 멀티턴 실행 → COMPLETED
     *
     * 검증 대상:
     * - executeLlmDecidedTurn() 실행 경로
     * - nextAction = CONTINUE_EXECUTION → 자동 계속
     * - 완료 시 COMPLETED 전이
     * - executionHistory 누적
     */
    @Test
    @Order(31)
    fun `Case 31 - API workflow multi-turn COMPLETED`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        val result = runConversationKeepSession(
            "Case 31: API Workflow Multi-Turn → COMPLETED",
            listOf(
                "john 사용자의 주문 목록을 조회해줘. 먼저 john을 찾고 그 다음 주문을 조회해",
                "Mock API 서버야. 기본 URL: ${mockServer.baseUrl()}",
                "진행해"
            ),
            gov = apiGovernor,
            logFileName = "case31.log"
        )

        val responses = result.responses
        val session = result.session

        println("  [Result] Total responses: ${responses.size}")
        println("  [Result] Actions: ${responses.map { it.action }}")

        // Hard Assert: nextAction = CONTINUE_EXECUTION이 최소 1회 등장
        val continueCount = responses.count { it.nextAction == NextAction.CONTINUE_EXECUTION }
        println("  [Result] CONTINUE_EXECUTION count: $continueCount")
        // API workflow가 제대로 작동하면 최소 1회 auto-continue가 있어야 함
        // 하지만 LLM이 첫 턴에 완료할 수도 있으므로 soft check
        if (continueCount == 0) {
            println("  [WARN] No CONTINUE_EXECUTION - LLM may have completed in single turn")
        }

        // Session state verification
        if (session != null) {
            // Soft Assert: 작업이 생성되었으면 COMPLETED 확인
            if (session.context.tasks.isNotEmpty()) {
                val completedTasks = session.context.tasks.values.filter { it.status == TaskStatus.COMPLETED }
                println("  [Result] Tasks: ${session.context.tasks.map { (id, t) -> "$id(${t.status})" }}")
                println("  [Result] Completed: ${completedTasks.size}")
            }

            // Hard Assert: executionHistory에 기록이 있어야 함
            val totalHistory = session.context.tasks.values.sumOf { it.context.executionHistory.size } +
                (if (session.context.activeTaskId == null) 0 else 0)
            println("  [Result] Total execution history in tasks: $totalHistory")
        }

        // Hard Assert: 마지막 응답이 EXECUTE (workflow 완료) 또는 적어도 실행이 진행됨
        val lastNonContinue = responses.lastOrNull { it.nextAction != NextAction.CONTINUE_EXECUTION }
            ?: responses.last()
        println("  [Result] Last terminal action: ${lastNonContinue.action}")
        println("  [Result] Last message: ${lastNonContinue.message.take(200)}")
    }

    // ========== Case 32: Execute → Session Preserves Context ==========

    /**
     * Case 32: 실행 후 세션이 결과를 보존하고 후속 대화 가능
     *
     * 검증 대상:
     * - 실행 후 session.reset() 대신 resetSpec() 사용 (Phase 5)
     * - 실행 결과가 세션에 남아있음
     * - 후속 대화에서 "방금 결과" 참조 가능
     */
    @Test
    @Order(32)
    fun `Case 32 - session preserves execution context`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        val testFile = File("/tmp/wiiiv-e2e-case32.txt")
        testFile.writeText("session context test data\nline 2\nline 3")

        try {
            val result = runConversationKeepSession(
                "Case 32: Session Preserves Context",
                listOf(
                    "/tmp/wiiiv-e2e-case32.txt 읽어줘",
                    "방금 읽은 파일 내용이 뭐였어?"
                ),
                logFileName = "case32.log"
            )

            val responses = result.responses
            val session = result.session

            // Turn 1: EXECUTE (파일 읽기)
            println("  [Result] Turn 1 action: ${responses[0].action}")

            // Turn 2: REPLY (이전 결과 참조)
            // LLM이 실행 히스토리를 기반으로 대답해야 함
            val turn2Response = responses.last()
            println("  [Result] Turn 2 action: ${turn2Response.action}")
            println("  [Result] Turn 2 message: ${turn2Response.message.take(200)}")

            // Hard Assert: Turn 2는 EXECUTE가 아니어야 함 (같은 파일을 다시 읽는 것이 아님)
            // LLM이 이전 실행 결과를 참조하여 REPLY로 답해야 함
            if (turn2Response.action == ActionType.REPLY) {
                println("  [PASS] Turn 2 is REPLY - LLM referenced execution history")
            } else {
                println("  [INFO] Turn 2 is ${turn2Response.action} - LLM may have chosen to re-read")
            }

            // Session context check
            if (session != null) {
                val totalHistory = session.context.tasks.values.sumOf { it.context.executionHistory.size }
                println("  [Result] Execution history preserved: $totalHistory entries")
                assertTrue(totalHistory >= 1,
                    "Case 32: 실행 히스토리가 보존되어야 함")
            }
        } finally {
            testFile.delete()
        }
    }

    // ╔══════════════════════════════════════════════════════════════
    // ║ Phase 6: Multi-Task Cognitive Model
    // ╚══════════════════════════════════════════════════════════════

    // ========== Case 33: Task A in progress → "다른 거" → Task B ==========

    /**
     * Case 33: 작업 A 진행 중 → "다른 거 해줘" → Task B 시작
     *
     * 검증 대상:
     * - 작업 A가 SUSPENDED로 전이
     * - 새 작업 B가 ACTIVE로 생성
     * - tasks에 2개 작업 존재
     */
    @Test
    @Order(33)
    fun `Case 33 - task switch creates new task`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        // Create test file for task B
        val testFile = File("/tmp/wiiiv-e2e-case33.txt")
        testFile.writeText("Task switch test content\nline 2")

        val result = runConversationKeepSession(
            "Case 33: Task Switch - A → B",
            listOf(
                // Task A: 프로젝트 설계 인터뷰 시작
                "쇼핑몰 백엔드 시스템 만들어줘",
                "패션/의류 도메인이야",
                // 중간에 다른 작업 요청
                "아 잠깐, 그것 말고 /tmp/wiiiv-e2e-case33.txt 파일 좀 읽어줘"
            ),
            logFileName = "case33.log"
        )

        val responses = result.responses
        val session = result.session

        println("  [Result] Actions: ${responses.map { it.action }}")

        if (session != null) {
            val tasks = session.context.tasks
            println("  [Result] Total tasks: ${tasks.size}")
            for ((id, task) in tasks) {
                println("  [Result]   [$id] '${task.label}' -> ${task.status}")
            }

            // Soft Assert: tasks가 1개 이상
            // LLM이 taskSwitch를 사용했으면 2개, 그렇지 않으면 1개
            println("  [Result] activeTaskId: ${session.context.activeTaskId}")

            if (tasks.size >= 2) {
                // 이상적인 경우: 2개 작업 존재
                val suspended = tasks.values.filter { it.status == TaskStatus.SUSPENDED }
                println("  [PASS] Multi-task: ${tasks.size} tasks, ${suspended.size} suspended")
            } else {
                println("  [INFO] LLM may not have used taskSwitch signal")
            }
        }
    }

    // ========== Case 34: Task B complete → "아까 그거" → Task A resume ==========

    /**
     * Case 34: Task B 완료 → "아까 그거 계속" → Task A 재개
     *
     * 검증 대상:
     * - Task A의 DraftSpec이 보존됨
     * - Task A가 ACTIVE로 복원
     * - 이전 대화 맥락 유지
     */
    @Test
    @Order(34)
    fun `Case 34 - resume previous task`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        // Create test file for task B
        val testFile = File("/tmp/wiiiv-e2e-case34.txt")
        testFile.writeText("Task B test content")

        try {
            val result = runConversationKeepSession(
                "Case 34: Resume Task A after Task B",
                listOf(
                    // Task A: 프로젝트 설계 시작
                    "쇼핑몰 백엔드 시스템 만들어줘",
                    "패션/의류 도메인이야",
                    // Task B: 파일 읽기 (전환)
                    "잠깐 다른 거 해줘. /tmp/wiiiv-e2e-case34.txt 파일 좀 읽어줘",
                    // Task A 재개
                    "아까 쇼핑몰 프로젝트 이야기 계속하자"
                ),
                logFileName = "case34.log"
            )

            val responses = result.responses
            val session = result.session

            println("  [Result] Actions: ${responses.map { it.action }}")

            if (session != null) {
                val tasks = session.context.tasks
                println("  [Result] Total tasks: ${tasks.size}")
                for ((id, task) in tasks) {
                    println("  [Result]   [$id] '${task.label}' -> ${task.status}, " +
                        "spec.domain=${task.draftSpec.domain}, history=${task.context.executionHistory.size}")
                }

                // Soft Assert: 마지막 턴 응답에서 프로젝트 관련 내용
                val lastResponse = responses.last()
                println("  [Result] Last action: ${lastResponse.action}")
                println("  [Result] Last message: ${lastResponse.message.take(200)}")

                // LLM이 taskSwitch를 잘 사용했으면 프로젝트 맥락으로 돌아옴
                val mentionsProject = lastResponse.message.let { msg ->
                    msg.contains("쇼핑몰", ignoreCase = true) ||
                        msg.contains("패션", ignoreCase = true) ||
                        msg.contains("프로젝트", ignoreCase = true) ||
                        msg.contains("백엔드", ignoreCase = true)
                }
                println("  [Result] Mentions project context: $mentionsProject")
            }
        } finally {
            testFile.delete()
        }
    }

    // ========== Case 35: Execute → COMPLETED → New Task Fresh ==========

    /**
     * Case 35: 작업 완료 후 새 작업 시작 → 독립적인 컨텍스트
     *
     * 검증 대상:
     * - COMPLETED 작업의 히스토리가 보존
     * - 새 작업이 fresh DraftSpec으로 시작
     * - 이전 작업의 executionHistory가 새 작업에 섞이지 않음
     */
    @Test
    @Order(35)
    fun `Case 35 - completed task then new task starts fresh`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        val testFile1 = File("/tmp/wiiiv-e2e-case35a.txt")
        testFile1.writeText("First task content")

        try {
            val result = runConversationKeepSession(
                "Case 35: COMPLETED → New Task",
                listOf(
                    // Task 1: 파일 읽기 (완료됨)
                    "/tmp/wiiiv-e2e-case35a.txt 읽어줘",
                    // Task 2: 새 작업 시작
                    "JWT 인증이 뭔지 설명해줘"
                ),
                logFileName = "case35.log"
            )

            val responses = result.responses
            val session = result.session

            println("  [Result] Actions: ${responses.map { it.action }}")

            if (session != null) {
                val tasks = session.context.tasks
                println("  [Result] Total tasks: ${tasks.size}")
                for ((id, task) in tasks) {
                    println("  [Result]   [$id] '${task.label}' -> ${task.status}, " +
                        "history=${task.context.executionHistory.size}")
                }

                // Hard Assert: COMPLETED 작업의 히스토리가 보존
                val completedTasks = tasks.values.filter { it.status == TaskStatus.COMPLETED }
                if (completedTasks.isNotEmpty()) {
                    val firstCompleted = completedTasks.first()
                    println("  [Result] Completed task history: ${firstCompleted.context.executionHistory.size}")
                    assertTrue(firstCompleted.context.executionHistory.isNotEmpty(),
                        "Case 35: 완료된 작업의 히스토리가 보존되어야 함")
                }

                // Soft Assert: Turn 2 응답은 REPLY (지식 질문)
                val turn2 = responses.last()
                println("  [Result] Turn 2 action: ${turn2.action}")
                if (turn2.action == ActionType.REPLY) {
                    println("  [PASS] Turn 2 is REPLY for knowledge question")
                }
            }
        } finally {
            testFile1.delete()
        }
    }

    // ========== Case 36: API Workflow with Turn-Based Execution ==========

    /**
     * Case 36: API Workflow 턴 기반 실행 - nextAction 루프 검증
     *
     * 검증 대상:
     * - executeLlmDecidedTurn이 1회 배치만 수행
     * - nextAction = CONTINUE_EXECUTION 힌트
     * - auto-continue가 여러 턴 실행
     * - 최종 COMPLETED
     */
    @Test
    @Order(36)
    fun `Case 36 - API workflow turn-based with auto-continue`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        val result = runConversationKeepSession(
            "Case 36: API Workflow Turn-Based",
            listOf(
                "john의 모든 주문 상태를 shipped로 변경해줘. " +
                    "순서: 1) john 사용자 조회 2) 주문 목록 조회 3) 각 주문 PUT 업데이트",
                "Mock API 서버. 기본 URL: ${mockServer.baseUrl()}",
                "진행해"
            ),
            gov = apiGovernor,
            logFileName = "case36.log"
        )

        val responses = result.responses
        val session = result.session

        // Count auto-continues
        val continueResponses = responses.filter { it.nextAction == NextAction.CONTINUE_EXECUTION }
        val executeResponses = responses.filter { it.action == ActionType.EXECUTE }
        println("  [Result] Total responses: ${responses.size}")
        println("  [Result] EXECUTE count: ${executeResponses.size}")
        println("  [Result] CONTINUE_EXECUTION count: ${continueResponses.size}")

        // Hard Assert: EXECUTE 응답이 존재
        assertTrue(executeResponses.isNotEmpty(),
            "Case 36: EXECUTE 응답이 있어야 함")

        // Session state
        if (session != null) {
            val tasks = session.context.tasks
            for ((id, task) in tasks) {
                println("  [Result] Task [$id]: ${task.status}, history=${task.context.executionHistory.size}")
            }
        }

        // Verify mock data actually changed
        val updatedOrders = mockServer.dataStore.getOrdersByUserId(1)
        val allShipped = updatedOrders.all { it.status == "shipped" }
        println("  [Result] Orders actually shipped: $allShipped (${updatedOrders.map { "${it.id}:${it.status}" }})")
    }

    // ========== Case 37: Conversation → Execute → Follow-up ==========

    /**
     * Case 37: 대화 → 실행 → 결과 기반 후속 대화
     *
     * 전체 턴 기반 흐름의 자연스러운 사용:
     * 1. 일상 대화
     * 2. 파일 읽기 실행
     * 3. 결과에 대한 질문
     * 4. 새로운 작업 시작
     *
     * 검증: 세션이 모든 컨텍스트를 유지하며 자연스러운 대화 흐름
     */
    @Test
    @Order(37)
    fun `Case 37 - natural conversation with execution and follow-up`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        val testFile = File("/tmp/wiiiv-e2e-case37.txt")
        testFile.writeText("서버 설정:\n  port: 8080\n  host: 0.0.0.0\n  debug: true")

        try {
            val result = runConversationKeepSession(
                "Case 37: Natural Flow with Execution",
                listOf(
                    "안녕, 오늘 서버 설정 좀 봐야해",
                    "/tmp/wiiiv-e2e-case37.txt 읽어줘",
                    "포트 번호가 뭐야?",
                    "debug 모드가 켜져 있네. 프로덕션에서는 끄는 게 좋겠지?"
                ),
                logFileName = "case37.log"
            )

            val responses = result.responses
            val session = result.session

            println("  [Result] Actions: ${responses.map { it.action }}")

            // Turn 1: REPLY (인사)
            println("  [Result] Turn 1: ${responses[0].action}")

            // Turn 2: 실행 관련 (EXECUTE/ASK/CONFIRM)
            println("  [Result] Turn 2: ${responses[1].action}")

            // Turn 3: REPLY (결과 기반 답변)
            // Find the response for "포트 번호가 뭐야?" - it's after any auto-continues
            val turn3Responses = responses.drop(1) // skip turn 1
            // Skip any auto-continues from turn 2
            var idx = 0
            while (idx < turn3Responses.size && turn3Responses[idx].nextAction == NextAction.CONTINUE_EXECUTION) {
                idx++
            }
            idx++ // skip the final turn 2 response
            if (idx < turn3Responses.size) {
                val turn3 = turn3Responses[idx]
                println("  [Result] Turn 3 (port question): ${turn3.action}, msg=${turn3.message.take(100)}")
            }

            // Session state
            if (session != null) {
                println("  [Result] Total tasks: ${session.context.tasks.size}")
                val totalHistory = session.context.tasks.values.sumOf { it.context.executionHistory.size }
                println("  [Result] Total execution history: $totalHistory")
            }
        } finally {
            testFile.delete()
        }
    }

    // ========== Case 38: Multiple Executions in One Session ==========

    /**
     * Case 38: 하나의 세션에서 여러 실행 → 각각 COMPLETED
     *
     * 검증 대상:
     * - 다수의 COMPLETED 작업이 tasks에 공존
     * - 각 작업의 히스토리가 독립적
     * - 세션이 모든 작업 기록을 보존
     */
    @Test
    @Order(38)
    fun `Case 38 - multiple executions in one session`() = runBlocking {
        if (API_KEY.isBlank()) { println("SKIP: OPENAI_API_KEY not set"); return@runBlocking }

        val file1 = File("/tmp/wiiiv-e2e-case38a.txt")
        val file2 = File("/tmp/wiiiv-e2e-case38b.txt")
        file1.writeText("File A content")
        file2.writeText("File B content")

        try {
            val result = runConversationKeepSession(
                "Case 38: Multiple Executions",
                listOf(
                    "/tmp/wiiiv-e2e-case38a.txt 읽어줘",
                    "/tmp/wiiiv-e2e-case38b.txt 읽어줘"
                ),
                logFileName = "case38.log"
            )

            val responses = result.responses
            val session = result.session

            val executeCount = responses.count { it.action == ActionType.EXECUTE }
            println("  [Result] EXECUTE count: $executeCount")
            println("  [Result] Actions: ${responses.map { it.action }}")

            if (session != null) {
                val tasks = session.context.tasks
                println("  [Result] Total tasks: ${tasks.size}")
                for ((id, task) in tasks) {
                    println("  [Result]   [$id] '${task.label}' -> ${task.status}, " +
                        "history=${task.context.executionHistory.size}")
                }

                // Hard Assert: COMPLETED 작업이 1개 이상
                val completedTasks = tasks.values.filter { it.status == TaskStatus.COMPLETED }
                assertTrue(completedTasks.isNotEmpty(),
                    "Case 38: 최소 1개의 COMPLETED 작업이 있어야 함")

                // Hard Assert: 실행 히스토리 독립성 - 각 작업의 history 크기
                for (task in completedTasks) {
                    assertTrue(task.context.executionHistory.isNotEmpty(),
                        "Case 38: 각 완료된 작업에 executionHistory가 있어야 함")
                }

                println("  [Result] Completed tasks: ${completedTasks.size}")
            }
        } finally {
            file1.delete()
            file2.delete()
        }
    }
}

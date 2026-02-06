package io.wiiiv.governor

import io.wiiiv.blueprint.BlueprintRunner
import io.wiiiv.blueprint.BlueprintStepType
import io.wiiiv.dacs.*
import io.wiiiv.execution.LlmAction
import io.wiiiv.execution.impl.LlmProvider
import io.wiiiv.execution.impl.LlmRequest
import io.wiiiv.execution.impl.LlmResponse
import io.wiiiv.execution.impl.LlmUsage
import io.wiiiv.execution.impl.NoopExecutor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import java.util.UUID

/**
 * ConversationalGovernor 테스트
 *
 * 대화형 Governor의 핵심 기능을 테스트
 */
class ConversationalGovernorTest {

    private lateinit var simpleDACS: DACS
    private lateinit var configDACS: ConfigurableDACS
    private lateinit var governor: ConversationalGovernor

    @BeforeEach
    fun setup() {
        simpleDACS = SimpleDACS.DEFAULT
        configDACS = ConfigurableDACS()
        governor = ConversationalGovernor.create(
            dacs = simpleDACS,
            llmProvider = null  // No LLM for basic tests
        )
    }

    @Nested
    @DisplayName("Session Management")
    inner class SessionManagementTests {

        @Test
        fun `startSession creates new session`() {
            val session = governor.startSession()

            assertNotNull(session)
            assertNotNull(session.sessionId)
            assertEquals(DraftSpec.empty().taskType, session.draftSpec.taskType)
        }

        @Test
        fun `startSession with custom ID`() {
            val customId = "my-session-123"
            val session = governor.startSession(customId)

            assertEquals(customId, session.sessionId)
        }

        @Test
        fun `getSession returns existing session`() {
            val session = governor.startSession()
            val retrieved = governor.getSession(session.sessionId)

            assertEquals(session.sessionId, retrieved?.sessionId)
        }

        @Test
        fun `getSession returns null for unknown session`() {
            val retrieved = governor.getSession("non-existent")

            assertNull(retrieved)
        }

        @Test
        fun `endSession removes session`() {
            val session = governor.startSession()
            governor.endSession(session.sessionId)

            assertNull(governor.getSession(session.sessionId))
        }
    }

    @Nested
    @DisplayName("Basic Chat (No LLM)")
    inner class BasicChatTests {

        @Test
        fun `chat with greeting returns reply`() = runBlocking {
            val session = governor.startSession()

            val response = governor.chat(session.sessionId, "안녕")

            assertEquals(ActionType.REPLY, response.action)
            assertTrue(response.message.contains("wiiiv Governor"))
        }

        @Test
        fun `chat with cancel keyword resets session`() = runBlocking {
            val session = governor.startSession()
            // Set some draft spec state first
            session.draftSpec = DraftSpec(intent = "test task", taskType = TaskType.FILE_READ)

            val response = governor.chat(session.sessionId, "취소")

            assertEquals(ActionType.CANCEL, response.action)
            assertEquals(DraftSpec.empty().taskType, session.draftSpec.taskType)
        }

        @Test
        fun `chat returns cancel for unknown session`() = runBlocking {
            val response = governor.chat("non-existent", "hello")

            assertEquals(ActionType.CANCEL, response.action)
            assertTrue(response.message.contains("세션을 찾을 수 없습니다"))
        }

        @Test
        fun `chat without LLM returns limited functionality message`() = runBlocking {
            val session = governor.startSession()

            val response = governor.chat(session.sessionId, "복잡한 작업 요청")

            assertEquals(ActionType.REPLY, response.action)
            assertTrue(response.message.contains("LLM이 연결되어 있지 않아"))
        }
    }

    @Nested
    @DisplayName("DraftSpec Management")
    inner class DraftSpecTests {

        @Test
        fun `DraftSpec getMissingSlots for FILE_READ`() {
            val spec = DraftSpec(taskType = TaskType.FILE_READ)

            val missing = spec.getMissingSlots()

            assertTrue("targetPath" in missing)
        }

        @Test
        fun `DraftSpec getMissingSlots for FILE_WRITE`() {
            val spec = DraftSpec(taskType = TaskType.FILE_WRITE)

            val missing = spec.getMissingSlots()

            assertTrue("targetPath" in missing)
            assertTrue("content" in missing)
        }

        @Test
        fun `DraftSpec isComplete for CONVERSATION`() {
            val spec = DraftSpec(taskType = TaskType.CONVERSATION)

            assertTrue(spec.isComplete())
        }

        @Test
        fun `DraftSpec isComplete for FILE_READ with path`() {
            val spec = DraftSpec(
                taskType = TaskType.FILE_READ,
                targetPath = "/tmp/test.txt"
            )

            assertTrue(spec.isComplete())
        }

        @Test
        fun `DraftSpec isRisky for FILE_DELETE`() {
            val spec = DraftSpec(taskType = TaskType.FILE_DELETE)

            assertTrue(spec.isRisky())
        }

        @Test
        fun `DraftSpec isRisky for COMMAND`() {
            val spec = DraftSpec(taskType = TaskType.COMMAND)

            assertTrue(spec.isRisky())
        }

        @Test
        fun `DraftSpec toSpec converts correctly`() {
            val spec = DraftSpec(
                intent = "Read test file",
                taskType = TaskType.FILE_READ,
                targetPath = "/tmp/test.txt"
            )

            val converted = spec.toSpec()

            assertEquals("Read test file", converted.intent)
            assertTrue(RequestType.FILE_READ in converted.allowedOperations)
        }

        @Test
        fun `DraftSpec summarize includes all fields`() {
            val spec = DraftSpec(
                intent = "Create project",
                taskType = TaskType.PROJECT_CREATE,
                domain = "E-commerce",
                techStack = listOf("Kotlin", "Spring"),
                scale = "1000 users"
            )

            val summary = spec.summarize()

            assertTrue(summary.contains("프로젝트 생성"))
            assertTrue(summary.contains("Create project"))
            assertTrue(summary.contains("E-commerce"))
            assertTrue(summary.contains("Kotlin"))
        }
    }

    @Nested
    @DisplayName("ConversationSession")
    inner class ConversationSessionTests {

        @Test
        fun `session tracks message history`() {
            val session = ConversationSession()

            session.addUserMessage("Hello")
            session.addGovernorMessage("Hi there!")

            assertEquals(2, session.history.size)
            assertEquals(MessageRole.USER, session.history[0].role)
            assertEquals(MessageRole.GOVERNOR, session.history[1].role)
        }

        @Test
        fun `session getRecentHistory limits count`() {
            val session = ConversationSession()

            repeat(20) {
                session.addUserMessage("Message $it")
            }

            val recent = session.getRecentHistory(5)

            assertEquals(5, recent.size)
            assertEquals("Message 15", recent[0].content)
        }

        @Test
        fun `session reset clears spec and confirmed`() {
            val session = ConversationSession()
            session.draftSpec = DraftSpec(intent = "test", taskType = TaskType.COMMAND)
            session.confirmed = true

            session.reset()

            assertNull(session.draftSpec.intent)
            assertNull(session.draftSpec.taskType)
            assertFalse(session.confirmed)
        }
    }

    @Nested
    @DisplayName("Governor Interface Compatibility")
    inner class GovernorInterfaceTests {

        @Test
        fun `createBlueprint works with ConversationalGovernor`() = runBlocking {
            val governor = ConversationalGovernor.create(
                dacs = simpleDACS
            )

            val spec = Spec(
                id = "spec-1",
                name = "Test Spec",
                description = "A test spec for file reading",
                intent = "Read a file for testing purposes",
                allowedOperations = listOf(RequestType.FILE_READ),
                allowedPaths = listOf("/tmp/**")
            )

            val request = GovernorRequest(
                type = RequestType.FILE_READ,
                targetPath = "/tmp/test.txt",
                intent = "Read a file for testing purposes"
            )

            val result = governor.createBlueprint(request, spec)

            assertTrue(result is GovernorResult.BlueprintCreated)
            val blueprint = (result as GovernorResult.BlueprintCreated).blueprint
            assertEquals(1, blueprint.steps.size)
        }

        @Test
        fun `createBlueprint denied by DACS`() = runBlocking {
            configDACS.setDefaultDecision(Consensus.NO, "Blocked for testing")

            val governor = ConversationalGovernor.create(
                dacs = configDACS
            )

            val spec = Spec(
                id = "spec-1",
                name = "Test Spec",
                description = "Test",
                allowedOperations = listOf(RequestType.FILE_DELETE),
                allowedPaths = listOf("/**")
            )

            val request = GovernorRequest(
                type = RequestType.FILE_DELETE,
                targetPath = "/",
                intent = "Delete everything"
            )

            val result = governor.createBlueprint(request, spec)

            assertTrue(result is GovernorResult.Denied)
        }
    }

    @Nested
    @DisplayName("GovernorPrompt")
    inner class GovernorPromptTests {

        @Test
        fun `DEFAULT prompt contains action instructions`() {
            val prompt = GovernorPrompt.DEFAULT

            assertTrue(prompt.contains("REPLY"))
            assertTrue(prompt.contains("ASK"))
            assertTrue(prompt.contains("CONFIRM"))
            assertTrue(prompt.contains("EXECUTE"))
            assertTrue(prompt.contains("CANCEL"))
        }

        @Test
        fun `DEFAULT prompt contains JSON format`() {
            val prompt = GovernorPrompt.DEFAULT

            assertTrue(prompt.contains("action"))
            assertTrue(prompt.contains("message"))
            assertTrue(prompt.contains("specUpdates"))
        }

        @Test
        fun `withContext includes current spec state`() {
            val draftSpec = DraftSpec(
                intent = "Create a REST API",
                taskType = TaskType.PROJECT_CREATE,
                domain = "E-commerce"
            )

            val prompt = GovernorPrompt.withContext(draftSpec, emptyList())

            assertTrue(prompt.contains("Create a REST API"))
            assertTrue(prompt.contains("E-commerce"))
        }

        @Test
        fun `withContext includes missing slots`() {
            val draftSpec = DraftSpec(
                taskType = TaskType.FILE_WRITE
                // Missing: targetPath, content
            )

            val prompt = GovernorPrompt.withContext(draftSpec, emptyList())

            assertTrue(prompt.contains("누락된 정보") || prompt.contains("targetPath"))
        }

        @Test
        fun `withContext includes recent history`() {
            val history = listOf(
                ConversationMessage(MessageRole.USER, "파일 읽어줘"),
                ConversationMessage(MessageRole.GOVERNOR, "어떤 파일을 읽을까요?")
            )

            val prompt = GovernorPrompt.withContext(DraftSpec.empty(), history)

            assertTrue(prompt.contains("파일 읽어줘"))
            assertTrue(prompt.contains("어떤 파일을 읽을까요"))
        }
    }

    @Nested
    @DisplayName("With Mock LLM")
    inner class MockLlmTests {

        @Test
        fun `chat with mock LLM returns parsed action`() = runBlocking {
            val mockLlm = TestLlmProvider { request ->
                """{"action": "REPLY", "message": "Hello from mock LLM!"}"""
            }

            val governor = ConversationalGovernor.create(
                dacs = simpleDACS,
                llmProvider = mockLlm,
                model = "mock-model"
            )

            val session = governor.startSession()
            val response = governor.chat(session.sessionId, "안녕하세요")

            assertEquals(ActionType.REPLY, response.action)
            assertEquals("Hello from mock LLM!", response.message)
        }

        @Test
        fun `chat updates spec from LLM response`() = runBlocking {
            val mockLlm = TestLlmProvider { request ->
                """
                {
                    "action": "ASK",
                    "message": "어떤 경로의 파일을 읽을까요?",
                    "specUpdates": {
                        "intent": "파일 읽기",
                        "taskType": "FILE_READ"
                    },
                    "askingFor": "targetPath"
                }
                """.trimIndent()
            }

            val governor = ConversationalGovernor.create(
                dacs = simpleDACS,
                llmProvider = mockLlm
            )

            val session = governor.startSession()
            governor.chat(session.sessionId, "파일 좀 읽어줘")

            assertEquals("파일 읽기", session.draftSpec.intent)
            assertEquals(TaskType.FILE_READ, session.draftSpec.taskType)
        }

        @Test
        fun `execute creates blueprint and runs`() = runBlocking {
            // First call: ASK with spec updates
            // Second call: EXECUTE
            var callCount = 0
            val mockLlm = TestLlmProvider { request ->
                callCount++
                if (callCount == 1) {
                    """
                    {
                        "action": "ASK",
                        "message": "경로를 입력해주세요",
                        "specUpdates": {
                            "intent": "파일 읽기",
                            "taskType": "FILE_READ"
                        }
                    }
                    """.trimIndent()
                } else {
                    """
                    {
                        "action": "EXECUTE",
                        "message": "실행합니다",
                        "specUpdates": {
                            "targetPath": "/tmp/test.txt"
                        }
                    }
                    """.trimIndent()
                }
            }

            val executor = NoopExecutor(handleAll = true)
            val runner = BlueprintRunner.create(executor)

            val governor = ConversationalGovernor.create(
                dacs = simpleDACS,
                llmProvider = mockLlm,
                blueprintRunner = runner
            )

            val session = governor.startSession()

            // First message: sets up spec
            governor.chat(session.sessionId, "파일 읽어줘")

            // Second message: triggers execution
            val response = governor.chat(session.sessionId, "/tmp/test.txt")

            assertEquals(ActionType.EXECUTE, response.action)
            assertNotNull(response.blueprint)
            assertNotNull(response.executionResult)
        }
    }

    @Nested
    @DisplayName("DACS Integration")
    inner class DACSIntegrationTests {

        @Test
        fun `risky operation calls DACS`() = runBlocking {
            var dacsCallCount = 0
            val trackingDACS = object : DACS {
                override suspend fun evaluate(request: DACSRequest): DACSResult {
                    dacsCallCount++
                    return DACSResult(
                        requestId = request.requestId,
                        consensus = Consensus.YES,
                        reason = "Approved",
                        personaOpinions = listOf(
                            PersonaOpinion(PersonaType.ARCHITECT, Vote.APPROVE, "OK", emptyList()),
                            PersonaOpinion(PersonaType.REVIEWER, Vote.APPROVE, "OK", emptyList()),
                            PersonaOpinion(PersonaType.ADVERSARY, Vote.APPROVE, "OK", emptyList())
                        )
                    )
                }
            }

            val mockLlm = TestLlmProvider { request ->
                """
                {
                    "action": "EXECUTE",
                    "message": "실행합니다",
                    "specUpdates": {
                        "intent": "Delete test file",
                        "taskType": "FILE_DELETE",
                        "targetPath": "/tmp/test.txt"
                    }
                }
                """.trimIndent()
            }

            val executor = NoopExecutor(handleAll = true)
            val runner = BlueprintRunner.create(executor)

            val governor = ConversationalGovernor.create(
                dacs = trackingDACS,
                llmProvider = mockLlm,
                blueprintRunner = runner
            )

            val session = governor.startSession()
            governor.chat(session.sessionId, "delete test file")

            assertEquals(1, dacsCallCount)
        }

        @Test
        fun `DACS NO blocks execution`() = runBlocking {
            configDACS.setDefaultDecision(Consensus.NO, "Security risk")

            val mockLlm = TestLlmProvider { request ->
                """
                {
                    "action": "EXECUTE",
                    "message": "실행합니다",
                    "specUpdates": {
                        "intent": "Delete system files",
                        "taskType": "FILE_DELETE",
                        "targetPath": "/etc/passwd"
                    }
                }
                """.trimIndent()
            }

            val governor = ConversationalGovernor.create(
                dacs = configDACS,
                llmProvider = mockLlm
            )

            val session = governor.startSession()
            val response = governor.chat(session.sessionId, "delete system files")

            assertEquals(ActionType.CANCEL, response.action)
            assertTrue(response.message.contains("보안상") || response.message.contains("실행할 수 없습니다"))
        }

        @Test
        fun `DACS REVISION requests more info`() = runBlocking {
            configDACS.setDefaultDecision(Consensus.REVISION, "Need more context")

            val mockLlm = TestLlmProvider { request ->
                """
                {
                    "action": "EXECUTE",
                    "message": "실행합니다",
                    "specUpdates": {
                        "intent": "Run command",
                        "taskType": "COMMAND",
                        "content": "ls -la"
                    }
                }
                """.trimIndent()
            }

            val governor = ConversationalGovernor.create(
                dacs = configDACS,
                llmProvider = mockLlm
            )

            val session = governor.startSession()
            val response = governor.chat(session.sessionId, "run ls")

            assertEquals(ActionType.ASK, response.action)
            assertTrue(response.message.contains("추가 확인"))
        }
    }

    @Nested
    @DisplayName("Conversation Scenario Tests (10 Cases)")
    inner class ConversationScenarioTests {

        private lateinit var executor: NoopExecutor
        private lateinit var runner: BlueprintRunner

        @BeforeEach
        fun setupScenario() {
            executor = NoopExecutor(handleAll = true)
            runner = BlueprintRunner.create(executor)
        }

        /**
         * Case 1 - 일상 인사 (2턴)
         * "안녕! 오늘 기분 어때?" → REPLY
         * "요즘 뭐 재미있는 거 없어?" → REPLY
         * 검증: taskType=CONVERSATION, blueprint=null, history 누적
         */
        @Test
        fun `Case 1 - casual greeting conversation (2 turns)`() = runBlocking {
            var callCount = 0
            val mockLlm = TestLlmProvider { _ ->
                callCount++
                when (callCount) {
                    1 -> """
                        {
                            "action": "REPLY",
                            "message": "안녕! 나도 잘 지내고 있어. 넌 어때?",
                            "specUpdates": { "taskType": "CONVERSATION" }
                        }
                    """.trimIndent()
                    else -> """
                        {
                            "action": "REPLY",
                            "message": "요즘 Kotlin 2.0이 나와서 재미있더라!",
                            "specUpdates": { "taskType": "CONVERSATION" }
                        }
                    """.trimIndent()
                }
            }

            val gov = ConversationalGovernor.create(
                dacs = simpleDACS,
                llmProvider = mockLlm,
                blueprintRunner = runner
            )
            val session = gov.startSession()

            // Turn 1
            val r1 = gov.chat(session.sessionId, "안녕! 오늘 기분 어때?")
            assertEquals(ActionType.REPLY, r1.action)
            assertEquals(TaskType.CONVERSATION, session.draftSpec.taskType)
            assertNull(r1.blueprint)

            // Turn 2
            val r2 = gov.chat(session.sessionId, "요즘 뭐 재미있는 거 없어?")
            assertEquals(ActionType.REPLY, r2.action)
            assertNull(r2.blueprint)

            // History accumulated (2 user + 2 governor = 4)
            assertEquals(4, session.history.size)
            assertEquals(MessageRole.USER, session.history[0].role)
            assertEquals(MessageRole.GOVERNOR, session.history[1].role)
            assertEquals(MessageRole.USER, session.history[2].role)
            assertEquals(MessageRole.GOVERNOR, session.history[3].role)
        }

        /**
         * Case 2 - 지식 Q&A (1턴)
         * "Kotlin이 뭐야?" → REPLY (정보 제공)
         * 검증: taskType=INFORMATION, 실행 안 함
         */
        @Test
        fun `Case 2 - knowledge QnA single turn`() = runBlocking {
            val mockLlm = TestLlmProvider { _ ->
                """
                {
                    "action": "REPLY",
                    "message": "Kotlin은 JetBrains에서 만든 현대적인 프로그래밍 언어입니다. JVM 위에서 동작하며 Java와 100% 호환됩니다.",
                    "specUpdates": { "taskType": "INFORMATION" }
                }
                """.trimIndent()
            }

            val gov = ConversationalGovernor.create(
                dacs = simpleDACS,
                llmProvider = mockLlm,
                blueprintRunner = runner
            )
            val session = gov.startSession()

            val response = gov.chat(session.sessionId, "Kotlin이 뭐야?")

            assertEquals(ActionType.REPLY, response.action)
            assertEquals(TaskType.INFORMATION, session.draftSpec.taskType)
            assertNull(response.blueprint)
            assertTrue(response.message.contains("Kotlin"))
        }

        /**
         * Case 3 - 파일 즉시 읽기
         * "/tmp/test.txt 읽어줘" → EXECUTE (경로 명시 = 즉시 실행)
         * 검증: Blueprint(FILE_READ), DACS 호출 안 함 (/tmp는 안전), 세션 리셋
         */
        @Test
        fun `Case 3 - immediate file read with explicit path`() = runBlocking {
            var dacsCallCount = 0
            val trackingDACS = object : DACS {
                override suspend fun evaluate(request: DACSRequest): DACSResult {
                    dacsCallCount++
                    return DACSResult(
                        requestId = request.requestId,
                        consensus = Consensus.YES,
                        reason = "Approved",
                        personaOpinions = listOf(
                            PersonaOpinion(PersonaType.ARCHITECT, Vote.APPROVE, "OK", emptyList()),
                            PersonaOpinion(PersonaType.REVIEWER, Vote.APPROVE, "OK", emptyList()),
                            PersonaOpinion(PersonaType.ADVERSARY, Vote.APPROVE, "OK", emptyList())
                        )
                    )
                }
            }

            val mockLlm = TestLlmProvider { _ ->
                """
                {
                    "action": "EXECUTE",
                    "message": "/tmp/test.txt 파일을 읽겠습니다.",
                    "specUpdates": {
                        "intent": "/tmp/test.txt 파일 읽기",
                        "taskType": "FILE_READ",
                        "targetPath": "/tmp/test.txt"
                    }
                }
                """.trimIndent()
            }

            val gov = ConversationalGovernor.create(
                dacs = trackingDACS,
                llmProvider = mockLlm,
                blueprintRunner = runner
            )
            val session = gov.startSession()

            val response = gov.chat(session.sessionId, "/tmp/test.txt 읽어줘")

            assertEquals(ActionType.EXECUTE, response.action)
            assertNotNull(response.blueprint)
            assertEquals(1, response.blueprint!!.steps.size)
            assertEquals(BlueprintStepType.FILE_READ, response.blueprint!!.steps[0].type)
            assertEquals("/tmp/test.txt", response.blueprint!!.steps[0].params["path"])

            // /tmp is safe → FILE_READ is not risky → DACS not called
            assertEquals(0, dacsCallCount)

            // Session reset after successful execution
            assertNull(session.draftSpec.taskType)
        }

        /**
         * Case 4 - 명령어 실행 + DACS 승인
         * "ls 실행해줘" → EXECUTE
         * 검증: COMMAND는 isRisky=true → DACS 호출됨, YES면 실행
         */
        @Test
        fun `Case 4 - command execution with DACS approval`() = runBlocking {
            var dacsCallCount = 0
            val trackingDACS = object : DACS {
                override suspend fun evaluate(request: DACSRequest): DACSResult {
                    dacsCallCount++
                    return DACSResult(
                        requestId = request.requestId,
                        consensus = Consensus.YES,
                        reason = "Approved",
                        personaOpinions = listOf(
                            PersonaOpinion(PersonaType.ARCHITECT, Vote.APPROVE, "OK", emptyList()),
                            PersonaOpinion(PersonaType.REVIEWER, Vote.APPROVE, "OK", emptyList()),
                            PersonaOpinion(PersonaType.ADVERSARY, Vote.APPROVE, "OK", emptyList())
                        )
                    )
                }
            }

            val mockLlm = TestLlmProvider { _ ->
                """
                {
                    "action": "EXECUTE",
                    "message": "ls 명령어를 실행합니다.",
                    "specUpdates": {
                        "intent": "ls 명령어 실행",
                        "taskType": "COMMAND",
                        "content": "ls"
                    }
                }
                """.trimIndent()
            }

            val gov = ConversationalGovernor.create(
                dacs = trackingDACS,
                llmProvider = mockLlm,
                blueprintRunner = runner
            )
            val session = gov.startSession()

            val response = gov.chat(session.sessionId, "ls 실행해줘")

            assertEquals(ActionType.EXECUTE, response.action)
            assertNotNull(response.blueprint)
            // COMMAND is risky → DACS was called
            assertEquals(1, dacsCallCount)
            assertEquals(BlueprintStepType.COMMAND, response.blueprint!!.steps[0].type)
        }

        /**
         * Case 5 - 파일 쓰기 인터뷰 (4턴)
         * "파일 만들어줘" → ASK(targetPath)
         * "/tmp/hello.txt" → ASK(content)
         * "Hello, World!" → CONFIRM
         * "응" → EXECUTE
         * 검증: 점진적 슬롯 채움, CONFIRM 요약, FILE_WRITE Blueprint
         */
        @Test
        fun `Case 5 - file write interview (4 turns)`() = runBlocking {
            var callCount = 0
            val mockLlm = TestLlmProvider { _ ->
                callCount++
                when (callCount) {
                    1 -> """
                        {
                            "action": "ASK",
                            "message": "어떤 경로에 파일을 만들까요?",
                            "specUpdates": {
                                "intent": "파일 생성",
                                "taskType": "FILE_WRITE"
                            },
                            "askingFor": "targetPath"
                        }
                    """.trimIndent()
                    2 -> """
                        {
                            "action": "ASK",
                            "message": "파일에 어떤 내용을 쓸까요?",
                            "specUpdates": {
                                "targetPath": "/tmp/hello.txt"
                            },
                            "askingFor": "content"
                        }
                    """.trimIndent()
                    3 -> """
                        {
                            "action": "CONFIRM",
                            "message": "다음 내용으로 파일을 생성할까요?\n경로: /tmp/hello.txt\n내용: Hello, World!",
                            "specUpdates": {
                                "content": "Hello, World!"
                            }
                        }
                    """.trimIndent()
                    else -> """
                        {
                            "action": "EXECUTE",
                            "message": "파일을 생성합니다."
                        }
                    """.trimIndent()
                }
            }

            val gov = ConversationalGovernor.create(
                dacs = simpleDACS,
                llmProvider = mockLlm,
                blueprintRunner = runner
            )
            val session = gov.startSession()

            // Turn 1: ASK for targetPath
            val r1 = gov.chat(session.sessionId, "파일 만들어줘")
            assertEquals(ActionType.ASK, r1.action)
            assertEquals("targetPath", r1.askingFor)
            assertEquals(TaskType.FILE_WRITE, session.draftSpec.taskType)

            // Turn 2: ASK for content
            val r2 = gov.chat(session.sessionId, "/tmp/hello.txt")
            assertEquals(ActionType.ASK, r2.action)
            assertEquals("content", r2.askingFor)
            assertEquals("/tmp/hello.txt", session.draftSpec.targetPath)

            // Turn 3: CONFIRM with summary
            val r3 = gov.chat(session.sessionId, "Hello, World!")
            assertEquals(ActionType.CONFIRM, r3.action)
            assertNotNull(r3.confirmationSummary)
            assertEquals("Hello, World!", session.draftSpec.content)

            // Turn 4: EXECUTE
            val r4 = gov.chat(session.sessionId, "응")
            assertEquals(ActionType.EXECUTE, r4.action)
            assertNotNull(r4.blueprint)
            assertEquals(BlueprintStepType.FILE_WRITE, r4.blueprint!!.steps[0].type)
            assertEquals("/tmp/hello.txt", r4.blueprint!!.steps[0].params["path"])
        }

        /**
         * Case 6 - 파일 삭제 → DACS 거부 (2턴)
         * "파일 삭제해줘" → ASK(targetPath)
         * "/etc/passwd" → DACS NO → CANCEL
         * 검증: SimpleDACS.DEFAULT가 /etc/passwd를 REJECT, 세션 리셋
         */
        @Test
        fun `Case 6 - file delete DACS rejection`() = runBlocking {
            var callCount = 0
            val mockLlm = TestLlmProvider { _ ->
                callCount++
                when (callCount) {
                    1 -> """
                        {
                            "action": "ASK",
                            "message": "어떤 파일을 삭제할까요?",
                            "specUpdates": {
                                "intent": "파일 삭제",
                                "taskType": "FILE_DELETE"
                            },
                            "askingFor": "targetPath"
                        }
                    """.trimIndent()
                    else -> """
                        {
                            "action": "EXECUTE",
                            "message": "/etc/passwd를 삭제합니다.",
                            "specUpdates": {
                                "targetPath": "/etc/passwd"
                            }
                        }
                    """.trimIndent()
                }
            }

            // Use SimpleDACS.DEFAULT which rejects /etc/passwd
            val gov = ConversationalGovernor.create(
                dacs = SimpleDACS.DEFAULT,
                llmProvider = mockLlm,
                blueprintRunner = runner
            )
            val session = gov.startSession()

            // Turn 1: ASK for path
            val r1 = gov.chat(session.sessionId, "파일 삭제해줘")
            assertEquals(ActionType.ASK, r1.action)

            // Turn 2: /etc/passwd → DACS rejects → CANCEL
            val r2 = gov.chat(session.sessionId, "/etc/passwd")
            assertEquals(ActionType.CANCEL, r2.action)
            assertNotNull(r2.dacsResult)
            assertEquals(Consensus.NO, r2.dacsResult!!.consensus)

            // Session reset after denial
            assertNull(session.draftSpec.taskType)
        }

        /**
         * Case 7 - 프로젝트 생성 인터뷰 (5턴)
         * "프로젝트 만들어줘" → ASK(domain)
         * "대학교 학점 관리" → ASK(techStack)
         * "Kotlin, Spring Boot, PostgreSQL" → ASK(scale)
         * "학생 5000명" → CONFIRM
         * "응" → EXECUTE
         * 검증: PROJECT_CREATE(mkdir+write), DACS 호출(risky), 전체 인터뷰 완주
         */
        @Test
        fun `Case 7 - project creation interview (5 turns)`() = runBlocking {
            var callCount = 0
            var dacsCallCount = 0
            val trackingDACS = object : DACS {
                override suspend fun evaluate(request: DACSRequest): DACSResult {
                    dacsCallCount++
                    return DACSResult(
                        requestId = request.requestId,
                        consensus = Consensus.YES,
                        reason = "Project creation approved",
                        personaOpinions = listOf(
                            PersonaOpinion(PersonaType.ARCHITECT, Vote.APPROVE, "OK", emptyList()),
                            PersonaOpinion(PersonaType.REVIEWER, Vote.APPROVE, "OK", emptyList()),
                            PersonaOpinion(PersonaType.ADVERSARY, Vote.APPROVE, "OK", emptyList())
                        )
                    )
                }
            }

            val mockLlm = TestLlmProvider { _ ->
                callCount++
                when (callCount) {
                    1 -> """
                        {
                            "action": "ASK",
                            "message": "어떤 도메인의 프로젝트인가요?",
                            "specUpdates": {
                                "intent": "프로젝트 생성",
                                "taskType": "PROJECT_CREATE"
                            },
                            "askingFor": "domain"
                        }
                    """.trimIndent()
                    2 -> """
                        {
                            "action": "ASK",
                            "message": "어떤 기술 스택을 사용할까요?",
                            "specUpdates": {
                                "domain": "대학교 학점 관리"
                            },
                            "askingFor": "techStack"
                        }
                    """.trimIndent()
                    3 -> """
                        {
                            "action": "ASK",
                            "message": "예상 규모는 어느 정도인가요?",
                            "specUpdates": {
                                "techStack": ["Kotlin", "Spring Boot", "PostgreSQL"]
                            },
                            "askingFor": "scale"
                        }
                    """.trimIndent()
                    4 -> """
                        {
                            "action": "CONFIRM",
                            "message": "다음 내용으로 프로젝트를 생성할까요?\n도메인: 대학교 학점 관리\n기술: Kotlin, Spring Boot, PostgreSQL\n규모: 학생 5000명",
                            "specUpdates": {
                                "scale": "학생 5000명"
                            }
                        }
                    """.trimIndent()
                    else -> """
                        {
                            "action": "EXECUTE",
                            "message": "프로젝트를 생성합니다."
                        }
                    """.trimIndent()
                }
            }

            val gov = ConversationalGovernor.create(
                dacs = trackingDACS,
                llmProvider = mockLlm,
                blueprintRunner = runner
            )
            val session = gov.startSession()

            // Turn 1: ASK domain
            val r1 = gov.chat(session.sessionId, "프로젝트 만들어줘")
            assertEquals(ActionType.ASK, r1.action)
            assertEquals("domain", r1.askingFor)

            // Turn 2: ASK techStack
            val r2 = gov.chat(session.sessionId, "대학교 학점 관리")
            assertEquals(ActionType.ASK, r2.action)
            assertEquals("techStack", r2.askingFor)
            assertEquals("대학교 학점 관리", session.draftSpec.domain)

            // Turn 3: ASK scale
            val r3 = gov.chat(session.sessionId, "Kotlin, Spring Boot, PostgreSQL")
            assertEquals(ActionType.ASK, r3.action)
            assertEquals("scale", r3.askingFor)
            assertNotNull(session.draftSpec.techStack)
            assertEquals(3, session.draftSpec.techStack!!.size)

            // Turn 4: CONFIRM
            val r4 = gov.chat(session.sessionId, "학생 5000명")
            assertEquals(ActionType.CONFIRM, r4.action)
            assertNotNull(r4.confirmationSummary)
            assertEquals("학생 5000명", session.draftSpec.scale)

            // Turn 5: EXECUTE
            val r5 = gov.chat(session.sessionId, "응")
            assertEquals(ActionType.EXECUTE, r5.action)
            assertNotNull(r5.blueprint)

            // PROJECT_CREATE → mkdir + write steps
            assertEquals(2, r5.blueprint!!.steps.size)
            assertEquals(BlueprintStepType.FILE_MKDIR, r5.blueprint!!.steps[0].type)
            assertEquals(BlueprintStepType.FILE_WRITE, r5.blueprint!!.steps[1].type)

            // PROJECT_CREATE is risky → DACS was called
            assertEquals(1, dacsCallCount)
        }

        /**
         * Case 8 - DACS REVISION → 재질문 → 승인 (2턴)
         * "rm -rf /var/log/old 실행해줘" → DACS REVISION → ASK
         * "한달 이전 로그만 삭제" → DACS YES → EXECUTE
         * 검증: REVISION→ASK 전환, 재시도 시 DACS 재호출, SYSTEM 메시지 기록
         */
        @Test
        fun `Case 8 - DACS REVISION then approval on retry`() = runBlocking {
            var dacsCallCount = 0
            val sequentialDACS = object : DACS {
                override suspend fun evaluate(request: DACSRequest): DACSResult {
                    dacsCallCount++
                    return if (dacsCallCount == 1) {
                        // First call: REVISION
                        DACSResult(
                            requestId = request.requestId,
                            consensus = Consensus.REVISION,
                            reason = "Command scope unclear - specify which logs to delete",
                            personaOpinions = listOf(
                                PersonaOpinion(PersonaType.ARCHITECT, Vote.APPROVE, "OK", emptyList()),
                                PersonaOpinion(PersonaType.REVIEWER, Vote.ABSTAIN, "Scope unclear", listOf("Need specific scope")),
                                PersonaOpinion(PersonaType.ADVERSARY, Vote.ABSTAIN, "Risky command", listOf("rm -rf requires clarification"))
                            )
                        )
                    } else {
                        // Second call: YES
                        DACSResult(
                            requestId = request.requestId,
                            consensus = Consensus.YES,
                            reason = "Scoped deletion approved",
                            personaOpinions = listOf(
                                PersonaOpinion(PersonaType.ARCHITECT, Vote.APPROVE, "OK", emptyList()),
                                PersonaOpinion(PersonaType.REVIEWER, Vote.APPROVE, "Scope clarified", emptyList()),
                                PersonaOpinion(PersonaType.ADVERSARY, Vote.APPROVE, "Scoped to old logs", emptyList())
                            )
                        )
                    }
                }
            }

            var llmCallCount = 0
            val mockLlm = TestLlmProvider { _ ->
                llmCallCount++
                when (llmCallCount) {
                    1 -> """
                        {
                            "action": "EXECUTE",
                            "message": "rm -rf /var/log/old를 실행합니다.",
                            "specUpdates": {
                                "intent": "rm -rf /var/log/old 실행",
                                "taskType": "COMMAND",
                                "content": "rm -rf /var/log/old"
                            }
                        }
                    """.trimIndent()
                    else -> """
                        {
                            "action": "EXECUTE",
                            "message": "한달 이전 로그를 삭제합니다.",
                            "specUpdates": {
                                "intent": "한달 이전 로그만 삭제",
                                "content": "find /var/log/old -mtime +30 -delete"
                            }
                        }
                    """.trimIndent()
                }
            }

            val gov = ConversationalGovernor.create(
                dacs = sequentialDACS,
                llmProvider = mockLlm,
                blueprintRunner = runner
            )
            val session = gov.startSession()

            // Turn 1: DACS REVISION → ASK
            val r1 = gov.chat(session.sessionId, "rm -rf /var/log/old 실행해줘")
            assertEquals(ActionType.ASK, r1.action)
            assertTrue(r1.message.contains("추가 확인"))
            assertNotNull(r1.dacsResult)
            assertEquals(Consensus.REVISION, r1.dacsResult!!.consensus)
            assertEquals(1, dacsCallCount)

            // Verify SYSTEM message was recorded in history
            val systemMessages = session.history.filter { it.role == MessageRole.SYSTEM }
            assertTrue(systemMessages.isNotEmpty())
            assertTrue(systemMessages.any { it.content.contains("DACS") })

            // Turn 2: Retry with more context → DACS YES → EXECUTE
            val r2 = gov.chat(session.sessionId, "한달 이전 로그만 삭제")
            assertEquals(ActionType.EXECUTE, r2.action)
            assertNotNull(r2.blueprint)
            assertEquals(2, dacsCallCount) // DACS was called again on retry
        }

        /**
         * Case 9 - 중간 취소 후 새 작업 (4턴)
         * "파일 만들어줘" → ASK → "/tmp/output.txt" → ASK
         * "됐어 취소" → CANCEL (세션 리셋)
         * "/tmp/data.csv 읽어줘" → EXECUTE (새 작업 성공)
         * 검증: reset 후 깨끗한 상태에서 새 작업
         */
        @Test
        fun `Case 9 - cancel mid-interview then new task`() = runBlocking {
            var callCount = 0
            val mockLlm = TestLlmProvider { _ ->
                callCount++
                when (callCount) {
                    1 -> """
                        {
                            "action": "ASK",
                            "message": "어떤 경로에 파일을 만들까요?",
                            "specUpdates": {
                                "intent": "파일 생성",
                                "taskType": "FILE_WRITE"
                            },
                            "askingFor": "targetPath"
                        }
                    """.trimIndent()
                    2 -> """
                        {
                            "action": "ASK",
                            "message": "파일에 어떤 내용을 쓸까요?",
                            "specUpdates": {
                                "targetPath": "/tmp/output.txt"
                            },
                            "askingFor": "content"
                        }
                    """.trimIndent()
                    3 -> """
                        {
                            "action": "CANCEL",
                            "message": "알겠습니다. 취소했어요."
                        }
                    """.trimIndent()
                    else -> """
                        {
                            "action": "EXECUTE",
                            "message": "/tmp/data.csv 파일을 읽겠습니다.",
                            "specUpdates": {
                                "intent": "/tmp/data.csv 파일 읽기",
                                "taskType": "FILE_READ",
                                "targetPath": "/tmp/data.csv"
                            }
                        }
                    """.trimIndent()
                }
            }

            val gov = ConversationalGovernor.create(
                dacs = simpleDACS,
                llmProvider = mockLlm,
                blueprintRunner = runner
            )
            val session = gov.startSession()

            // Turn 1: Start file write
            val r1 = gov.chat(session.sessionId, "파일 만들어줘")
            assertEquals(ActionType.ASK, r1.action)
            assertEquals(TaskType.FILE_WRITE, session.draftSpec.taskType)

            // Turn 2: Provide path
            val r2 = gov.chat(session.sessionId, "/tmp/output.txt")
            assertEquals(ActionType.ASK, r2.action)
            assertEquals("/tmp/output.txt", session.draftSpec.targetPath)

            // Turn 3: Cancel
            val r3 = gov.chat(session.sessionId, "됐어 취소")
            assertEquals(ActionType.CANCEL, r3.action)
            // Session should be reset
            assertNull(session.draftSpec.taskType)
            assertNull(session.draftSpec.targetPath)

            // Turn 4: New task succeeds on clean state
            val r4 = gov.chat(session.sessionId, "/tmp/data.csv 읽어줘")
            assertEquals(ActionType.EXECUTE, r4.action)
            assertNotNull(r4.blueprint)
            assertEquals(BlueprintStepType.FILE_READ, r4.blueprint!!.steps[0].type)
            assertEquals("/tmp/data.csv", r4.blueprint!!.steps[0].params["path"])
        }

        /**
         * Case 10 - 의도 변경/피봇 (5턴)
         * "파일 읽어줘" → ASK(targetPath) [FILE_READ]
         * "아 그거 말고 프로젝트 만들어줘" → ASK(domain) [PROJECT_CREATE로 피봇]
         * "블로그 플랫폼" → ASK(techStack)
         * "React, Node.js" → CONFIRM
         * "응" → EXECUTE
         * 검증: taskType이 FILE_READ→PROJECT_CREATE로 전환됨
         */
        @Test
        fun `Case 10 - intent pivot from file read to project create`() = runBlocking {
            var callCount = 0
            var dacsCallCount = 0
            val trackingDACS = object : DACS {
                override suspend fun evaluate(request: DACSRequest): DACSResult {
                    dacsCallCount++
                    return DACSResult(
                        requestId = request.requestId,
                        consensus = Consensus.YES,
                        reason = "Approved",
                        personaOpinions = listOf(
                            PersonaOpinion(PersonaType.ARCHITECT, Vote.APPROVE, "OK", emptyList()),
                            PersonaOpinion(PersonaType.REVIEWER, Vote.APPROVE, "OK", emptyList()),
                            PersonaOpinion(PersonaType.ADVERSARY, Vote.APPROVE, "OK", emptyList())
                        )
                    )
                }
            }

            val mockLlm = TestLlmProvider { _ ->
                callCount++
                when (callCount) {
                    1 -> """
                        {
                            "action": "ASK",
                            "message": "어떤 파일을 읽을까요?",
                            "specUpdates": {
                                "intent": "파일 읽기",
                                "taskType": "FILE_READ"
                            },
                            "askingFor": "targetPath"
                        }
                    """.trimIndent()
                    2 -> """
                        {
                            "action": "ASK",
                            "message": "어떤 도메인의 프로젝트인가요?",
                            "specUpdates": {
                                "intent": "프로젝트 생성",
                                "taskType": "PROJECT_CREATE"
                            },
                            "askingFor": "domain"
                        }
                    """.trimIndent()
                    3 -> """
                        {
                            "action": "ASK",
                            "message": "어떤 기술 스택을 사용할까요?",
                            "specUpdates": {
                                "domain": "블로그 플랫폼"
                            },
                            "askingFor": "techStack"
                        }
                    """.trimIndent()
                    4 -> """
                        {
                            "action": "CONFIRM",
                            "message": "다음 내용으로 프로젝트를 생성할까요?\n도메인: 블로그 플랫폼\n기술: React, Node.js",
                            "specUpdates": {
                                "techStack": ["React", "Node.js"]
                            }
                        }
                    """.trimIndent()
                    else -> """
                        {
                            "action": "EXECUTE",
                            "message": "프로젝트를 생성합니다."
                        }
                    """.trimIndent()
                }
            }

            val gov = ConversationalGovernor.create(
                dacs = trackingDACS,
                llmProvider = mockLlm,
                blueprintRunner = runner
            )
            val session = gov.startSession()

            // Turn 1: Start with FILE_READ
            val r1 = gov.chat(session.sessionId, "파일 읽어줘")
            assertEquals(ActionType.ASK, r1.action)
            assertEquals(TaskType.FILE_READ, session.draftSpec.taskType)

            // Turn 2: Pivot to PROJECT_CREATE
            val r2 = gov.chat(session.sessionId, "아 그거 말고 프로젝트 만들어줘")
            assertEquals(ActionType.ASK, r2.action)
            assertEquals(TaskType.PROJECT_CREATE, session.draftSpec.taskType) // Pivoted!
            assertEquals("domain", r2.askingFor)

            // Turn 3: domain filled
            val r3 = gov.chat(session.sessionId, "블로그 플랫폼")
            assertEquals(ActionType.ASK, r3.action)
            assertEquals("블로그 플랫폼", session.draftSpec.domain)
            assertEquals("techStack", r3.askingFor)

            // Turn 4: CONFIRM
            val r4 = gov.chat(session.sessionId, "React, Node.js")
            assertEquals(ActionType.CONFIRM, r4.action)
            assertNotNull(session.draftSpec.techStack)
            assertEquals(2, session.draftSpec.techStack!!.size)

            // Turn 5: EXECUTE
            val r5 = gov.chat(session.sessionId, "응")
            assertEquals(ActionType.EXECUTE, r5.action)
            assertNotNull(r5.blueprint)

            // Verify PROJECT_CREATE blueprint structure
            assertEquals(2, r5.blueprint!!.steps.size)
            assertEquals(BlueprintStepType.FILE_MKDIR, r5.blueprint!!.steps[0].type)
            assertEquals(BlueprintStepType.FILE_WRITE, r5.blueprint!!.steps[1].type)

            // PROJECT_CREATE is risky → DACS called
            assertEquals(1, dacsCallCount)
        }
    }

    @Nested
    @DisplayName("TaskType Classification")
    inner class TaskTypeTests {

        @Test
        fun `FILE_READ requires targetPath`() {
            val spec = DraftSpec(taskType = TaskType.FILE_READ)

            assertTrue("targetPath" in spec.getRequiredSlots())
            assertFalse(spec.isComplete())
        }

        @Test
        fun `FILE_WRITE requires targetPath and content`() {
            val spec = DraftSpec(taskType = TaskType.FILE_WRITE)

            val required = spec.getRequiredSlots()
            assertTrue("targetPath" in required)
            assertTrue("content" in required)
        }

        @Test
        fun `COMMAND requires content`() {
            val spec = DraftSpec(taskType = TaskType.COMMAND)

            assertTrue("content" in spec.getRequiredSlots())
        }

        @Test
        fun `PROJECT_CREATE requires domain and techStack`() {
            val spec = DraftSpec(taskType = TaskType.PROJECT_CREATE)

            val required = spec.getRequiredSlots()
            assertTrue("domain" in required)
            assertTrue("techStack" in required)
        }

        @Test
        fun `CONVERSATION has no required slots`() {
            val spec = DraftSpec(taskType = TaskType.CONVERSATION)

            assertTrue(spec.getRequiredSlots().isEmpty())
            assertTrue(spec.isComplete())
        }

        @Test
        fun `INFORMATION has no required slots`() {
            val spec = DraftSpec(taskType = TaskType.INFORMATION)

            assertTrue(spec.getRequiredSlots().isEmpty())
        }
    }
}

/**
 * Mock LLM Provider for testing
 */
class TestLlmProvider(
    private val responseGenerator: (LlmRequest) -> String
) : LlmProvider {

    override val defaultModel: String = "mock-model"
    override val defaultMaxTokens: Int = 1000

    override suspend fun call(request: LlmRequest): LlmResponse {
        val content = responseGenerator(request)
        return LlmResponse(
            content = content,
            finishReason = "stop",
            usage = LlmUsage(
                promptTokens = 100,
                completionTokens = 50,
                totalTokens = 150
            )
        )
    }

    override suspend fun cancel(executionId: String): Boolean = true
}

/**
 * Configurable DACS for testing
 */
class ConfigurableDACS : DACS {
    private var decision: Pair<Consensus, String> = Consensus.YES to "Approved"

    fun setDefaultDecision(consensus: Consensus, reason: String) {
        decision = consensus to reason
    }

    override suspend fun evaluate(request: DACSRequest): DACSResult {
        val vote = when (decision.first) {
            Consensus.YES -> Vote.APPROVE
            Consensus.NO -> Vote.REJECT
            Consensus.REVISION -> Vote.ABSTAIN
        }
        return DACSResult(
            requestId = request.requestId,
            consensus = decision.first,
            reason = decision.second,
            personaOpinions = listOf(
                PersonaOpinion(PersonaType.ARCHITECT, vote, decision.second, emptyList()),
                PersonaOpinion(PersonaType.REVIEWER, vote, decision.second, emptyList()),
                PersonaOpinion(PersonaType.ADVERSARY, vote, decision.second, emptyList())
            )
        )
    }
}

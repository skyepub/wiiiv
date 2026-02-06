package io.wiiiv.governor

import io.wiiiv.blueprint.BlueprintRunner
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

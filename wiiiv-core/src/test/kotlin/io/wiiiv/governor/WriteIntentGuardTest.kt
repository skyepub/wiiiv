package io.wiiiv.governor

import io.wiiiv.blueprint.BlueprintRunner
import io.wiiiv.blueprint.BlueprintStepType
import io.wiiiv.dacs.SimpleDACS
import io.wiiiv.execution.impl.LlmProvider
import io.wiiiv.execution.impl.LlmRequest
import io.wiiiv.execution.impl.LlmResponse
import io.wiiiv.execution.impl.LlmUsage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * WriteIntent Guard 불변 조건 테스트
 *
 * 4-Cell Consistency Matrix 검증:
 *
 * | writeIntent | 실제 calls     | Guard 결과        |
 * |-------------|----------------|-------------------|
 * | false       | READ만         | OK                |
 * | true        | WRITE >= 1     | OK                |
 * | true        | WRITE 없음     | Continue 강제     |
 * | false       | WRITE 존재     | Abort             |
 *
 * LLM 호출 없이 Mock으로 검증한다. 확률이 아닌 구조적 보장.
 */
class WriteIntentGuardTest {

    private lateinit var governor: ConversationalGovernor
    private lateinit var mockLlm: SequencedMockLlmProvider

    @BeforeEach
    fun setup() {
        mockLlm = SequencedMockLlmProvider()
        governor = ConversationalGovernor.create(
            dacs = SimpleDACS.DEFAULT,
            llmProvider = mockLlm,
            model = "mock"
        )
    }

    /**
     * 세션을 API_WORKFLOW 실행 직전 상태로 준비한다.
     * chat() → decideAction() → EXECUTE → executeTurn() → executeLlmDecidedTurn()
     */
    private fun prepareApiWorkflowSession(): ConversationSession {
        val session = governor.startSession()
        // DraftSpec을 API_WORKFLOW 완성 상태로 직접 설정
        session.draftSpec = DraftSpec(
            intent = "테스트 워크플로우",
            taskType = TaskType.API_WORKFLOW,
            domain = "test"
        )
        // 활성 태스크 확정 (DACS 통과 후 실행으로 진입하려면 필요)
        session.ensureActiveTask()
        return session
    }

    @Nested
    @DisplayName("Case 4: writeIntent=false + WRITE 호출 → Abort")
    inner class Case4AbortTests {

        @Test
        fun `writeIntent false with PUT call must abort`() = runBlocking {
            val session = prepareApiWorkflowSession()

            // 1st LLM call: decideAction → EXECUTE
            mockLlm.enqueue("""{"action":"EXECUTE","message":"실행합니다","specUpdates":{"taskType":"API_WORKFLOW","intent":"테스트","domain":"test"}}""")
            // 2nd LLM call: decideNextApiCall → writeIntent=false이지만 PUT 호출 포함
            mockLlm.enqueue("""{
                "writeIntent": false,
                "isComplete": false,
                "isAbort": false,
                "reasoning": "사용자 주문 상태를 변경합니다",
                "summary": "주문 상태 변경 중",
                "calls": [{"method": "PUT", "url": "http://localhost/api/orders/1", "headers": {}, "body": "{\"status\":\"shipped\"}"}]
            }""")

            val response = governor.chat(session.sessionId, "진행해")

            // Guard는 반드시 Abort해야 한다
            assertEquals(ActionType.CANCEL, response.action)
            assertTrue(response.message.contains("writeIntent=false"))
            assertTrue(response.message.contains("PUT"))
        }

        @Test
        fun `writeIntent false with POST call must abort`() = runBlocking {
            val session = prepareApiWorkflowSession()

            mockLlm.enqueue("""{"action":"EXECUTE","message":"실행","specUpdates":{"taskType":"API_WORKFLOW","intent":"테스트","domain":"test"}}""")
            mockLlm.enqueue("""{
                "writeIntent": false,
                "isComplete": false,
                "isAbort": false,
                "reasoning": "새 사용자 생성",
                "summary": "사용자 생성 중",
                "calls": [{"method": "POST", "url": "http://localhost/api/users", "headers": {}, "body": "{\"name\":\"test\"}"}]
            }""")

            val response = governor.chat(session.sessionId, "진행해")

            assertEquals(ActionType.CANCEL, response.action)
            assertTrue(response.message.contains("writeIntent=false"))
        }

        @Test
        fun `writeIntent false with DELETE call must abort`() = runBlocking {
            val session = prepareApiWorkflowSession()

            mockLlm.enqueue("""{"action":"EXECUTE","message":"실행","specUpdates":{"taskType":"API_WORKFLOW","intent":"테스트","domain":"test"}}""")
            mockLlm.enqueue("""{
                "writeIntent": false,
                "isComplete": false,
                "isAbort": false,
                "reasoning": "사용자 삭제",
                "summary": "삭제 중",
                "calls": [{"method": "DELETE", "url": "http://localhost/api/users/1", "headers": {}}]
            }""")

            val response = governor.chat(session.sessionId, "진행해")

            assertEquals(ActionType.CANCEL, response.action)
            assertTrue(response.message.contains("writeIntent=false"))
        }

        @Test
        fun `writeIntent false with mixed GET and PUT must abort`() = runBlocking {
            val session = prepareApiWorkflowSession()

            mockLlm.enqueue("""{"action":"EXECUTE","message":"실행","specUpdates":{"taskType":"API_WORKFLOW","intent":"테스트","domain":"test"}}""")
            mockLlm.enqueue("""{
                "writeIntent": false,
                "isComplete": false,
                "isAbort": false,
                "reasoning": "조회 후 변경",
                "summary": "조회 및 변경",
                "calls": [
                    {"method": "GET", "url": "http://localhost/api/users", "headers": {}},
                    {"method": "PUT", "url": "http://localhost/api/orders/1", "headers": {}, "body": "{}"}
                ]
            }""")

            val response = governor.chat(session.sessionId, "진행해")

            assertEquals(ActionType.CANCEL, response.action)
        }
    }

    @Nested
    @DisplayName("Case 3: writeIntent=true + isComplete=true + WRITE 미실행 → Continue 강제")
    inner class Case3ContinueTests {

        @Test
        fun `writeIntent true with isComplete but no write executed must continue`() = runBlocking {
            val session = prepareApiWorkflowSession()

            // 1st LLM call: decideAction → EXECUTE
            mockLlm.enqueue("""{"action":"EXECUTE","message":"실행합니다","specUpdates":{"taskType":"API_WORKFLOW","intent":"테스트","domain":"test"}}""")
            // 2nd LLM call: writeIntent=true인데 isComplete=true, calls 비어있음 (WRITE 미실행)
            mockLlm.enqueue("""{
                "writeIntent": true,
                "isComplete": true,
                "isAbort": false,
                "reasoning": "사용자 조회 완료",
                "summary": "john 사용자를 조회했습니다",
                "calls": []
            }""")

            val response = governor.chat(session.sessionId, "진행해")

            // Guard는 Continue를 강제해야 한다
            assertEquals(ActionType.EXECUTE, response.action)
            assertEquals(NextAction.CONTINUE_EXECUTION, response.nextAction)
            assertTrue(response.message.contains("쓰기 작업 진행 중"))
        }

        @Test
        fun `writeIntent true with empty calls and no write executed must continue`() = runBlocking {
            val session = prepareApiWorkflowSession()

            mockLlm.enqueue("""{"action":"EXECUTE","message":"실행","specUpdates":{"taskType":"API_WORKFLOW","intent":"테스트","domain":"test"}}""")
            // isComplete=false이지만 calls가 비어있음 → 완료로 간주되는 분기
            mockLlm.enqueue("""{
                "writeIntent": true,
                "isComplete": false,
                "isAbort": false,
                "reasoning": "조회만 완료",
                "summary": "조회 완료",
                "calls": []
            }""")

            val response = governor.chat(session.sessionId, "진행해")

            // calls 비어있는 분기에서도 writeIntent 검증이 작동해야 함
            assertEquals(ActionType.EXECUTE, response.action)
            assertEquals(NextAction.CONTINUE_EXECUTION, response.nextAction)
        }
    }

    @Nested
    @DisplayName("Case 1 & 2: 정상 경로 (일관성 있는 선언)")
    inner class ConsistentCases {

        @Test
        fun `writeIntent false with GET only completes normally`() = runBlocking {
            val session = prepareApiWorkflowSession()

            mockLlm.enqueue("""{"action":"EXECUTE","message":"실행","specUpdates":{"taskType":"API_WORKFLOW","intent":"테스트","domain":"test"}}""")
            mockLlm.enqueue("""{
                "writeIntent": false,
                "isComplete": true,
                "isAbort": false,
                "reasoning": "조회 완료",
                "summary": "사용자 목록을 조회했습니다",
                "calls": []
            }""")

            val response = governor.chat(session.sessionId, "진행해")

            // 정상 완료 (Continue 강제 없음)
            assertEquals(ActionType.EXECUTE, response.action)
            assertNull(response.nextAction)
            assertTrue(response.message.contains("사용자 목록을 조회했습니다"))
        }
    }

    @Nested
    @DisplayName("writeIntent 세션 고정 (첫 선언 이후 변경 불가)")
    inner class WriteIntentSessionLockTests {

        @Test
        fun `declaredWriteIntent is stored on first decision`() = runBlocking {
            val session = prepareApiWorkflowSession()

            // 첫 번째 decision에서 writeIntent=true 선언
            mockLlm.enqueue("""{"action":"EXECUTE","message":"실행","specUpdates":{"taskType":"API_WORKFLOW","intent":"테스트","domain":"test"}}""")
            mockLlm.enqueue("""{
                "writeIntent": true,
                "isComplete": false,
                "isAbort": false,
                "reasoning": "GET 먼저 수행",
                "summary": "사용자 조회 중",
                "calls": [{"method": "GET", "url": "http://localhost/api/users", "headers": {}}]
            }""")

            governor.chat(session.sessionId, "진행해")

            // SessionContext에 writeIntent가 저장되어야 함
            assertEquals(true, session.context.declaredWriteIntent)
        }

        @Test
        fun `second decision writeIntent false does not override stored true`() = runBlocking {
            val session = prepareApiWorkflowSession()
            // 세션에 미리 writeIntent=true를 설정 (이전 턴에서 선언된 것처럼)
            session.context.declaredWriteIntent = true

            // 두 번째 결정에서 writeIntent=false로 바꾸려 해도, isComplete=true + WRITE 미실행이면
            // 저장된 true가 우선 → Continue 강제
            mockLlm.enqueue("""{"action":"EXECUTE","message":"실행","specUpdates":{"taskType":"API_WORKFLOW","intent":"테스트","domain":"test"}}""")
            mockLlm.enqueue("""{
                "writeIntent": false,
                "isComplete": true,
                "isAbort": false,
                "reasoning": "완료",
                "summary": "조회 완료",
                "calls": []
            }""")

            val response = governor.chat(session.sessionId, "계속")

            // 저장된 writeIntent=true가 우선이므로, WRITE 미실행 → Continue 강제
            assertEquals(ActionType.EXECUTE, response.action)
            assertEquals(NextAction.CONTINUE_EXECUTION, response.nextAction)
        }
    }
}

/**
 * 순차 응답을 반환하는 Mock LLM Provider
 *
 * enqueue()로 등록한 순서대로 응답을 반환한다.
 * 큐가 비면 마지막 응답을 반복 반환한다.
 */
class SequencedMockLlmProvider(
    override val defaultModel: String = "mock-model",
    override val defaultMaxTokens: Int = 1000
) : LlmProvider {
    private val responseQueue = ArrayDeque<String>()
    private var lastResponse: String = """{"action":"REPLY","message":"no response queued"}"""

    fun enqueue(content: String) {
        responseQueue.addLast(content)
    }

    override suspend fun call(request: LlmRequest): LlmResponse {
        val content = if (responseQueue.isNotEmpty()) {
            responseQueue.removeFirst().also { lastResponse = it }
        } else {
            lastResponse
        }
        return LlmResponse(
            content = content,
            finishReason = "stop",
            usage = LlmUsage.of(10, 20)
        )
    }

    override suspend fun cancel(executionId: String): Boolean = true
}

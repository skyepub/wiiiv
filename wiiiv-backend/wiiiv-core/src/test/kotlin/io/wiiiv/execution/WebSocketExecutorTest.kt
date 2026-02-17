package io.wiiiv.execution

import io.wiiiv.execution.impl.WebSocketExecutor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * WebSocket Executor 테스트
 *
 * Canonical: Executor 정의서 v1.0
 *
 * ## Executor 원칙 검증
 *
 * - 판단하지 않는다
 * - 해석하지 않는다
 * - 잘못된 입력에 대해 적절한 오류 반환
 */
class WebSocketExecutorTest {

    private lateinit var executor: WebSocketExecutor
    private lateinit var context: ExecutionContext

    @BeforeEach
    fun setup() {
        executor = WebSocketExecutor()
        context = ExecutionContext(
            executionId = "exec-ws-test",
            blueprintId = "bp-ws-test",
            instructionId = "instr-ws-test"
        )
    }

    @Test
    fun `canHandle should return true for WebSocketStep`() {
        val step = ExecutionStep.WebSocketStep(
            stepId = "ws-1",
            action = WebSocketAction.SEND,
            url = "ws://localhost:8080/ws"
        )

        assertTrue(executor.canHandle(step))
    }

    @Test
    fun `canHandle should return false for other steps`() {
        val step = ExecutionStep.NoopStep(stepId = "noop-1")

        assertFalse(executor.canHandle(step))
    }

    @Test
    fun `should return error for invalid step type`() = runTest {
        val step = ExecutionStep.NoopStep(stepId = "noop-1")
        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("INVALID_STEP_TYPE", result.error.code)
    }

    @Test
    fun `should return error for invalid URL`() = runTest {
        val step = ExecutionStep.WebSocketStep(
            stepId = "ws-1",
            action = WebSocketAction.SEND,
            url = "not a valid url",
            message = "hello"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("INVALID_URL", result.error.code)
    }

    @Test
    fun `should return error for non-websocket scheme`() = runTest {
        val step = ExecutionStep.WebSocketStep(
            stepId = "ws-1",
            action = WebSocketAction.SEND,
            url = "http://localhost:8080/ws",
            message = "hello"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("INVALID_SCHEME", result.error.code)
    }

    @Test
    fun `should return error for SEND without message`() = runTest {
        val step = ExecutionStep.WebSocketStep(
            stepId = "ws-1",
            action = WebSocketAction.SEND,
            url = "ws://localhost:8080/ws"
            // message is null
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("MESSAGE_REQUIRED", result.error.code)
    }

    @Test
    fun `should return error for SEND_RECEIVE without message`() = runTest {
        val step = ExecutionStep.WebSocketStep(
            stepId = "ws-1",
            action = WebSocketAction.SEND_RECEIVE,
            url = "ws://localhost:8080/ws"
            // message is null
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("MESSAGE_REQUIRED", result.error.code)
    }

    @Test
    fun `should return error for connection failure`() = runTest {
        val step = ExecutionStep.WebSocketStep(
            stepId = "ws-1",
            action = WebSocketAction.SEND,
            url = "ws://localhost:59999/nonexistent",
            message = "hello",
            timeoutMs = 1000
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        // Connection failure or timeout expected
        assertTrue(
            result.error.category == ErrorCategory.EXTERNAL_SERVICE_ERROR ||
            result.error.category == ErrorCategory.TIMEOUT
        )
    }

    @Test
    fun `should return error for receive timeout`() = runTest {
        val step = ExecutionStep.WebSocketStep(
            stepId = "ws-1",
            action = WebSocketAction.RECEIVE,
            url = "ws://localhost:59999/nonexistent",
            timeoutMs = 500
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        // Connection failure or timeout expected
        assertTrue(
            result.error.category == ErrorCategory.EXTERNAL_SERVICE_ERROR ||
            result.error.category == ErrorCategory.TIMEOUT
        )
    }

    @Test
    fun `step should have correct type`() {
        val step = ExecutionStep.WebSocketStep(
            stepId = "ws-1",
            action = WebSocketAction.SEND,
            url = "ws://localhost:8080/ws"
        )

        assertEquals(StepType.WEBSOCKET, step.type)
    }

    @Test
    fun `step should preserve all parameters`() {
        val headers = mapOf("Authorization" to "Bearer token")
        val params = mapOf("key" to "value")

        val step = ExecutionStep.WebSocketStep(
            stepId = "ws-1",
            action = WebSocketAction.SEND_RECEIVE,
            url = "wss://secure.example.com/ws",
            message = "test message",
            timeoutMs = 60000,
            headers = headers,
            params = params
        )

        assertEquals("ws-1", step.stepId)
        assertEquals(WebSocketAction.SEND_RECEIVE, step.action)
        assertEquals("wss://secure.example.com/ws", step.url)
        assertEquals("test message", step.message)
        assertEquals(60000, step.timeoutMs)
        assertEquals(headers, step.headers)
        assertEquals(params, step.params)
    }

    @Test
    fun `meta should include stepId and resourceRefs`() = runTest {
        val step = ExecutionStep.WebSocketStep(
            stepId = "ws-1",
            action = WebSocketAction.SEND,
            url = "ws://localhost:59999/ws",
            message = "hello",
            timeoutMs = 500
        )

        val result = executor.execute(step, context)

        // Even on failure, meta should be present
        assertIs<ExecutionResult.Failure>(result)
        assertEquals("ws-1", result.meta.stepId)
        assertTrue(result.meta.resourceRefs.contains("ws://localhost:59999/ws") == true)
    }
}

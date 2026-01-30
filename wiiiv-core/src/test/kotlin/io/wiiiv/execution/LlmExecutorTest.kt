package io.wiiiv.execution

import io.wiiiv.execution.impl.*
import io.wiiiv.runner.ExecutionRunner
import io.wiiiv.runner.RunnerStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * LlmExecutor Tests
 *
 * LLM 호출 Executor 테스트
 *
 * Mock Provider를 사용하여 실제 LLM 호출 없이 테스트
 */
class LlmExecutorTest {

    private lateinit var mockProvider: MockLlmProvider
    private lateinit var executor: LlmExecutor
    private lateinit var context: ExecutionContext

    @BeforeTest
    fun setup() {
        mockProvider = MockLlmProvider()
        executor = LlmExecutor.create(mockProvider)
        context = ExecutionContext.create(
            executionId = "test-exec",
            blueprintId = "test-bp",
            instructionId = "test-inst"
        )
    }

    // ==================== Basic Tests ====================

    @Test
    fun `should handle LlmCallStep`() {
        val step = ExecutionStep.LlmCallStep(
            stepId = "llm-1",
            action = LlmAction.COMPLETE,
            prompt = "Hello"
        )
        assertTrue(executor.canHandle(step))
    }

    @Test
    fun `should not handle other step types`() {
        val step = ExecutionStep.FileStep(
            stepId = "file-1",
            action = FileAction.READ,
            path = "/tmp/test.txt"
        )
        assertFalse(executor.canHandle(step))
    }

    @Test
    fun `should return error for invalid step type`() = runBlocking {
        val step = ExecutionStep.CommandStep(
            stepId = "cmd-1",
            command = "echo"
        )
        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, (result as ExecutionResult.Failure).error.category)
        assertEquals("INVALID_STEP_TYPE", result.error.code)
    }

    // ==================== LLM Action Tests ====================

    @Test
    fun `should execute COMPLETE action`() = runBlocking {
        mockProvider.setMockResponse("Completed text", "stop")

        val step = ExecutionStep.LlmCallStep(
            stepId = "complete-1",
            action = LlmAction.COMPLETE,
            prompt = "Complete this sentence"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = (result as ExecutionResult.Success).output
        assertEquals("Completed text", output.json["content"]?.jsonPrimitive?.content)
        assertEquals("stop", output.json["finishReason"]?.jsonPrimitive?.content)

        // Verify call was made with correct action
        val lastCall = mockProvider.getLastCall()
        assertNotNull(lastCall)
        assertEquals(LlmAction.COMPLETE, lastCall.action)
    }

    @Test
    fun `should execute ANALYZE action`() = runBlocking {
        mockProvider.setMockResponse("Analysis result", "stop")

        val step = ExecutionStep.LlmCallStep(
            stepId = "analyze-1",
            action = LlmAction.ANALYZE,
            prompt = "Analyze this code"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        assertEquals("Analysis result", (result as ExecutionResult.Success).output.json["content"]?.jsonPrimitive?.content)

        val lastCall = mockProvider.getLastCall()
        assertEquals(LlmAction.ANALYZE, lastCall?.action)
    }

    @Test
    fun `should execute SUMMARIZE action`() = runBlocking {
        mockProvider.setMockResponse("Summary", "stop")

        val step = ExecutionStep.LlmCallStep(
            stepId = "summarize-1",
            action = LlmAction.SUMMARIZE,
            prompt = "Summarize this document"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        assertEquals("Summary", (result as ExecutionResult.Success).output.json["content"]?.jsonPrimitive?.content)

        val lastCall = mockProvider.getLastCall()
        assertEquals(LlmAction.SUMMARIZE, lastCall?.action)
    }

    // ==================== Model and Token Tests ====================

    @Test
    fun `should use specified model`() = runBlocking {
        val step = ExecutionStep.LlmCallStep(
            stepId = "model-test",
            action = LlmAction.COMPLETE,
            prompt = "Test",
            model = "gpt-4"
        )

        executor.execute(step, context)

        val lastCall = mockProvider.getLastCall()
        assertEquals("gpt-4", lastCall?.model)
    }

    @Test
    fun `should use default model when not specified`() = runBlocking {
        val step = ExecutionStep.LlmCallStep(
            stepId = "default-model",
            action = LlmAction.COMPLETE,
            prompt = "Test"
        )

        executor.execute(step, context)

        val lastCall = mockProvider.getLastCall()
        assertEquals("mock-model", lastCall?.model)  // MockLlmProvider default
    }

    @Test
    fun `should use specified maxTokens`() = runBlocking {
        val step = ExecutionStep.LlmCallStep(
            stepId = "tokens-test",
            action = LlmAction.COMPLETE,
            prompt = "Test",
            maxTokens = 500
        )

        executor.execute(step, context)

        val lastCall = mockProvider.getLastCall()
        assertEquals(500, lastCall?.maxTokens)
    }

    @Test
    fun `should use default maxTokens when not specified`() = runBlocking {
        val step = ExecutionStep.LlmCallStep(
            stepId = "default-tokens",
            action = LlmAction.COMPLETE,
            prompt = "Test"
        )

        executor.execute(step, context)

        val lastCall = mockProvider.getLastCall()
        assertEquals(1000, lastCall?.maxTokens)  // MockLlmProvider default
    }

    // ==================== Output Tests ====================

    @Test
    fun `output should contain all required fields`() = runBlocking {
        mockProvider.setMockResponse(
            LlmResponse(
                content = "Response content",
                finishReason = "stop",
                usage = LlmUsage.of(100, 50)
            )
        )

        val step = ExecutionStep.LlmCallStep(
            stepId = "output-test",
            action = LlmAction.COMPLETE,
            prompt = "Test"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = (result as ExecutionResult.Success).output

        // Check JSON fields
        assertNotNull(output.json["model"])
        assertNotNull(output.json["content"])
        assertNotNull(output.json["finishReason"])
        assertNotNull(output.json["promptTokens"])
        assertNotNull(output.json["completionTokens"])
        assertNotNull(output.json["totalTokens"])
        assertNotNull(output.json["partial"])

        // Check values
        assertEquals("Response content", output.json["content"]?.jsonPrimitive?.content)
        assertEquals("stop", output.json["finishReason"]?.jsonPrimitive?.content)
        assertEquals(100, output.json["promptTokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals(50, output.json["completionTokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals(150, output.json["totalTokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals("false", output.json["partial"]?.jsonPrimitive?.content)

        // Check artifacts
        assertEquals("Response content", output.artifacts["content"])
        assertNotNull(output.artifacts["model"])
    }

    @Test
    fun `output should be added to context`() = runBlocking {
        mockProvider.setMockResponse("Context test response")

        val step = ExecutionStep.LlmCallStep(
            stepId = "context-test",
            action = LlmAction.COMPLETE,
            prompt = "Test"
        )

        executor.execute(step, context)

        val storedOutput = context.getStepOutput("context-test")
        assertNotNull(storedOutput)
        assertEquals("Context test response", storedOutput.json["content"]?.jsonPrimitive?.content)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `should return failure for provider error`() = runBlocking {
        mockProvider.setFailure(
            ErrorCategory.EXTERNAL_SERVICE_ERROR,
            "API_ERROR",
            "API call failed"
        )

        val step = ExecutionStep.LlmCallStep(
            stepId = "error-test",
            action = LlmAction.COMPLETE,
            prompt = "Test"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.EXTERNAL_SERVICE_ERROR, (result as ExecutionResult.Failure).error.category)
        assertEquals("API_ERROR", result.error.code)
    }

    @Test
    fun `should return failure for timeout`() = runBlocking {
        mockProvider.setFailure(
            ErrorCategory.TIMEOUT,
            "REQUEST_TIMEOUT",
            "Request timed out"
        )

        val step = ExecutionStep.LlmCallStep(
            stepId = "timeout-test",
            action = LlmAction.COMPLETE,
            prompt = "Test"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.TIMEOUT, (result as ExecutionResult.Failure).error.category)
    }

    @Test
    fun `should return failure for rate limit`() = runBlocking {
        // Rate limit is treated as EXTERNAL_SERVICE_ERROR (retryable)
        mockProvider.setFailure(
            ErrorCategory.EXTERNAL_SERVICE_ERROR,
            "RATE_LIMIT",
            "Rate limit exceeded"
        )

        val step = ExecutionStep.LlmCallStep(
            stepId = "rate-limit-test",
            action = LlmAction.COMPLETE,
            prompt = "Test"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.EXTERNAL_SERVICE_ERROR, (result as ExecutionResult.Failure).error.category)
        assertEquals("RATE_LIMIT", (result as ExecutionResult.Failure).error.code)
    }

    // ==================== Finish Reason Tests ====================

    @Test
    fun `should capture length finish reason`() = runBlocking {
        mockProvider.setMockResponse(
            LlmResponse(
                content = "Truncated response...",
                finishReason = "length",
                usage = LlmUsage.of(100, 1000)
            )
        )

        val step = ExecutionStep.LlmCallStep(
            stepId = "length-test",
            action = LlmAction.COMPLETE,
            prompt = "Generate a long text"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = (result as ExecutionResult.Success).output
        assertEquals("length", output.json["finishReason"]?.jsonPrimitive?.content)
    }

    // ==================== EchoLlmProvider Tests ====================

    @Test
    fun `EchoLlmProvider should echo with action prefix`() = runBlocking {
        val echoProvider = EchoLlmProvider()
        val echoExecutor = LlmExecutor.create(echoProvider)

        val step = ExecutionStep.LlmCallStep(
            stepId = "echo-complete",
            action = LlmAction.COMPLETE,
            prompt = "Hello world"
        )

        val result = echoExecutor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        assertEquals("[COMPLETE] Hello world", (result as ExecutionResult.Success).output.json["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `EchoLlmProvider should handle ANALYZE action`() = runBlocking {
        val echoProvider = EchoLlmProvider()
        val echoExecutor = LlmExecutor.create(echoProvider)

        val step = ExecutionStep.LlmCallStep(
            stepId = "echo-analyze",
            action = LlmAction.ANALYZE,
            prompt = "Some code"
        )

        val result = echoExecutor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        assertEquals("[ANALYZE] Some code", (result as ExecutionResult.Success).output.json["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `EchoLlmProvider should handle SUMMARIZE action`() = runBlocking {
        val echoProvider = EchoLlmProvider()
        val echoExecutor = LlmExecutor.create(echoProvider)

        val step = ExecutionStep.LlmCallStep(
            stepId = "echo-summarize",
            action = LlmAction.SUMMARIZE,
            prompt = "Long document"
        )

        val result = echoExecutor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        assertEquals("[SUMMARIZE] Long document", (result as ExecutionResult.Success).output.json["content"]?.jsonPrimitive?.content)
    }

    // ==================== Integration with Runner ====================

    @Test
    fun `Runner should execute multiple LLM steps`() = runBlocking {
        mockProvider.setMockResponse("Response")
        val runner = ExecutionRunner.create(executor)

        val steps = listOf(
            ExecutionStep.LlmCallStep(
                stepId = "llm-1",
                action = LlmAction.COMPLETE,
                prompt = "First prompt"
            ),
            ExecutionStep.LlmCallStep(
                stepId = "llm-2",
                action = LlmAction.ANALYZE,
                prompt = "Second prompt"
            ),
            ExecutionStep.LlmCallStep(
                stepId = "llm-3",
                action = LlmAction.SUMMARIZE,
                prompt = "Third prompt"
            )
        )

        val runnerContext = ExecutionContext.create(
            executionId = "workflow-exec",
            blueprintId = "workflow-bp",
            instructionId = "workflow-inst"
        )

        val result = runner.execute(steps, runnerContext)

        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertEquals(3, result.successCount)
        assertTrue(result.isAllSuccess)

        // Verify all calls were made
        assertEquals(3, mockProvider.getCallHistory().size)
    }

    @Test
    fun `Runner should fail-fast on LLM error`() = runBlocking {
        // Use CountingLlmProvider that fails on 2nd call with non-retryable error
        val provider = CountingLlmProvider(failOnCall = 2, retryable = false)
        val llmExecutor = LlmExecutor.create(provider)
        val runner = ExecutionRunner.create(llmExecutor)

        val steps = listOf(
            ExecutionStep.LlmCallStep(stepId = "llm-1", action = LlmAction.COMPLETE, prompt = "1"),
            ExecutionStep.LlmCallStep(stepId = "llm-2", action = LlmAction.COMPLETE, prompt = "2"),
            ExecutionStep.LlmCallStep(stepId = "llm-3", action = LlmAction.COMPLETE, prompt = "3")
        )

        val runnerContext = ExecutionContext.create("exec", "bp", "inst")
        val result = runner.execute(steps, runnerContext)

        assertEquals(RunnerStatus.FAILED, result.status)
        assertEquals(1, result.successCount)
        assertEquals(1, result.failureCount)
        // Third step should not execute due to fail-fast
        assertEquals(2, provider.callCount)
    }

    // ==================== Params Tests ====================

    @Test
    fun `should pass params to provider`() = runBlocking {
        val step = ExecutionStep.LlmCallStep(
            stepId = "params-test",
            action = LlmAction.COMPLETE,
            prompt = "Test",
            params = mapOf(
                "temperature" to "0.7",
                "top_p" to "0.9"
            )
        )

        executor.execute(step, context)

        val lastCall = mockProvider.getLastCall()
        assertNotNull(lastCall)
        assertEquals("0.7", lastCall.params["temperature"])
        assertEquals("0.9", lastCall.params["top_p"])
    }

    // ==================== Call History Tests ====================

    @Test
    fun `should track call history`() = runBlocking {
        val steps = listOf(
            ExecutionStep.LlmCallStep(stepId = "h1", action = LlmAction.COMPLETE, prompt = "First"),
            ExecutionStep.LlmCallStep(stepId = "h2", action = LlmAction.ANALYZE, prompt = "Second")
        )

        steps.forEach { executor.execute(it, context) }

        val history = mockProvider.getCallHistory()
        assertEquals(2, history.size)
        assertEquals("First", history[0].prompt)
        assertEquals("Second", history[1].prompt)
    }

    @Test
    fun `should clear call history`() = runBlocking {
        val step = ExecutionStep.LlmCallStep(stepId = "clear-test", action = LlmAction.COMPLETE, prompt = "Test")
        executor.execute(step, context)

        assertEquals(1, mockProvider.getCallHistory().size)

        mockProvider.clearHistory()

        assertEquals(0, mockProvider.getCallHistory().size)
    }
}

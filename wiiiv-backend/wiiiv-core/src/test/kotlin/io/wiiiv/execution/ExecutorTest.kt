package io.wiiiv.execution

import io.wiiiv.execution.impl.NoopExecutor
import io.wiiiv.runner.ExecutionRunner
import io.wiiiv.runner.RetryPolicy
import io.wiiiv.runner.RunnerStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Executor and Runner Tests
 *
 * wiiiv v2.0 핵심 실행 계층 검증
 */
class ExecutorTest {

    @Test
    fun `NoopExecutor should return Success for any step`() = runBlocking {
        // Given
        val executor = NoopExecutor.DEFAULT
        val step = ExecutionStep.NoopStep(stepId = "test-step-1")
        val context = ExecutionContext.create(
            executionId = "exec-1",
            blueprintId = "bp-1",
            instructionId = "inst-1"
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        assertEquals("test-step-1", result.meta.stepId)
    }

    @Test
    fun `CompositeExecutor should delegate to matching executor`() = runBlocking {
        // Given
        val executor = CompositeExecutor.builder()
            .add(NoopExecutor.DEFAULT)
            .build()

        val step = ExecutionStep.NoopStep(stepId = "test-step-2")
        val context = ExecutionContext.create(
            executionId = "exec-2",
            blueprintId = "bp-2",
            instructionId = "inst-2"
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
    }

    @Test
    fun `CompositeExecutor should return CONTRACT_VIOLATION when no executor found`() = runBlocking {
        // Given
        val executor = CompositeExecutor.builder()
            .add(NoopExecutor.STRICT)  // Only handles NoopStep
            .build()

        // CommandStep - NoopExecutor.STRICT won't handle this
        val step = ExecutionStep.CommandStep(
            stepId = "cmd-step",
            command = "echo hello"
        )
        val context = ExecutionContext.create(
            executionId = "exec-3",
            blueprintId = "bp-3",
            instructionId = "inst-3"
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("NO_EXECUTOR", result.error.code)
    }

    @Test
    fun `ExecutionRunner should execute steps sequentially`() = runBlocking {
        // Given
        val executor = NoopExecutor.DEFAULT
        val runner = ExecutionRunner.create(executor)

        val steps = listOf(
            ExecutionStep.NoopStep(stepId = "step-1"),
            ExecutionStep.NoopStep(stepId = "step-2"),
            ExecutionStep.NoopStep(stepId = "step-3")
        )

        val context = ExecutionContext.create(
            executionId = "exec-4",
            blueprintId = "bp-4",
            instructionId = "inst-4"
        )

        // When
        val result = runner.execute(steps, context)

        // Then
        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertEquals(3, result.results.size)
        assertTrue(result.isAllSuccess)
        assertEquals(3, result.successCount)
    }

    @Test
    fun `ExecutionContext should store step outputs append-only`() = runBlocking {
        // Given
        val context = ExecutionContext.create(
            executionId = "exec-5",
            blueprintId = "bp-5",
            instructionId = "inst-5"
        )

        val output1 = StepOutput.empty("step-1")
        val output2 = StepOutput.empty("step-2")

        // When
        context.addStepOutput("step-1", output1)
        context.addStepOutput("step-2", output2)

        // Then
        assertEquals(2, context.stepOutputs.size)
        assertEquals(output1, context.getStepOutput("step-1"))
        assertEquals(output2, context.getStepOutput("step-2"))
    }

    @Test
    fun `RetryPolicy should determine retryable categories`() {
        // Given
        val policy = RetryPolicy.DEFAULT

        // Then - Retryable
        assertTrue(policy.shouldRetry(ErrorCategory.IO_ERROR, 1))
        assertTrue(policy.shouldRetry(ErrorCategory.TIMEOUT, 1))
        assertTrue(policy.shouldRetry(ErrorCategory.EXTERNAL_SERVICE_ERROR, 1))

        // Then - Not Retryable
        assertTrue(!policy.shouldRetry(ErrorCategory.CONTRACT_VIOLATION, 1))
        assertTrue(!policy.shouldRetry(ErrorCategory.RESOURCE_NOT_FOUND, 1))
        assertTrue(!policy.shouldRetry(ErrorCategory.PERMISSION_DENIED, 1))
        assertTrue(!policy.shouldRetry(ErrorCategory.UNKNOWN, 1))
    }

    @Test
    fun `RetryPolicy should respect max attempts`() {
        // Given
        val policy = RetryPolicy(maxAttempts = 3)

        // Then
        assertTrue(policy.shouldRetry(ErrorCategory.IO_ERROR, 1))
        assertTrue(policy.shouldRetry(ErrorCategory.IO_ERROR, 2))
        assertTrue(!policy.shouldRetry(ErrorCategory.IO_ERROR, 3))  // Max reached
        assertTrue(!policy.shouldRetry(ErrorCategory.IO_ERROR, 4))
    }

    @Test
    fun `ExecutionResult types should be correct`() {
        // Success
        val success = ExecutionResult.Success(
            output = StepOutput.empty("s1"),
            meta = ExecutionMeta.now("s1")
        )
        assertTrue(success.isSuccess)
        assertTrue(!success.isFailure)
        assertTrue(!success.isCancelled)

        // Failure
        val failure = ExecutionResult.Failure(
            error = ExecutionError.unknown("test error"),
            meta = ExecutionMeta.now("s2")
        )
        assertTrue(!failure.isSuccess)
        assertTrue(failure.isFailure)
        assertTrue(!failure.isCancelled)

        // Cancelled
        val cancelled = ExecutionResult.Cancelled(
            reason = CancelReason(CancelSource.USER_REQUEST, "User cancelled"),
            meta = ExecutionMeta.now("s3")
        )
        assertTrue(!cancelled.isSuccess)
        assertTrue(!cancelled.isFailure)
        assertTrue(cancelled.isCancelled)
    }
}

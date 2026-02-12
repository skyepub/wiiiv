package io.wiiiv.runner

import io.wiiiv.execution.*
import io.wiiiv.execution.impl.FileExecutor
import io.wiiiv.execution.impl.NoopExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Parallel Execution Tests
 *
 * Canonical: ExecutionRunner Spec v1.0 §5.2
 *
 * 병렬 실행 기능 검증
 */
class ParallelExecutionTest {

    private lateinit var testDir: File

    @BeforeTest
    fun setup() {
        testDir = File(System.getProperty("java.io.tmpdir"), "wiiiv-parallel-test-${System.currentTimeMillis()}")
        testDir.mkdirs()
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    // ==================== Basic Parallel Tests ====================

    @Test
    fun `executeParallel should execute all steps concurrently`() = runBlocking {
        // Given
        val executor = NoopExecutor.DEFAULT
        val runner = ExecutionRunner.create(executor)
        val context = ExecutionContext.create("exec-1", "bp-1", "inst-1")

        val steps = listOf(
            ExecutionStep.NoopStep(stepId = "step-1"),
            ExecutionStep.NoopStep(stepId = "step-2"),
            ExecutionStep.NoopStep(stepId = "step-3")
        )

        // When
        val result = runner.executeParallel(steps, context)

        // Then
        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertEquals(3, result.results.size)
        assertEquals(3, result.successCount)
        assertTrue(result.isAllSuccess)
    }

    @Test
    fun `executeParallel should return empty result for empty steps`() = runBlocking {
        // Given
        val runner = ExecutionRunner.create(NoopExecutor.DEFAULT)
        val context = ExecutionContext.create("exec-1", "bp-1", "inst-1")

        // When
        val result = runner.executeParallel(emptyList(), context)

        // Then
        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertTrue(result.results.isEmpty())
    }

    @Test
    fun `executeParallel should execute steps in parallel not sequentially`() = runBlocking {
        // Given - Executor that tracks execution order
        val executionOrder = mutableListOf<String>()
        val delayExecutor = object : Executor {
            override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
                executionOrder.add("start-${step.stepId}")
                delay(50) // Small delay to allow concurrent execution
                executionOrder.add("end-${step.stepId}")
                return ExecutionResult.Success(
                    output = StepOutput.empty(step.stepId),
                    meta = ExecutionMeta.now(step.stepId)
                )
            }
            override suspend fun cancel(executionId: String, reason: CancelReason) = true
            override fun canHandle(step: ExecutionStep) = true
        }

        val runner = ExecutionRunner.create(delayExecutor)
        val context = ExecutionContext.create("exec-1", "bp-1", "inst-1")

        val steps = listOf(
            ExecutionStep.NoopStep(stepId = "A"),
            ExecutionStep.NoopStep(stepId = "B"),
            ExecutionStep.NoopStep(stepId = "C")
        )

        // When
        runner.executeParallel(steps, context)

        // Then - If parallel, starts should be interleaved with ends
        // Sequential would be: start-A, end-A, start-B, end-B, start-C, end-C
        // Parallel should have all starts before any ends (or interleaved)
        val startIndices = executionOrder.mapIndexedNotNull { idx, s -> if (s.startsWith("start")) idx else null }
        val endIndices = executionOrder.mapIndexedNotNull { idx, s -> if (s.startsWith("end")) idx else null }

        // At least some starts should come before some ends (true parallel)
        val startsBeforeFirstEnd = startIndices.count { it < endIndices.min() }
        assertTrue(startsBeforeFirstEnd >= 2, "Expected parallel execution but got sequential")
    }

    // ==================== Fail-Fast Tests ====================

    @Test
    fun `executeParallel should fail-fast when one step fails`() = runBlocking {
        // Given - Executor that fails for specific step
        val failingExecutor = object : Executor {
            override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
                return if (step.stepId == "fail-step") {
                    ExecutionResult.Failure(
                        error = ExecutionError(ErrorCategory.IO_ERROR, "FAIL", "Intentional failure"),
                        meta = ExecutionMeta.now(step.stepId)
                    )
                } else {
                    delay(100) // Delay to allow failure to propagate
                    ExecutionResult.Success(
                        output = StepOutput.empty(step.stepId),
                        meta = ExecutionMeta.now(step.stepId)
                    )
                }
            }
            override suspend fun cancel(executionId: String, reason: CancelReason) = true
            override fun canHandle(step: ExecutionStep) = true
        }

        val runner = ExecutionRunner.create(failingExecutor)
        val context = ExecutionContext.create("exec-1", "bp-1", "inst-1")

        val steps = listOf(
            ExecutionStep.NoopStep(stepId = "step-1"),
            ExecutionStep.NoopStep(stepId = "fail-step"),
            ExecutionStep.NoopStep(stepId = "step-3")
        )

        // When
        val result = runner.executeParallel(steps, context)

        // Then
        assertEquals(RunnerStatus.FAILED, result.status)
        assertTrue(result.failureCount >= 1)
    }

    @Test
    fun `failed parallel group should cancel other steps with PARENT_CANCELLED`() = runBlocking {
        // Given
        val executionStarted = AtomicInteger(0)
        val slowExecutor = object : Executor {
            override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
                executionStarted.incrementAndGet()
                return when (step.stepId) {
                    "fast-fail" -> {
                        // Fail immediately
                        ExecutionResult.Failure(
                            error = ExecutionError(ErrorCategory.IO_ERROR, "FAST_FAIL", "Fast failure"),
                            meta = ExecutionMeta.now(step.stepId)
                        )
                    }
                    else -> {
                        // Slow steps
                        delay(500)
                        ExecutionResult.Success(
                            output = StepOutput.empty(step.stepId),
                            meta = ExecutionMeta.now(step.stepId)
                        )
                    }
                }
            }
            override suspend fun cancel(executionId: String, reason: CancelReason) = true
            override fun canHandle(step: ExecutionStep) = true
        }

        val runner = ExecutionRunner.create(slowExecutor)
        val context = ExecutionContext.create("exec-1", "bp-1", "inst-1")

        val steps = listOf(
            ExecutionStep.NoopStep(stepId = "slow-1"),
            ExecutionStep.NoopStep(stepId = "fast-fail"),
            ExecutionStep.NoopStep(stepId = "slow-2")
        )

        // When
        val result = runner.executeParallel(steps, context)

        // Then
        assertEquals(RunnerStatus.FAILED, result.status)

        // Check for PARENT_CANCELLED
        val cancelledResults = result.results.values.filterIsInstance<ExecutionResult.Cancelled>()
        cancelledResults.forEach { cancelled ->
            assertEquals(CancelSource.PARENT_CANCELLED, cancelled.reason.source)
        }
    }

    // ==================== ExecutionPlan Tests ====================

    @Test
    fun `executePlan should execute sequential steps in order`() = runBlocking {
        // Given
        val executionOrder = mutableListOf<String>()
        val trackingExecutor = object : Executor {
            override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
                executionOrder.add(step.stepId)
                return ExecutionResult.Success(
                    output = StepOutput.empty(step.stepId),
                    meta = ExecutionMeta.now(step.stepId)
                )
            }
            override suspend fun cancel(executionId: String, reason: CancelReason) = true
            override fun canHandle(step: ExecutionStep) = true
        }

        val runner = ExecutionRunner.create(trackingExecutor)
        val context = ExecutionContext.create("exec-1", "bp-1", "inst-1")

        val plan = ExecutionPlan.sequential(listOf(
            ExecutionStep.NoopStep(stepId = "step-1"),
            ExecutionStep.NoopStep(stepId = "step-2"),
            ExecutionStep.NoopStep(stepId = "step-3")
        ))

        // When
        val result = runner.executePlan(plan, context)

        // Then
        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertEquals(listOf("step-1", "step-2", "step-3"), executionOrder)
    }

    @Test
    fun `executePlan should execute mixed sequential and parallel`() = runBlocking {
        // Given
        val executor = NoopExecutor.DEFAULT
        val runner = ExecutionRunner.create(executor)
        val context = ExecutionContext.create("exec-1", "bp-1", "inst-1")

        // Plan: step-1 → [step-2, step-3, step-4] → step-5
        val plan = ExecutionPlan.builder()
            .addStep(ExecutionStep.NoopStep(stepId = "step-1"))
            .addParallel(
                ExecutionStep.NoopStep(stepId = "step-2"),
                ExecutionStep.NoopStep(stepId = "step-3"),
                ExecutionStep.NoopStep(stepId = "step-4")
            )
            .addStep(ExecutionStep.NoopStep(stepId = "step-5"))
            .build()

        // When
        val result = runner.executePlan(plan, context)

        // Then
        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertEquals(5, result.results.size)
        assertEquals(5, result.successCount)
    }

    @Test
    fun `executePlan should stop on parallel group failure`() = runBlocking {
        // Given - Executor that fails for specific step
        val failingExecutor = object : Executor {
            override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
                return if (step.stepId == "fail-step") {
                    ExecutionResult.Failure(
                        error = ExecutionError(ErrorCategory.IO_ERROR, "FAIL", "Intentional failure"),
                        meta = ExecutionMeta.now(step.stepId)
                    )
                } else {
                    ExecutionResult.Success(
                        output = StepOutput.empty(step.stepId),
                        meta = ExecutionMeta.now(step.stepId)
                    )
                }
            }
            override suspend fun cancel(executionId: String, reason: CancelReason) = true
            override fun canHandle(step: ExecutionStep) = true
        }

        val runner = ExecutionRunner.create(failingExecutor)
        val context = ExecutionContext.create("exec-1", "bp-1", "inst-1")

        // Plan: step-1 → [step-2, fail-step] → step-3 (should not execute)
        val plan = ExecutionPlan.builder()
            .addStep(ExecutionStep.NoopStep(stepId = "step-1"))
            .addParallel(
                ExecutionStep.NoopStep(stepId = "step-2"),
                ExecutionStep.NoopStep(stepId = "fail-step")
            )
            .addStep(ExecutionStep.NoopStep(stepId = "step-3"))
            .build()

        // When
        val result = runner.executePlan(plan, context)

        // Then
        assertEquals(RunnerStatus.FAILED, result.status)

        // step-3 should not be executed
        val step3Result = result.results.find { it.meta.stepId == "step-3" }
        assertNull(step3Result, "step-3 should not have been executed")
    }

    // ==================== Real File Executor Parallel Tests ====================

    @Test
    fun `parallel file operations should execute correctly`() = runBlocking {
        // Given
        val runner = ExecutionRunner.create(FileExecutor.INSTANCE)
        val context = ExecutionContext.create("exec-1", "bp-1", "inst-1")

        // Create multiple files in parallel
        val steps = (1..5).map { i ->
            ExecutionStep.FileStep(
                stepId = "write-$i",
                action = FileAction.WRITE,
                path = "${testDir.absolutePath}/file-$i.txt",
                content = "Content of file $i"
            )
        }

        // When
        val result = runner.executeParallel(steps, context)

        // Then
        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertEquals(5, result.successCount)

        // Verify all files were created
        (1..5).forEach { i ->
            val file = File("${testDir.absolutePath}/file-$i.txt")
            assertTrue(file.exists(), "File $i should exist")
            assertEquals("Content of file $i", file.readText())
        }
    }

    @Test
    fun `parallel read operations should execute correctly`() = runBlocking {
        // Given - Create files first
        (1..3).forEach { i ->
            File("${testDir.absolutePath}/read-$i.txt").writeText("Read content $i")
        }

        val runner = ExecutionRunner.create(FileExecutor.INSTANCE)
        val context = ExecutionContext.create("exec-1", "bp-1", "inst-1")

        val steps = (1..3).map { i ->
            ExecutionStep.FileStep(
                stepId = "read-$i",
                action = FileAction.READ,
                path = "${testDir.absolutePath}/read-$i.txt"
            )
        }

        // When
        val result = runner.executeParallel(steps, context)

        // Then
        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertEquals(3, result.successCount)

        // Verify outputs are in context
        (1..3).forEach { i ->
            val output = context.getStepOutput("read-$i")
            assertNotNull(output)
        }
    }

    // ==================== ExecutionPlan Builder Tests ====================

    @Test
    fun `ExecutionPlan builder should create correct plan`() {
        // Given & When
        val plan = ExecutionPlan.builder()
            .addStep(ExecutionStep.NoopStep(stepId = "a"))
            .addParallel(
                ExecutionStep.NoopStep(stepId = "b"),
                ExecutionStep.NoopStep(stepId = "c")
            )
            .addSteps(listOf(
                ExecutionStep.NoopStep(stepId = "d"),
                ExecutionStep.NoopStep(stepId = "e")
            ))
            .build()

        // Then
        assertEquals(4, plan.nodes.size)
        assertTrue(plan.nodes[0] is ExecutionNode.Single)
        assertTrue(plan.nodes[1] is ExecutionNode.Parallel)
        assertTrue(plan.nodes[2] is ExecutionNode.Single)
        assertTrue(plan.nodes[3] is ExecutionNode.Single)
        assertEquals(5, plan.totalSteps)
    }

    @Test
    fun `ExecutionPlan sequential should create all single nodes`() {
        // Given
        val steps = listOf(
            ExecutionStep.NoopStep(stepId = "1"),
            ExecutionStep.NoopStep(stepId = "2"),
            ExecutionStep.NoopStep(stepId = "3")
        )

        // When
        val plan = ExecutionPlan.sequential(steps)

        // Then
        assertEquals(3, plan.nodes.size)
        assertTrue(plan.nodes.all { it is ExecutionNode.Single })
    }

    @Test
    fun `ExecutionPlan parallel should create single parallel node`() {
        // Given
        val steps = listOf(
            ExecutionStep.NoopStep(stepId = "1"),
            ExecutionStep.NoopStep(stepId = "2"),
            ExecutionStep.NoopStep(stepId = "3")
        )

        // When
        val plan = ExecutionPlan.parallel(steps)

        // Then
        assertEquals(1, plan.nodes.size)
        assertTrue(plan.nodes[0] is ExecutionNode.Parallel)
        assertEquals(3, (plan.nodes[0] as ExecutionNode.Parallel).steps.size)
    }

    // ==================== Status Priority Tests ====================

    @Test
    fun `parallel status should prioritize CANCELLED over FAILED`() = runBlocking {
        // Given - Executor that returns both failure and cancelled
        val mixedExecutor = object : Executor {
            override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
                return when (step.stepId) {
                    "fail" -> ExecutionResult.Failure(
                        error = ExecutionError(ErrorCategory.IO_ERROR, "FAIL", "Failed"),
                        meta = ExecutionMeta.now(step.stepId)
                    )
                    "cancel" -> ExecutionResult.Cancelled(
                        reason = CancelReason(CancelSource.USER_REQUEST, "Cancelled"),
                        meta = ExecutionMeta.now(step.stepId)
                    )
                    else -> ExecutionResult.Success(
                        output = StepOutput.empty(step.stepId),
                        meta = ExecutionMeta.now(step.stepId)
                    )
                }
            }
            override suspend fun cancel(executionId: String, reason: CancelReason) = true
            override fun canHandle(step: ExecutionStep) = true
        }

        val runner = ExecutionRunner.create(mixedExecutor)
        val context = ExecutionContext.create("exec-1", "bp-1", "inst-1")

        val steps = listOf(
            ExecutionStep.NoopStep(stepId = "fail"),
            ExecutionStep.NoopStep(stepId = "cancel"),
            ExecutionStep.NoopStep(stepId = "success")
        )

        // When
        val result = runner.executeParallel(steps, context)

        // Then - Canonical: Cancelled takes priority
        assertEquals(RunnerStatus.CANCELLED, result.status)
    }
}

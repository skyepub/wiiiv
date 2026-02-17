package io.wiiiv.runner

import io.wiiiv.execution.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Execution Runner
 *
 * Canonical: ExecutionRunner Spec v1.0
 *
 * ## Runner의 정의
 *
 * "ExecutionRunner는 Blueprint에 정의된 실행 흐름을 따라
 *  step 실행을 Executor에 위임하고,
 *  그 결과를 집계하여 Governor에게 반환하는 실행 오케스트레이터이다."
 *
 * ## Runner의 위상
 *
 * ```
 * Governor
 *  └─ ExecutionRunner
 *      └─ Executor
 * ```
 *
 * - Runner는 Governor 외부에서 직접 호출되지 않는다
 * - Runner는 독립적인 판단 주체가 아니다
 * - Runner의 모든 실행은 Governor의 실행 위임으로만 시작된다
 *
 * ## Runner의 기본 원칙
 *
 * ### 판단 금지 원칙
 * - ❌ 실행 결과의 의미 판단
 * - ❌ 성공/실패를 해석하여 흐름을 재구성
 * - ❌ "이 정도면 괜찮다"와 같은 정성적 판단
 * - ❌ "n개 중 m개 성공이면 OK" 같은 해석
 *
 * ### 집계 허용 원칙
 * - ✅ step 실행 결과를 순서대로 집계
 * - ✅ step 결과를 ExecutionContext에 append-only로 저장
 * - ✅ 결과 상태의 기계적 집계
 */
class ExecutionRunner(
    private val executor: Executor,
    private val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT
) {

    /**
     * Execute steps sequentially
     *
     * Blueprint의 step들을 순차적으로 실행한다.
     * v1.0에서는 순차 실행만 지원 (병렬 group은 별도 메서드).
     *
     * @param steps 실행할 step 목록
     * @param context 실행 컨텍스트
     * @return 실행 결과
     */
    suspend fun execute(
        steps: List<ExecutionStep>,
        context: ExecutionContext
    ): RunnerResult {
        val results = mutableListOf<ExecutionResult>()

        for (step in steps) {
            // Check for cancellation
            if (!currentCoroutineContext().isActive) {
                val cancelResult = ExecutionResult.Cancelled(
                    reason = CancelReason(CancelSource.SYSTEM_SHUTDOWN, "Coroutine cancelled"),
                    meta = ExecutionMeta.now(step.stepId)
                )
                results.add(cancelResult)
                return RunnerResult(
                    results = results,
                    status = RunnerStatus.CANCELLED
                )
            }

            // Execute step with retry
            val result = executeWithRetry(step, context)
            results.add(result)

            // On failure, stop execution (fail-fast)
            if (result is ExecutionResult.Failure) {
                return RunnerResult(
                    results = results,
                    status = RunnerStatus.FAILED
                )
            }

            // On cancellation, stop execution
            if (result is ExecutionResult.Cancelled) {
                return RunnerResult(
                    results = results,
                    status = RunnerStatus.CANCELLED
                )
            }
        }

        return RunnerResult(
            results = results,
            status = RunnerStatus.COMPLETED
        )
    }

    /**
     * Execute a single step with retry policy
     *
     * RetryPolicy를 적용하여 step을 실행한다.
     *
     * Canonical: ExecutionRunner Spec v1.0 §7
     * - Executor는 재시도 여부를 알지 못한다
     * - 각 재시도는 완전히 새로운 실행으로 취급된다
     */
    private suspend fun executeWithRetry(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        var lastResult: ExecutionResult? = null
        var attempt = 0

        while (attempt < retryPolicy.maxAttempts) {
            attempt++

            try {
                val result = executor.execute(step, context)

                when (result) {
                    is ExecutionResult.Success -> {
                        return result
                    }
                    is ExecutionResult.Cancelled -> {
                        // Cancelled는 재시도하지 않음
                        return result
                    }
                    is ExecutionResult.Failure -> {
                        lastResult = result

                        // Check if retryable
                        if (!retryPolicy.shouldRetry(result.error.category, attempt)) {
                            return result
                        }

                        // Wait before retry
                        if (attempt < retryPolicy.maxAttempts) {
                            kotlinx.coroutines.delay(retryPolicy.intervalMs)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e  // Propagate cancellation
            } catch (e: Exception) {
                // Unexpected exception - wrap as Failure
                lastResult = ExecutionResult.Failure(
                    error = ExecutionError.unknown("Unexpected error: ${e.message}"),
                    meta = ExecutionMeta.now(step.stepId)
                )

                if (!retryPolicy.shouldRetry(ErrorCategory.UNKNOWN, attempt)) {
                    return lastResult
                }

                if (attempt < retryPolicy.maxAttempts) {
                    kotlinx.coroutines.delay(retryPolicy.intervalMs)
                }
            }
        }

        // All retries exhausted
        return lastResult ?: ExecutionResult.Failure(
            error = ExecutionError.unknown("All retry attempts exhausted"),
            meta = ExecutionMeta.now(step.stepId)
        )
    }

    /**
     * Execute a single step without retry
     */
    suspend fun executeSingle(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        return executor.execute(step, context)
    }

    /**
     * Execute steps in parallel (parallel group)
     *
     * Canonical: ExecutionRunner Spec v1.0 §5.2
     *
     * 동일 parallel group에 속한 step들을 동시에 실행한다.
     * v1.0에서 병렬 group의 실패 정책은 **fail-fast**로 고정된다.
     *
     * ## 실패 처리
     *
     * 병렬 group 내 step 중 하나라도 Failure 발생 시:
     * - Runner는 해당 group을 실패로 간주
     * - 남은 step들은 cancel 된다 (cancel_source = PARENT_CANCELLED)
     *
     * @param steps 병렬 실행할 step 목록
     * @param context 실행 컨텍스트
     * @return 병렬 실행 결과
     */
    suspend fun executeParallel(
        steps: List<ExecutionStep>,
        context: ExecutionContext
    ): ParallelResult = coroutineScope {
        if (steps.isEmpty()) {
            return@coroutineScope ParallelResult(
                results = emptyMap(),
                status = RunnerStatus.COMPLETED
            )
        }

        val results = mutableMapOf<String, ExecutionResult>()
        val resultsMutex = Mutex()
        val failureDetected = AtomicBoolean(false)

        // Launch all steps in parallel
        val jobs = steps.map { step ->
            async {
                // Check if another step already failed
                if (failureDetected.get()) {
                    val cancelledResult = ExecutionResult.Cancelled(
                        reason = CancelReason(
                            CancelSource.PARENT_CANCELLED,
                            "Parallel group failed - step cancelled before execution"
                        ),
                        meta = ExecutionMeta.now(step.stepId)
                    )
                    resultsMutex.withLock {
                        results[step.stepId] = cancelledResult
                    }
                    return@async cancelledResult
                }

                // Execute step with retry
                val result = executeWithRetry(step, context)

                // Record result
                resultsMutex.withLock {
                    results[step.stepId] = result
                }

                // On failure, signal other steps to cancel
                if (result is ExecutionResult.Failure) {
                    failureDetected.set(true)
                }

                result
            }
        }

        // Wait for all jobs to complete or cancel on failure
        try {
            // Wait for first failure or all completions
            var firstFailure: ExecutionResult.Failure? = null

            for (job in jobs) {
                val result = job.await()
                if (result is ExecutionResult.Failure && firstFailure == null) {
                    firstFailure = result
                    // Cancel remaining jobs
                    jobs.forEach { it.cancel() }
                }
            }

            // Await all jobs to get their final results (including cancelled)
            jobs.forEach { job ->
                try {
                    job.await()
                } catch (e: CancellationException) {
                    // Job was cancelled - result should already be recorded or we need to record it
                }
            }

        } catch (e: CancellationException) {
            // Coroutine was cancelled externally
            // Mark remaining steps as cancelled
            steps.forEach { step ->
                resultsMutex.withLock {
                    if (step.stepId !in results) {
                        results[step.stepId] = ExecutionResult.Cancelled(
                            reason = CancelReason(
                                CancelSource.PARENT_CANCELLED,
                                "Parallel execution cancelled"
                            ),
                            meta = ExecutionMeta.now(step.stepId)
                        )
                    }
                }
            }
        }

        // Ensure all steps have results
        steps.forEach { step ->
            resultsMutex.withLock {
                if (step.stepId !in results) {
                    results[step.stepId] = ExecutionResult.Cancelled(
                        reason = CancelReason(
                            CancelSource.PARENT_CANCELLED,
                            "Step cancelled due to parallel group failure"
                        ),
                        meta = ExecutionMeta.now(step.stepId)
                    )
                }
            }
        }

        // Determine final status
        val finalStatus = determineParallelStatus(results.values.toList())

        ParallelResult(
            results = results.toMap(),
            status = finalStatus
        )
    }

    /**
     * Execute an execution plan (mixed sequential and parallel)
     *
     * ExecutionPlan은 순차 step과 병렬 group의 조합이다.
     *
     * @param plan 실행 계획
     * @param context 실행 컨텍스트
     * @return 실행 결과
     */
    suspend fun executePlan(
        plan: ExecutionPlan,
        context: ExecutionContext
    ): RunnerResult {
        val allResults = mutableListOf<ExecutionResult>()

        for (node in plan.nodes) {
            // Check for cancellation
            if (!currentCoroutineContext().isActive) {
                // Mark remaining nodes as cancelled
                return RunnerResult(
                    results = allResults,
                    status = RunnerStatus.CANCELLED
                )
            }

            when (node) {
                is ExecutionNode.Single -> {
                    val result = executeWithRetry(node.step, context)
                    allResults.add(result)

                    // Fail-fast for sequential steps
                    if (result is ExecutionResult.Failure) {
                        return RunnerResult(
                            results = allResults,
                            status = RunnerStatus.FAILED
                        )
                    }
                    if (result is ExecutionResult.Cancelled) {
                        return RunnerResult(
                            results = allResults,
                            status = RunnerStatus.CANCELLED
                        )
                    }
                }

                is ExecutionNode.Parallel -> {
                    val parallelResult = executeParallel(node.steps, context)

                    // Add all parallel results in order
                    node.steps.forEach { step ->
                        parallelResult.results[step.stepId]?.let { allResults.add(it) }
                    }

                    // Fail-fast for parallel groups
                    if (parallelResult.status == RunnerStatus.FAILED) {
                        return RunnerResult(
                            results = allResults,
                            status = RunnerStatus.FAILED
                        )
                    }
                    if (parallelResult.status == RunnerStatus.CANCELLED) {
                        return RunnerResult(
                            results = allResults,
                            status = RunnerStatus.CANCELLED
                        )
                    }
                }
            }
        }

        return RunnerResult(
            results = allResults,
            status = RunnerStatus.COMPLETED
        )
    }

    /**
     * Determine parallel execution status
     */
    private fun determineParallelStatus(results: List<ExecutionResult>): RunnerStatus {
        // Canonical: ExecutionRunner Spec v1.0 §4.2
        // 1. cancelled ← Cancelled가 하나라도 있으면 최우선
        // 2. failed ← Cancelled 없고 Failure가 있으면
        // 3. completed ← 모두 Success인 경우만

        val hasCancelled = results.any { it is ExecutionResult.Cancelled }
        val hasFailure = results.any { it is ExecutionResult.Failure }

        return when {
            hasCancelled -> RunnerStatus.CANCELLED
            hasFailure -> RunnerStatus.FAILED
            else -> RunnerStatus.COMPLETED
        }
    }

    companion object {
        /**
         * Create a runner with default settings
         */
        fun create(executor: Executor): ExecutionRunner = ExecutionRunner(executor)

        /**
         * Create a runner with custom retry policy
         */
        fun create(executor: Executor, retryPolicy: RetryPolicy): ExecutionRunner =
            ExecutionRunner(executor, retryPolicy)
    }
}

/**
 * Runner Result - 실행 결과 집합
 *
 * Canonical: ExecutionRunner Spec v1.0 §4.2
 *
 * Runner의 출력은 실행 결과 집합이다.
 * Runner는 단일 "요약 결과"를 생성하지 않는다.
 */
data class RunnerResult(
    /**
     * Step별 ExecutionResult 목록
     */
    val results: List<ExecutionResult>,

    /**
     * 최종 실행 상태
     *
     * 기계적 집계로 결정됨 (의미 해석 아님)
     */
    val status: RunnerStatus
) {
    /**
     * 모든 step이 성공했는지 확인
     */
    val isAllSuccess: Boolean get() = status == RunnerStatus.COMPLETED

    /**
     * 실패한 step이 있는지 확인
     */
    val hasFailure: Boolean get() = results.any { it is ExecutionResult.Failure }

    /**
     * 취소된 step이 있는지 확인
     */
    val hasCancelled: Boolean get() = results.any { it is ExecutionResult.Cancelled }

    /**
     * 성공한 step 수
     */
    val successCount: Int get() = results.count { it is ExecutionResult.Success }

    /**
     * 실패한 step 수
     */
    val failureCount: Int get() = results.count { it is ExecutionResult.Failure }
}

/**
 * Runner Status - 최종 실행 상태
 *
 * Canonical: ExecutionRunner Spec v1.0 §4.2
 *
 * ## 상태 결정 우선순위
 *
 * 1. cancelled ← Cancelled가 하나라도 있으면 최우선
 * 2. failed ← Cancelled 없고 Failure가 있으면
 * 3. completed ← 모두 Success인 경우만
 *
 * 이 결정은 의미 해석이 아닌, 결과 상태의 단순 집계이다.
 */
enum class RunnerStatus {
    /**
     * 모든 step 성공
     */
    COMPLETED,

    /**
     * 하나 이상의 step 실패
     */
    FAILED,

    /**
     * 하나 이상의 step 취소
     */
    CANCELLED
}

/**
 * Parallel Result - 병렬 실행 결과
 *
 * Canonical: ExecutionRunner Spec v1.0 §5.2
 */
data class ParallelResult(
    /**
     * stepId -> ExecutionResult 매핑
     */
    val results: Map<String, ExecutionResult>,

    /**
     * 병렬 그룹의 최종 상태
     */
    val status: RunnerStatus
) {
    /**
     * 모든 step이 성공했는지 확인
     */
    val isAllSuccess: Boolean get() = status == RunnerStatus.COMPLETED

    /**
     * 성공한 step 수
     */
    val successCount: Int get() = results.values.count { it is ExecutionResult.Success }

    /**
     * 실패한 step 수
     */
    val failureCount: Int get() = results.values.count { it is ExecutionResult.Failure }

    /**
     * 취소된 step 수
     */
    val cancelledCount: Int get() = results.values.count { it is ExecutionResult.Cancelled }
}

/**
 * Execution Plan - 실행 계획
 *
 * 순차 step과 병렬 group의 조합을 표현한다.
 *
 * ```
 * [Single(step1)] → [Parallel(step2, step3, step4)] → [Single(step5)]
 * ```
 */
data class ExecutionPlan(
    /**
     * 실행 노드 목록 (순서대로 실행)
     */
    val nodes: List<ExecutionNode>
) {
    /**
     * 총 step 수
     */
    val totalSteps: Int get() = nodes.sumOf { node ->
        when (node) {
            is ExecutionNode.Single -> 1
            is ExecutionNode.Parallel -> node.steps.size
        }
    }

    companion object {
        /**
         * 순차 실행 계획 생성
         */
        fun sequential(steps: List<ExecutionStep>): ExecutionPlan {
            return ExecutionPlan(steps.map { ExecutionNode.Single(it) })
        }

        /**
         * 단일 병렬 그룹 계획 생성
         */
        fun parallel(steps: List<ExecutionStep>): ExecutionPlan {
            return ExecutionPlan(listOf(ExecutionNode.Parallel(steps)))
        }

        /**
         * Builder for complex plans
         */
        fun builder() = ExecutionPlanBuilder()
    }
}

/**
 * Execution Node - 실행 계획의 노드
 *
 * 단일 step 또는 병렬 group을 표현한다.
 */
sealed class ExecutionNode {
    /**
     * 단일 step
     */
    data class Single(val step: ExecutionStep) : ExecutionNode()

    /**
     * 병렬 group
     *
     * Canonical: ExecutionRunner Spec v1.0 §5.2.1
     * "명시적 parallel group만 허용"
     */
    data class Parallel(val steps: List<ExecutionStep>) : ExecutionNode()
}

/**
 * Execution Plan Builder
 *
 * 복잡한 실행 계획을 구성하기 위한 빌더
 */
class ExecutionPlanBuilder {
    private val nodes = mutableListOf<ExecutionNode>()

    /**
     * 순차 step 추가
     */
    fun addStep(step: ExecutionStep): ExecutionPlanBuilder {
        nodes.add(ExecutionNode.Single(step))
        return this
    }

    /**
     * 여러 순차 step 추가
     */
    fun addSteps(steps: List<ExecutionStep>): ExecutionPlanBuilder {
        steps.forEach { nodes.add(ExecutionNode.Single(it)) }
        return this
    }

    /**
     * 병렬 group 추가
     */
    fun addParallel(steps: List<ExecutionStep>): ExecutionPlanBuilder {
        if (steps.isNotEmpty()) {
            nodes.add(ExecutionNode.Parallel(steps))
        }
        return this
    }

    /**
     * 병렬 group 추가 (vararg)
     */
    fun addParallel(vararg steps: ExecutionStep): ExecutionPlanBuilder {
        return addParallel(steps.toList())
    }

    /**
     * 실행 계획 빌드
     */
    fun build(): ExecutionPlan = ExecutionPlan(nodes.toList())
}

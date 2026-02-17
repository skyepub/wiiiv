package io.wiiiv.execution

/**
 * Composite Executor
 *
 * Canonical: Executor Interface Spec v1.0 §1.2
 *
 * 여러 전문화된 Executor를 보유하고, step type에 따라 적절한 Executor에 위임한다.
 *
 * ## 라우팅 규칙
 *
 * - canHandle()가 true인 executor가 0개 → Failure(CONTRACT_VIOLATION)
 * - canHandle()가 true인 executor가 1개 → 해당 executor 실행
 * - canHandle()가 true인 executor가 2개 이상 → Failure(CONTRACT_VIOLATION: ambiguous executor)
 *
 * "애매하면 실패"가 Canonical 원칙이다.
 * 자동 선택(우선순위 기반)은 구현자 판단이 개입될 여지가 크므로 허용하지 않는다.
 */
class CompositeExecutor(
    private val executors: List<Executor>
) : Executor {

    override suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        val matchingExecutors = executors.filter { it.canHandle(step) }

        return when (matchingExecutors.size) {
            0 -> {
                // No executor found
                ExecutionResult.contractViolation(
                    stepId = step.stepId,
                    code = "NO_EXECUTOR",
                    message = "No executor found for step type: ${step.type}"
                )
            }
            1 -> {
                // Exactly one executor - delegate
                matchingExecutors[0].execute(step, context)
            }
            else -> {
                // Ambiguous - multiple executors can handle
                // This is a CONTRACT_VIOLATION, not a runtime decision
                ExecutionResult.contractViolation(
                    stepId = step.stepId,
                    code = "AMBIGUOUS_EXECUTOR",
                    message = "Multiple executors (${matchingExecutors.size}) can handle step type: ${step.type}. " +
                            "Ambiguous executor selection is not allowed."
                )
            }
        }
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean {
        // Delegate cancel to all executors
        // At least one successful cancel is considered success
        return executors.any { executor ->
            runCatching { executor.cancel(executionId, reason) }.getOrDefault(false)
        }
    }

    override fun canHandle(step: ExecutionStep): Boolean {
        // Can handle if exactly one executor can handle
        return executors.count { it.canHandle(step) } == 1
    }

    /**
     * 등록된 executor 수
     */
    val executorCount: Int get() = executors.size

    /**
     * 특정 step을 처리할 수 있는 executor 수
     */
    fun countHandlers(step: ExecutionStep): Int = executors.count { it.canHandle(step) }

    companion object {
        /**
         * Builder를 통한 CompositeExecutor 생성
         */
        fun builder(): Builder = Builder()
    }

    /**
     * CompositeExecutor Builder
     */
    class Builder {
        private val executors = mutableListOf<Executor>()

        fun add(executor: Executor): Builder {
            executors.add(executor)
            return this
        }

        fun addAll(vararg executors: Executor): Builder {
            this.executors.addAll(executors)
            return this
        }

        fun build(): CompositeExecutor = CompositeExecutor(executors.toList())
    }
}

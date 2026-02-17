package io.wiiiv.execution.impl

import io.wiiiv.execution.*
import java.time.Instant

/**
 * Noop Executor - 테스트/검증용 Executor
 *
 * 실제 실행 없이 항상 Success를 반환한다.
 * Runner/Contract 흐름 검증용으로 사용한다.
 *
 * ## 용도
 *
 * 1. ExecutionRunner 흐름 테스트
 * 2. ExecutionResult/ExecutionContext 계약 검증
 * 3. 개발 초기 단계 placeholder
 *
 * ## 동작
 *
 * - execute(): 항상 Success 반환 (빈 output)
 * - cancel(): 항상 true 반환
 * - canHandle(): 모든 step에 대해 true 반환 (또는 특정 조건)
 *
 * ## Canonical 준수
 *
 * NoopExecutor도 Executor이므로:
 * - 판단하지 않는다
 * - 해석하지 않는다
 * - 단지 "실행했다"는 사실만 반환한다
 */
class NoopExecutor(
    /**
     * 모든 step을 처리할지 여부
     *
     * true: 모든 step 처리 (기본값, 테스트용)
     * false: NoopStep만 처리
     */
    private val handleAll: Boolean = true,

    /**
     * 실행 지연 시간 (밀리초)
     *
     * 실제 실행 시간을 시뮬레이션하기 위한 옵션
     */
    private val delayMs: Long = 0
) : Executor {

    override suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        val startedAt = Instant.now()

        // Optional delay for simulation
        if (delayMs > 0) {
            kotlinx.coroutines.delay(delayMs)
        }

        val endedAt = Instant.now()

        val output = StepOutput.empty(step.stepId)

        val meta = ExecutionMeta.of(
            stepId = step.stepId,
            startedAt = startedAt,
            endedAt = endedAt
        )

        // Add output to context (append-only)
        context.addStepOutput(step.stepId, output)

        return ExecutionResult.Success(
            output = output,
            meta = meta
        )
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean {
        // NoopExecutor는 즉시 완료되므로 취소할 것이 없음
        // 하지만 인터페이스 계약상 true 반환
        return true
    }

    override fun canHandle(step: ExecutionStep): Boolean {
        return if (handleAll) {
            true
        } else {
            step is ExecutionStep.NoopStep
        }
    }

    companion object {
        /**
         * 모든 step을 처리하는 NoopExecutor (테스트용 기본값)
         */
        val DEFAULT = NoopExecutor(handleAll = true)

        /**
         * NoopStep만 처리하는 NoopExecutor (프로덕션 안전)
         */
        val STRICT = NoopExecutor(handleAll = false)

        /**
         * 지연이 있는 NoopExecutor (성능 테스트용)
         */
        fun withDelay(delayMs: Long): NoopExecutor = NoopExecutor(
            handleAll = true,
            delayMs = delayMs
        )
    }
}

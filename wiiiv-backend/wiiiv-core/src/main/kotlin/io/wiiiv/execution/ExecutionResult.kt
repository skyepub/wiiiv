package io.wiiiv.execution

import kotlinx.serialization.Serializable

/**
 * Execution Result - 실행 결과
 *
 * Canonical: Executor Interface Spec v1.0 §4.1, Executor 정의서 v1.0 §7
 *
 * Executor의 실행 결과는 반드시 이 세 가지 중 하나이다:
 * - Success: 실행 성공
 * - Failure: 실행 실패
 * - Cancelled: 실행 취소
 *
 * ## 금지된 결과 타입
 *
 * 다음 결과 타입은 Executor에서 사용할 수 없다:
 * - ❌ ReviewRequired → Governor/DACS 계층의 책임
 * - ❌ Denied → Gate 계층의 책임
 * - ❌ Pending → 실행 중 상태, 결과가 아님
 *
 * ## 불변성 원칙
 *
 * Canonical: Executor Interface Spec v1.0 §4.0
 *
 * ExecutionResult는 불변 객체(immutable object)이다.
 * 한 번 생성되면 수정되지 않으며, 실행 사실의 스냅샷이다.
 */
@Serializable
sealed class ExecutionResult {
    /**
     * 실행 메타데이터
     *
     * 모든 결과는 메타데이터를 포함한다.
     */
    abstract val meta: ExecutionMeta

    /**
     * Success - 실행 성공
     *
     * Step이 정상적으로 실행 완료됨.
     *
     * @property output step 실행 결과
     * @property meta 실행 메타데이터
     */
    @Serializable
    data class Success(
        val output: StepOutput,
        override val meta: ExecutionMeta
    ) : ExecutionResult()

    /**
     * Failure - 실행 실패
     *
     * Step 실행이 시도된 후 오류로 인해 완료되지 않은 상태.
     *
     * ## Failure의 의미
     *
     * Canonical: Executor 정의서 v1.0 §7.3
     *
     * Failure는 다음을 의미한다:
     * - step 실행이 계획대로 완료되지 않았다
     * - 오류가 발생했거나, 리소스 조건이 충족되지 않았다
     *
     * Failure는 다음을 의미하지 않는다:
     * - ❌ "이 step은 하면 안 된다" (정책 거부)
     * - ❌ "사용자가 다시 검토해야 한다" (리뷰 요구)
     *
     * @property error 오류 정보
     * @property partialOutput 부분 출력 (가능한 경우)
     * @property meta 실행 메타데이터
     */
    @Serializable
    data class Failure(
        val error: ExecutionError,
        val partialOutput: StepOutput? = null,
        override val meta: ExecutionMeta
    ) : ExecutionResult()

    /**
     * Cancelled - 실행 취소
     *
     * 실행이 외부 요인에 의해 중단되어, 정상적인 완료 또는 실패 판단이 불가능한 상태.
     *
     * ## Cancelled vs Failure 구분
     *
     * Canonical: Executor 정의서 v1.0 §7.2
     *
     * - Failure: 실행이 시도된 후 오류로 인해 완료되지 않음
     * - Cancelled: 외부 요인에 의해 중단되어 완료/실패 판단 불가
     *
     * ## RetryPolicy와의 관계
     *
     * Canonical: RetryPolicy Spec v1.0 §6
     *
     * Cancelled는 Failure가 아니므로 RetryPolicy의 대상이 아니다.
     * "Cancelled는 '실패'가 아니라 '중단'이다."
     *
     * @property reason 취소 사유
     * @property partialOutput 부분 출력 (가능한 경우)
     * @property meta 실행 메타데이터
     */
    @Serializable
    data class Cancelled(
        val reason: CancelReason,
        val partialOutput: StepOutput? = null,
        override val meta: ExecutionMeta
    ) : ExecutionResult()

    /**
     * 결과가 성공인지 확인
     */
    val isSuccess: Boolean get() = this is Success

    /**
     * 결과가 실패인지 확인
     */
    val isFailure: Boolean get() = this is Failure

    /**
     * 결과가 취소인지 확인
     */
    val isCancelled: Boolean get() = this is Cancelled

    companion object {
        /**
         * Success 결과 생성 헬퍼
         */
        fun success(output: StepOutput, meta: ExecutionMeta): ExecutionResult =
            Success(output, meta)

        /**
         * Failure 결과 생성 헬퍼
         */
        fun failure(error: ExecutionError, meta: ExecutionMeta, partialOutput: StepOutput? = null): ExecutionResult =
            Failure(error, partialOutput, meta)

        /**
         * Cancelled 결과 생성 헬퍼
         */
        fun cancelled(reason: CancelReason, meta: ExecutionMeta, partialOutput: StepOutput? = null): ExecutionResult =
            Cancelled(reason, partialOutput, meta)

        /**
         * CONTRACT_VIOLATION Failure 생성 헬퍼
         *
         * Runner가 Blueprint 계약 위반을 감지했을 때 사용.
         * Canonical: ExecutionRunner Spec v1.0 §9.1
         */
        fun contractViolation(stepId: String, code: String, message: String): ExecutionResult =
            Failure(
                error = ExecutionError.contractViolation(code, message),
                meta = ExecutionMeta.now(stepId)
            )
    }
}

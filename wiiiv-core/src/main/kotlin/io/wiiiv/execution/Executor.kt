package io.wiiiv.execution

/**
 * Executor Interface
 *
 * Canonical: Executor 정의서 v1.0, Executor Interface Spec v1.0 §1
 *
 * ## Executor의 정의
 *
 * "Executor는 Blueprint에 정의된 step을 판단 없이 실행하고,
 *  결과를 raw하게 반환하는 실행 프레임이다."
 *
 * ## Executor의 기본 전제
 *
 * | 전제 | 설명 |
 * |------|------|
 * | 왜 실행하는지를 묻지 않는다 | 실행 목적은 Executor의 관심사가 아니다 |
 * | 무엇을 실행할지를 결정하지 않는다 | Blueprint가 이미 결정했다 |
 * | Blueprint만 입력으로 받는다 | Spec, DACS 결과 등은 보지 않는다 |
 * | 실행 결과를 해석하지 않는다 | 실행 사실과 결과만 반환한다 |
 * | Blueprint를 신뢰한다 | 정합성과 합법성이 이미 검증되었다고 가정한다 |
 *
 * ## Executor가 하지 않는 것 (절대 제약)
 *
 * - ❌ Spec의 의미 해석
 * - ❌ 사용자 의도 추론
 * - ❌ 실행 목적 재해석
 * - ❌ "이건 위험해 보인다"와 같은 의미 판단
 * - ❌ Step 추가/삭제/재정렬
 * - ❌ "리뷰가 필요하다" 같은 흐름 변경 신호 생성
 * - ❌ "거부(Denied)" 같은 정책 판정 결과 생성
 * - ❌ LLM 결과를 요약/분석/판단하여 의미를 부여
 *
 * ## Executor의 출력 제약
 *
 * 허용되는 결과: Success, Failure, Cancelled
 * 금지된 결과: ReviewRequired, Denied
 */
interface Executor {
    /**
     * Execute a single step from Blueprint
     *
     * Executor는 step을 판단 없이 실행하고, 결과를 raw하게 반환한다.
     *
     * @param step 실행할 step (Blueprint에서 추출)
     * @param context 실행 컨텍스트 (환경, 변수, 리소스)
     * @return 실행 결과 (Success / Failure / Cancelled)
     */
    suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult

    /**
     * Cancel an ongoing execution
     *
     * 진행 중인 실행을 취소한다.
     *
     * @param executionId 취소할 실행의 ID
     * @param reason 취소 사유
     * @return 취소 성공 여부
     */
    suspend fun cancel(
        executionId: String,
        reason: CancelReason
    ): Boolean

    /**
     * Check if this executor can handle a step type
     *
     * 이 executor가 해당 step을 처리할 수 있는지 확인한다.
     *
     * @param step 확인할 step
     * @return 처리 가능 여부
     */
    fun canHandle(step: ExecutionStep): Boolean
}

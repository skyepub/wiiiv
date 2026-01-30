package io.wiiiv.execution

import kotlinx.serialization.Serializable

/**
 * Cancel Source - 취소를 발생시킨 주체
 *
 * Canonical: Executor Interface Spec v1.0 §4.4
 */
@Serializable
enum class CancelSource {
    /**
     * 사용자가 취소 요청
     */
    USER_REQUEST,

    /**
     * 시스템 타임아웃
     */
    TIMEOUT,

    /**
     * 시스템 종료
     */
    SYSTEM_SHUTDOWN,

    /**
     * Gate 런타임 강제 중단
     *
     * Canonical: Gate Spec v1.0 §4.1
     * Gate runtime enforcement 시 사용.
     * 재시도 금지 (정책 위반이므로)
     */
    GATE_ENFORCEMENT,

    /**
     * 상위 실행 취소로 인한 연쇄 취소
     *
     * Canonical: ExecutionRunner Spec v1.0 §5.2.3
     * 병렬 group 실패 시 남은 step들이 이 사유로 취소됨.
     */
    PARENT_CANCELLED
}

/**
 * Cancel Reason - 취소 사유
 *
 * Canonical: Executor Interface Spec v1.0 §4.4
 *
 * @property source 취소를 발생시킨 주체
 * @property message 취소 사유 메시지
 */
@Serializable
data class CancelReason(
    /**
     * 취소를 발생시킨 주체
     */
    val source: CancelSource,

    /**
     * 취소 사유 메시지
     */
    val message: String
)

/**
 * Cancelled는 RetryPolicy의 대상이 아니다.
 *
 * Canonical: RetryPolicy Spec v1.0 §6
 * "Cancelled는 '실패'가 아니라 '중단'이다. RetryPolicy의 대상이 아니다."
 */
val CancelSource.isRetryable: Boolean
    get() = false  // 모든 CancelSource는 재시도 불가

package io.wiiiv.runner

import io.wiiiv.execution.ErrorCategory
import io.wiiiv.execution.isRetryable
import kotlinx.serialization.Serializable

/**
 * Retry Policy - 재시도 정책
 *
 * Canonical: RetryPolicy Spec v1.0
 *
 * ## RetryPolicy의 목적
 *
 * "RetryPolicy는 실행 중 발생한 실패에 대해
 *  해당 실행을 다시 시도할 수 있는지 여부를 기계적으로 판단하는 규칙 집합이다."
 *
 * ## 책임 경계
 *
 * RetryPolicy는 다음을 절대 하지 않는다:
 * - ❌ 실행 결과의 의미 해석
 * - ❌ 성공/실패의 중요도 판단
 * - ❌ 실행 흐름 결정
 * - ❌ 결과 요약, 병합, 축약
 * - ❌ Executor 동작 변경
 *
 * ## Executor와의 분리
 *
 * - Executor는 재시도 여부를 알지 못한다
 * - Executor는 "이번이 몇 번째 시도인지"를 인지하지 않는다
 * - 재시도 횟수, 간격, 조건 판단은 전적으로 Runner의 책임이다
 *
 * ## Cancelled와 RetryPolicy
 *
 * Cancelled는 Failure가 아니므로 RetryPolicy의 대상이 아니다.
 * "Cancelled는 '실패'가 아니라 '중단'이다."
 */
@Serializable
data class RetryPolicy(
    /**
     * 재시도 활성화 여부
     */
    val enabled: Boolean = true,

    /**
     * 최대 시도 횟수 (첫 시도 포함)
     */
    val maxAttempts: Int = 3,

    /**
     * 재시도 간격 (밀리초)
     *
     * v1.0에서는 고정 간격만 지원.
     * exponential backoff, jitter는 v1.x+ 에서 확장 가능.
     */
    val intervalMs: Long = 1000
) {
    /**
     * 재시도 여부 판단
     *
     * Canonical: RetryPolicy Spec v1.0 §5.1
     *
     * RetryPolicy는 error.category만을 기준으로 재시도 여부를 판단한다.
     * Executor는 재시도 가능 여부를 명시하지 않는다.
     *
     * ## 기본 원칙
     * "애매하면 재시도하지 않는다."
     *
     * @param category 오류 분류
     * @param attemptNumber 현재 시도 번호 (1부터 시작)
     * @return 재시도 가능 여부
     */
    fun shouldRetry(category: ErrorCategory, attemptNumber: Int): Boolean {
        // Check if enabled
        if (!enabled) return false

        // Check if max attempts reached
        if (attemptNumber >= maxAttempts) return false

        // Check if category is retryable
        return category.isRetryable
    }

    companion object {
        /**
         * 기본 RetryPolicy
         *
         * - enabled: true
         * - maxAttempts: 3
         * - intervalMs: 1000
         */
        val DEFAULT = RetryPolicy()

        /**
         * 재시도 비활성화
         */
        val DISABLED = RetryPolicy(enabled = false)

        /**
         * 단일 시도 (재시도 없음)
         */
        val SINGLE_ATTEMPT = RetryPolicy(maxAttempts = 1)

        /**
         * 공격적 재시도 (5회, 500ms 간격)
         */
        val AGGRESSIVE = RetryPolicy(
            maxAttempts = 5,
            intervalMs = 500
        )

        /**
         * 보수적 재시도 (2회, 2초 간격)
         */
        val CONSERVATIVE = RetryPolicy(
            maxAttempts = 2,
            intervalMs = 2000
        )
    }
}

/**
 * Step-specific Retry Policy
 *
 * Blueprint 계약에 포함될 수 있는 step별 재시도 정책.
 *
 * Canonical: RetryPolicy Spec v1.0 §3
 *
 * ## 우선순위 규칙
 *
 * - Step에 retryPolicy가 명시된 경우 → Blueprint 계약이 최우선
 * - Step에 retryPolicy가 없는 경우 → Runner의 기본 RetryPolicy 적용
 */
@Serializable
data class StepRetryPolicy(
    /**
     * 재시도 활성화 여부
     */
    val enabled: Boolean = true,

    /**
     * 최대 시도 횟수
     */
    val maxAttempts: Int = 3,

    /**
     * 재시도 간격 (밀리초)
     */
    val intervalMs: Long = 1000
) {
    /**
     * RetryPolicy로 변환
     */
    fun toRetryPolicy(): RetryPolicy = RetryPolicy(
        enabled = enabled,
        maxAttempts = maxAttempts,
        intervalMs = intervalMs
    )
}

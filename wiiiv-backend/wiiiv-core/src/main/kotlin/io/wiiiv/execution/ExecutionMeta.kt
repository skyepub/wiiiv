package io.wiiiv.execution

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * Execution Meta - 실행 메타데이터
 *
 * Canonical: Executor Interface Spec v1.0 §4.2
 *
 * 모든 ExecutionResult는 이 메타데이터를 포함한다.
 * Executor는 실행 사실을 이 구조로 기록한다.
 *
 * @property stepId 실행된 step의 ID
 * @property startedAt 실행 시작 시각
 * @property endedAt 실행 종료 시각
 * @property durationMs 실행 시간 (밀리초)
 * @property resourceRefs 사용된 리소스 식별자 목록
 * @property traceId 감사 추적용 Trace ID
 */
@Serializable
data class ExecutionMeta(
    /**
     * 실행된 step의 ID
     */
    val stepId: String,

    /**
     * 실행 시작 시각 (ISO-8601)
     */
    val startedAt: String,

    /**
     * 실행 종료 시각 (ISO-8601)
     */
    val endedAt: String,

    /**
     * 실행 시간 (밀리초)
     */
    val durationMs: Long,

    /**
     * 사용된 리소스 식별자 목록
     *
     * 예: 파일 경로, URL, DB 식별자
     */
    val resourceRefs: List<String> = emptyList(),

    /**
     * 감사 추적용 Trace ID
     */
    val traceId: String? = null
) {
    companion object {
        /**
         * 현재 시각으로 즉시 완료된 메타 생성
         *
         * 주로 CONTRACT_VIOLATION 등 실제 실행 없이 즉시 반환할 때 사용
         */
        fun now(stepId: String): ExecutionMeta {
            val now = Instant.now().toString()
            return ExecutionMeta(
                stepId = stepId,
                startedAt = now,
                endedAt = now,
                durationMs = 0
            )
        }

        /**
         * 시작/종료 시각으로 메타 생성
         */
        fun of(
            stepId: String,
            startedAt: Instant,
            endedAt: Instant,
            resourceRefs: List<String> = emptyList(),
            traceId: String? = null
        ): ExecutionMeta {
            return ExecutionMeta(
                stepId = stepId,
                startedAt = startedAt.toString(),
                endedAt = endedAt.toString(),
                durationMs = Duration.between(startedAt, endedAt).toMillis(),
                resourceRefs = resourceRefs,
                traceId = traceId
            )
        }
    }
}

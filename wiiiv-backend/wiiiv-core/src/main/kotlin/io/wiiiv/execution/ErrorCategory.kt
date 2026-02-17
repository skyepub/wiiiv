package io.wiiiv.execution

import kotlinx.serialization.Serializable

/**
 * Error Category for Execution Failures
 *
 * Canonical: Executor Interface Spec v1.0 §4.3
 *
 * ErrorCategory는 실행 실패의 분류 체계이다.
 * RetryPolicy는 이 category를 기준으로 재시도 여부를 판단한다.
 */
@Serializable
enum class ErrorCategory {
    /**
     * Blueprint/Step 스키마 위반
     *
     * 재시도: ❌
     * 예: step 필수 필드 누락, node type 미지원, 파라미터 타입 불일치
     */
    CONTRACT_VIOLATION,

    /**
     * 리소스를 찾을 수 없음
     *
     * 재시도: ❌
     * 예: 파일 없음, URL 없음, DB 테이블 없음
     */
    RESOURCE_NOT_FOUND,

    /**
     * 권한 거부
     *
     * 재시도: ❌
     * 예: 파일 접근 권한 없음, API 인증 실패
     */
    PERMISSION_DENIED,

    /**
     * 네트워크/IO 오류
     *
     * 재시도: ⭕ (일시적 오류 가능)
     * 예: 네트워크 연결 실패, 파일 시스템 일시 오류
     */
    IO_ERROR,

    /**
     * 실행 시간 초과
     *
     * 재시도: ⭕ (일시적 오류 가능)
     * 예: 명령 실행 timeout, API 응답 timeout
     */
    TIMEOUT,

    /**
     * 외부 서비스 오류
     *
     * 재시도: ⭕ (일시적 오류 가능)
     * 예: API 서버 오류, DB 연결 오류, LLM 서비스 오류
     */
    EXTERNAL_SERVICE_ERROR,

    /**
     * 분류 불가능한 오류
     *
     * 재시도: ❌
     * 예: 예상치 못한 예외
     */
    UNKNOWN
}

/**
 * 이 ErrorCategory가 재시도 가능한지 여부
 *
 * Canonical: RetryPolicy Spec v1.0 §5.1
 */
val ErrorCategory.isRetryable: Boolean
    get() = when (this) {
        ErrorCategory.IO_ERROR -> true
        ErrorCategory.TIMEOUT -> true
        ErrorCategory.EXTERNAL_SERVICE_ERROR -> true
        ErrorCategory.CONTRACT_VIOLATION -> false
        ErrorCategory.RESOURCE_NOT_FOUND -> false
        ErrorCategory.PERMISSION_DENIED -> false
        ErrorCategory.UNKNOWN -> false
    }

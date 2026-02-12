package io.wiiiv.execution

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Execution Error - 실행 오류 정보
 *
 * Canonical: Executor Interface Spec v1.0 §4.3
 *
 * ExecutionError는 실행 실패의 구조화된 정보이다.
 * Executor는 이 구조로 오류를 반환하며, 오류의 "의미"를 해석하지 않는다.
 *
 * @property category 오류 분류 (ErrorCategory)
 * @property code 표준화된 오류 코드
 * @property message 사람이 읽을 수 있는 오류 메시지
 * @property details 추가 상세 정보 (선택)
 */
@Serializable
data class ExecutionError(
    /**
     * 오류 분류
     *
     * RetryPolicy는 이 값만을 기준으로 재시도 여부를 판단한다.
     */
    val category: ErrorCategory,

    /**
     * 표준화된 오류 코드
     *
     * 예: "FILE_NOT_FOUND", "CONNECTION_TIMEOUT", "INVALID_SQL"
     */
    val code: String,

    /**
     * 사람이 읽을 수 있는 오류 메시지
     *
     * Executor는 이 메시지를 해석하지 않는다.
     */
    val message: String,

    /**
     * 추가 상세 정보
     *
     * 예: stack trace, raw response, 원본 예외 정보
     * 감사/디버깅 목적으로만 사용.
     */
    val details: Map<String, JsonElement> = emptyMap()
) {
    companion object {
        /**
         * CONTRACT_VIOLATION 오류 생성 헬퍼
         */
        fun contractViolation(code: String, message: String): ExecutionError =
            ExecutionError(
                category = ErrorCategory.CONTRACT_VIOLATION,
                code = code,
                message = message
            )

        /**
         * RESOURCE_NOT_FOUND 오류 생성 헬퍼
         */
        fun resourceNotFound(code: String, message: String): ExecutionError =
            ExecutionError(
                category = ErrorCategory.RESOURCE_NOT_FOUND,
                code = code,
                message = message
            )

        /**
         * IO_ERROR 오류 생성 헬퍼
         */
        fun ioError(code: String, message: String): ExecutionError =
            ExecutionError(
                category = ErrorCategory.IO_ERROR,
                code = code,
                message = message
            )

        /**
         * TIMEOUT 오류 생성 헬퍼
         */
        fun timeout(code: String, message: String): ExecutionError =
            ExecutionError(
                category = ErrorCategory.TIMEOUT,
                code = code,
                message = message
            )

        /**
         * EXTERNAL_SERVICE_ERROR 오류 생성 헬퍼
         */
        fun externalServiceError(code: String, message: String): ExecutionError =
            ExecutionError(
                category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                code = code,
                message = message
            )

        /**
         * UNKNOWN 오류 생성 헬퍼
         */
        fun unknown(message: String, cause: Throwable? = null): ExecutionError =
            ExecutionError(
                category = ErrorCategory.UNKNOWN,
                code = "UNKNOWN_ERROR",
                message = message
            )
    }
}

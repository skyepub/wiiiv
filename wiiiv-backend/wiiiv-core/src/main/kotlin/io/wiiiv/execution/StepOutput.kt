package io.wiiiv.execution

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Step Output - Step 실행 결과
 *
 * Canonical: Executor Interface Spec v1.0 §5
 *
 * Step 실행의 출력을 담는 구조.
 * Executor는 이 구조로 결과를 반환하며, 결과의 "의미"를 해석하지 않는다.
 *
 * @property stepId 결과를 생성한 step의 ID
 * @property json 구조화된 JSON 출력
 * @property stdout 표준 출력 (command step용)
 * @property stderr 표준 에러 (command step용)
 * @property exitCode 종료 코드 (command step용)
 * @property artifacts 생성된 파일 아티팩트
 * @property warnings 경고 목록 (치명적이지 않은 이슈)
 * @property confidence 신뢰도 점수 (multimodal/LLM step용)
 * @property rawResponse 원본 응답 (API/LLM step용)
 * @property durationMs 실행 시간 (밀리초)
 */
@Serializable
data class StepOutput(
    /**
     * 결과를 생성한 step의 ID
     */
    val stepId: String,

    /**
     * 구조화된 JSON 출력
     *
     * Key: 필드명, Value: JSON 값
     */
    val json: Map<String, JsonElement> = emptyMap(),

    /**
     * 표준 출력 (command step용)
     */
    val stdout: String? = null,

    /**
     * 표준 에러 (command step용)
     */
    val stderr: String? = null,

    /**
     * 종료 코드 (command step용)
     */
    val exitCode: Int? = null,

    /**
     * 생성된 파일 아티팩트
     *
     * Key: 아티팩트 이름, Value: 파일 경로
     */
    val artifacts: Map<String, String> = emptyMap(),

    /**
     * 경고 목록 (치명적이지 않은 이슈)
     */
    val warnings: List<String> = emptyList(),

    /**
     * 신뢰도 점수 (multimodal/LLM step용)
     *
     * 0.0 ~ 1.0 범위
     */
    val confidence: Double? = null,

    /**
     * 원본 응답 (API/LLM step용)
     */
    val rawResponse: String? = null,

    /**
     * 실행 시간 (밀리초)
     */
    val durationMs: Long = 0
) {
    companion object {
        /**
         * 빈 출력 생성
         */
        fun empty(stepId: String): StepOutput = StepOutput(stepId = stepId)

        /**
         * Command 실행 결과 생성
         */
        fun command(
            stepId: String,
            stdout: String?,
            stderr: String?,
            exitCode: Int,
            durationMs: Long = 0
        ): StepOutput = StepOutput(
            stepId = stepId,
            stdout = stdout,
            stderr = stderr,
            exitCode = exitCode,
            durationMs = durationMs
        )

        /**
         * JSON 결과 생성
         */
        fun json(
            stepId: String,
            data: Map<String, JsonElement>,
            durationMs: Long = 0
        ): StepOutput = StepOutput(
            stepId = stepId,
            json = data,
            durationMs = durationMs
        )
    }
}

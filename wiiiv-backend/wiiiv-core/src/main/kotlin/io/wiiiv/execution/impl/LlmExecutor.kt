package io.wiiiv.execution.impl

import io.wiiiv.execution.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/**
 * LLM Executor - LLM 호출 Executor
 *
 * Canonical: Executor 정의서 v1.0 §6, Executor Interface Spec v1.0
 *
 * ## Executor 원칙 준수
 *
 * - 판단하지 않는다: LLM 응답의 의미를 해석하지 않음
 * - 해석하지 않는다: LLM을 도구처럼 호출하고 결과를 그대로 반환
 * - Blueprint를 신뢰한다: 정합성과 합법성이 이미 검증되었다고 가정
 *
 * ## 핵심 규칙 (Canonical: Executor 정의서 v1.0 §6)
 *
 * LLM 호출은 "실행"으로 허용되지만, "판단"은 금지된다.
 * - 허용: 텍스트 생성, 요약, 분석 결과 반환
 * - 금지: LLM 응답을 기반으로 흐름 결정, 조건 분기
 *
 * ## 지원 기능
 *
 * - LLM Action: COMPLETE, ANALYZE, SUMMARIZE
 * - 모델 선택 (provider 의존)
 * - 최대 토큰 제한
 * - 타임아웃 지원
 *
 * ## 오류 처리
 *
 * - Provider 미설정 → Failure (CONTRACT_VIOLATION)
 * - API 오류 → Failure (EXTERNAL_SERVICE_ERROR)
 * - 타임아웃 → Failure (TIMEOUT)
 * - 토큰 초과 → Failure (RESOURCE_EXHAUSTED)
 */
class LlmExecutor(
    private val provider: LlmProvider
) : Executor {

    override suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        // Type check
        if (step !is ExecutionStep.LlmCallStep) {
            return ExecutionResult.contractViolation(
                stepId = step.stepId,
                code = "INVALID_STEP_TYPE",
                message = "LlmExecutor can only handle LlmCallStep, got: ${step::class.simpleName}"
            )
        }

        val startedAt = Instant.now()

        return try {
            val result = executeLlmCall(step)

            val endedAt = Instant.now()

            when (result) {
                is LlmResult.Success -> {
                    val output = result.output
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.model ?: provider.defaultModel)
                    )

                    // Add to context
                    context.addStepOutput(step.stepId, output)

                    ExecutionResult.Success(output = output, meta = meta)
                }
                is LlmResult.Error -> {
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.model ?: provider.defaultModel)
                    )

                    ExecutionResult.Failure(
                        error = result.error,
                        partialOutput = result.partialOutput,
                        meta = meta
                    )
                }
            }
        } catch (e: Exception) {
            ExecutionResult.Failure(
                error = ExecutionError.unknown("Unexpected error: ${e.message}"),
                meta = ExecutionMeta.of(
                    stepId = step.stepId,
                    startedAt = startedAt,
                    endedAt = Instant.now(),
                    resourceRefs = listOf(step.model ?: provider.defaultModel)
                )
            )
        }
    }

    /**
     * LLM 호출 실행
     */
    private suspend fun executeLlmCall(step: ExecutionStep.LlmCallStep): LlmResult {
        val model = step.model ?: provider.defaultModel
        val maxTokens = step.maxTokens ?: provider.defaultMaxTokens

        // Build request based on action
        val request = LlmRequest(
            action = step.action,
            prompt = step.prompt,
            model = model,
            maxTokens = maxTokens,
            params = step.params
        )

        // Call provider
        val response = try {
            provider.call(request)
        } catch (e: LlmProviderException) {
            return LlmResult.Error(
                error = ExecutionError(
                    category = e.category,
                    code = e.code,
                    message = e.message
                ),
                partialOutput = e.partialResponse?.let { createOutput(step.stepId, it, model, true) }
            )
        }

        // Build output
        val output = createOutput(step.stepId, response, model, false)

        return LlmResult.Success(output)
    }

    /**
     * 출력 생성
     */
    private fun createOutput(
        stepId: String,
        response: LlmResponse,
        model: String,
        partial: Boolean
    ): StepOutput {
        return StepOutput(
            stepId = stepId,
            json = buildJsonObject {
                put("model", JsonPrimitive(model))
                put("content", JsonPrimitive(response.content))
                put("finishReason", JsonPrimitive(response.finishReason))
                put("promptTokens", JsonPrimitive(response.usage.promptTokens))
                put("completionTokens", JsonPrimitive(response.usage.completionTokens))
                put("totalTokens", JsonPrimitive(response.usage.totalTokens))
                put("partial", JsonPrimitive(partial))
            },
            artifacts = mapOf(
                "content" to response.content,
                "model" to model
            )
        )
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean {
        // LLM 요청 취소는 provider에 위임
        return provider.cancel(executionId)
    }

    override fun canHandle(step: ExecutionStep): Boolean {
        return step is ExecutionStep.LlmCallStep
    }

    /**
     * Internal result type for LLM operations
     */
    private sealed class LlmResult {
        data class Success(val output: StepOutput) : LlmResult()
        data class Error(
            val error: ExecutionError,
            val partialOutput: StepOutput? = null
        ) : LlmResult()
    }

    companion object {
        /**
         * Provider로 Executor 생성
         */
        fun create(provider: LlmProvider): LlmExecutor {
            return LlmExecutor(provider)
        }
    }
}

/**
 * LLM Provider Interface
 *
 * LLM 백엔드 추상화 (OpenAI, Anthropic, 로컬 모델 등)
 */
interface LlmProvider {
    /**
     * 기본 모델 이름
     */
    val defaultModel: String

    /**
     * 기본 최대 토큰
     */
    val defaultMaxTokens: Int

    /**
     * LLM 호출
     *
     * @throws LlmProviderException API 오류 시
     */
    suspend fun call(request: LlmRequest): LlmResponse

    /**
     * 진행 중인 요청 취소
     */
    suspend fun cancel(executionId: String): Boolean
}

/**
 * LLM 이미지 데이터 (Vision API용)
 */
data class LlmImage(
    val data: ByteArray,
    val mimeType: String  // "image/png", "image/jpeg", "image/gif", "image/webp"
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LlmImage) return false
        return data.contentEquals(other.data) && mimeType == other.mimeType
    }
    override fun hashCode(): Int = 31 * data.contentHashCode() + mimeType.hashCode()
}

/**
 * LLM Request
 */
data class LlmRequest(
    val action: LlmAction,
    val prompt: String,
    val model: String,
    val maxTokens: Int,
    val params: Map<String, String> = emptyMap(),
    val images: List<LlmImage> = emptyList()
)

/**
 * LLM Response
 */
data class LlmResponse(
    val content: String,
    val finishReason: String,
    val usage: LlmUsage
)

/**
 * LLM Usage (토큰 사용량)
 */
data class LlmUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
) {
    companion object {
        fun of(promptTokens: Int, completionTokens: Int): LlmUsage {
            return LlmUsage(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = promptTokens + completionTokens
            )
        }

        val ZERO = LlmUsage(0, 0, 0)
    }
}

/**
 * LLM Provider Exception
 */
class LlmProviderException(
    val category: ErrorCategory,
    val code: String,
    override val message: String,
    val partialResponse: LlmResponse? = null
) : Exception(message)


package io.wiiiv.execution.impl

import io.wiiiv.execution.ErrorCategory
import io.wiiiv.execution.LlmAction
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Anthropic Provider - Anthropic Claude API 연동
 *
 * ## 지원 모델
 *
 * - claude-sonnet-4-20250514 (기본)
 * - claude-opus-4-20250514
 * - claude-3-5-sonnet-20241022
 * - claude-3-5-haiku-20241022
 *
 * ## 환경 변수
 *
 * - ANTHROPIC_API_KEY: API 키 (필수)
 * - ANTHROPIC_BASE_URL: 커스텀 엔드포인트 (선택)
 *
 * ## Executor 원칙 준수
 *
 * - 판단하지 않는다: API 응답의 의미를 해석하지 않음
 * - 결과를 그대로 반환: LLM은 도구로만 사용
 */
class AnthropicProvider(
    private val apiKey: String = System.getenv("ANTHROPIC_API_KEY") ?: "",
    private val baseUrl: String = System.getenv("ANTHROPIC_BASE_URL") ?: DEFAULT_BASE_URL,
    override val defaultModel: String = DEFAULT_MODEL,
    override val defaultMaxTokens: Int = DEFAULT_MAX_TOKENS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val httpClient: HttpClient = DEFAULT_HTTP_CLIENT
) : LlmProvider {

    override suspend fun call(request: LlmRequest): LlmResponse {
        if (apiKey.isBlank()) {
            throw LlmProviderException(
                category = ErrorCategory.CONTRACT_VIOLATION,
                code = "API_KEY_MISSING",
                message = "ANTHROPIC_API_KEY environment variable is not set"
            )
        }

        val model = request.model.ifBlank { defaultModel }
        val maxTokens = if (request.maxTokens > 0) request.maxTokens else defaultMaxTokens

        // Build request body (Anthropic Messages API format)
        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("max_tokens", JsonPrimitive(maxTokens))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(request.prompt))
                }
            }

            // Optional parameters from request.params
            request.params["temperature"]?.let {
                put("temperature", JsonPrimitive(it.toDoubleOrNull() ?: 0.7))
            }
            request.params["top_p"]?.let {
                put("top_p", JsonPrimitive(it.toDoubleOrNull() ?: 1.0))
            }
            request.params["system"]?.let {
                put("system", JsonPrimitive(it))
            }
        }

        // Build HTTP request (요청별 timeout 우선, 없으면 provider 기본값)
        val effectiveTimeout = request.timeoutMs ?: timeoutMs
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .timeout(Duration.ofMillis(effectiveTimeout))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()

        // Execute request
        val response = try {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.http.HttpTimeoutException) {
            throw LlmProviderException(
                category = ErrorCategory.TIMEOUT,
                code = "REQUEST_TIMEOUT",
                message = "Anthropic API request timed out after ${effectiveTimeout}ms"
            )
        } catch (e: java.net.ConnectException) {
            throw LlmProviderException(
                category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                code = "CONNECTION_FAILED",
                message = "Failed to connect to Anthropic API: ${e.message}"
            )
        } catch (e: Exception) {
            throw LlmProviderException(
                category = ErrorCategory.IO_ERROR,
                code = "REQUEST_FAILED",
                message = "Anthropic API request failed: ${e.message}"
            )
        }

        // Parse response
        return parseResponse(response)
    }

    /**
     * API 응답 파싱
     */
    private fun parseResponse(response: HttpResponse<String>): LlmResponse {
        val body = response.body()

        // Check for HTTP errors
        if (response.statusCode() != 200) {
            val errorMessage = try {
                val json = Json.parseToJsonElement(body).jsonObject
                json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    ?: "Unknown error"
            } catch (e: Exception) {
                body.take(200)
            }

            val category = when (response.statusCode()) {
                401 -> ErrorCategory.PERMISSION_DENIED
                429 -> ErrorCategory.EXTERNAL_SERVICE_ERROR  // Rate limit
                500, 502, 503, 504, 529 -> ErrorCategory.EXTERNAL_SERVICE_ERROR  // 529 is Anthropic overload
                else -> ErrorCategory.EXTERNAL_SERVICE_ERROR
            }

            val code = when (response.statusCode()) {
                401 -> "UNAUTHORIZED"
                429 -> "RATE_LIMIT"
                529 -> "OVERLOADED"
                else -> "API_ERROR_${response.statusCode()}"
            }

            throw LlmProviderException(
                category = category,
                code = code,
                message = "Anthropic API error (${response.statusCode()}): $errorMessage"
            )
        }

        // Parse successful response
        val json = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw LlmProviderException(
                category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                code = "INVALID_RESPONSE",
                message = "Failed to parse Anthropic response: ${e.message}"
            )
        }

        // Extract content from Anthropic format
        val content = json["content"]?.jsonArray
            ?.mapNotNull { block ->
                val blockObj = block.jsonObject
                if (blockObj["type"]?.jsonPrimitive?.content == "text") {
                    blockObj["text"]?.jsonPrimitive?.content
                } else null
            }
            ?.joinToString("") ?: ""

        val stopReason = json["stop_reason"]?.jsonPrimitive?.content ?: "unknown"

        // Parse usage
        val usage = json["usage"]?.jsonObject
        val inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.int ?: 0
        val outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.int ?: 0

        return LlmResponse(
            content = content,
            finishReason = stopReason,
            usage = LlmUsage.of(inputTokens, outputTokens)
        )
    }

    override suspend fun cancel(executionId: String): Boolean {
        // Anthropic doesn't support request cancellation
        return true
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com/v1"
        const val DEFAULT_MODEL = "claude-sonnet-4-20250514"
        const val DEFAULT_MAX_TOKENS = 4096
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val ANTHROPIC_VERSION = "2023-06-01"

        private val DEFAULT_HTTP_CLIENT: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        /**
         * 환경 변수에서 Provider 생성
         */
        fun fromEnv(
            model: String = DEFAULT_MODEL,
            maxTokens: Int = DEFAULT_MAX_TOKENS
        ): AnthropicProvider {
            return AnthropicProvider(
                defaultModel = model,
                defaultMaxTokens = maxTokens
            )
        }
    }
}

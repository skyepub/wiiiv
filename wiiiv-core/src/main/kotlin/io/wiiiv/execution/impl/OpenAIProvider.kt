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
 * OpenAI Provider - OpenAI API 연동
 *
 * ## 지원 모델
 *
 * - gpt-4o (기본)
 * - gpt-4o-mini
 * - gpt-4-turbo
 * - gpt-3.5-turbo
 *
 * ## 환경 변수
 *
 * - OPENAI_API_KEY: API 키 (필수)
 * - OPENAI_BASE_URL: 커스텀 엔드포인트 (선택, Azure 등)
 *
 * ## Executor 원칙 준수
 *
 * - 판단하지 않는다: API 응답의 의미를 해석하지 않음
 * - 결과를 그대로 반환: LLM은 도구로만 사용
 */
class OpenAIProvider(
    private val apiKey: String = System.getenv("OPENAI_API_KEY") ?: "",
    private val baseUrl: String = System.getenv("OPENAI_BASE_URL") ?: DEFAULT_BASE_URL,
    override val defaultModel: String = DEFAULT_MODEL,
    override val defaultMaxTokens: Int = DEFAULT_MAX_TOKENS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val httpClient: HttpClient = DEFAULT_HTTP_CLIENT
) : LlmProvider {

    init {
        if (apiKey.isBlank()) {
            // API 키가 없어도 생성은 가능 (테스트용)
            // 실제 호출 시 오류 발생
        }
    }

    override suspend fun call(request: LlmRequest): LlmResponse {
        if (apiKey.isBlank()) {
            throw LlmProviderException(
                category = ErrorCategory.CONTRACT_VIOLATION,
                code = "API_KEY_MISSING",
                message = "OPENAI_API_KEY environment variable is not set"
            )
        }

        val model = request.model.ifBlank { defaultModel }
        val maxTokens = if (request.maxTokens > 0) request.maxTokens else defaultMaxTokens

        // Build request body
        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("max_tokens", JsonPrimitive(maxTokens))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", JsonPrimitive("user"))
                    if (request.images.isEmpty()) {
                        put("content", JsonPrimitive(request.prompt))
                    } else {
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(request.prompt))
                            }
                            for (image in request.images) {
                                addJsonObject {
                                    put("type", JsonPrimitive("image_url"))
                                    putJsonObject("image_url") {
                                        val base64 = java.util.Base64.getEncoder().encodeToString(image.data)
                                        put("url", JsonPrimitive("data:${image.mimeType};base64,$base64"))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Optional parameters from request.params
            request.params["temperature"]?.let {
                put("temperature", JsonPrimitive(it.toDoubleOrNull() ?: 0.7))
            }
            request.params["top_p"]?.let {
                put("top_p", JsonPrimitive(it.toDoubleOrNull() ?: 1.0))
            }
        }

        // Build HTTP request
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofMillis(timeoutMs))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()

        // Execute request
        val response = try {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.http.HttpTimeoutException) {
            throw LlmProviderException(
                category = ErrorCategory.TIMEOUT,
                code = "REQUEST_TIMEOUT",
                message = "OpenAI API request timed out after ${timeoutMs}ms"
            )
        } catch (e: java.net.ConnectException) {
            throw LlmProviderException(
                category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                code = "CONNECTION_FAILED",
                message = "Failed to connect to OpenAI API: ${e.message}"
            )
        } catch (e: Exception) {
            throw LlmProviderException(
                category = ErrorCategory.IO_ERROR,
                code = "REQUEST_FAILED",
                message = "OpenAI API request failed: ${e.message}"
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
                500, 502, 503, 504 -> ErrorCategory.EXTERNAL_SERVICE_ERROR
                else -> ErrorCategory.EXTERNAL_SERVICE_ERROR
            }

            val code = when (response.statusCode()) {
                401 -> "UNAUTHORIZED"
                429 -> "RATE_LIMIT"
                else -> "API_ERROR_${response.statusCode()}"
            }

            throw LlmProviderException(
                category = category,
                code = code,
                message = "OpenAI API error (${response.statusCode()}): $errorMessage"
            )
        }

        // Parse successful response
        val json = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw LlmProviderException(
                category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                code = "INVALID_RESPONSE",
                message = "Failed to parse OpenAI response: ${e.message}"
            )
        }

        val choices = json["choices"]?.jsonArray
            ?: throw LlmProviderException(
                category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                code = "INVALID_RESPONSE",
                message = "OpenAI response missing 'choices' field"
            )

        if (choices.isEmpty()) {
            throw LlmProviderException(
                category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                code = "EMPTY_RESPONSE",
                message = "OpenAI returned empty choices"
            )
        }

        val choice = choices[0].jsonObject
        val message = choice["message"]?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.content ?: ""
        val finishReason = choice["finish_reason"]?.jsonPrimitive?.content ?: "unknown"

        // Parse usage
        val usage = json["usage"]?.jsonObject
        val promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.int ?: 0
        val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.int ?: 0

        return LlmResponse(
            content = content,
            finishReason = finishReason,
            usage = LlmUsage.of(promptTokens, completionTokens)
        )
    }

    override suspend fun cancel(executionId: String): Boolean {
        // OpenAI doesn't support request cancellation
        return true
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o"
        const val DEFAULT_MAX_TOKENS = 4096
        const val DEFAULT_TIMEOUT_MS = 60_000L

        private val DEFAULT_HTTP_CLIENT: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        /**
         * 환경 변수에서 Provider 생성
         */
        fun fromEnv(
            model: String = DEFAULT_MODEL,
            maxTokens: Int = DEFAULT_MAX_TOKENS
        ): OpenAIProvider {
            return OpenAIProvider(
                defaultModel = model,
                defaultMaxTokens = maxTokens
            )
        }
    }
}

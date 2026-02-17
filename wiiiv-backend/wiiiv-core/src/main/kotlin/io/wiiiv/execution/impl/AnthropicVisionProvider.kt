package io.wiiiv.execution.impl

import io.wiiiv.execution.MultimodalAction
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Anthropic Vision Provider - Claude Vision API 연동
 *
 * Claude 3 모델을 사용한 이미지 분석
 *
 * ## 지원 기능
 *
 * - ANALYZE_IMAGE: 이미지 설명/분석
 * - EXTRACT_TEXT: OCR
 * - VISION_QA: 이미지 기반 질의응답
 *
 * ## 환경 변수
 *
 * - ANTHROPIC_API_KEY: API 키 (필수)
 *
 * ## 지원 이미지 형식
 *
 * - PNG, JPEG, GIF, WebP (base64 인코딩)
 */
class AnthropicVisionProvider(
    private val apiKey: String = System.getenv("ANTHROPIC_API_KEY") ?: "",
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val httpClient: HttpClient = DEFAULT_HTTP_CLIENT
) : MultimodalProvider {

    override val id: String = "anthropic-vision"

    override fun supports(action: MultimodalAction): Boolean {
        return action in SUPPORTED_ACTIONS
    }

    override fun analyzeImage(
        imageData: ByteArray,
        mimeType: String,
        prompt: String?,
        timeoutMs: Long
    ): MultimodalResponse {
        validateApiKey()
        validateImageType(mimeType)

        val systemPrompt = prompt ?: "Describe this image in detail."
        return callClaudeVision(imageData, mimeType, systemPrompt, timeoutMs)
    }

    override fun extractText(
        imageData: ByteArray,
        mimeType: String,
        timeoutMs: Long
    ): MultimodalResponse {
        validateApiKey()
        validateImageType(mimeType)

        val prompt = "Extract all text from this image. Return only the extracted text, preserving the original formatting as much as possible."
        return callClaudeVision(imageData, mimeType, prompt, timeoutMs)
    }

    override fun parseDocument(
        documentData: ByteArray,
        mimeType: String,
        outputFormat: String,
        timeoutMs: Long
    ): MultimodalResponse {
        if (mimeType == "application/pdf") {
            throw MultimodalException(
                code = "UNSUPPORTED_FORMAT",
                message = "PDF parsing requires conversion to images first. Use image formats (PNG, JPEG)."
            )
        }

        validateApiKey()
        validateImageType(mimeType)

        val prompt = when (outputFormat.lowercase()) {
            "json" -> "Parse this document and extract all structured data. Return as JSON."
            "markdown" -> "Parse this document and convert to Markdown format, preserving structure."
            else -> "Parse this document and extract the text content."
        }

        return callClaudeVision(documentData, mimeType, prompt, timeoutMs)
    }

    override fun transcribeAudio(
        audioData: ByteArray,
        mimeType: String,
        timeoutMs: Long
    ): MultimodalResponse {
        throw MultimodalException(
            code = "UNSUPPORTED_ACTION",
            message = "Audio transcription not supported by Anthropic Vision."
        )
    }

    override fun visionQA(
        imageData: ByteArray,
        mimeType: String,
        question: String,
        timeoutMs: Long
    ): MultimodalResponse {
        validateApiKey()
        validateImageType(mimeType)

        return callClaudeVision(imageData, mimeType, question, timeoutMs)
    }

    private fun callClaudeVision(
        imageData: ByteArray,
        mimeType: String,
        prompt: String,
        timeoutMs: Long
    ): MultimodalResponse {
        val base64Image = Base64.getEncoder().encodeToString(imageData)

        // Claude API format for vision
        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("max_tokens", JsonPrimitive(4096))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", JsonPrimitive("user"))
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", JsonPrimitive("image"))
                            putJsonObject("source") {
                                put("type", JsonPrimitive("base64"))
                                put("media_type", JsonPrimitive(mimeType))
                                put("data", JsonPrimitive(base64Image))
                            }
                        }
                        addJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(prompt))
                        }
                    }
                }
            }
        }

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .timeout(Duration.ofMillis(timeoutMs))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()

        val response = try {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.http.HttpTimeoutException) {
            throw MultimodalException(
                code = "TIMEOUT",
                message = "Anthropic Vision API request timed out after ${timeoutMs}ms",
                isTimeout = true
            )
        } catch (e: Exception) {
            throw MultimodalException(
                code = "REQUEST_FAILED",
                message = "Anthropic Vision API request failed: ${e.message}"
            )
        }

        return parseResponse(response)
    }

    private fun parseResponse(response: HttpResponse<String>): MultimodalResponse {
        val body = response.body()

        if (response.statusCode() != 200) {
            val errorMessage = try {
                val json = Json.parseToJsonElement(body).jsonObject
                json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
            } catch (e: Exception) {
                body.take(200)
            }

            throw MultimodalException(
                code = "API_ERROR_${response.statusCode()}",
                message = "Anthropic Vision API error (${response.statusCode()}): $errorMessage"
            )
        }

        val json = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw MultimodalException(
                code = "INVALID_RESPONSE",
                message = "Failed to parse Anthropic Vision response: ${e.message}"
            )
        }

        // Parse content array
        val contentArray = json["content"]?.jsonArray
            ?: throw MultimodalException(
                code = "INVALID_RESPONSE",
                message = "Anthropic Vision response missing 'content' field"
            )

        // Extract text from content blocks
        val textContent = contentArray
            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            .joinToString("\n")

        // Parse usage
        val usage = json["usage"]?.jsonObject
        val inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.int ?: 0
        val outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.int ?: 0

        return MultimodalResponse(
            text = textContent,
            metadata = mapOf(
                "model" to model,
                "inputTokens" to inputTokens.toString(),
                "outputTokens" to outputTokens.toString(),
                "totalTokens" to (inputTokens + outputTokens).toString(),
                "stopReason" to (json["stop_reason"]?.jsonPrimitive?.content ?: "unknown")
            )
        )
    }

    private fun validateApiKey() {
        if (apiKey.isBlank()) {
            throw MultimodalException(
                code = "API_KEY_MISSING",
                message = "ANTHROPIC_API_KEY environment variable is not set"
            )
        }
    }

    private fun validateImageType(mimeType: String) {
        if (mimeType !in SUPPORTED_IMAGE_TYPES) {
            throw MultimodalException(
                code = "UNSUPPORTED_FORMAT",
                message = "Unsupported image format: $mimeType. Supported: ${SUPPORTED_IMAGE_TYPES.joinToString()}"
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com/v1"
        const val DEFAULT_MODEL = "claude-sonnet-4-20250514"
        const val ANTHROPIC_VERSION = "2023-06-01"

        private val DEFAULT_HTTP_CLIENT: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        private val SUPPORTED_ACTIONS = setOf(
            MultimodalAction.ANALYZE_IMAGE,
            MultimodalAction.EXTRACT_TEXT,
            MultimodalAction.PARSE_DOCUMENT,
            MultimodalAction.VISION_QA
        )

        private val SUPPORTED_IMAGE_TYPES = setOf(
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp"
        )

        fun fromEnv(model: String = DEFAULT_MODEL): AnthropicVisionProvider {
            return AnthropicVisionProvider(model = model)
        }
    }
}

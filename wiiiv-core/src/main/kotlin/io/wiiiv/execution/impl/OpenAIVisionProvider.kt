package io.wiiiv.execution.impl

import io.wiiiv.execution.ErrorCategory
import io.wiiiv.execution.MultimodalAction
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * OpenAI Vision Provider - OpenAI Vision API 연동
 *
 * GPT-4 Vision (gpt-4o, gpt-4-turbo-vision) 모델을 사용한 이미지 분석
 *
 * ## 지원 기능
 *
 * - ANALYZE_IMAGE: 이미지 설명/분석
 * - EXTRACT_TEXT: OCR (GPT-4V 내장 OCR)
 * - VISION_QA: 이미지 기반 질의응답
 *
 * ## 환경 변수
 *
 * - OPENAI_API_KEY: API 키 (필수)
 *
 * ## 지원 이미지 형식
 *
 * - PNG, JPEG, GIF, WebP (최대 20MB)
 */
class OpenAIVisionProvider(
    private val apiKey: String = System.getenv("OPENAI_API_KEY") ?: "",
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val httpClient: HttpClient = DEFAULT_HTTP_CLIENT
) : MultimodalProvider {

    override val id: String = "openai-vision"

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
        return callVisionApi(imageData, mimeType, systemPrompt, timeoutMs)
    }

    override fun extractText(
        imageData: ByteArray,
        mimeType: String,
        timeoutMs: Long
    ): MultimodalResponse {
        validateApiKey()
        validateImageType(mimeType)

        val prompt = "Extract all text from this image. Return only the extracted text, preserving the original formatting as much as possible."
        return callVisionApi(imageData, mimeType, prompt, timeoutMs)
    }

    override fun parseDocument(
        documentData: ByteArray,
        mimeType: String,
        outputFormat: String,
        timeoutMs: Long
    ): MultimodalResponse {
        // GPT-4V doesn't directly support PDF, but can process document images
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

        return callVisionApi(documentData, mimeType, prompt, timeoutMs)
    }

    override fun transcribeAudio(
        audioData: ByteArray,
        mimeType: String,
        timeoutMs: Long
    ): MultimodalResponse {
        // GPT-4V doesn't support audio - use Whisper API instead
        throw MultimodalException(
            code = "UNSUPPORTED_ACTION",
            message = "Audio transcription not supported by OpenAI Vision. Use OpenAI Whisper API."
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

        return callVisionApi(imageData, mimeType, question, timeoutMs)
    }

    private fun callVisionApi(
        imageData: ByteArray,
        mimeType: String,
        prompt: String,
        timeoutMs: Long
    ): MultimodalResponse {
        val base64Image = Base64.getEncoder().encodeToString(imageData)

        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("max_tokens", JsonPrimitive(4096))
            putJsonArray("messages") {
                addJsonObject {
                    put("role", JsonPrimitive("user"))
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(prompt))
                        }
                        addJsonObject {
                            put("type", JsonPrimitive("image_url"))
                            putJsonObject("image_url") {
                                put("url", JsonPrimitive("data:$mimeType;base64,$base64Image"))
                            }
                        }
                    }
                }
            }
        }

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofMillis(timeoutMs))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build()

        val response = try {
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.http.HttpTimeoutException) {
            throw MultimodalException(
                code = "TIMEOUT",
                message = "OpenAI Vision API request timed out after ${timeoutMs}ms",
                isTimeout = true
            )
        } catch (e: Exception) {
            throw MultimodalException(
                code = "REQUEST_FAILED",
                message = "OpenAI Vision API request failed: ${e.message}"
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
                message = "OpenAI Vision API error (${response.statusCode()}): $errorMessage"
            )
        }

        val json = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw MultimodalException(
                code = "INVALID_RESPONSE",
                message = "Failed to parse OpenAI Vision response: ${e.message}"
            )
        }

        val choices = json["choices"]?.jsonArray
            ?: throw MultimodalException(
                code = "INVALID_RESPONSE",
                message = "OpenAI Vision response missing 'choices' field"
            )

        if (choices.isEmpty()) {
            throw MultimodalException(
                code = "EMPTY_RESPONSE",
                message = "OpenAI Vision returned empty choices"
            )
        }

        val choice = choices[0].jsonObject
        val message = choice["message"]?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.content ?: ""

        val usage = json["usage"]?.jsonObject
        val promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.int ?: 0
        val completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.int ?: 0

        return MultimodalResponse(
            text = content,
            metadata = mapOf(
                "model" to model,
                "promptTokens" to promptTokens.toString(),
                "completionTokens" to completionTokens.toString(),
                "totalTokens" to (promptTokens + completionTokens).toString()
            )
        )
    }

    private fun validateApiKey() {
        if (apiKey.isBlank()) {
            throw MultimodalException(
                code = "API_KEY_MISSING",
                message = "OPENAI_API_KEY environment variable is not set"
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
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o"

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

        fun fromEnv(model: String = DEFAULT_MODEL): OpenAIVisionProvider {
            return OpenAIVisionProvider(model = model)
        }
    }
}

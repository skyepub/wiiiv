package io.wiiiv.testutil

import io.wiiiv.execution.*
import io.wiiiv.execution.impl.*
import io.wiiiv.rag.embedding.*
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

// ==================== Embedding Providers ====================

/**
 * 테스트용 Embedding Provider
 *
 * 실제 API 호출 없이 결정론적 벡터를 생성한다.
 * 동일 텍스트 → 동일 벡터 (해시 기반 시드)
 */
class TestEmbeddingProvider(
    private val dimension: Int = 1536,
    override val defaultModel: String = "test-embedding-v1"
) : EmbeddingProvider {

    override val providerId: String = "test"

    override val supportedModels: List<String> = listOf(
        "test-embedding-v1",
        "test-embedding-small",
        "test-embedding-large"
    )

    override fun getDimension(model: String?): Int = dimension

    override suspend fun embed(text: String, model: String?): Embedding {
        val vector = generateDeterministicVector(text)
        return Embedding(
            id = UUID.randomUUID().toString(),
            vector = vector,
            text = text,
            metadata = mapOf("model" to (model ?: defaultModel), "provider" to "test")
        )
    }

    override suspend fun embedBatch(texts: List<String>, model: String?): EmbeddingResponse {
        val embeddings = texts.map { text ->
            Embedding(
                id = UUID.randomUUID().toString(),
                vector = generateDeterministicVector(text),
                text = text,
                metadata = mapOf("model" to (model ?: defaultModel), "provider" to "test")
            )
        }

        return EmbeddingResponse(
            embeddings = embeddings,
            model = model ?: defaultModel,
            usage = TokenUsage(
                promptTokens = texts.sumOf { it.length / 4 },
                totalTokens = texts.sumOf { it.length / 4 }
            )
        )
    }

    override suspend fun isAvailable(): Boolean = true

    private fun generateDeterministicVector(text: String): FloatArray {
        val seed = text.hashCode().toLong()
        val random = Random(seed)
        val vector = FloatArray(dimension) { random.nextFloat() * 2 - 1 }
        return CosineSimilarity.normalize(vector)
    }
}

/**
 * 유사도 인식 테스트용 Provider
 *
 * 유사한 단어를 포함한 텍스트는 유사한 벡터를 생성한다.
 */
class SimilarityAwareTestProvider(
    private val dimension: Int = 1536,
    override val defaultModel: String = "similarity-aware-test-v1"
) : EmbeddingProvider {

    override val providerId: String = "similarity-test"

    override val supportedModels: List<String> = listOf(defaultModel)

    override fun getDimension(model: String?): Int = dimension

    private val wordPositions = mutableMapOf<String, Int>()
    private var nextPosition = 0

    override suspend fun embed(text: String, model: String?): Embedding {
        val vector = generateSimilarityAwareVector(text)
        return Embedding(
            id = UUID.randomUUID().toString(),
            vector = vector,
            text = text,
            metadata = mapOf("model" to (model ?: defaultModel), "provider" to "similarity-test")
        )
    }

    override suspend fun embedBatch(texts: List<String>, model: String?): EmbeddingResponse {
        val embeddings = texts.map { text ->
            Embedding(
                id = UUID.randomUUID().toString(),
                vector = generateSimilarityAwareVector(text),
                text = text,
                metadata = mapOf("model" to (model ?: defaultModel))
            )
        }

        return EmbeddingResponse(
            embeddings = embeddings,
            model = model ?: defaultModel,
            usage = null
        )
    }

    override suspend fun isAvailable(): Boolean = true

    private fun generateSimilarityAwareVector(text: String): FloatArray {
        val words = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val vector = FloatArray(dimension) { 0f }

        for (word in words) {
            val position = wordPositions.getOrPut(word) {
                (nextPosition++ % dimension)
            }
            vector[position] += 1f
            if (position > 0) vector[position - 1] += 0.3f
            if (position < dimension - 1) vector[position + 1] += 0.3f
        }

        val random = Random(text.hashCode().toLong())
        for (i in vector.indices) {
            vector[i] += random.nextFloat() * 0.1f
        }

        return CosineSimilarity.normalize(vector)
    }
}

// ==================== LLM Providers ====================

/**
 * 테스트용 LLM Provider - 설정된 응답을 반환
 */
class TestLlmProvider(
    override val defaultModel: String = "test-model",
    override val defaultMaxTokens: Int = 1000
) : LlmProvider {

    private var testResponse: LlmResponse = LlmResponse(
        content = "Test response",
        finishReason = "stop",
        usage = LlmUsage.of(10, 20)
    )

    private var shouldFail: Boolean = false
    private var failureError: LlmProviderException? = null

    private val callHistory = mutableListOf<LlmRequest>()

    fun setResponse(response: LlmResponse) {
        testResponse = response
        shouldFail = false
    }

    fun setResponse(content: String, finishReason: String = "stop") {
        testResponse = LlmResponse(
            content = content,
            finishReason = finishReason,
            usage = LlmUsage.of(content.length / 4, content.length / 4)
        )
        shouldFail = false
    }

    fun setFailure(category: ErrorCategory, code: String, message: String) {
        shouldFail = true
        failureError = LlmProviderException(category, code, message)
    }

    fun getCallHistory(): List<LlmRequest> = callHistory.toList()

    fun getLastCall(): LlmRequest? = callHistory.lastOrNull()

    fun clearHistory() {
        callHistory.clear()
    }

    override suspend fun call(request: LlmRequest): LlmResponse {
        callHistory.add(request)

        if (shouldFail) {
            throw failureError ?: LlmProviderException(
                category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                code = "TEST_FAILURE",
                message = "Test failure"
            )
        }

        return testResponse
    }

    override suspend fun cancel(executionId: String): Boolean = true
}

/**
 * 테스트용 Echo LLM Provider - 프롬프트를 그대로 응답으로 반환
 */
class EchoTestLlmProvider(
    override val defaultModel: String = "echo-model",
    override val defaultMaxTokens: Int = 1000
) : LlmProvider {

    override suspend fun call(request: LlmRequest): LlmResponse {
        val content = when (request.action) {
            LlmAction.COMPLETE -> "[COMPLETE] ${request.prompt}"
            LlmAction.ANALYZE -> "[ANALYZE] ${request.prompt}"
            LlmAction.SUMMARIZE -> "[SUMMARIZE] ${request.prompt}"
        }

        return LlmResponse(
            content = content,
            finishReason = "stop",
            usage = LlmUsage.of(request.prompt.length / 4, content.length / 4)
        )
    }

    override suspend fun cancel(executionId: String): Boolean = true
}

/**
 * 테스트용 Counting LLM Provider - 호출 횟수 추적, 특정 횟수에 실패 가능
 */
class CountingTestLlmProvider(
    override val defaultModel: String = "counting-model",
    override val defaultMaxTokens: Int = 1000,
    private val failOnCall: Int = -1,
    private val retryable: Boolean = false
) : LlmProvider {

    var callCount: Int = 0
        private set

    override suspend fun call(request: LlmRequest): LlmResponse {
        callCount++

        if (callCount == failOnCall) {
            throw LlmProviderException(
                category = if (retryable) ErrorCategory.EXTERNAL_SERVICE_ERROR else ErrorCategory.CONTRACT_VIOLATION,
                code = "PROVIDER_ERROR",
                message = "Provider failed on call $callCount"
            )
        }

        return LlmResponse(
            content = "Response $callCount",
            finishReason = "stop",
            usage = LlmUsage.of(10, 20)
        )
    }

    override suspend fun cancel(executionId: String): Boolean = true
}

// ==================== Multimodal Provider ====================

/**
 * 테스트용 Multimodal Provider
 */
class TestMultimodalProvider(
    override val id: String = "test"
) : MultimodalProvider {
    private val supportedActions = mutableSetOf(
        MultimodalAction.ANALYZE_IMAGE,
        MultimodalAction.EXTRACT_TEXT,
        MultimodalAction.PARSE_DOCUMENT,
        MultimodalAction.TRANSCRIBE_AUDIO,
        MultimodalAction.VISION_QA
    )

    private val responses = ConcurrentHashMap<MultimodalAction, MultimodalResponse>()

    override fun supports(action: MultimodalAction): Boolean = action in supportedActions

    fun setSupports(vararg actions: MultimodalAction) {
        supportedActions.clear()
        supportedActions.addAll(actions)
    }

    fun setResponse(action: MultimodalAction, response: MultimodalResponse) {
        responses[action] = response
    }

    override fun analyzeImage(imageData: ByteArray, mimeType: String, prompt: String?, timeoutMs: Long): MultimodalResponse {
        return responses[MultimodalAction.ANALYZE_IMAGE]
            ?: MultimodalResponse(text = "Test image analysis: ${imageData.size} bytes, type: $mimeType", confidence = 0.95, metadata = mapOf("test" to "true", "size" to imageData.size.toString()))
    }

    override fun extractText(imageData: ByteArray, mimeType: String, timeoutMs: Long): MultimodalResponse {
        return responses[MultimodalAction.EXTRACT_TEXT]
            ?: MultimodalResponse(text = "Test extracted text from image", confidence = 0.90, metadata = mapOf("test" to "true"))
    }

    override fun parseDocument(documentData: ByteArray, mimeType: String, outputFormat: String, timeoutMs: Long): MultimodalResponse {
        return responses[MultimodalAction.PARSE_DOCUMENT]
            ?: MultimodalResponse(text = "Test document content", metadata = mapOf("test" to "true", "pageCount" to "1", "format" to outputFormat))
    }

    override fun transcribeAudio(audioData: ByteArray, mimeType: String, timeoutMs: Long): MultimodalResponse {
        return responses[MultimodalAction.TRANSCRIBE_AUDIO]
            ?: MultimodalResponse(text = "Test audio transcript", metadata = mapOf("test" to "true", "durationMs" to "1000"))
    }

    override fun visionQA(imageData: ByteArray, mimeType: String, question: String, timeoutMs: Long): MultimodalResponse {
        return responses[MultimodalAction.VISION_QA]
            ?: MultimodalResponse(text = "Test answer to: $question", metadata = mapOf("test" to "true"))
    }

    fun clear() {
        responses.clear()
        supportedActions.clear()
        supportedActions.addAll(MultimodalAction.entries)
    }
}

// ==================== gRPC Provider ====================

/**
 * 테스트용 gRPC Provider
 */
class TestGrpcProvider(
    override val targetPattern: String = ".*"
) : GrpcProvider {
    private val responses = ConcurrentHashMap<String, GrpcResponse>()
    private val streamResponses = ConcurrentHashMap<String, List<String>>()

    fun setResponse(service: String, method: String, response: GrpcResponse) {
        responses["$service/$method"] = response
    }

    fun setStreamResponses(service: String, method: String, responses: List<String>) {
        streamResponses["$service/$method"] = responses
    }

    override fun callUnary(service: String, method: String, request: String, metadata: Map<String, String>, timeoutMs: Long): GrpcResponse {
        return responses["$service/$method"]
            ?: GrpcResponse(statusCode = 0, statusMessage = "OK", body = "test response for $service/$method")
    }

    override fun callServerStreaming(service: String, method: String, request: String, metadata: Map<String, String>, timeoutMs: Long): List<String> {
        return streamResponses["$service/$method"]
            ?: listOf("stream response 1", "stream response 2")
    }

    override fun callClientStreaming(service: String, method: String, requests: List<String>, metadata: Map<String, String>, timeoutMs: Long): GrpcResponse {
        return responses["$service/$method"]
            ?: GrpcResponse(statusCode = 0, statusMessage = "OK", body = "received ${requests.size} messages")
    }

    override fun callBidirectionalStreaming(service: String, method: String, requests: List<String>, metadata: Map<String, String>, timeoutMs: Long): List<String> {
        return streamResponses["$service/$method"]
            ?: requests.map { "echo: $it" }
    }

    fun clear() {
        responses.clear()
        streamResponses.clear()
    }
}

// ==================== DB Connection Provider ====================

/**
 * 테스트용 DB Connection Provider
 */
class TestConnectionProvider(
    private val testConnection: Connection? = null
) : DbConnectionProvider {

    private var shouldFail = false
    private var failureMessage = "Test connection failure"

    fun setFailure(message: String) {
        shouldFail = true
        failureMessage = message
    }

    fun clearFailure() {
        shouldFail = false
    }

    override fun getConnection(connectionId: String?): Connection {
        if (shouldFail) {
            throw SQLException(failureMessage)
        }
        return testConnection ?: throw SQLException("No test connection provided")
    }

    override fun releaseConnection(connection: Connection) {
        // Test - do nothing
    }

    override fun close() {
        testConnection?.close()
    }
}

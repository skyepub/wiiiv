package io.wiiiv.execution.impl

import io.wiiiv.execution.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Multimodal Executor - 멀티모달 데이터 처리 Executor
 *
 * Canonical: Executor 정의서 v1.0, Executor Interface Spec v1.0
 *
 * ## Executor 원칙 준수
 *
 * - 판단하지 않는다: 입력이 안전한지 판단하지 않음
 * - 해석하지 않는다: 결과의 의미를 해석하지 않음
 * - Blueprint를 신뢰한다: 정합성과 합법성이 이미 검증되었다고 가정
 *
 * ## 지원 기능
 *
 * - ANALYZE_IMAGE: 이미지 분석 (설명, 객체 탐지)
 * - EXTRACT_TEXT: OCR (이미지에서 텍스트 추출)
 * - PARSE_DOCUMENT: 문서 파싱 (PDF, DOCX 등)
 * - TRANSCRIBE_AUDIO: 오디오 전사
 * - VISION_QA: 비전 기반 질의응답
 *
 * ## Provider 패턴
 *
 * MultimodalProvider 인터페이스를 통해 다양한 구현 지원:
 * - MockMultimodalProvider (테스트용)
 * - 향후: OpenAI Vision, Claude Vision, Tesseract 등
 */
class MultimodalExecutor(
    private val providerRegistry: MultimodalProviderRegistry = DefaultMultimodalProviderRegistry()
) : Executor {

    override suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        if (step !is ExecutionStep.MultimodalStep) {
            return ExecutionResult.contractViolation(
                stepId = step.stepId,
                code = "INVALID_STEP_TYPE",
                message = "MultimodalExecutor can only handle MultimodalStep, got: ${step::class.simpleName}"
            )
        }

        val startedAt = Instant.now()

        return try {
            val provider = providerRegistry.getProvider(step.providerId)
                ?: return ExecutionResult.Failure(
                    error = ExecutionError(
                        category = ErrorCategory.CONTRACT_VIOLATION,
                        code = "NO_PROVIDER",
                        message = "No multimodal provider found for: ${step.providerId ?: "default"}"
                    ),
                    meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = Instant.now(),
                        resourceRefs = listOf(step.inputPath)
                    )
                )

            // Validate input file exists
            val inputFile = File(step.inputPath)
            if (!inputFile.exists()) {
                return ExecutionResult.Failure(
                    error = ExecutionError(
                        category = ErrorCategory.RESOURCE_NOT_FOUND,
                        code = "INPUT_NOT_FOUND",
                        message = "Input file not found: ${step.inputPath}"
                    ),
                    meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = Instant.now(),
                        resourceRefs = listOf(step.inputPath)
                    )
                )
            }

            val result = executeMultimodal(step, provider, inputFile)

            val endedAt = Instant.now()

            when (result) {
                is MultimodalResult.Success -> {
                    val output = result.output
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.inputPath)
                    )
                    context.addStepOutput(step.stepId, output)
                    ExecutionResult.Success(output = output, meta = meta)
                }
                is MultimodalResult.Error -> {
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.inputPath)
                    )
                    ExecutionResult.Failure(error = result.error, meta = meta)
                }
            }
        } catch (e: Exception) {
            ExecutionResult.Failure(
                error = ExecutionError.unknown("Unexpected error: ${e.message}"),
                meta = ExecutionMeta.of(
                    stepId = step.stepId,
                    startedAt = startedAt,
                    endedAt = Instant.now(),
                    resourceRefs = listOf(step.inputPath)
                )
            )
        }
    }

    private fun executeMultimodal(
        step: ExecutionStep.MultimodalStep,
        provider: MultimodalProvider,
        inputFile: File
    ): MultimodalResult {
        // Check if provider supports this action
        if (!provider.supports(step.action)) {
            return MultimodalResult.Error(
                ExecutionError(
                    category = ErrorCategory.CONTRACT_VIOLATION,
                    code = "UNSUPPORTED_ACTION",
                    message = "Provider '${provider.id}' does not support action: ${step.action}"
                )
            )
        }

        return when (step.action) {
            MultimodalAction.ANALYZE_IMAGE -> analyzeImage(step, provider, inputFile)
            MultimodalAction.EXTRACT_TEXT -> extractText(step, provider, inputFile)
            MultimodalAction.PARSE_DOCUMENT -> parseDocument(step, provider, inputFile)
            MultimodalAction.TRANSCRIBE_AUDIO -> transcribeAudio(step, provider, inputFile)
            MultimodalAction.VISION_QA -> visionQA(step, provider, inputFile)
        }
    }

    private fun analyzeImage(
        step: ExecutionStep.MultimodalStep,
        provider: MultimodalProvider,
        inputFile: File
    ): MultimodalResult {
        return try {
            val response = provider.analyzeImage(
                imageData = inputFile.readBytes(),
                mimeType = detectMimeType(inputFile),
                prompt = step.prompt,
                timeoutMs = step.timeoutMs
            )

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("ANALYZE_IMAGE"))
                    put("inputPath", JsonPrimitive(step.inputPath))
                    put("description", JsonPrimitive(response.text))
                    put("confidence", JsonPrimitive(response.confidence ?: 0.0))
                },
                artifacts = buildMap {
                    put("input_path", step.inputPath)
                    put("description", response.text)
                    response.metadata.forEach { (k, v) -> put(k, v) }
                }
            )

            MultimodalResult.Success(output)
        } catch (e: MultimodalException) {
            MultimodalResult.Error(e.toExecutionError())
        }
    }

    private fun extractText(
        step: ExecutionStep.MultimodalStep,
        provider: MultimodalProvider,
        inputFile: File
    ): MultimodalResult {
        return try {
            val response = provider.extractText(
                imageData = inputFile.readBytes(),
                mimeType = detectMimeType(inputFile),
                timeoutMs = step.timeoutMs
            )

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("EXTRACT_TEXT"))
                    put("inputPath", JsonPrimitive(step.inputPath))
                    put("extractedText", JsonPrimitive(response.text))
                    put("confidence", JsonPrimitive(response.confidence ?: 0.0))
                },
                artifacts = buildMap {
                    put("input_path", step.inputPath)
                    put("extracted_text", response.text)
                    response.metadata.forEach { (k, v) -> put(k, v) }
                }
            )

            MultimodalResult.Success(output)
        } catch (e: MultimodalException) {
            MultimodalResult.Error(e.toExecutionError())
        }
    }

    private fun parseDocument(
        step: ExecutionStep.MultimodalStep,
        provider: MultimodalProvider,
        inputFile: File
    ): MultimodalResult {
        return try {
            val response = provider.parseDocument(
                documentData = inputFile.readBytes(),
                mimeType = detectMimeType(inputFile),
                outputFormat = step.outputFormat ?: "text",
                timeoutMs = step.timeoutMs
            )

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("PARSE_DOCUMENT"))
                    put("inputPath", JsonPrimitive(step.inputPath))
                    put("content", JsonPrimitive(response.text))
                    put("pageCount", JsonPrimitive(response.metadata["pageCount"] ?: "0"))
                },
                artifacts = buildMap {
                    put("input_path", step.inputPath)
                    put("content", response.text)
                    response.metadata.forEach { (k, v) -> put(k, v) }
                }
            )

            MultimodalResult.Success(output)
        } catch (e: MultimodalException) {
            MultimodalResult.Error(e.toExecutionError())
        }
    }

    private fun transcribeAudio(
        step: ExecutionStep.MultimodalStep,
        provider: MultimodalProvider,
        inputFile: File
    ): MultimodalResult {
        return try {
            val response = provider.transcribeAudio(
                audioData = inputFile.readBytes(),
                mimeType = detectMimeType(inputFile),
                timeoutMs = step.timeoutMs
            )

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("TRANSCRIBE_AUDIO"))
                    put("inputPath", JsonPrimitive(step.inputPath))
                    put("transcript", JsonPrimitive(response.text))
                    put("durationMs", JsonPrimitive(response.metadata["durationMs"] ?: "0"))
                },
                artifacts = buildMap {
                    put("input_path", step.inputPath)
                    put("transcript", response.text)
                    response.metadata.forEach { (k, v) -> put(k, v) }
                }
            )

            MultimodalResult.Success(output)
        } catch (e: MultimodalException) {
            MultimodalResult.Error(e.toExecutionError())
        }
    }

    private fun visionQA(
        step: ExecutionStep.MultimodalStep,
        provider: MultimodalProvider,
        inputFile: File
    ): MultimodalResult {
        if (step.prompt.isNullOrBlank()) {
            return MultimodalResult.Error(
                ExecutionError(
                    category = ErrorCategory.CONTRACT_VIOLATION,
                    code = "PROMPT_REQUIRED",
                    message = "VISION_QA action requires a prompt"
                )
            )
        }

        return try {
            val response = provider.visionQA(
                imageData = inputFile.readBytes(),
                mimeType = detectMimeType(inputFile),
                question = step.prompt,
                timeoutMs = step.timeoutMs
            )

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("VISION_QA"))
                    put("inputPath", JsonPrimitive(step.inputPath))
                    put("question", JsonPrimitive(step.prompt))
                    put("answer", JsonPrimitive(response.text))
                },
                artifacts = buildMap {
                    put("input_path", step.inputPath)
                    put("question", step.prompt)
                    put("answer", response.text)
                    response.metadata.forEach { (k, v) -> put(k, v) }
                }
            )

            MultimodalResult.Success(output)
        } catch (e: MultimodalException) {
            MultimodalResult.Error(e.toExecutionError())
        }
    }

    private fun detectMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean {
        return true
    }

    override fun canHandle(step: ExecutionStep): Boolean {
        return step is ExecutionStep.MultimodalStep
    }

    private sealed class MultimodalResult {
        data class Success(val output: StepOutput) : MultimodalResult()
        data class Error(val error: ExecutionError) : MultimodalResult()
    }

    companion object {
        val INSTANCE = MultimodalExecutor()
    }
}

/**
 * Multimodal Provider Interface
 */
interface MultimodalProvider {
    val id: String

    /** 지원하는 액션 목록 */
    fun supports(action: MultimodalAction): Boolean

    /** 이미지 분석 */
    fun analyzeImage(
        imageData: ByteArray,
        mimeType: String,
        prompt: String?,
        timeoutMs: Long
    ): MultimodalResponse

    /** 텍스트 추출 (OCR) */
    fun extractText(
        imageData: ByteArray,
        mimeType: String,
        timeoutMs: Long
    ): MultimodalResponse

    /** 문서 파싱 */
    fun parseDocument(
        documentData: ByteArray,
        mimeType: String,
        outputFormat: String,
        timeoutMs: Long
    ): MultimodalResponse

    /** 오디오 전사 */
    fun transcribeAudio(
        audioData: ByteArray,
        mimeType: String,
        timeoutMs: Long
    ): MultimodalResponse

    /** 비전 QA */
    fun visionQA(
        imageData: ByteArray,
        mimeType: String,
        question: String,
        timeoutMs: Long
    ): MultimodalResponse
}

/**
 * Multimodal Provider Registry
 */
interface MultimodalProviderRegistry {
    fun getProvider(providerId: String?): MultimodalProvider?
    fun registerProvider(provider: MultimodalProvider)
}

/**
 * Default Multimodal Provider Registry
 */
class DefaultMultimodalProviderRegistry(
    defaultProvider: MultimodalProvider? = null
) : MultimodalProviderRegistry {
    private val providers = ConcurrentHashMap<String, MultimodalProvider>()
    private var _defaultProvider: MultimodalProvider? = defaultProvider

    init {
        _defaultProvider?.let { providers[it.id] = it }
    }

    override fun getProvider(providerId: String?): MultimodalProvider? {
        return if (providerId == null) _defaultProvider else providers[providerId]
    }

    override fun registerProvider(provider: MultimodalProvider) {
        providers[provider.id] = provider
        if (_defaultProvider == null) {
            _defaultProvider = provider
        }
    }

    fun setDefaultProvider(provider: MultimodalProvider) {
        _defaultProvider = provider
        providers[provider.id] = provider
    }
}

/**
 * Mock Multimodal Provider (테스트용)
 */
class MockMultimodalProvider(
    override val id: String = "mock"
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

    override fun analyzeImage(
        imageData: ByteArray,
        mimeType: String,
        prompt: String?,
        timeoutMs: Long
    ): MultimodalResponse {
        return responses[MultimodalAction.ANALYZE_IMAGE]
            ?: MultimodalResponse(
                text = "Mock image analysis: ${imageData.size} bytes, type: $mimeType",
                confidence = 0.95,
                metadata = mapOf("mock" to "true", "size" to imageData.size.toString())
            )
    }

    override fun extractText(
        imageData: ByteArray,
        mimeType: String,
        timeoutMs: Long
    ): MultimodalResponse {
        return responses[MultimodalAction.EXTRACT_TEXT]
            ?: MultimodalResponse(
                text = "Mock extracted text from image",
                confidence = 0.90,
                metadata = mapOf("mock" to "true")
            )
    }

    override fun parseDocument(
        documentData: ByteArray,
        mimeType: String,
        outputFormat: String,
        timeoutMs: Long
    ): MultimodalResponse {
        return responses[MultimodalAction.PARSE_DOCUMENT]
            ?: MultimodalResponse(
                text = "Mock document content",
                metadata = mapOf("mock" to "true", "pageCount" to "1", "format" to outputFormat)
            )
    }

    override fun transcribeAudio(
        audioData: ByteArray,
        mimeType: String,
        timeoutMs: Long
    ): MultimodalResponse {
        return responses[MultimodalAction.TRANSCRIBE_AUDIO]
            ?: MultimodalResponse(
                text = "Mock audio transcript",
                metadata = mapOf("mock" to "true", "durationMs" to "1000")
            )
    }

    override fun visionQA(
        imageData: ByteArray,
        mimeType: String,
        question: String,
        timeoutMs: Long
    ): MultimodalResponse {
        return responses[MultimodalAction.VISION_QA]
            ?: MultimodalResponse(
                text = "Mock answer to: $question",
                metadata = mapOf("mock" to "true")
            )
    }

    fun clear() {
        responses.clear()
        supportedActions.clear()
        supportedActions.addAll(MultimodalAction.entries)
    }
}

// Response and Exception types
data class MultimodalResponse(
    val text: String,
    val confidence: Double? = null,
    val metadata: Map<String, String> = emptyMap()
)

class MultimodalException(
    val code: String,
    override val message: String,
    val isTimeout: Boolean = false
) : Exception(message) {
    fun toExecutionError(): ExecutionError = ExecutionError(
        category = if (isTimeout) ErrorCategory.TIMEOUT else ErrorCategory.EXTERNAL_SERVICE_ERROR,
        code = code,
        message = message
    )
}

package io.wiiiv.execution

import io.wiiiv.execution.impl.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Multimodal Executor 테스트
 *
 * Canonical: Executor 정의서 v1.0
 *
 * ## Executor 원칙 검증
 *
 * - 판단하지 않는다
 * - 해석하지 않는다
 * - Provider 패턴으로 다양한 멀티모달 구현 지원
 */
class MultimodalExecutorTest {

    private lateinit var executor: MultimodalExecutor
    private lateinit var context: ExecutionContext
    private lateinit var mockProvider: MockMultimodalProvider
    private lateinit var testDir: File
    private lateinit var testImageFile: File
    private lateinit var testDocFile: File
    private lateinit var testAudioFile: File

    @BeforeEach
    fun setup() {
        mockProvider = MockMultimodalProvider()
        val registry = DefaultMultimodalProviderRegistry(mockProvider)
        executor = MultimodalExecutor(registry)
        context = ExecutionContext(
            executionId = "exec-mm-test",
            blueprintId = "bp-mm-test",
            instructionId = "instr-mm-test"
        )

        // Create test files
        testDir = File(System.getProperty("java.io.tmpdir"), "multimodal-test-${System.currentTimeMillis()}")
        testDir.mkdirs()

        testImageFile = File(testDir, "test.png")
        testImageFile.writeBytes(ByteArray(100) { it.toByte() }) // Dummy image data

        testDocFile = File(testDir, "test.pdf")
        testDocFile.writeBytes(ByteArray(200) { it.toByte() }) // Dummy PDF data

        testAudioFile = File(testDir, "test.mp3")
        testAudioFile.writeBytes(ByteArray(150) { it.toByte() }) // Dummy audio data
    }

    @AfterEach
    fun cleanup() {
        testDir.deleteRecursively()
    }

    @Test
    fun `canHandle should return true for MultimodalStep`() {
        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-1",
            action = MultimodalAction.ANALYZE_IMAGE,
            inputPath = testImageFile.absolutePath
        )

        assertTrue(executor.canHandle(step))
    }

    @Test
    fun `canHandle should return false for other steps`() {
        val step = ExecutionStep.NoopStep(stepId = "noop-1")

        assertFalse(executor.canHandle(step))
    }

    @Test
    fun `should return error for invalid step type`() = runTest {
        val step = ExecutionStep.NoopStep(stepId = "noop-1")
        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("INVALID_STEP_TYPE", result.error.code)
    }

    @Test
    fun `should return error when input file not found`() = runTest {
        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-1",
            action = MultimodalAction.ANALYZE_IMAGE,
            inputPath = "/nonexistent/file.png"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.RESOURCE_NOT_FOUND, result.error.category)
        assertEquals("INPUT_NOT_FOUND", result.error.code)
    }

    @Test
    fun `should return error when no provider found`() = runTest {
        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-1",
            action = MultimodalAction.ANALYZE_IMAGE,
            inputPath = testImageFile.absolutePath,
            providerId = "unknown-provider"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("NO_PROVIDER", result.error.code)
    }

    @Test
    fun `should return error for unsupported action`() = runTest {
        mockProvider.setSupports(MultimodalAction.ANALYZE_IMAGE) // Only support ANALYZE_IMAGE

        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-1",
            action = MultimodalAction.TRANSCRIBE_AUDIO,
            inputPath = testAudioFile.absolutePath
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("UNSUPPORTED_ACTION", result.error.code)
    }

    @Test
    fun `should execute ANALYZE_IMAGE successfully`() = runTest {
        mockProvider.setResponse(
            MultimodalAction.ANALYZE_IMAGE,
            MultimodalResponse(
                text = "A beautiful sunset over the ocean",
                confidence = 0.98,
                metadata = mapOf("objects" to "sun,ocean,clouds")
            )
        )

        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-1",
            action = MultimodalAction.ANALYZE_IMAGE,
            inputPath = testImageFile.absolutePath,
            prompt = "Describe this image"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("ANALYZE_IMAGE", result.output.json["action"]?.jsonPrimitive?.content)
        assertEquals("A beautiful sunset over the ocean", result.output.json["description"]?.jsonPrimitive?.content)
        assertEquals("0.98", result.output.json["confidence"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should execute EXTRACT_TEXT successfully`() = runTest {
        mockProvider.setResponse(
            MultimodalAction.EXTRACT_TEXT,
            MultimodalResponse(
                text = "Hello World\nThis is OCR text",
                confidence = 0.92
            )
        )

        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-2",
            action = MultimodalAction.EXTRACT_TEXT,
            inputPath = testImageFile.absolutePath
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("EXTRACT_TEXT", result.output.json["action"]?.jsonPrimitive?.content)
        assertEquals("Hello World\nThis is OCR text", result.output.json["extractedText"]?.jsonPrimitive?.content)
        assertEquals("Hello World\nThis is OCR text", result.output.artifacts["extracted_text"])
    }

    @Test
    fun `should execute PARSE_DOCUMENT successfully`() = runTest {
        mockProvider.setResponse(
            MultimodalAction.PARSE_DOCUMENT,
            MultimodalResponse(
                text = "Document content here",
                metadata = mapOf("pageCount" to "5", "title" to "Test Doc")
            )
        )

        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-3",
            action = MultimodalAction.PARSE_DOCUMENT,
            inputPath = testDocFile.absolutePath,
            outputFormat = "markdown"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("PARSE_DOCUMENT", result.output.json["action"]?.jsonPrimitive?.content)
        assertEquals("5", result.output.json["pageCount"]?.jsonPrimitive?.content)
        assertEquals("Document content here", result.output.artifacts["content"])
    }

    @Test
    fun `should execute TRANSCRIBE_AUDIO successfully`() = runTest {
        mockProvider.setResponse(
            MultimodalAction.TRANSCRIBE_AUDIO,
            MultimodalResponse(
                text = "Hello, this is a transcription test.",
                metadata = mapOf("durationMs" to "5000", "language" to "en")
            )
        )

        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-4",
            action = MultimodalAction.TRANSCRIBE_AUDIO,
            inputPath = testAudioFile.absolutePath
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("TRANSCRIBE_AUDIO", result.output.json["action"]?.jsonPrimitive?.content)
        assertEquals("Hello, this is a transcription test.", result.output.json["transcript"]?.jsonPrimitive?.content)
        assertEquals("5000", result.output.json["durationMs"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should execute VISION_QA successfully`() = runTest {
        mockProvider.setResponse(
            MultimodalAction.VISION_QA,
            MultimodalResponse(
                text = "There are 3 people in the image.",
                confidence = 0.95
            )
        )

        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-5",
            action = MultimodalAction.VISION_QA,
            inputPath = testImageFile.absolutePath,
            prompt = "How many people are in this image?"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("VISION_QA", result.output.json["action"]?.jsonPrimitive?.content)
        assertEquals("How many people are in this image?", result.output.json["question"]?.jsonPrimitive?.content)
        assertEquals("There are 3 people in the image.", result.output.json["answer"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should return error for VISION_QA without prompt`() = runTest {
        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-5",
            action = MultimodalAction.VISION_QA,
            inputPath = testImageFile.absolutePath
            // No prompt
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("PROMPT_REQUIRED", result.error.code)
    }

    @Test
    fun `step should have correct type`() {
        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-1",
            action = MultimodalAction.ANALYZE_IMAGE,
            inputPath = "/test/image.png"
        )

        assertEquals(StepType.MULTIMODAL, step.type)
    }

    @Test
    fun `step should preserve all parameters`() {
        val params = mapOf("key" to "value")

        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-1",
            action = MultimodalAction.VISION_QA,
            inputPath = "/test/image.png",
            prompt = "What is this?",
            outputFormat = "json",
            providerId = "openai-vision",
            timeoutMs = 120000,
            params = params
        )

        assertEquals("mm-1", step.stepId)
        assertEquals(MultimodalAction.VISION_QA, step.action)
        assertEquals("/test/image.png", step.inputPath)
        assertEquals("What is this?", step.prompt)
        assertEquals("json", step.outputFormat)
        assertEquals("openai-vision", step.providerId)
        assertEquals(120000, step.timeoutMs)
        assertEquals(params, step.params)
    }

    @Test
    fun `output should be added to context`() = runTest {
        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-ctx",
            action = MultimodalAction.ANALYZE_IMAGE,
            inputPath = testImageFile.absolutePath
        )

        executor.execute(step, context)

        val output = context.getStepOutput("mm-ctx")
        assertNotNull(output)
        assertEquals("mm-ctx", output.stepId)
    }

    @Test
    fun `meta should include stepId and resourceRefs`() = runTest {
        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-meta",
            action = MultimodalAction.ANALYZE_IMAGE,
            inputPath = testImageFile.absolutePath
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("mm-meta", result.meta.stepId)
        assertTrue(result.meta.resourceRefs.contains(testImageFile.absolutePath) == true)
    }

    @Test
    fun `should detect mime types correctly`() = runTest {
        // Test with different file extensions
        val jpegFile = File(testDir, "test.jpg")
        jpegFile.writeBytes(ByteArray(10))

        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-mime",
            action = MultimodalAction.ANALYZE_IMAGE,
            inputPath = jpegFile.absolutePath
        )

        val result = executor.execute(step, context)
        assertIs<ExecutionResult.Success>(result)
    }

    @Test
    fun `DefaultMultimodalProviderRegistry should manage providers`() {
        val registry = DefaultMultimodalProviderRegistry()
        val customProvider = MockMultimodalProvider(id = "custom")

        registry.registerProvider(customProvider)

        val provider = registry.getProvider("custom")
        assertNotNull(provider)
        assertEquals("custom", provider.id)
    }

    @Test
    fun `MockMultimodalProvider clear should reset state`() = runTest {
        mockProvider.setResponse(
            MultimodalAction.ANALYZE_IMAGE,
            MultimodalResponse(text = "Custom response")
        )
        mockProvider.setSupports(MultimodalAction.ANALYZE_IMAGE)

        mockProvider.clear()

        // Should now support all actions again
        assertTrue(mockProvider.supports(MultimodalAction.TRANSCRIBE_AUDIO))

        val step = ExecutionStep.MultimodalStep(
            stepId = "mm-clear",
            action = MultimodalAction.ANALYZE_IMAGE,
            inputPath = testImageFile.absolutePath
        )

        val result = executor.execute(step, context)
        assertIs<ExecutionResult.Success>(result)
        // Should return default mock response
        assertTrue(result.output.json["description"]?.jsonPrimitive?.content?.contains("Mock image analysis") == true)
    }

    @Test
    fun `should handle various image formats`() = runTest {
        val formats = listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

        for (format in formats) {
            val file = File(testDir, "test.$format")
            file.writeBytes(ByteArray(50))

            val step = ExecutionStep.MultimodalStep(
                stepId = "mm-$format",
                action = MultimodalAction.ANALYZE_IMAGE,
                inputPath = file.absolutePath
            )

            // Create new context for each test
            val ctx = ExecutionContext(
                executionId = "exec-$format",
                blueprintId = "bp-$format",
                instructionId = "instr-$format"
            )

            val result = executor.execute(step, ctx)
            assertIs<ExecutionResult.Success>(result)
        }
    }

    @Test
    fun `should handle various document formats`() = runTest {
        val formats = listOf("pdf", "docx", "doc")

        for (format in formats) {
            val file = File(testDir, "test.$format")
            file.writeBytes(ByteArray(100))

            val step = ExecutionStep.MultimodalStep(
                stepId = "mm-$format",
                action = MultimodalAction.PARSE_DOCUMENT,
                inputPath = file.absolutePath
            )

            val ctx = ExecutionContext(
                executionId = "exec-$format",
                blueprintId = "bp-$format",
                instructionId = "instr-$format"
            )

            val result = executor.execute(step, ctx)
            assertIs<ExecutionResult.Success>(result)
        }
    }

    @Test
    fun `should handle various audio formats`() = runTest {
        val formats = listOf("mp3", "wav", "m4a", "ogg", "flac")

        for (format in formats) {
            val file = File(testDir, "test.$format")
            file.writeBytes(ByteArray(75))

            val step = ExecutionStep.MultimodalStep(
                stepId = "mm-$format",
                action = MultimodalAction.TRANSCRIBE_AUDIO,
                inputPath = file.absolutePath
            )

            val ctx = ExecutionContext(
                executionId = "exec-$format",
                blueprintId = "bp-$format",
                instructionId = "instr-$format"
            )

            val result = executor.execute(step, ctx)
            assertIs<ExecutionResult.Success>(result)
        }
    }
}

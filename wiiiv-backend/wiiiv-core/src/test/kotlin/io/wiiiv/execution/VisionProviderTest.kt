package io.wiiiv.execution

import io.wiiiv.execution.impl.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Vision Provider 테스트
 *
 * OpenAI Vision, Anthropic Vision Provider 검증
 * API 호출 없이 구조 및 유효성 검증만 수행
 */
class VisionProviderTest {

    // OpenAI Vision Provider Tests

    @Test
    fun `OpenAI Vision should have correct id`() {
        val provider = OpenAIVisionProvider(apiKey = "test-key")
        assertEquals("openai-vision", provider.id)
    }

    @Test
    fun `OpenAI Vision should support image analysis actions`() {
        val provider = OpenAIVisionProvider(apiKey = "test-key")

        assertTrue(provider.supports(MultimodalAction.ANALYZE_IMAGE))
        assertTrue(provider.supports(MultimodalAction.EXTRACT_TEXT))
        assertTrue(provider.supports(MultimodalAction.VISION_QA))
        assertTrue(provider.supports(MultimodalAction.PARSE_DOCUMENT))
        assertFalse(provider.supports(MultimodalAction.TRANSCRIBE_AUDIO))
    }

    @Test
    fun `OpenAI Vision should throw error without API key`() {
        val provider = OpenAIVisionProvider(apiKey = "")
        val imageData = ByteArray(100)

        val exception = assertFailsWith<MultimodalException> {
            provider.analyzeImage(imageData, "image/png", null, 30000)
        }

        assertEquals("API_KEY_MISSING", exception.code)
    }

    @Test
    fun `OpenAI Vision should reject unsupported image types`() {
        val provider = OpenAIVisionProvider(apiKey = "test-key")
        val imageData = ByteArray(100)

        val exception = assertFailsWith<MultimodalException> {
            provider.analyzeImage(imageData, "image/bmp", null, 30000)
        }

        assertEquals("UNSUPPORTED_FORMAT", exception.code)
    }

    @Test
    fun `OpenAI Vision should reject PDF for parseDocument`() {
        val provider = OpenAIVisionProvider(apiKey = "test-key")
        val pdfData = ByteArray(100)

        val exception = assertFailsWith<MultimodalException> {
            provider.parseDocument(pdfData, "application/pdf", "text", 30000)
        }

        assertEquals("UNSUPPORTED_FORMAT", exception.code)
    }

    @Test
    fun `OpenAI Vision should reject audio transcription`() {
        val provider = OpenAIVisionProvider(apiKey = "test-key")
        val audioData = ByteArray(100)

        val exception = assertFailsWith<MultimodalException> {
            provider.transcribeAudio(audioData, "audio/mpeg", 30000)
        }

        assertEquals("UNSUPPORTED_ACTION", exception.code)
    }

    @Test
    fun `OpenAI Vision fromEnv should create provider`() {
        // This will use empty string if env var not set
        val provider = OpenAIVisionProvider.fromEnv()
        assertNotNull(provider)
        assertEquals("openai-vision", provider.id)
    }

    // Anthropic Vision Provider Tests

    @Test
    fun `Anthropic Vision should have correct id`() {
        val provider = AnthropicVisionProvider(apiKey = "test-key")
        assertEquals("anthropic-vision", provider.id)
    }

    @Test
    fun `Anthropic Vision should support image analysis actions`() {
        val provider = AnthropicVisionProvider(apiKey = "test-key")

        assertTrue(provider.supports(MultimodalAction.ANALYZE_IMAGE))
        assertTrue(provider.supports(MultimodalAction.EXTRACT_TEXT))
        assertTrue(provider.supports(MultimodalAction.VISION_QA))
        assertTrue(provider.supports(MultimodalAction.PARSE_DOCUMENT))
        assertFalse(provider.supports(MultimodalAction.TRANSCRIBE_AUDIO))
    }

    @Test
    fun `Anthropic Vision should throw error without API key`() {
        val provider = AnthropicVisionProvider(apiKey = "")
        val imageData = ByteArray(100)

        val exception = assertFailsWith<MultimodalException> {
            provider.analyzeImage(imageData, "image/png", null, 30000)
        }

        assertEquals("API_KEY_MISSING", exception.code)
    }

    @Test
    fun `Anthropic Vision should reject unsupported image types`() {
        val provider = AnthropicVisionProvider(apiKey = "test-key")
        val imageData = ByteArray(100)

        val exception = assertFailsWith<MultimodalException> {
            provider.analyzeImage(imageData, "image/tiff", null, 30000)
        }

        assertEquals("UNSUPPORTED_FORMAT", exception.code)
    }

    @Test
    fun `Anthropic Vision should reject PDF for parseDocument`() {
        val provider = AnthropicVisionProvider(apiKey = "test-key")
        val pdfData = ByteArray(100)

        val exception = assertFailsWith<MultimodalException> {
            provider.parseDocument(pdfData, "application/pdf", "text", 30000)
        }

        assertEquals("UNSUPPORTED_FORMAT", exception.code)
    }

    @Test
    fun `Anthropic Vision should reject audio transcription`() {
        val provider = AnthropicVisionProvider(apiKey = "test-key")
        val audioData = ByteArray(100)

        val exception = assertFailsWith<MultimodalException> {
            provider.transcribeAudio(audioData, "audio/mpeg", 30000)
        }

        assertEquals("UNSUPPORTED_ACTION", exception.code)
    }

    @Test
    fun `Anthropic Vision fromEnv should create provider`() {
        val provider = AnthropicVisionProvider.fromEnv()
        assertNotNull(provider)
        assertEquals("anthropic-vision", provider.id)
    }

    // Kafka Provider Tests

    @Test
    fun `KafkaProvider isAvailable should return false without dependency`() {
        // Kafka client is not in test dependencies, so should return false
        assertFalse(KafkaProvider.isAvailable())
    }

    @Test
    fun `KafkaProvider create should throw without dependency`() {
        assertFailsWith<IllegalStateException> {
            KafkaProvider.create()
        }
    }

    // MultimodalResponse Tests

    @Test
    fun `MultimodalResponse should hold all fields`() {
        val response = MultimodalResponse(
            text = "Test response",
            confidence = 0.95,
            metadata = mapOf("key" to "value")
        )

        assertEquals("Test response", response.text)
        assertEquals(0.95, response.confidence)
        assertEquals("value", response.metadata["key"])
    }

    @Test
    fun `MultimodalException should convert to ExecutionError`() {
        val exception = MultimodalException(
            code = "TEST_ERROR",
            message = "Test message",
            isTimeout = false
        )

        val error = exception.toExecutionError()

        assertEquals(ErrorCategory.EXTERNAL_SERVICE_ERROR, error.category)
        assertEquals("TEST_ERROR", error.code)
        assertEquals("Test message", error.message)
    }

    @Test
    fun `MultimodalException timeout should have correct category`() {
        val exception = MultimodalException(
            code = "TIMEOUT",
            message = "Timed out",
            isTimeout = true
        )

        val error = exception.toExecutionError()

        assertEquals(ErrorCategory.TIMEOUT, error.category)
    }
}

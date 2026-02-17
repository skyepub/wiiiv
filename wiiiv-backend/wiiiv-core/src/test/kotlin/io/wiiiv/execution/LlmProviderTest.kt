package io.wiiiv.execution

import io.wiiiv.execution.impl.*
import kotlinx.coroutines.runBlocking
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.*

/**
 * LLM Provider Tests
 *
 * OpenAI/Anthropic Provider 테스트
 *
 * 참고: 실제 API 호출 없이 Mock HTTP Client 사용
 */
class LlmProviderTest {

    // ==================== OpenAI Provider Tests ====================

    @Test
    fun `OpenAIProvider should have correct defaults`() {
        val provider = OpenAIProvider(apiKey = "test-key")

        assertEquals("gpt-4o", provider.defaultModel)
        assertEquals(4096, provider.defaultMaxTokens)
    }

    @Test
    fun `OpenAIProvider should throw on missing API key`() = runBlocking {
        val provider = OpenAIProvider(apiKey = "")
        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = "Hello",
            model = "gpt-4o",
            maxTokens = 100
        )

        val exception = assertFailsWith<LlmProviderException> {
            provider.call(request)
        }

        assertEquals(ErrorCategory.CONTRACT_VIOLATION, exception.category)
        assertEquals("API_KEY_MISSING", exception.code)
    }

    @Test
    fun `OpenAIProvider should parse successful response`() = runBlocking {
        val mockResponse = """
            {
                "id": "chatcmpl-123",
                "object": "chat.completion",
                "created": 1677652288,
                "model": "gpt-4o",
                "choices": [{
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "Hello! How can I help you today?"
                    },
                    "finish_reason": "stop"
                }],
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 8,
                    "total_tokens": 18
                }
            }
        """.trimIndent()

        val mockClient = createMockHttpClient(200, mockResponse)
        val provider = OpenAIProvider(
            apiKey = "test-key",
            httpClient = mockClient
        )

        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = "Hello",
            model = "gpt-4o",
            maxTokens = 100
        )

        val response = provider.call(request)

        assertEquals("Hello! How can I help you today?", response.content)
        assertEquals("stop", response.finishReason)
        assertEquals(10, response.usage.promptTokens)
        assertEquals(8, response.usage.completionTokens)
        assertEquals(18, response.usage.totalTokens)
    }

    @Test
    fun `OpenAIProvider should handle 401 unauthorized`() = runBlocking {
        val mockResponse = """
            {
                "error": {
                    "message": "Incorrect API key provided",
                    "type": "invalid_request_error",
                    "code": "invalid_api_key"
                }
            }
        """.trimIndent()

        val mockClient = createMockHttpClient(401, mockResponse)
        val provider = OpenAIProvider(
            apiKey = "invalid-key",
            httpClient = mockClient
        )

        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = "Hello",
            model = "gpt-4o",
            maxTokens = 100
        )

        val exception = assertFailsWith<LlmProviderException> {
            provider.call(request)
        }

        assertEquals(ErrorCategory.PERMISSION_DENIED, exception.category)
        assertEquals("UNAUTHORIZED", exception.code)
    }

    @Test
    fun `OpenAIProvider should handle 429 rate limit`() = runBlocking {
        val mockResponse = """
            {
                "error": {
                    "message": "Rate limit exceeded",
                    "type": "rate_limit_error"
                }
            }
        """.trimIndent()

        val mockClient = createMockHttpClient(429, mockResponse)
        val provider = OpenAIProvider(
            apiKey = "test-key",
            httpClient = mockClient
        )

        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = "Hello",
            model = "gpt-4o",
            maxTokens = 100
        )

        val exception = assertFailsWith<LlmProviderException> {
            provider.call(request)
        }

        assertEquals(ErrorCategory.EXTERNAL_SERVICE_ERROR, exception.category)
        assertEquals("RATE_LIMIT", exception.code)
    }

    @Test
    fun `OpenAIProvider should handle 500 server error`() = runBlocking {
        val mockClient = createMockHttpClient(500, "Internal Server Error")
        val provider = OpenAIProvider(
            apiKey = "test-key",
            httpClient = mockClient
        )

        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = "Hello",
            model = "gpt-4o",
            maxTokens = 100
        )

        val exception = assertFailsWith<LlmProviderException> {
            provider.call(request)
        }

        assertEquals(ErrorCategory.EXTERNAL_SERVICE_ERROR, exception.category)
        assertTrue(exception.code.startsWith("API_ERROR"))
    }

    @Test
    fun `OpenAIProvider should handle empty choices`() = runBlocking {
        val mockResponse = """
            {
                "id": "chatcmpl-123",
                "choices": [],
                "usage": {"prompt_tokens": 10, "completion_tokens": 0, "total_tokens": 10}
            }
        """.trimIndent()

        val mockClient = createMockHttpClient(200, mockResponse)
        val provider = OpenAIProvider(
            apiKey = "test-key",
            httpClient = mockClient
        )

        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = "Hello",
            model = "gpt-4o",
            maxTokens = 100
        )

        val exception = assertFailsWith<LlmProviderException> {
            provider.call(request)
        }

        assertEquals("EMPTY_RESPONSE", exception.code)
    }

    @Test
    fun `OpenAIProvider should handle params gracefully`() = runBlocking {
        val mockClient = createMockHttpClient(200, OPENAI_SUCCESS_RESPONSE)

        val provider = OpenAIProvider(
            apiKey = "test-key",
            httpClient = mockClient
        )

        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = "Hello",
            model = "gpt-4o",
            maxTokens = 100,
            params = mapOf("temperature" to "0.5", "top_p" to "0.9")
        )

        // Should not throw even with params
        val response = provider.call(request)
        assertNotNull(response)
        assertEquals("Test response", response.content)
    }

    // ==================== Anthropic Provider Tests ====================

    @Test
    fun `AnthropicProvider should have correct defaults`() {
        val provider = AnthropicProvider(apiKey = "test-key")

        assertEquals("claude-sonnet-4-20250514", provider.defaultModel)
        assertEquals(4096, provider.defaultMaxTokens)
    }

    @Test
    fun `AnthropicProvider should throw on missing API key`() = runBlocking {
        val provider = AnthropicProvider(apiKey = "")
        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = "Hello",
            model = "claude-sonnet-4-20250514",
            maxTokens = 100
        )

        val exception = assertFailsWith<LlmProviderException> {
            provider.call(request)
        }

        assertEquals(ErrorCategory.CONTRACT_VIOLATION, exception.category)
        assertEquals("API_KEY_MISSING", exception.code)
    }

    @Test
    fun `AnthropicProvider should parse successful response`() = runBlocking {
        val mockResponse = """
            {
                "id": "msg_123",
                "type": "message",
                "role": "assistant",
                "content": [
                    {
                        "type": "text",
                        "text": "Hello! I'm Claude, an AI assistant."
                    }
                ],
                "model": "claude-sonnet-4-20250514",
                "stop_reason": "end_turn",
                "usage": {
                    "input_tokens": 10,
                    "output_tokens": 12
                }
            }
        """.trimIndent()

        val mockClient = createMockHttpClient(200, mockResponse)
        val provider = AnthropicProvider(
            apiKey = "test-key",
            httpClient = mockClient
        )

        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = "Hello",
            model = "claude-sonnet-4-20250514",
            maxTokens = 100
        )

        val response = provider.call(request)

        assertEquals("Hello! I'm Claude, an AI assistant.", response.content)
        assertEquals("end_turn", response.finishReason)
        assertEquals(10, response.usage.promptTokens)
        assertEquals(12, response.usage.completionTokens)
        assertEquals(22, response.usage.totalTokens)
    }

    @Test
    fun `AnthropicProvider should handle 401 unauthorized`() = runBlocking {
        val mockResponse = """
            {
                "type": "error",
                "error": {
                    "type": "authentication_error",
                    "message": "Invalid API key"
                }
            }
        """.trimIndent()

        val mockClient = createMockHttpClient(401, mockResponse)
        val provider = AnthropicProvider(
            apiKey = "invalid-key",
            httpClient = mockClient
        )

        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = "Hello",
            model = "claude-sonnet-4-20250514",
            maxTokens = 100
        )

        val exception = assertFailsWith<LlmProviderException> {
            provider.call(request)
        }

        assertEquals(ErrorCategory.PERMISSION_DENIED, exception.category)
        assertEquals("UNAUTHORIZED", exception.code)
    }

    @Test
    fun `AnthropicProvider should handle 429 rate limit`() = runBlocking {
        val mockResponse = """
            {
                "type": "error",
                "error": {
                    "type": "rate_limit_error",
                    "message": "Rate limit exceeded"
                }
            }
        """.trimIndent()

        val mockClient = createMockHttpClient(429, mockResponse)
        val provider = AnthropicProvider(
            apiKey = "test-key",
            httpClient = mockClient
        )

        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = "Hello",
            model = "claude-sonnet-4-20250514",
            maxTokens = 100
        )

        val exception = assertFailsWith<LlmProviderException> {
            provider.call(request)
        }

        assertEquals(ErrorCategory.EXTERNAL_SERVICE_ERROR, exception.category)
        assertEquals("RATE_LIMIT", exception.code)
    }

    @Test
    fun `AnthropicProvider should handle 529 overload`() = runBlocking {
        val mockResponse = """
            {
                "type": "error",
                "error": {
                    "type": "overloaded_error",
                    "message": "API is overloaded"
                }
            }
        """.trimIndent()

        val mockClient = createMockHttpClient(529, mockResponse)
        val provider = AnthropicProvider(
            apiKey = "test-key",
            httpClient = mockClient
        )

        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = "Hello",
            model = "claude-sonnet-4-20250514",
            maxTokens = 100
        )

        val exception = assertFailsWith<LlmProviderException> {
            provider.call(request)
        }

        assertEquals(ErrorCategory.EXTERNAL_SERVICE_ERROR, exception.category)
        assertEquals("OVERLOADED", exception.code)
    }

    @Test
    fun `AnthropicProvider should handle multiple content blocks`() = runBlocking {
        val mockResponse = """
            {
                "id": "msg_123",
                "type": "message",
                "role": "assistant",
                "content": [
                    {"type": "text", "text": "First part. "},
                    {"type": "text", "text": "Second part."}
                ],
                "stop_reason": "end_turn",
                "usage": {"input_tokens": 10, "output_tokens": 5}
            }
        """.trimIndent()

        val mockClient = createMockHttpClient(200, mockResponse)
        val provider = AnthropicProvider(
            apiKey = "test-key",
            httpClient = mockClient
        )

        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = "Hello",
            model = "claude-sonnet-4-20250514",
            maxTokens = 100
        )

        val response = provider.call(request)

        assertEquals("First part. Second part.", response.content)
    }

    // ==================== Integration Tests ====================

    @Test
    fun `OpenAIProvider should work with LlmExecutor`() = runBlocking {
        val mockClient = createMockHttpClient(200, OPENAI_SUCCESS_RESPONSE)
        val provider = OpenAIProvider(
            apiKey = "test-key",
            httpClient = mockClient
        )
        val executor = LlmExecutor.create(provider)
        val context = ExecutionContext.create("exec-1", "bp-1", "inst-1")

        val step = ExecutionStep.LlmCallStep(
            stepId = "llm-step-1",
            action = LlmAction.COMPLETE,
            prompt = "Hello"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        assertNotNull(result.output.artifacts["content"])
    }

    @Test
    fun `AnthropicProvider should work with LlmExecutor`() = runBlocking {
        val mockClient = createMockHttpClient(200, ANTHROPIC_SUCCESS_RESPONSE)
        val provider = AnthropicProvider(
            apiKey = "test-key",
            httpClient = mockClient
        )
        val executor = LlmExecutor.create(provider)
        val context = ExecutionContext.create("exec-1", "bp-1", "inst-1")

        val step = ExecutionStep.LlmCallStep(
            stepId = "llm-step-1",
            action = LlmAction.COMPLETE,
            prompt = "Hello"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
    }

    // ==================== Helpers ====================

    /**
     * Mock HTTP Client 생성
     */
    private fun createMockHttpClient(
        statusCode: Int,
        responseBody: String
    ): HttpClient {
        return object : HttpClient() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> send(
                request: HttpRequest,
                responseBodyHandler: HttpResponse.BodyHandler<T>
            ): HttpResponse<T> {
                return MockHttpResponse(statusCode, responseBody, request) as HttpResponse<T>
            }

            override fun <T> sendAsync(
                request: HttpRequest,
                responseBodyHandler: HttpResponse.BodyHandler<T>
            ) = throw UnsupportedOperationException()

            override fun <T> sendAsync(
                request: HttpRequest,
                responseBodyHandler: HttpResponse.BodyHandler<T>,
                pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?
            ) = throw UnsupportedOperationException()

            override fun cookieHandler() = java.util.Optional.empty<java.net.CookieHandler>()
            override fun connectTimeout() = java.util.Optional.empty<java.time.Duration>()
            override fun followRedirects() = Redirect.NEVER
            override fun proxy() = java.util.Optional.empty<java.net.ProxySelector>()
            override fun sslContext(): javax.net.ssl.SSLContext? = null
            override fun sslParameters(): javax.net.ssl.SSLParameters? = null
            override fun authenticator() = java.util.Optional.empty<java.net.Authenticator>()
            override fun version() = Version.HTTP_1_1
            override fun executor() = java.util.Optional.empty<java.util.concurrent.Executor>()
        }
    }

    /**
     * Mock HTTP Response
     */
    private class MockHttpResponse(
        private val statusCode: Int,
        private val responseBody: String,
        private val httpRequest: HttpRequest
    ) : HttpResponse<String> {
        override fun statusCode() = statusCode
        override fun body() = responseBody
        override fun request() = httpRequest
        override fun previousResponse() = java.util.Optional.empty<HttpResponse<String>>()
        override fun headers() = java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }
        override fun sslSession() = java.util.Optional.empty<javax.net.ssl.SSLSession>()
        override fun uri() = httpRequest.uri()
        override fun version() = HttpClient.Version.HTTP_1_1
    }

    companion object {
        private val OPENAI_SUCCESS_RESPONSE = """
            {
                "id": "chatcmpl-123",
                "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "Test response"},
                    "finish_reason": "stop"
                }],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            }
        """.trimIndent()

        private val ANTHROPIC_SUCCESS_RESPONSE = """
            {
                "id": "msg_123",
                "content": [{"type": "text", "text": "Test response"}],
                "stop_reason": "end_turn",
                "usage": {"input_tokens": 10, "output_tokens": 5}
            }
        """.trimIndent()
    }
}

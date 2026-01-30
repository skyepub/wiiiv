package io.wiiiv.execution.impl

import io.wiiiv.execution.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * WebSocket Executor - WebSocket 통신 Executor
 *
 * Canonical: Executor 정의서 v1.0, Executor Interface Spec v1.0
 *
 * ## Executor 원칙 준수
 *
 * - 판단하지 않는다: URL이 안전한지 판단하지 않음
 * - 해석하지 않는다: 메시지의 의미를 해석하지 않음
 * - Blueprint를 신뢰한다: 정합성과 합법성이 이미 검증되었다고 가정
 *
 * ## 지원 기능
 *
 * - SEND: 연결 후 메시지 송신
 * - RECEIVE: 연결 후 메시지 수신 대기
 * - SEND_RECEIVE: 메시지 송신 후 응답 수신
 *
 * ## 오류 처리
 *
 * - 잘못된 URL → Failure (CONTRACT_VIOLATION)
 * - 연결 실패 → Failure (EXTERNAL_SERVICE_ERROR)
 * - 타임아웃 → Failure (TIMEOUT)
 */
class WebSocketExecutor(
    private val httpClient: HttpClient = DEFAULT_CLIENT
) : Executor {

    override suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        if (step !is ExecutionStep.WebSocketStep) {
            return ExecutionResult.contractViolation(
                stepId = step.stepId,
                code = "INVALID_STEP_TYPE",
                message = "WebSocketExecutor can only handle WebSocketStep, got: ${step::class.simpleName}"
            )
        }

        val startedAt = Instant.now()

        return try {
            val result = executeWebSocket(step)

            val endedAt = Instant.now()

            when (result) {
                is WebSocketResult.Success -> {
                    val output = result.output
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.url)
                    )

                    context.addStepOutput(step.stepId, output)
                    ExecutionResult.Success(output = output, meta = meta)
                }
                is WebSocketResult.Error -> {
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.url)
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
                    resourceRefs = listOf(step.url)
                )
            )
        }
    }

    private fun executeWebSocket(step: ExecutionStep.WebSocketStep): WebSocketResult {
        // Validate URL
        val uri = try {
            URI.create(step.url)
        } catch (e: IllegalArgumentException) {
            return WebSocketResult.Error(
                ExecutionError(
                    category = ErrorCategory.CONTRACT_VIOLATION,
                    code = "INVALID_URL",
                    message = "Invalid WebSocket URL: ${step.url}"
                )
            )
        }

        // Validate scheme
        if (uri.scheme != "ws" && uri.scheme != "wss") {
            return WebSocketResult.Error(
                ExecutionError(
                    category = ErrorCategory.CONTRACT_VIOLATION,
                    code = "INVALID_SCHEME",
                    message = "WebSocket URL must use ws:// or wss:// scheme: ${step.url}"
                )
            )
        }

        return when (step.action) {
            WebSocketAction.SEND -> executeSend(step, uri)
            WebSocketAction.RECEIVE -> executeReceive(step, uri)
            WebSocketAction.SEND_RECEIVE -> executeSendReceive(step, uri)
        }
    }

    private fun executeSend(step: ExecutionStep.WebSocketStep, uri: URI): WebSocketResult {
        if (step.message == null) {
            return WebSocketResult.Error(
                ExecutionError(
                    category = ErrorCategory.CONTRACT_VIOLATION,
                    code = "MESSAGE_REQUIRED",
                    message = "SEND action requires message"
                )
            )
        }

        val listener = SimpleWebSocketListener()

        return try {
            val webSocket = connectWebSocket(uri, listener, step.timeoutMs)

            // Send message
            webSocket.sendText(step.message, true).get(step.timeoutMs, TimeUnit.MILLISECONDS)

            // Close connection
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Done").get(5000, TimeUnit.MILLISECONDS)

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("SEND"))
                    put("url", JsonPrimitive(step.url))
                    put("messageSent", JsonPrimitive(step.message))
                    put("success", JsonPrimitive(true))
                },
                artifacts = mapOf(
                    "message_sent" to step.message,
                    "url" to step.url
                )
            )

            WebSocketResult.Success(output)
        } catch (e: TimeoutException) {
            WebSocketResult.Error(
                ExecutionError(
                    category = ErrorCategory.TIMEOUT,
                    code = "WEBSOCKET_TIMEOUT",
                    message = "WebSocket operation timeout after ${step.timeoutMs}ms"
                )
            )
        } catch (e: Exception) {
            WebSocketResult.Error(
                ExecutionError(
                    category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                    code = "WEBSOCKET_ERROR",
                    message = "WebSocket error: ${e.message}"
                )
            )
        }
    }

    private fun executeReceive(step: ExecutionStep.WebSocketStep, uri: URI): WebSocketResult {
        val listener = SimpleWebSocketListener()

        return try {
            val webSocket = connectWebSocket(uri, listener, step.timeoutMs)

            // Wait for message
            val receivedMessage = listener.waitForMessage(step.timeoutMs)

            // Close connection
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Done")

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("RECEIVE"))
                    put("url", JsonPrimitive(step.url))
                    put("messageReceived", JsonPrimitive(receivedMessage ?: ""))
                    put("received", JsonPrimitive(receivedMessage != null))
                },
                artifacts = buildMap {
                    put("url", step.url)
                    if (receivedMessage != null) {
                        put("message_received", receivedMessage)
                    }
                }
            )

            WebSocketResult.Success(output)
        } catch (e: TimeoutException) {
            WebSocketResult.Error(
                ExecutionError(
                    category = ErrorCategory.TIMEOUT,
                    code = "WEBSOCKET_TIMEOUT",
                    message = "WebSocket receive timeout after ${step.timeoutMs}ms"
                )
            )
        } catch (e: Exception) {
            WebSocketResult.Error(
                ExecutionError(
                    category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                    code = "WEBSOCKET_ERROR",
                    message = "WebSocket error: ${e.message}"
                )
            )
        }
    }

    private fun executeSendReceive(step: ExecutionStep.WebSocketStep, uri: URI): WebSocketResult {
        if (step.message == null) {
            return WebSocketResult.Error(
                ExecutionError(
                    category = ErrorCategory.CONTRACT_VIOLATION,
                    code = "MESSAGE_REQUIRED",
                    message = "SEND_RECEIVE action requires message"
                )
            )
        }

        val listener = SimpleWebSocketListener()

        return try {
            val webSocket = connectWebSocket(uri, listener, step.timeoutMs)

            // Send message
            webSocket.sendText(step.message, true).get(step.timeoutMs, TimeUnit.MILLISECONDS)

            // Wait for response
            val receivedMessage = listener.waitForMessage(step.timeoutMs)

            // Close connection
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Done")

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("SEND_RECEIVE"))
                    put("url", JsonPrimitive(step.url))
                    put("messageSent", JsonPrimitive(step.message))
                    put("messageReceived", JsonPrimitive(receivedMessage ?: ""))
                    put("received", JsonPrimitive(receivedMessage != null))
                },
                artifacts = buildMap {
                    put("url", step.url)
                    put("message_sent", step.message)
                    if (receivedMessage != null) {
                        put("message_received", receivedMessage)
                    }
                }
            )

            WebSocketResult.Success(output)
        } catch (e: TimeoutException) {
            WebSocketResult.Error(
                ExecutionError(
                    category = ErrorCategory.TIMEOUT,
                    code = "WEBSOCKET_TIMEOUT",
                    message = "WebSocket operation timeout after ${step.timeoutMs}ms"
                )
            )
        } catch (e: Exception) {
            WebSocketResult.Error(
                ExecutionError(
                    category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                    code = "WEBSOCKET_ERROR",
                    message = "WebSocket error: ${e.message}"
                )
            )
        }
    }

    private fun connectWebSocket(uri: URI, listener: WebSocket.Listener, timeoutMs: Long): WebSocket {
        return httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .buildAsync(uri, listener)
            .get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean {
        return true
    }

    override fun canHandle(step: ExecutionStep): Boolean {
        return step is ExecutionStep.WebSocketStep
    }

    /**
     * Simple WebSocket listener for message collection
     */
    private class SimpleWebSocketListener : WebSocket.Listener {
        private val messages = mutableListOf<String>()
        private val messageReceived = CompletableFuture<String?>()
        private val buffer = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            buffer.append(data)
            if (last) {
                val message = buffer.toString()
                buffer.clear()
                messages.add(message)
                if (!messageReceived.isDone) {
                    messageReceived.complete(message)
                }
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            if (!messageReceived.isDone) {
                messageReceived.completeExceptionally(error)
            }
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*> {
            if (!messageReceived.isDone) {
                messageReceived.complete(null)
            }
            return CompletableFuture.completedFuture(null)
        }

        fun waitForMessage(timeoutMs: Long): String? {
            return try {
                messageReceived.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private sealed class WebSocketResult {
        data class Success(val output: StepOutput) : WebSocketResult()
        data class Error(val error: ExecutionError) : WebSocketResult()
    }

    companion object {
        private val DEFAULT_CLIENT: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        val INSTANCE = WebSocketExecutor()
    }
}

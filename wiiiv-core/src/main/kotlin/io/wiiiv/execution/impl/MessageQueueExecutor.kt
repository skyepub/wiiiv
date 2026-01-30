package io.wiiiv.execution.impl

import io.wiiiv.execution.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Message Queue Executor - 메시지 큐 Executor
 *
 * Canonical: Executor 정의서 v1.0, Executor Interface Spec v1.0
 *
 * ## Executor 원칙 준수
 *
 * - 판단하지 않는다: 토픽이 안전한지 판단하지 않음
 * - 해석하지 않는다: 메시지의 의미를 해석하지 않음
 * - Blueprint를 신뢰한다: 정합성과 합법성이 이미 검증되었다고 가정
 *
 * ## 지원 기능
 *
 * - PUBLISH: 메시지 발행
 * - CONSUME: 메시지 소비 (단일)
 * - REQUEST_REPLY: 요청-응답 패턴
 *
 * ## Provider 패턴
 *
 * MessageQueueProvider 인터페이스를 통해 다양한 MQ 구현 지원:
 * - InMemoryMessageQueueProvider (테스트용)
 * - 향후: KafkaProvider, RabbitMQProvider 등
 */
class MessageQueueExecutor(
    private val providerRegistry: MessageQueueProviderRegistry = DefaultProviderRegistry()
) : Executor {

    override suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        if (step !is ExecutionStep.MessageQueueStep) {
            return ExecutionResult.contractViolation(
                stepId = step.stepId,
                code = "INVALID_STEP_TYPE",
                message = "MessageQueueExecutor can only handle MessageQueueStep, got: ${step::class.simpleName}"
            )
        }

        val startedAt = Instant.now()

        return try {
            val provider = providerRegistry.getProvider(step.brokerId)
                ?: return ExecutionResult.Failure(
                    error = ExecutionError(
                        category = ErrorCategory.CONTRACT_VIOLATION,
                        code = "UNKNOWN_BROKER",
                        message = "Unknown broker ID: ${step.brokerId ?: "default"}"
                    ),
                    meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = Instant.now(),
                        resourceRefs = listOf(step.topic)
                    )
                )

            val result = executeAction(step, provider)

            val endedAt = Instant.now()

            when (result) {
                is MQResult.Success -> {
                    val output = result.output
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.topic)
                    )
                    context.addStepOutput(step.stepId, output)
                    ExecutionResult.Success(output = output, meta = meta)
                }
                is MQResult.Error -> {
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.topic)
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
                    resourceRefs = listOf(step.topic)
                )
            )
        }
    }

    private fun executeAction(
        step: ExecutionStep.MessageQueueStep,
        provider: MessageQueueProvider
    ): MQResult {
        return when (step.action) {
            MessageQueueAction.PUBLISH -> executePublish(step, provider)
            MessageQueueAction.CONSUME -> executeConsume(step, provider)
            MessageQueueAction.REQUEST_REPLY -> executeRequestReply(step, provider)
        }
    }

    private fun executePublish(
        step: ExecutionStep.MessageQueueStep,
        provider: MessageQueueProvider
    ): MQResult {
        if (step.message == null) {
            return MQResult.Error(
                ExecutionError(
                    category = ErrorCategory.CONTRACT_VIOLATION,
                    code = "MESSAGE_REQUIRED",
                    message = "PUBLISH action requires message"
                )
            )
        }

        return try {
            val result = provider.publish(step.topic, step.message, step.timeoutMs)

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("PUBLISH"))
                    put("topic", JsonPrimitive(step.topic))
                    put("messageId", JsonPrimitive(result.messageId ?: ""))
                    put("success", JsonPrimitive(result.success))
                },
                artifacts = buildMap {
                    put("topic", step.topic)
                    put("message_published", step.message)
                    if (result.messageId != null) {
                        put("message_id", result.messageId)
                    }
                }
            )

            MQResult.Success(output)
        } catch (e: MessageQueueException) {
            MQResult.Error(e.toExecutionError())
        }
    }

    private fun executeConsume(
        step: ExecutionStep.MessageQueueStep,
        provider: MessageQueueProvider
    ): MQResult {
        return try {
            val result = provider.consume(step.topic, step.timeoutMs)

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("CONSUME"))
                    put("topic", JsonPrimitive(step.topic))
                    put("message", JsonPrimitive(result.message ?: ""))
                    put("received", JsonPrimitive(result.message != null))
                },
                artifacts = buildMap {
                    put("topic", step.topic)
                    if (result.message != null) {
                        put("message_received", result.message)
                    }
                    if (result.messageId != null) {
                        put("message_id", result.messageId)
                    }
                }
            )

            MQResult.Success(output)
        } catch (e: MessageQueueException) {
            MQResult.Error(e.toExecutionError())
        }
    }

    private fun executeRequestReply(
        step: ExecutionStep.MessageQueueStep,
        provider: MessageQueueProvider
    ): MQResult {
        if (step.message == null) {
            return MQResult.Error(
                ExecutionError(
                    category = ErrorCategory.CONTRACT_VIOLATION,
                    code = "MESSAGE_REQUIRED",
                    message = "REQUEST_REPLY action requires message"
                )
            )
        }

        return try {
            val result = provider.requestReply(step.topic, step.message, step.timeoutMs)

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("REQUEST_REPLY"))
                    put("topic", JsonPrimitive(step.topic))
                    put("requestMessage", JsonPrimitive(step.message))
                    put("replyMessage", JsonPrimitive(result.reply ?: ""))
                    put("received", JsonPrimitive(result.reply != null))
                },
                artifacts = buildMap {
                    put("topic", step.topic)
                    put("request_message", step.message)
                    if (result.reply != null) {
                        put("reply_message", result.reply)
                    }
                }
            )

            MQResult.Success(output)
        } catch (e: MessageQueueException) {
            MQResult.Error(e.toExecutionError())
        }
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean {
        return true
    }

    override fun canHandle(step: ExecutionStep): Boolean {
        return step is ExecutionStep.MessageQueueStep
    }

    private sealed class MQResult {
        data class Success(val output: StepOutput) : MQResult()
        data class Error(val error: ExecutionError) : MQResult()
    }

    companion object {
        val INSTANCE = MessageQueueExecutor()
    }
}

/**
 * Message Queue Provider Interface
 *
 * 다양한 메시지 큐 구현을 추상화
 */
interface MessageQueueProvider {
    /** Provider 식별자 */
    val id: String

    /** 메시지 발행 */
    fun publish(topic: String, message: String, timeoutMs: Long): PublishResult

    /** 메시지 소비 */
    fun consume(topic: String, timeoutMs: Long): ConsumeResult

    /** 요청-응답 */
    fun requestReply(topic: String, request: String, timeoutMs: Long): RequestReplyResult
}

/**
 * Provider Registry Interface
 */
interface MessageQueueProviderRegistry {
    fun getProvider(brokerId: String?): MessageQueueProvider?
    fun registerProvider(provider: MessageQueueProvider)
}

/**
 * Default Provider Registry
 */
class DefaultProviderRegistry(
    defaultProvider: MessageQueueProvider? = null
) : MessageQueueProviderRegistry {
    private val providers = ConcurrentHashMap<String, MessageQueueProvider>()
    private var _defaultProvider: MessageQueueProvider? = defaultProvider

    init {
        _defaultProvider?.let { providers[it.id] = it }
    }

    override fun getProvider(brokerId: String?): MessageQueueProvider? {
        return if (brokerId == null) _defaultProvider else providers[brokerId]
    }

    override fun registerProvider(provider: MessageQueueProvider) {
        providers[provider.id] = provider
        if (_defaultProvider == null) {
            _defaultProvider = provider
        }
    }

    fun setDefaultProvider(provider: MessageQueueProvider) {
        _defaultProvider = provider
        providers[provider.id] = provider
    }
}

/**
 * In-Memory Message Queue Provider (테스트용)
 */
class InMemoryMessageQueueProvider : MessageQueueProvider {
    override val id: String = "in-memory"

    private val queues = ConcurrentHashMap<String, LinkedBlockingQueue<QueueMessage>>()
    private var messageCounter = 0L

    override fun publish(topic: String, message: String, timeoutMs: Long): PublishResult {
        val queue = queues.computeIfAbsent(topic) { LinkedBlockingQueue() }
        val msgId = "msg-${++messageCounter}"
        queue.offer(QueueMessage(msgId, message))
        return PublishResult(success = true, messageId = msgId)
    }

    override fun consume(topic: String, timeoutMs: Long): ConsumeResult {
        val queue = queues[topic] ?: return ConsumeResult(message = null, messageId = null)
        val msg = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        return ConsumeResult(message = msg?.content, messageId = msg?.id)
    }

    override fun requestReply(topic: String, request: String, timeoutMs: Long): RequestReplyResult {
        // Simple echo for in-memory (테스트용)
        val replyTopic = "$topic.reply"
        publish(topic, request, timeoutMs)

        // For testing, check if there's a reply already queued
        val replyQueue = queues[replyTopic]
        val reply = replyQueue?.poll(timeoutMs, TimeUnit.MILLISECONDS)

        return RequestReplyResult(reply = reply?.content)
    }

    /** Queue에 메시지 추가 (테스트용) */
    fun addMessage(topic: String, message: String): String {
        val queue = queues.computeIfAbsent(topic) { LinkedBlockingQueue() }
        val msgId = "msg-${++messageCounter}"
        queue.offer(QueueMessage(msgId, message))
        return msgId
    }

    /** Queue 초기화 (테스트용) */
    fun clear() {
        queues.clear()
        messageCounter = 0
    }

    private data class QueueMessage(val id: String, val content: String)
}

// Result Types
data class PublishResult(val success: Boolean, val messageId: String?)
data class ConsumeResult(val message: String?, val messageId: String?)
data class RequestReplyResult(val reply: String?)

// Exception
class MessageQueueException(
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

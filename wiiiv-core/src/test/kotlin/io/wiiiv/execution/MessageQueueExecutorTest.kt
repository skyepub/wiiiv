package io.wiiiv.execution

import io.wiiiv.execution.impl.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Message Queue Executor 테스트
 *
 * Canonical: Executor 정의서 v1.0
 *
 * ## Executor 원칙 검증
 *
 * - 판단하지 않는다
 * - 해석하지 않는다
 * - Provider 패턴으로 다양한 MQ 지원
 */
class MessageQueueExecutorTest {

    private lateinit var executor: MessageQueueExecutor
    private lateinit var context: ExecutionContext
    private lateinit var inMemoryProvider: InMemoryMessageQueueProvider

    @BeforeEach
    fun setup() {
        inMemoryProvider = InMemoryMessageQueueProvider()
        val registry = DefaultProviderRegistry(inMemoryProvider)
        executor = MessageQueueExecutor(registry)
        context = ExecutionContext(
            executionId = "exec-mq-test",
            blueprintId = "bp-mq-test",
            instructionId = "instr-mq-test"
        )
    }

    @Test
    fun `canHandle should return true for MessageQueueStep`() {
        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-1",
            action = MessageQueueAction.PUBLISH,
            topic = "test-topic",
            message = "hello"
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
    fun `should publish message successfully`() = runTest {
        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-1",
            action = MessageQueueAction.PUBLISH,
            topic = "test-topic",
            message = "hello world"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("PUBLISH", result.output.json["action"]?.jsonPrimitive?.content)
        assertEquals("test-topic", result.output.json["topic"]?.jsonPrimitive?.content)
        assertEquals("true", result.output.json["success"]?.jsonPrimitive?.content)
        assertEquals("hello world", result.output.artifacts["message_published"])
    }

    @Test
    fun `should return error for PUBLISH without message`() = runTest {
        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-1",
            action = MessageQueueAction.PUBLISH,
            topic = "test-topic"
            // message is null
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("MESSAGE_REQUIRED", result.error.code)
    }

    @Test
    fun `should consume message successfully`() = runTest {
        // Pre-populate the queue
        inMemoryProvider.addMessage("consume-topic", "queued message")

        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-1",
            action = MessageQueueAction.CONSUME,
            topic = "consume-topic"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("CONSUME", result.output.json["action"]?.jsonPrimitive?.content)
        assertEquals("true", result.output.json["received"]?.jsonPrimitive?.content)
        assertEquals("queued message", result.output.json["message"]?.jsonPrimitive?.content)
        assertEquals("queued message", result.output.artifacts["message_received"])
    }

    @Test
    fun `should return empty result for consume from empty queue`() = runTest {
        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-1",
            action = MessageQueueAction.CONSUME,
            topic = "empty-topic",
            timeoutMs = 100
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("false", result.output.json["received"]?.jsonPrimitive?.content)
        assertNull(result.output.artifacts["message_received"])
    }

    @Test
    fun `should return error for REQUEST_REPLY without message`() = runTest {
        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-1",
            action = MessageQueueAction.REQUEST_REPLY,
            topic = "test-topic"
            // message is null
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("MESSAGE_REQUIRED", result.error.code)
    }

    @Test
    fun `should execute request reply`() = runTest {
        // Pre-populate reply
        inMemoryProvider.addMessage("request-topic.reply", "reply message")

        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-1",
            action = MessageQueueAction.REQUEST_REPLY,
            topic = "request-topic",
            message = "request message"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("REQUEST_REPLY", result.output.json["action"]?.jsonPrimitive?.content)
        assertEquals("request message", result.output.json["requestMessage"]?.jsonPrimitive?.content)
        assertEquals("request message", result.output.artifacts["request_message"])
    }

    @Test
    fun `should return error for unknown broker`() = runTest {
        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-1",
            action = MessageQueueAction.PUBLISH,
            topic = "test-topic",
            message = "hello",
            brokerId = "unknown-broker"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("UNKNOWN_BROKER", result.error.code)
    }

    @Test
    fun `step should have correct type`() {
        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-1",
            action = MessageQueueAction.PUBLISH,
            topic = "test-topic"
        )

        assertEquals(StepType.MESSAGE_QUEUE, step.type)
    }

    @Test
    fun `step should preserve all parameters`() {
        val params = mapOf("key" to "value")

        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-1",
            action = MessageQueueAction.REQUEST_REPLY,
            topic = "my-topic",
            message = "test message",
            brokerId = "kafka-broker",
            timeoutMs = 60000,
            params = params
        )

        assertEquals("mq-1", step.stepId)
        assertEquals(MessageQueueAction.REQUEST_REPLY, step.action)
        assertEquals("my-topic", step.topic)
        assertEquals("test message", step.message)
        assertEquals("kafka-broker", step.brokerId)
        assertEquals(60000, step.timeoutMs)
        assertEquals(params, step.params)
    }

    @Test
    fun `output should be added to context`() = runTest {
        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-ctx",
            action = MessageQueueAction.PUBLISH,
            topic = "test-topic",
            message = "context test"
        )

        executor.execute(step, context)

        val output = context.getStepOutput("mq-ctx")
        assertNotNull(output)
        assertEquals("mq-ctx", output.stepId)
    }

    @Test
    fun `meta should include stepId and resourceRefs`() = runTest {
        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-meta",
            action = MessageQueueAction.PUBLISH,
            topic = "meta-topic",
            message = "hello"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("mq-meta", result.meta?.stepId)
        assertTrue(result.meta?.resourceRefs?.contains("meta-topic") == true)
    }

    @Test
    fun `InMemoryProvider should manage multiple topics`() = runTest {
        // Publish to different topics
        val step1 = ExecutionStep.MessageQueueStep(
            stepId = "mq-1",
            action = MessageQueueAction.PUBLISH,
            topic = "topic-a",
            message = "message A"
        )
        val step2 = ExecutionStep.MessageQueueStep(
            stepId = "mq-2",
            action = MessageQueueAction.PUBLISH,
            topic = "topic-b",
            message = "message B"
        )

        executor.execute(step1, context)
        executor.execute(step2, context)

        // Consume from topic-a
        val consumeA = ExecutionStep.MessageQueueStep(
            stepId = "mq-3",
            action = MessageQueueAction.CONSUME,
            topic = "topic-a"
        )
        val resultA = executor.execute(consumeA, context)

        assertIs<ExecutionResult.Success>(resultA)
        assertEquals("message A", resultA.output.artifacts["message_received"])

        // Consume from topic-b
        val consumeB = ExecutionStep.MessageQueueStep(
            stepId = "mq-4",
            action = MessageQueueAction.CONSUME,
            topic = "topic-b"
        )
        val resultB = executor.execute(consumeB, context)

        assertIs<ExecutionResult.Success>(resultB)
        assertEquals("message B", resultB.output.artifacts["message_received"])
    }

    @Test
    fun `InMemoryProvider should preserve message order (FIFO)`() = runTest {
        // Publish multiple messages
        inMemoryProvider.addMessage("fifo-topic", "first")
        inMemoryProvider.addMessage("fifo-topic", "second")
        inMemoryProvider.addMessage("fifo-topic", "third")

        // Consume in order
        val consume1 = ExecutionStep.MessageQueueStep(
            stepId = "c1", action = MessageQueueAction.CONSUME, topic = "fifo-topic"
        )
        val consume2 = ExecutionStep.MessageQueueStep(
            stepId = "c2", action = MessageQueueAction.CONSUME, topic = "fifo-topic"
        )
        val consume3 = ExecutionStep.MessageQueueStep(
            stepId = "c3", action = MessageQueueAction.CONSUME, topic = "fifo-topic"
        )

        val r1 = executor.execute(consume1, context) as ExecutionResult.Success
        val r2 = executor.execute(consume2, context) as ExecutionResult.Success
        val r3 = executor.execute(consume3, context) as ExecutionResult.Success

        assertEquals("first", r1.output.artifacts["message_received"])
        assertEquals("second", r2.output.artifacts["message_received"])
        assertEquals("third", r3.output.artifacts["message_received"])
    }

    @Test
    fun `InMemoryProvider clear should reset all queues`() = runTest {
        inMemoryProvider.addMessage("topic", "message")
        inMemoryProvider.clear()

        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-1",
            action = MessageQueueAction.CONSUME,
            topic = "topic",
            timeoutMs = 100
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("false", result.output.json["received"]?.jsonPrimitive?.content)
    }

    @Test
    fun `DefaultProviderRegistry should manage providers`() {
        val registry = DefaultProviderRegistry()
        val customProvider = object : MessageQueueProvider {
            override val id = "custom"
            override fun publish(topic: String, message: String, timeoutMs: Long) =
                PublishResult(true, "custom-id")
            override fun consume(topic: String, timeoutMs: Long) =
                ConsumeResult(null, null)
            override fun requestReply(topic: String, request: String, timeoutMs: Long) =
                RequestReplyResult(null)
        }

        registry.registerProvider(customProvider)

        val provider = registry.getProvider("custom")
        assertNotNull(provider)
        assertEquals("custom", provider.id)
    }

    @Test
    fun `should work with custom provider in registry`() = runTest {
        val customProvider = object : MessageQueueProvider {
            override val id = "custom-mq"
            override fun publish(topic: String, message: String, timeoutMs: Long) =
                PublishResult(true, "custom-msg-id")
            override fun consume(topic: String, timeoutMs: Long) =
                ConsumeResult("custom response", "custom-msg-id")
            override fun requestReply(topic: String, request: String, timeoutMs: Long) =
                RequestReplyResult("custom reply")
        }

        val registry = DefaultProviderRegistry()
        registry.registerProvider(customProvider)
        val customExecutor = MessageQueueExecutor(registry)

        val step = ExecutionStep.MessageQueueStep(
            stepId = "mq-custom",
            action = MessageQueueAction.CONSUME,
            topic = "any-topic",
            brokerId = "custom-mq"
        )

        val result = customExecutor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("custom response", result.output.artifacts["message_received"])
    }
}

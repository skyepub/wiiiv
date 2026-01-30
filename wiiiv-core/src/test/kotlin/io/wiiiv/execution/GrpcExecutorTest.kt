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
import kotlin.test.assertTrue

/**
 * gRPC Executor 테스트
 *
 * Canonical: Executor 정의서 v1.0
 *
 * ## Executor 원칙 검증
 *
 * - 판단하지 않는다
 * - 해석하지 않는다
 * - Provider 패턴으로 다양한 gRPC 구현 지원
 */
class GrpcExecutorTest {

    private lateinit var executor: GrpcExecutor
    private lateinit var context: ExecutionContext
    private lateinit var mockProvider: MockGrpcProvider

    @BeforeEach
    fun setup() {
        mockProvider = MockGrpcProvider(targetPattern = "localhost:50051")
        val registry = DefaultGrpcProviderRegistry().apply {
            registerProvider(mockProvider)
        }
        executor = GrpcExecutor(registry)
        context = ExecutionContext(
            executionId = "exec-grpc-test",
            blueprintId = "bp-grpc-test",
            instructionId = "instr-grpc-test"
        )
    }

    @Test
    fun `canHandle should return true for GrpcStep`() {
        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-1",
            action = GrpcAction.UNARY,
            target = "localhost:50051",
            service = "TestService",
            method = "TestMethod"
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
    fun `should return error for invalid target format`() = runTest {
        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-1",
            action = GrpcAction.UNARY,
            target = "invalid target",
            service = "TestService",
            method = "TestMethod"
        )

        // Need to register a provider that matches this target first
        val wildcardProvider = MockGrpcProvider(targetPattern = ".*")
        val registry = DefaultGrpcProviderRegistry().apply {
            registerProvider(wildcardProvider)
        }
        val executorWithWildcard = GrpcExecutor(registry)

        val result = executorWithWildcard.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("INVALID_TARGET", result.error.code)
    }

    @Test
    fun `should return error when no provider found`() = runTest {
        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-1",
            action = GrpcAction.UNARY,
            target = "unknown:9999",
            service = "TestService",
            method = "TestMethod"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Failure>(result)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("NO_PROVIDER", result.error.code)
    }

    @Test
    fun `should execute UNARY call successfully`() = runTest {
        mockProvider.setResponse("TestService", "TestMethod",
            GrpcResponse(statusCode = 0, statusMessage = "OK", body = "test response"))

        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-1",
            action = GrpcAction.UNARY,
            target = "localhost:50051",
            service = "TestService",
            method = "TestMethod",
            request = "test request"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("UNARY", result.output.json["action"]?.jsonPrimitive?.content)
        assertEquals("TestService", result.output.json["service"]?.jsonPrimitive?.content)
        assertEquals("TestMethod", result.output.json["method"]?.jsonPrimitive?.content)
        assertEquals("test response", result.output.json["response"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should execute SERVER_STREAMING call successfully`() = runTest {
        mockProvider.setStreamResponses("TestService", "StreamMethod",
            listOf("response 1", "response 2", "response 3"))

        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-2",
            action = GrpcAction.SERVER_STREAMING,
            target = "localhost:50051",
            service = "TestService",
            method = "StreamMethod",
            request = "stream request"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("SERVER_STREAMING", result.output.json["action"]?.jsonPrimitive?.content)
        assertEquals("3", result.output.json["responseCount"]?.jsonPrimitive?.content)
        assertEquals("response 1", result.output.artifacts["response_0"])
        assertEquals("response 2", result.output.artifacts["response_1"])
        assertEquals("response 3", result.output.artifacts["response_2"])
    }

    @Test
    fun `should execute CLIENT_STREAMING call successfully`() = runTest {
        mockProvider.setResponse("TestService", "ClientStreamMethod",
            GrpcResponse(statusCode = 0, statusMessage = "OK", body = "aggregated response"))

        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-3",
            action = GrpcAction.CLIENT_STREAMING,
            target = "localhost:50051",
            service = "TestService",
            method = "ClientStreamMethod",
            request = "request 1\nrequest 2\nrequest 3"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("CLIENT_STREAMING", result.output.json["action"]?.jsonPrimitive?.content)
        assertEquals("3", result.output.json["requestCount"]?.jsonPrimitive?.content)
        assertEquals("aggregated response", result.output.json["response"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should execute BIDIRECTIONAL_STREAMING call successfully`() = runTest {
        mockProvider.setStreamResponses("TestService", "BidiMethod",
            listOf("reply 1", "reply 2"))

        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-4",
            action = GrpcAction.BIDIRECTIONAL_STREAMING,
            target = "localhost:50051",
            service = "TestService",
            method = "BidiMethod",
            request = "msg 1\nmsg 2"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("BIDIRECTIONAL_STREAMING", result.output.json["action"]?.jsonPrimitive?.content)
        assertEquals("2", result.output.json["requestCount"]?.jsonPrimitive?.content)
        assertEquals("2", result.output.json["responseCount"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should include metadata in call`() = runTest {
        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-5",
            action = GrpcAction.UNARY,
            target = "localhost:50051",
            service = "TestService",
            method = "TestMethod",
            metadata = mapOf("authorization" to "Bearer token", "x-request-id" to "123")
        )

        val result = executor.execute(step, context)

        // MockProvider receives metadata, just verify the call succeeded
        assertIs<ExecutionResult.Success>(result)
    }

    @Test
    fun `should handle gRPC error status codes`() = runTest {
        // gRPC status code 14 = UNAVAILABLE
        mockProvider.setResponse("TestService", "FailingMethod",
            GrpcResponse(statusCode = 14, statusMessage = "Service unavailable", body = null))

        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-6",
            action = GrpcAction.UNARY,
            target = "localhost:50051",
            service = "TestService",
            method = "FailingMethod"
        )

        val result = executor.execute(step, context)

        // Executor does not judge - non-OK status is still Success
        assertIs<ExecutionResult.Success>(result)
        assertEquals("14", result.output.json["statusCode"]?.jsonPrimitive?.content)
        assertEquals("Service unavailable", result.output.json["statusMessage"]?.jsonPrimitive?.content)
    }

    @Test
    fun `step should have correct type`() {
        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-1",
            action = GrpcAction.UNARY,
            target = "localhost:50051",
            service = "TestService",
            method = "TestMethod"
        )

        assertEquals(StepType.GRPC, step.type)
    }

    @Test
    fun `step should preserve all parameters`() {
        val metadata = mapOf("key" to "value")
        val params = mapOf("param" to "value")

        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-1",
            action = GrpcAction.SERVER_STREAMING,
            target = "localhost:50051",
            service = "MyService",
            method = "MyMethod",
            request = "test request",
            metadata = metadata,
            timeoutMs = 60000,
            params = params
        )

        assertEquals("grpc-1", step.stepId)
        assertEquals(GrpcAction.SERVER_STREAMING, step.action)
        assertEquals("localhost:50051", step.target)
        assertEquals("MyService", step.service)
        assertEquals("MyMethod", step.method)
        assertEquals("test request", step.request)
        assertEquals(metadata, step.metadata)
        assertEquals(60000, step.timeoutMs)
        assertEquals(params, step.params)
    }

    @Test
    fun `output should be added to context`() = runTest {
        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-ctx",
            action = GrpcAction.UNARY,
            target = "localhost:50051",
            service = "TestService",
            method = "TestMethod"
        )

        executor.execute(step, context)

        val output = context.getStepOutput("grpc-ctx")
        assertNotNull(output)
        assertEquals("grpc-ctx", output.stepId)
    }

    @Test
    fun `meta should include stepId and resourceRefs`() = runTest {
        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-meta",
            action = GrpcAction.UNARY,
            target = "localhost:50051",
            service = "TestService",
            method = "TestMethod"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("grpc-meta", result.meta?.stepId)
        assertTrue(result.meta?.resourceRefs?.contains("localhost:50051") == true)
        assertTrue(result.meta?.resourceRefs?.contains("TestService/TestMethod") == true)
    }

    @Test
    fun `should accept dns format target`() = runTest {
        val dnsProvider = MockGrpcProvider(targetPattern = "dns:///myservice.example.com:50051")
        val registry = DefaultGrpcProviderRegistry().apply {
            registerProvider(dnsProvider)
        }
        val dnsExecutor = GrpcExecutor(registry)

        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-dns",
            action = GrpcAction.UNARY,
            target = "dns:///myservice.example.com:50051",
            service = "TestService",
            method = "TestMethod"
        )

        val result = dnsExecutor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
    }

    @Test
    fun `MockGrpcProvider should echo in bidirectional streaming by default`() = runTest {
        // Don't set custom stream responses - use default echo behavior
        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-echo",
            action = GrpcAction.BIDIRECTIONAL_STREAMING,
            target = "localhost:50051",
            service = "Echo",
            method = "BidiEcho",
            request = "hello\nworld"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        assertEquals("echo: hello", result.output.artifacts["response_0"])
        assertEquals("echo: world", result.output.artifacts["response_1"])
    }

    @Test
    fun `DefaultGrpcProviderRegistry should find provider by pattern`() {
        val registry = DefaultGrpcProviderRegistry()
        val provider = MockGrpcProvider(targetPattern = "localhost:.*")
        registry.registerProvider(provider)

        val found = registry.getProvider("localhost:50051")
        assertNotNull(found)
    }

    @Test
    fun `MockGrpcProvider clear should reset responses`() = runTest {
        mockProvider.setResponse("TestService", "TestMethod",
            GrpcResponse(statusCode = 0, statusMessage = "Custom", body = "custom"))

        mockProvider.clear()

        val step = ExecutionStep.GrpcStep(
            stepId = "grpc-clear",
            action = GrpcAction.UNARY,
            target = "localhost:50051",
            service = "TestService",
            method = "TestMethod"
        )

        val result = executor.execute(step, context)

        assertIs<ExecutionResult.Success>(result)
        // Should return default mock response after clear
        assertTrue(result.output.json["response"]?.jsonPrimitive?.content?.contains("mock response") == true)
    }
}

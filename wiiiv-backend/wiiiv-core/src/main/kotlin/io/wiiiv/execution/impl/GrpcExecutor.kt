package io.wiiiv.execution.impl

import io.wiiiv.execution.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * gRPC Executor - gRPC 호출 Executor
 *
 * Canonical: Executor 정의서 v1.0, Executor Interface Spec v1.0
 *
 * ## Executor 원칙 준수
 *
 * - 판단하지 않는다: 서비스가 안전한지 판단하지 않음
 * - 해석하지 않는다: 응답의 의미를 해석하지 않음
 * - Blueprint를 신뢰한다: 정합성과 합법성이 이미 검증되었다고 가정
 *
 * ## 지원 기능
 *
 * - UNARY: 단항 호출
 * - SERVER_STREAMING: 서버 스트리밍 (응답을 리스트로 수집)
 * - CLIENT_STREAMING: 클라이언트 스트리밍 (요청을 구분자로 분리)
 * - BIDIRECTIONAL_STREAMING: 양방향 스트리밍
 *
 * ## Provider 패턴
 *
 * GrpcProvider 인터페이스를 통해 다양한 gRPC 구현 지원:
 * - TestGrpcProvider (테스트용, src/test에 위치)
 * - 향후: io.grpc 기반 구현 등
 *
 * ## 오류 처리
 *
 * - 잘못된 target → Failure (CONTRACT_VIOLATION)
 * - 연결 실패 → Failure (EXTERNAL_SERVICE_ERROR)
 * - 타임아웃 → Failure (TIMEOUT)
 * - gRPC 상태 코드 (OK 외) → Success (Executor는 판단하지 않음)
 */
class GrpcExecutor(
    private val providerRegistry: GrpcProviderRegistry = DefaultGrpcProviderRegistry()
) : Executor {

    override suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        if (step !is ExecutionStep.GrpcStep) {
            return ExecutionResult.contractViolation(
                stepId = step.stepId,
                code = "INVALID_STEP_TYPE",
                message = "GrpcExecutor can only handle GrpcStep, got: ${step::class.simpleName}"
            )
        }

        val startedAt = Instant.now()

        return try {
            val provider = providerRegistry.getProvider(step.target)
                ?: return ExecutionResult.Failure(
                    error = ExecutionError(
                        category = ErrorCategory.CONTRACT_VIOLATION,
                        code = "NO_PROVIDER",
                        message = "No gRPC provider found for target: ${step.target}"
                    ),
                    meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = Instant.now(),
                        resourceRefs = listOf(step.target)
                    )
                )

            val result = executeGrpc(step, provider)

            val endedAt = Instant.now()

            when (result) {
                is GrpcResult.Success -> {
                    val output = result.output
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.target, "${step.service}/${step.method}")
                    )
                    context.addStepOutput(step.stepId, output)
                    ExecutionResult.Success(output = output, meta = meta)
                }
                is GrpcResult.Error -> {
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.target, "${step.service}/${step.method}")
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
                    resourceRefs = listOf(step.target)
                )
            )
        }
    }

    private fun executeGrpc(step: ExecutionStep.GrpcStep, provider: GrpcProvider): GrpcResult {
        // Validate target format
        if (!isValidTarget(step.target)) {
            return GrpcResult.Error(
                ExecutionError(
                    category = ErrorCategory.CONTRACT_VIOLATION,
                    code = "INVALID_TARGET",
                    message = "Invalid gRPC target format: ${step.target}"
                )
            )
        }

        return when (step.action) {
            GrpcAction.UNARY -> executeUnary(step, provider)
            GrpcAction.SERVER_STREAMING -> executeServerStreaming(step, provider)
            GrpcAction.CLIENT_STREAMING -> executeClientStreaming(step, provider)
            GrpcAction.BIDIRECTIONAL_STREAMING -> executeBidirectionalStreaming(step, provider)
        }
    }

    private fun executeUnary(step: ExecutionStep.GrpcStep, provider: GrpcProvider): GrpcResult {
        return try {
            val response = provider.callUnary(
                service = step.service,
                method = step.method,
                request = step.request ?: "",
                metadata = step.metadata,
                timeoutMs = step.timeoutMs
            )

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("UNARY"))
                    put("target", JsonPrimitive(step.target))
                    put("service", JsonPrimitive(step.service))
                    put("method", JsonPrimitive(step.method))
                    put("statusCode", JsonPrimitive(response.statusCode))
                    put("statusMessage", JsonPrimitive(response.statusMessage ?: ""))
                    put("response", JsonPrimitive(response.body ?: ""))
                },
                artifacts = buildMap {
                    put("target", step.target)
                    put("service", step.service)
                    put("method", step.method)
                    put("status_code", response.statusCode.toString())
                    if (response.body != null) {
                        put("response_body", response.body)
                    }
                }
            )

            GrpcResult.Success(output)
        } catch (e: GrpcException) {
            GrpcResult.Error(e.toExecutionError())
        }
    }

    private fun executeServerStreaming(step: ExecutionStep.GrpcStep, provider: GrpcProvider): GrpcResult {
        return try {
            val responses = provider.callServerStreaming(
                service = step.service,
                method = step.method,
                request = step.request ?: "",
                metadata = step.metadata,
                timeoutMs = step.timeoutMs
            )

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("SERVER_STREAMING"))
                    put("target", JsonPrimitive(step.target))
                    put("service", JsonPrimitive(step.service))
                    put("method", JsonPrimitive(step.method))
                    put("responseCount", JsonPrimitive(responses.size))
                },
                artifacts = buildMap {
                    put("target", step.target)
                    put("service", step.service)
                    put("method", step.method)
                    put("response_count", responses.size.toString())
                    responses.forEachIndexed { index, response ->
                        put("response_$index", response)
                    }
                }
            )

            GrpcResult.Success(output)
        } catch (e: GrpcException) {
            GrpcResult.Error(e.toExecutionError())
        }
    }

    private fun executeClientStreaming(step: ExecutionStep.GrpcStep, provider: GrpcProvider): GrpcResult {
        return try {
            // Split request by newlines for client streaming
            val requests = step.request?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

            val response = provider.callClientStreaming(
                service = step.service,
                method = step.method,
                requests = requests,
                metadata = step.metadata,
                timeoutMs = step.timeoutMs
            )

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("CLIENT_STREAMING"))
                    put("target", JsonPrimitive(step.target))
                    put("service", JsonPrimitive(step.service))
                    put("method", JsonPrimitive(step.method))
                    put("requestCount", JsonPrimitive(requests.size))
                    put("statusCode", JsonPrimitive(response.statusCode))
                    put("response", JsonPrimitive(response.body ?: ""))
                },
                artifacts = buildMap {
                    put("target", step.target)
                    put("service", step.service)
                    put("method", step.method)
                    put("request_count", requests.size.toString())
                    put("status_code", response.statusCode.toString())
                    if (response.body != null) {
                        put("response_body", response.body)
                    }
                }
            )

            GrpcResult.Success(output)
        } catch (e: GrpcException) {
            GrpcResult.Error(e.toExecutionError())
        }
    }

    private fun executeBidirectionalStreaming(step: ExecutionStep.GrpcStep, provider: GrpcProvider): GrpcResult {
        return try {
            val requests = step.request?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

            val responses = provider.callBidirectionalStreaming(
                service = step.service,
                method = step.method,
                requests = requests,
                metadata = step.metadata,
                timeoutMs = step.timeoutMs
            )

            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("action", JsonPrimitive("BIDIRECTIONAL_STREAMING"))
                    put("target", JsonPrimitive(step.target))
                    put("service", JsonPrimitive(step.service))
                    put("method", JsonPrimitive(step.method))
                    put("requestCount", JsonPrimitive(requests.size))
                    put("responseCount", JsonPrimitive(responses.size))
                },
                artifacts = buildMap {
                    put("target", step.target)
                    put("service", step.service)
                    put("method", step.method)
                    put("request_count", requests.size.toString())
                    put("response_count", responses.size.toString())
                    responses.forEachIndexed { index, response ->
                        put("response_$index", response)
                    }
                }
            )

            GrpcResult.Success(output)
        } catch (e: GrpcException) {
            GrpcResult.Error(e.toExecutionError())
        }
    }

    private fun isValidTarget(target: String): Boolean {
        // Simple validation: host:port format or dns:///host:port
        return target.matches(Regex("^([a-zA-Z0-9.-]+:[0-9]+|dns:///[a-zA-Z0-9.-]+:[0-9]+)$"))
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean {
        return true
    }

    override fun canHandle(step: ExecutionStep): Boolean {
        return step is ExecutionStep.GrpcStep
    }

    private sealed class GrpcResult {
        data class Success(val output: StepOutput) : GrpcResult()
        data class Error(val error: ExecutionError) : GrpcResult()
    }

    companion object {
        val INSTANCE = GrpcExecutor()
    }
}

/**
 * gRPC Provider Interface
 *
 * 다양한 gRPC 구현을 추상화
 */
interface GrpcProvider {
    /** Provider가 처리할 target 패턴 */
    val targetPattern: String

    /** Unary 호출 */
    fun callUnary(
        service: String,
        method: String,
        request: String,
        metadata: Map<String, String>,
        timeoutMs: Long
    ): GrpcResponse

    /** Server Streaming 호출 */
    fun callServerStreaming(
        service: String,
        method: String,
        request: String,
        metadata: Map<String, String>,
        timeoutMs: Long
    ): List<String>

    /** Client Streaming 호출 */
    fun callClientStreaming(
        service: String,
        method: String,
        requests: List<String>,
        metadata: Map<String, String>,
        timeoutMs: Long
    ): GrpcResponse

    /** Bidirectional Streaming 호출 */
    fun callBidirectionalStreaming(
        service: String,
        method: String,
        requests: List<String>,
        metadata: Map<String, String>,
        timeoutMs: Long
    ): List<String>
}

/**
 * gRPC Provider Registry
 */
interface GrpcProviderRegistry {
    fun getProvider(target: String): GrpcProvider?
    fun registerProvider(provider: GrpcProvider)
}

/**
 * Default gRPC Provider Registry
 */
class DefaultGrpcProviderRegistry : GrpcProviderRegistry {
    private val providers = ConcurrentHashMap<String, GrpcProvider>()

    override fun getProvider(target: String): GrpcProvider? {
        // Try exact match first
        providers[target]?.let { return it }

        // Try pattern match
        return providers.values.find { provider ->
            target.matches(Regex(provider.targetPattern))
        }
    }

    override fun registerProvider(provider: GrpcProvider) {
        providers[provider.targetPattern] = provider
    }
}


// Response and Exception types
data class GrpcResponse(
    val statusCode: Int,
    val statusMessage: String?,
    val body: String?
)

class GrpcException(
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

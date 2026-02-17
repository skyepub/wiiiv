package io.wiiiv.server.dto.execution

import kotlinx.serialization.Serializable

/**
 * Execution Request - Blueprint 실행 요청
 *
 * Executor는 실행만 한다. 판단하지 않는다.
 */
@Serializable
data class ExecutionRequest(
    val blueprintId: String,
    val options: ExecutionOptions? = null
)

@Serializable
data class ExecutionOptions(
    val dryRun: Boolean = false,
    val stopOnError: Boolean = true,
    val parallelism: Int = 1,
    val timeout: Long? = null
)

/**
 * Execution Response - 실행 결과
 */
@Serializable
data class ExecutionResponse(
    val executionId: String,
    val blueprintId: String,
    val status: ExecutionStatus,
    val startedAt: String,
    val completedAt: String? = null,
    val results: List<StepResult>? = null,
    val error: ExecutionError? = null
)

@Serializable
enum class ExecutionStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Serializable
data class StepResult(
    val nodeId: String,
    val status: String,
    val output: String? = null,
    val duration: Long? = null,
    val error: String? = null
)

@Serializable
data class ExecutionError(
    val code: String,
    val message: String,
    val nodeId: String? = null,
    val recoverable: Boolean = false
)

/**
 * Execution List Response
 */
@Serializable
data class ExecutionListResponse(
    val executions: List<ExecutionSummary>,
    val total: Int,
    val page: Int,
    val pageSize: Int
)

@Serializable
data class ExecutionSummary(
    val executionId: String,
    val blueprintId: String,
    val status: ExecutionStatus,
    val startedAt: String,
    val completedAt: String? = null
)

/**
 * Cancel Execution Response
 */
@Serializable
data class CancelResponse(
    val executionId: String,
    val cancelled: Boolean,
    val message: String? = null
)

/**
 * Execution Logs Response
 */
@Serializable
data class LogsResponse(
    val executionId: String,
    val logs: List<String>
)

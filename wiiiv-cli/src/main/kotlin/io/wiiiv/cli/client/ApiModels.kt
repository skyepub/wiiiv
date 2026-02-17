package io.wiiiv.cli.client

import kotlinx.serialization.Serializable

/**
 * 서버 DTO의 클라이언트 측 복사본
 *
 * wiiiv-server의 DTO와 동일한 구조를 유지한다.
 * 향후 wiiiv-dto 공유 모듈로 분리 가능.
 */

// === Generic API Response ===

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null
)

@Serializable
data class ApiError(
    val code: String,
    val message: String
)

// === Auth ===

@Serializable
data class LoginResponse(
    val accessToken: String,
    val userId: String
)

// === Session ===

@Serializable
data class CreateSessionRequest(
    val workspace: String? = null
)

@Serializable
data class SessionResponse(
    val sessionId: String,
    val userId: String,
    val createdAt: String,
    val status: String = "ACTIVE"
)

@Serializable
data class SessionListResponse(
    val sessions: List<SessionResponse>,
    val total: Int
)

@Serializable
data class DeleteSessionResponse(
    val sessionId: String,
    val deleted: Boolean,
    val message: String
)

// === Chat ===

@Serializable
data class ChatRequest(
    val message: String,
    val images: List<ImageData>? = null,
    val autoContinue: Boolean = true,
    val maxContinue: Int = 10
)

@Serializable
data class ImageData(
    val base64: String,
    val mimeType: String
)

@Serializable
data class ChatResponse(
    val action: String,
    val message: String,
    val sessionId: String,
    val askingFor: String? = null,
    val confirmationSummary: String? = null,
    val blueprintId: String? = null,
    val executionSuccess: Boolean? = null,
    val executionStepCount: Int? = null,
    val error: String? = null,
    val nextAction: String? = null,
    val isFinal: Boolean = true,
    val executionSummary: ExecutionSummaryDto? = null
)

@Serializable
data class ProgressEventDto(
    val phase: String,
    val detail: String? = null,
    val stepIndex: Int? = null,
    val totalSteps: Int? = null
)

// === Session State ===

@Serializable
data class SessionStateResponse(
    val sessionId: String,
    val createdAt: Long,
    val turnCount: Int,
    val spec: DraftSpecDto?,
    val activeTask: TaskSummaryDto?,
    val tasks: List<TaskSummaryDto>,
    val declaredWriteIntent: Boolean?,
    val workspace: String?,
    val serverInfo: ServerInfoDto
)

@Serializable
data class DraftSpecDto(
    val id: String,
    val intent: String? = null,
    val taskType: String? = null,
    val taskTypeDisplayName: String? = null,
    val domain: String? = null,
    val techStack: List<String>? = null,
    val targetPath: String? = null,
    val content: String? = null,
    val scale: String? = null,
    val constraints: List<String>? = null,
    val isComplete: Boolean = false,
    val isRisky: Boolean = false,
    val filledSlots: List<String> = emptyList(),
    val requiredSlots: List<String> = emptyList(),
    val missingSlots: List<String> = emptyList()
)

@Serializable
data class TaskSummaryDto(
    val id: String,
    val label: String,
    val status: String,
    val taskType: String? = null,
    val taskTypeDisplayName: String? = null,
    val targetPath: String? = null,
    val specComplete: Boolean = false,
    val specRisky: Boolean = false,
    val filledSlotCount: Int = 0,
    val requiredSlotCount: Int = 0,
    val executionCount: Int = 0,
    val createdAt: Long,
    val artifacts: Map<String, String> = emptyMap()
)

@Serializable
data class ServerInfoDto(
    val modelName: String?,
    val dacsTypeName: String,
    val llmAvailable: Boolean,
    val ragAvailable: Boolean
)

// === History ===

@Serializable
data class HistoryResponse(
    val messages: List<HistoryMessageDto>,
    val total: Int
)

@Serializable
data class HistoryMessageDto(
    val role: String,
    val content: String,
    val timestamp: Long
)

// === Control ===

@Serializable
data class ControlRequest(
    val action: String,
    val targetId: String? = null,
    val workspace: String? = null
)

@Serializable
data class ControlResponse(
    val success: Boolean,
    val message: String,
    val affectedTaskId: String? = null
)

// === Execution Summary ===

@Serializable
data class ExecutionSummaryDto(
    val blueprintId: String,
    val steps: List<StepSummaryDto>,
    val totalDurationMs: Long = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0
)

@Serializable
data class StepSummaryDto(
    val stepId: String,
    val type: String,
    val params: Map<String, String>,
    val success: Boolean,
    val durationMs: Long = 0,
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Int? = null,
    val error: String? = null,
    val artifacts: Map<String, String> = emptyMap()
)

// === RAG ===

@Serializable
data class IngestRequest(
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class IngestResponse(
    val documentId: String,
    val chunkCount: Int
)

@Serializable
data class SearchRequest(
    val query: String,
    val topK: Int = 5
)

@Serializable
data class SearchResponse(
    val results: List<SearchResultDto>,
    val totalFound: Int
)

@Serializable
data class SearchResultDto(
    val content: String,
    val score: Double,
    val sourceId: String,
    val chunkIndex: Int,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class SizeResponse(
    val size: Int
)

@Serializable
data class DocumentListResponse(
    val documents: List<DocumentInfoDto>,
    val total: Int
)

@Serializable
data class DocumentInfoDto(
    val documentId: String,
    val chunkCount: Int,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class DeleteResponse(
    val deleted: Int
)

// === System ===

@Serializable
data class SystemInfoDto(
    val version: String,
    val uptime: Long,
    val status: String
)

// === Health ===

@Serializable
data class HealthResponse(
    val status: String,
    val version: String? = null
)

// === HLX Workflow ===

@Serializable
data class HlxWorkflowDto(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val nodeCount: Int,
    val createdAt: String
)

@Serializable
data class HlxWorkflowDetailDto(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val nodes: List<HlxNodeSummaryDto>,
    val trigger: String? = null,
    val createdAt: String,
    val rawJson: String
)

@Serializable
data class HlxNodeSummaryDto(
    val id: String,
    val type: String,
    val description: String
)

@Serializable
data class HlxWorkflowListDto(
    val workflows: List<HlxWorkflowDto>,
    val total: Int
)

@Serializable
data class HlxValidateDto(
    val valid: Boolean,
    val errors: List<String> = emptyList()
)

@Serializable
data class HlxDeleteDto(
    val id: String,
    val deleted: Boolean
)

// === HLX Execution ===

@Serializable
data class HlxExecutionDto(
    val executionId: String,
    val workflowId: String,
    val status: String,
    val nodeRecords: List<HlxNodeRecordSummaryDto>,
    val totalDurationMs: Long,
    val variables: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val error: String? = null
)

@Serializable
data class HlxNodeRecordSummaryDto(
    val nodeId: String,
    val nodeType: String,
    val status: String,
    val durationMs: Long,
    val error: String? = null,
    val selectedBranch: String? = null,
    val iterationCount: Int? = null
)

@Serializable
data class HlxExecutionListDto(
    val executions: List<HlxExecutionSummaryDto>,
    val total: Int
)

@Serializable
data class HlxExecutionSummaryDto(
    val executionId: String,
    val workflowId: String,
    val status: String,
    val totalDurationMs: Long,
    val executedAt: String
)

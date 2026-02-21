package io.wiiiv.server.dto.session

import kotlinx.serialization.Serializable

// === Request ===

@Serializable
data class CreateSessionRequest(
    val workspace: String? = null
)

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

// === Response ===

@Serializable
data class SessionResponse(
    val sessionId: String,
    val userId: String,
    val createdAt: String,
    val status: String = "ACTIVE",
    val projectId: Long? = null          // F-4
)

@Serializable
data class SessionListResponse(
    val sessions: List<SessionResponse>,
    val total: Int
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

@Serializable
data class DeleteSessionResponse(
    val sessionId: String,
    val deleted: Boolean,
    val message: String
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

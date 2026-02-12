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
    val status: String = "ACTIVE"
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
    val isFinal: Boolean = true
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

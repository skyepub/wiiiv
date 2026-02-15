package io.wiiiv.server.dto.hlx

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// === Request ===

@Serializable
data class HlxWorkflowCreateRequest(val workflow: String)

@Serializable
data class HlxExecuteRequest(
    val variables: Map<String, JsonElement> = emptyMap()
)

// === Response ===

@Serializable
data class HlxWorkflowResponse(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val nodeCount: Int,
    val createdAt: String
)

@Serializable
data class HlxWorkflowDetailResponse(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val nodes: List<HlxNodeSummary>,
    val trigger: String?,
    val createdAt: String,
    val rawJson: String
)

@Serializable
data class HlxNodeSummary(
    val id: String,
    val type: String,
    val description: String
)

@Serializable
data class HlxWorkflowListResponse(
    val workflows: List<HlxWorkflowResponse>,
    val total: Int
)

@Serializable
data class HlxValidateResponse(
    val valid: Boolean,
    val errors: List<String> = emptyList()
)

@Serializable
data class HlxExecutionResponse(
    val executionId: String,
    val workflowId: String,
    val status: String,
    val nodeRecords: List<HlxNodeRecordDto>,
    val totalDurationMs: Long,
    val variables: Map<String, JsonElement> = emptyMap(),
    val error: String? = null
)

@Serializable
data class HlxNodeRecordDto(
    val nodeId: String,
    val nodeType: String,
    val status: String,
    val durationMs: Long,
    val error: String? = null,
    val selectedBranch: String? = null,
    val iterationCount: Int? = null
)

@Serializable
data class HlxExecutionListResponse(
    val executions: List<HlxExecutionSummary>,
    val total: Int
)

@Serializable
data class HlxExecutionSummary(
    val executionId: String,
    val workflowId: String,
    val status: String,
    val totalDurationMs: Long,
    val executedAt: String
)

@Serializable
data class HlxDeleteResponse(
    val id: String,
    val deleted: Boolean
)

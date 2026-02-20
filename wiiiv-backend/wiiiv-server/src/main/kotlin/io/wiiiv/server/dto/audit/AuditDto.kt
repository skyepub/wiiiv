package io.wiiiv.server.dto.audit

import io.wiiiv.audit.AuditRecord
import io.wiiiv.audit.AuditStats
import kotlinx.serialization.Serializable

@Serializable
data class AuditSummaryDto(
    val auditId: String,
    val timestamp: String,
    val executionPath: String,
    val sessionId: String? = null,
    val userId: String? = null,
    val role: String? = null,
    val workflowId: String? = null,
    val workflowName: String? = null,
    val intent: String? = null,
    val taskType: String? = null,
    val status: String,
    val durationMs: Long,
    val nodeCount: Int,
    val error: String? = null,
    val governanceApproved: Boolean,
    val riskLevel: String? = null
)

@Serializable
data class AuditDetailDto(
    val auditId: String,
    val timestamp: String,
    val executionPath: String,
    val sessionId: String? = null,
    val userId: String? = null,
    val role: String? = null,
    val workflowId: String? = null,
    val workflowName: String? = null,
    val intent: String? = null,
    val taskType: String? = null,
    val status: String,
    val durationMs: Long,
    val nodeCount: Int,
    val error: String? = null,
    val governanceApproved: Boolean,
    val riskLevel: String? = null,
    val gatesPassed: String? = null,
    val deniedBy: String? = null,
    val nodeRecordsJson: String? = null,
    val gateTraceJson: String? = null
)

@Serializable
data class AuditListResponse(
    val records: List<AuditSummaryDto>,
    val total: Int
)

@Serializable
data class AuditStatsResponse(
    val totalRecords: Long,
    val completedCount: Long,
    val failedCount: Long,
    val pathCounts: Map<String, Long>
)

fun AuditRecord.toSummaryDto(): AuditSummaryDto = AuditSummaryDto(
    auditId = auditId,
    timestamp = timestamp.toString(),
    executionPath = executionPath.name,
    sessionId = sessionId,
    userId = userId,
    role = role,
    workflowId = workflowId,
    workflowName = workflowName,
    intent = intent,
    taskType = taskType,
    status = status,
    durationMs = durationMs,
    nodeCount = nodeCount,
    error = error,
    governanceApproved = governanceApproved,
    riskLevel = riskLevel
)

fun AuditRecord.toDetailDto(): AuditDetailDto = AuditDetailDto(
    auditId = auditId,
    timestamp = timestamp.toString(),
    executionPath = executionPath.name,
    sessionId = sessionId,
    userId = userId,
    role = role,
    workflowId = workflowId,
    workflowName = workflowName,
    intent = intent,
    taskType = taskType,
    status = status,
    durationMs = durationMs,
    nodeCount = nodeCount,
    error = error,
    governanceApproved = governanceApproved,
    riskLevel = riskLevel,
    gatesPassed = gatesPassed,
    deniedBy = deniedBy,
    nodeRecordsJson = nodeRecordsJson,
    gateTraceJson = gateTraceJson
)

fun AuditStats.toDto(): AuditStatsResponse = AuditStatsResponse(
    totalRecords = totalRecords,
    completedCount = completedCount,
    failedCount = failedCount,
    pathCounts = pathCounts
)

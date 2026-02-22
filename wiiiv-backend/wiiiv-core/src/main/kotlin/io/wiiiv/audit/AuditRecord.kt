package io.wiiiv.audit

import io.wiiiv.blueprint.Blueprint
import io.wiiiv.blueprint.BlueprintExecutionResult
import io.wiiiv.hlx.runner.HlxExecutionResult
import io.wiiiv.hlx.model.HlxWorkflow
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

/**
 * Audit Record - 감사 가능한 AI 실행 기록
 *
 * 엔터프라이즈 핵심 요구: "누가 무엇을 왜 실행했는지" 증명
 */
data class AuditRecord(
    val auditId: String = UUID.randomUUID().toString(),
    val timestamp: Instant = Instant.now(),
    val executionPath: ExecutionPath,
    val sessionId: String? = null,
    val userId: String? = null,
    val role: String? = null,
    val userInput: String? = null,
    val workflowId: String? = null,
    val workflowName: String? = null,
    val intent: String? = null,
    val taskType: String? = null,
    val status: String,
    val durationMs: Long = 0,
    val nodeCount: Int = 0,
    val error: String? = null,
    val governanceApproved: Boolean = true,
    val riskLevel: String? = null,
    val gatesPassed: String? = null,
    val deniedBy: String? = null,
    val projectId: Long? = null,
    val nodeRecordsJson: String? = null,
    val gateTraceJson: String? = null
)

/**
 * 실행 경로 - 어디서 실행이 트리거되었는지
 */
enum class ExecutionPath {
    /** 세션 채팅 → DB 쿼리 HLX (ConversationalGovernor.executeDbQueryHlx) */
    DB_QUERY_HLX,

    /** 세션 채팅 → API 워크플로우 HLX (ConversationalGovernor.executeHlxApiWorkflow) */
    API_WORKFLOW_HLX,

    /** Direct API → HLX 실행 (HlxRoutes POST /execute) */
    DIRECT_HLX_API,

    /** 세션 채팅 → Blueprint 직행 실행 (FILE_*, COMMAND, PROJECT_CREATE) */
    DIRECT_BLUEPRINT
}

/**
 * HlxExecutionResult → AuditRecord 팩토리
 */
object AuditRecordFactory {

    private val json = Json { prettyPrint = false }

    fun fromHlxResult(
        hlxResult: HlxExecutionResult,
        workflow: HlxWorkflow?,
        executionPath: ExecutionPath,
        sessionId: String? = null,
        userId: String? = null,
        role: String? = null,
        userInput: String? = null,
        intent: String? = null,
        taskType: String? = null,
        projectId: Long? = null
    ): AuditRecord {
        val gateTraces = hlxResult.nodeRecords
            .filter { it.gate != null }
            .map { record ->
                buildJsonObject {
                    put("nodeId", record.nodeId)
                    put("nodeType", record.nodeType.name)
                    record.gate?.let { put("gate", it) }
                }
            }

        val gateTraceJson = if (gateTraces.isNotEmpty()) {
            json.encodeToString(JsonArray.serializer(), JsonArray(gateTraces))
        } else null

        val gatesPassed = extractGatesPassed(hlxResult)
        val deniedBy = extractDeniedBy(hlxResult)
        val riskLevel = extractMaxRiskLevel(hlxResult)
        val nodeRecordsJson = encodeNodeRecords(hlxResult)

        return AuditRecord(
            executionPath = executionPath,
            sessionId = sessionId,
            userId = userId,
            role = role,
            userInput = userInput,
            workflowId = hlxResult.workflowId,
            workflowName = workflow?.name,
            intent = intent,
            taskType = taskType,
            status = hlxResult.status.name,
            durationMs = hlxResult.totalDurationMs,
            nodeCount = hlxResult.nodeRecords.size,
            error = hlxResult.error,
            governanceApproved = deniedBy == null,
            riskLevel = riskLevel,
            gatesPassed = gatesPassed,
            deniedBy = deniedBy,
            projectId = projectId,
            nodeRecordsJson = nodeRecordsJson,
            gateTraceJson = gateTraceJson
        )
    }

    /**
     * Blueprint 직행 실행 결과 → AuditRecord 팩토리
     *
     * Direct 경로는 Gate를 거치지 않으므로 gate 관련 필드는 null.
     * DACS 판정이 있었다면 dacsConsensus로 전달받아 riskLevel에 기록.
     */
    fun fromBlueprintResult(
        blueprint: Blueprint,
        result: BlueprintExecutionResult?,
        executionPath: ExecutionPath = ExecutionPath.DIRECT_BLUEPRINT,
        sessionId: String? = null,
        userId: String? = null,
        role: String? = null,
        userInput: String? = null,
        intent: String? = null,
        taskType: String? = null,
        dacsConsensus: String? = null,
        meta: Map<String, String>? = null,
        projectId: Long? = null
    ): AuditRecord {
        val status = when {
            result == null -> "ERROR"
            result.isSuccess -> "COMPLETED"
            else -> "FAILED"
        }

        val metaJson = if (!meta.isNullOrEmpty()) {
            json.encodeToString(JsonObject.serializer(), buildJsonObject {
                meta.forEach { (k, v) -> put(k, v) }
            })
        } else null

        return AuditRecord(
            executionPath = executionPath,
            sessionId = sessionId,
            userId = userId,
            role = role,
            userInput = userInput,
            workflowId = blueprint.id,
            workflowName = blueprint.specSnapshot.specId,
            intent = intent,
            taskType = taskType,
            status = status,
            durationMs = 0,
            nodeCount = blueprint.steps.size,
            error = if (status == "ERROR") "BlueprintRunner returned null" else null,
            governanceApproved = true,
            riskLevel = dacsConsensus,
            gatesPassed = null,
            deniedBy = null,
            projectId = projectId,
            nodeRecordsJson = metaJson,
            gateTraceJson = null
        )
    }

    private fun extractGatesPassed(hlxResult: HlxExecutionResult): String? =
        hlxResult.nodeRecords
            .mapNotNull { record ->
                val gate = record.gate as? JsonObject ?: return@mapNotNull null
                val allowed = gate["allowed"]?.jsonPrimitive?.booleanOrNull ?: true
                if (allowed) gate["gateName"]?.jsonPrimitive?.contentOrNull else null
            }
            .distinct()
            .joinToString(",")
            .ifBlank { null }

    private fun extractDeniedBy(hlxResult: HlxExecutionResult): String? =
        hlxResult.nodeRecords
            .mapNotNull { record ->
                val gate = record.gate as? JsonObject ?: return@mapNotNull null
                val allowed = gate["allowed"]?.jsonPrimitive?.booleanOrNull ?: true
                if (!allowed) gate["gateName"]?.jsonPrimitive?.contentOrNull else null
            }
            .firstOrNull()

    private fun extractMaxRiskLevel(hlxResult: HlxExecutionResult): String? =
        hlxResult.nodeRecords
            .mapNotNull { record ->
                val gate = record.gate as? JsonObject ?: return@mapNotNull null
                gate["riskLevel"]?.jsonPrimitive?.contentOrNull
            }
            .maxByOrNull { when (it) { "HIGH" -> 3; "MEDIUM" -> 2; "LOW" -> 1; else -> 0 } }

    private fun encodeNodeRecords(hlxResult: HlxExecutionResult): String? = try {
        val nodeArray = hlxResult.nodeRecords.map { record ->
            buildJsonObject {
                put("nodeId", record.nodeId)
                put("nodeType", record.nodeType.name)
                put("status", record.status.name)
                put("durationMs", record.durationMs)
                record.error?.let { put("error", it) }
                record.selectedBranch?.let { put("selectedBranch", it) }
                record.iterationCount?.let { put("iterationCount", it) }
            }
        }
        json.encodeToString(JsonArray.serializer(), JsonArray(nodeArray))
    } catch (_: Exception) { null }
}

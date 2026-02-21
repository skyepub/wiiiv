package io.wiiiv.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.execution.*
import io.wiiiv.gate.GateContext
import io.wiiiv.gate.GateResult
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.registry.WiiivRegistry
import kotlinx.serialization.json.*

/**
 * Dev Routes - Executor 스모크 테스트 (개발 전용)
 *
 * GET  /api/v2/dev/executor-status - 활성 Executor 목록
 * POST /api/v2/dev/executor-test  - 단일 Executor 직접 실행
 * POST /api/v2/dev/chain-test     - 멀티 Executor 체인 실행 (step간 데이터 파이프라인)
 *
 * Gate/Blueprint/Governor를 우회하여 CompositeExecutor를 직접 호출한다.
 */
fun Route.devRoutes() {
    route("/dev") {

        // 활성 Executor 목록
        get("/executor-status") {
            val probes = mapOf(
                "FILE" to ExecutionStep.FileStep(stepId = "probe", action = FileAction.READ, path = "/dev/null"),
                "COMMAND" to ExecutionStep.CommandStep(stepId = "probe", command = "echo"),
                "API" to ExecutionStep.ApiCallStep(stepId = "probe", method = HttpMethod.GET, url = "http://localhost"),
                "LLM" to ExecutionStep.LlmCallStep(stepId = "probe", action = LlmAction.COMPLETE, prompt = "test"),
                "DB" to ExecutionStep.DbStep(stepId = "probe", sql = "SELECT 1", mode = DbMode.QUERY),
                "MQ" to ExecutionStep.MessageQueueStep(stepId = "probe", action = MessageQueueAction.PUBLISH, topic = "test"),
            )
            val status = probes.mapValues { (_, step) ->
                if (WiiivRegistry.compositeExecutor.canHandle(step)) "ACTIVE" else "DISABLED"
            }
            call.respond(ApiResponse.success(status))
        }

        // 단일 Executor 스모크 테스트
        post("/executor-test") {
            val body = call.receiveText()
            val json = Json.parseToJsonElement(body).jsonObject

            val executor = json["executor"]?.jsonPrimitive?.contentOrNull?.uppercase()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    ApiResponse.error<String>(ApiError("MISSING_FIELD", "executor field required")))

            val step: ExecutionStep = try {
                when (executor) {
                    "DB" -> {
                        val sql = json["sql"]?.jsonPrimitive?.contentOrNull ?: "SELECT 1"
                        val mode = json["mode"]?.jsonPrimitive?.contentOrNull?.let { DbMode.valueOf(it.uppercase()) } ?: DbMode.QUERY
                        ExecutionStep.DbStep(stepId = "smoke-db", sql = sql, mode = mode)
                    }
                    "LLM" -> {
                        val prompt = json["prompt"]?.jsonPrimitive?.contentOrNull ?: "Say hello in one word."
                        val action = json["action"]?.jsonPrimitive?.contentOrNull?.let { LlmAction.valueOf(it.uppercase()) } ?: LlmAction.COMPLETE
                        ExecutionStep.LlmCallStep(stepId = "smoke-llm", action = action, prompt = prompt)
                    }
                    "MQ" -> {
                        val topic = json["topic"]?.jsonPrimitive?.contentOrNull ?: "smoke-test"
                        val message = json["message"]?.jsonPrimitive?.contentOrNull ?: """{"event":"smoke-test"}"""
                        val action = json["action"]?.jsonPrimitive?.contentOrNull?.let { MessageQueueAction.valueOf(it.uppercase()) } ?: MessageQueueAction.PUBLISH
                        ExecutionStep.MessageQueueStep(stepId = "smoke-mq", action = action, topic = topic, message = message)
                    }
                    "FILE" -> {
                        val path = json["path"]?.jsonPrimitive?.contentOrNull ?: "/tmp/wiiiv-smoke-test.txt"
                        val action = json["action"]?.jsonPrimitive?.contentOrNull?.let { FileAction.valueOf(it.uppercase()) } ?: FileAction.READ
                        val content = json["content"]?.jsonPrimitive?.contentOrNull
                        ExecutionStep.FileStep(stepId = "smoke-file", action = action, path = path, content = content)
                    }
                    "PLUGIN" -> {
                        val pluginId = json["pluginId"]?.jsonPrimitive?.contentOrNull
                            ?: return@post call.respond(HttpStatusCode.BadRequest,
                                ApiResponse.error<String>(ApiError("MISSING_FIELD", "pluginId required")))
                        val action = json["action"]?.jsonPrimitive?.contentOrNull
                            ?: return@post call.respond(HttpStatusCode.BadRequest,
                                ApiResponse.error<String>(ApiError("MISSING_FIELD", "action required")))
                        val params = json.filterKeys { it !in setOf("executor", "pluginId", "action") }
                            .mapValues { it.value.jsonPrimitive.content }
                        ExecutionStep.PluginStep(stepId = "smoke-plugin-$pluginId", pluginId = pluginId, action = action, params = params)
                    }
                    else -> return@post call.respond(HttpStatusCode.BadRequest,
                        ApiResponse.error<String>(ApiError("UNKNOWN_EXECUTOR", "Supported: DB, LLM, MQ, FILE, PLUGIN")))
                }
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    ApiResponse.error<String>(ApiError("INVALID_PARAMS", e.message ?: "Invalid parameters")))
            }

            // canHandle 확인
            if (!WiiivRegistry.compositeExecutor.canHandle(step)) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable,
                    ApiResponse.error<String>(ApiError("EXECUTOR_DISABLED", "$executor executor is not active")))
            }

            // 실행
            val context = ExecutionContext(executionId = "smoke-test", blueprintId = "smoke-test", instructionId = "smoke-test")
            val result = try {
                WiiivRegistry.compositeExecutor.execute(step, context)
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.InternalServerError,
                    ApiResponse.error<String>(ApiError("EXECUTION_ERROR", e.message ?: "Unknown error")))
            }

            // 결과 반환
            val response = buildJsonObject {
                put("executor", JsonPrimitive(executor))
                put("stepId", JsonPrimitive(step.stepId))
                put("success", JsonPrimitive(result is ExecutionResult.Success))
                when (result) {
                    is ExecutionResult.Success -> {
                        result.output?.let { output ->
                            put("output", JsonObject(output.json))
                            if (output.artifacts.isNotEmpty()) {
                                put("artifacts", buildJsonObject {
                                    output.artifacts.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                                })
                            }
                        }
                    }
                    is ExecutionResult.Failure -> {
                        put("error", buildJsonObject {
                            put("category", JsonPrimitive(result.error.category.name))
                            put("code", JsonPrimitive(result.error.code))
                            put("message", JsonPrimitive(result.error.message))
                        })
                    }
                    is ExecutionResult.Cancelled -> {
                        put("cancelled", JsonPrimitive(true))
                        put("reason", JsonPrimitive(result.reason.message))
                    }
                }
                result.meta?.let { meta ->
                    put("durationMs", JsonPrimitive(meta.durationMs))
                }
            }

            call.respond(ApiResponse.success(response))
        }

        // 멀티 Executor 체인 테스트 — step간 데이터 파이프라인 + 거버넌스
        //
        // governance: true  → 각 step에 RiskLevel 정책 + GateChain 평가 적용
        // governance: false → Gate 우회 (기존 동작)
        // role: "ADMIN" | "OPERATOR" | "VIEWER" → maxRiskLevel 자동 결정
        // maxRiskLevel: "LOW" | "MEDIUM" | "HIGH" (role보다 명시적 지정이 우선)
        post("/chain-test") {
            val body = call.receiveText()
            val json = try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    ApiResponse.error<String>(ApiError("INVALID_JSON", e.message ?: "Invalid JSON")))
            }

            val steps = json["steps"]?.jsonArray
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    ApiResponse.error<String>(ApiError("MISSING_FIELD", "steps array required")))

            if (steps.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    ApiResponse.error<String>(ApiError("EMPTY_CHAIN", "At least one step required")))
            }

            // 거버넌스 설정: role → maxRiskLevel 자동 매핑, 명시적 maxRiskLevel이 있으면 우선
            val governance = json["governance"]?.jsonPrimitive?.booleanOrNull ?: false
            val role = json["role"]?.jsonPrimitive?.contentOrNull?.uppercase()
            val maxRiskLevel = if (governance) {
                // 명시적 maxRiskLevel이 있으면 우선
                val explicit = json["maxRiskLevel"]?.jsonPrimitive?.contentOrNull?.uppercase()
                if (explicit != null) {
                    try { RiskLevel.valueOf(explicit) } catch (_: Exception) { RiskLevel.MEDIUM }
                } else {
                    // role 기반 자동 결정
                    resolveRolePolicy(role)?.maxRisk ?: RiskLevel.MEDIUM
                }
            } else null

            val stepOutputs = mutableMapOf<String, Map<String, JsonElement>>()
            val results = mutableListOf<JsonObject>()
            var chainSuccess = true

            for ((index, stepJson) in steps.withIndex()) {
                val stepObj = stepJson.jsonObject
                val stepId = stepObj["id"]?.jsonPrimitive?.contentOrNull ?: "step${index + 1}"
                val executor = stepObj["executor"]?.jsonPrimitive?.contentOrNull?.uppercase()
                    ?: return@post call.respond(HttpStatusCode.BadRequest,
                        ApiResponse.error<String>(ApiError("MISSING_FIELD", "executor required in step $stepId")))

                // 템플릿 치환: {{stepId.path}} → 이전 step 결과값
                val resolvedStep = resolveTemplates(stepObj, stepOutputs)

                // ExecutionStep 생성
                val step = try {
                    buildExecutionStep(executor, stepId, resolvedStep)
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest,
                        ApiResponse.error<String>(ApiError("INVALID_STEP", "Step $stepId: ${e.message}")))
                }
                if (step == null) {
                    return@post call.respond(HttpStatusCode.BadRequest,
                        ApiResponse.error<String>(ApiError("UNKNOWN_EXECUTOR", "Step $stepId: unknown executor $executor")))
                }

                // canHandle 확인
                if (!WiiivRegistry.compositeExecutor.canHandle(step)) {
                    return@post call.respond(HttpStatusCode.ServiceUnavailable,
                        ApiResponse.error<String>(ApiError("EXECUTOR_DISABLED", "Step $stepId: $executor executor is not active")))
                }

                // === 거버넌스 평가 ===
                val governanceInfo = if (governance) {
                    evaluateGovernance(executor, stepId, resolvedStep, maxRiskLevel!!)
                } else null

                if (governanceInfo != null && governanceInfo.denied) {
                    println("[CHAIN] step=$stepId executor=$executor DENIED by ${governanceInfo.deniedBy}")
                    chainSuccess = false
                    results.add(buildJsonObject {
                        put("stepId", JsonPrimitive(stepId))
                        put("executor", JsonPrimitive(executor))
                        put("success", JsonPrimitive(false))
                        put("governance", governanceInfo.toJson())
                    })
                    break
                }

                // 실행
                println("[CHAIN] step=$stepId executor=$executor" +
                    if (governanceInfo != null) " riskLevel=${governanceInfo.riskLevel} gate=APPROVED" else "")
                val context = ExecutionContext(executionId = "chain-test", blueprintId = "chain-test", instructionId = stepId)
                val result = try {
                    WiiivRegistry.compositeExecutor.execute(step, context)
                } catch (e: Exception) {
                    chainSuccess = false
                    results.add(buildJsonObject {
                        put("stepId", JsonPrimitive(stepId))
                        put("executor", JsonPrimitive(executor))
                        put("success", JsonPrimitive(false))
                        put("error", JsonPrimitive(e.message ?: "Execution error"))
                        governanceInfo?.let { put("governance", it.toJson()) }
                    })
                    break
                }

                // 출력 저장 (다음 step의 템플릿 치환용)
                if (result is ExecutionResult.Success) {
                    stepOutputs[stepId] = result.output.json
                }

                // 결과 기록
                results.add(buildJsonObject {
                    put("stepId", JsonPrimitive(stepId))
                    put("executor", JsonPrimitive(executor))
                    put("success", JsonPrimitive(result is ExecutionResult.Success))
                    governanceInfo?.let { put("governance", it.toJson()) }
                    when (result) {
                        is ExecutionResult.Success -> put("output", JsonObject(result.output.json))
                        is ExecutionResult.Failure -> put("error", JsonPrimitive(result.error.message))
                        is ExecutionResult.Cancelled -> put("cancelled", JsonPrimitive(result.reason.message))
                    }
                    result.meta?.let { put("durationMs", JsonPrimitive(it.durationMs)) }
                })

                // Fail-fast: step 실패 시 체인 중단
                if (result !is ExecutionResult.Success) {
                    chainSuccess = false
                    break
                }
            }

            call.respond(ApiResponse.success(buildJsonObject {
                put("chainSuccess", JsonPrimitive(chainSuccess))
                put("totalSteps", JsonPrimitive(steps.size))
                put("completedSteps", JsonPrimitive(results.size))
                if (governance) {
                    put("governance", buildJsonObject {
                        put("enabled", JsonPrimitive(true))
                        role?.let { put("role", JsonPrimitive(it)) }
                        put("maxRiskLevel", JsonPrimitive(maxRiskLevel!!.name))
                    })
                }
                put("steps", JsonArray(results))
            }))
        }
    }
}

// === Helper: Role 기반 정책 ===

private data class RolePolicy(
    val role: String,
    val maxRisk: RiskLevel
    // 확장 여지: allowedExecutors, timeWindow, costLimit 등
)

private val rolePolicies = mapOf(
    "ADMIN" to RolePolicy("ADMIN", RiskLevel.HIGH),
    "OPERATOR" to RolePolicy("OPERATOR", RiskLevel.MEDIUM),
    "VIEWER" to RolePolicy("VIEWER", RiskLevel.LOW)
)

private fun resolveRolePolicy(role: String?): RolePolicy? =
    role?.let { rolePolicies[it.uppercase()] }

// === Helper: 거버넌스 평가 ===

private data class GovernanceDecision(
    val riskLevel: String,
    val capabilities: List<String>,
    val maxAllowed: String,
    val denied: Boolean,
    val deniedBy: String?,
    val reason: String?,
    val gateDecision: String,
    val gatesPassed: Int,
    val stoppedAt: String?
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("riskLevel", JsonPrimitive(riskLevel))
        put("capabilities", JsonArray(capabilities.map { JsonPrimitive(it) }))
        put("maxAllowed", JsonPrimitive(maxAllowed))
        put("decision", JsonPrimitive(if (denied) "DENIED" else "APPROVED"))
        deniedBy?.let { put("deniedBy", JsonPrimitive(it)) }
        reason?.let { put("reason", JsonPrimitive(it)) }
        put("gateDecision", JsonPrimitive(gateDecision))
        put("gatesPassed", JsonPrimitive(gatesPassed))
        stoppedAt?.let { put("stoppedAt", JsonPrimitive(it)) }
    }
}

private fun evaluateGovernance(
    executor: String,
    stepId: String,
    step: JsonObject,
    maxRiskLevel: RiskLevel
): GovernanceDecision {
    val scheme = executorToScheme(executor)
    val meta = scheme?.let { WiiivRegistry.executorMetaRegistry.getByScheme(it) }

    val riskLevel = meta?.riskLevel ?: RiskLevel.MEDIUM
    val capabilities = meta?.capabilities?.map { it.name } ?: emptyList()

    // 1. RiskLevel 정책 검사
    if (riskLevel > maxRiskLevel) {
        return GovernanceDecision(
            riskLevel = riskLevel.name,
            capabilities = capabilities,
            maxAllowed = maxRiskLevel.name,
            denied = true,
            deniedBy = "RISK_POLICY",
            reason = "Risk level $riskLevel exceeds maximum allowed $maxRiskLevel",
            gateDecision = "SKIPPED",
            gatesPassed = 0,
            stoppedAt = "risk-policy"
        )
    }

    // 2. GateChain 검사 (DACS=YES, UserApproved=true로 기본 통과시키고 Permission/Cost 검사)
    val action = determineAction(executor, step)
    val gateContext = GateContext(
        requestId = "chain-$stepId",
        blueprintId = "chain-test",
        dacsConsensus = "YES",
        userApproved = true,
        executorId = "${scheme}-executor",
        action = action,
        estimatedCost = 0.0,
        costLimit = 100.0
    )
    val gateResult = WiiivRegistry.gateChain.check(gateContext)

    return if (gateResult.isDeny) {
        val deny = gateResult.finalResult as GateResult.Deny
        GovernanceDecision(
            riskLevel = riskLevel.name,
            capabilities = capabilities,
            maxAllowed = maxRiskLevel.name,
            denied = true,
            deniedBy = "GATE_CHAIN",
            reason = "Gate denied: ${deny.code}",
            gateDecision = "DENIED",
            gatesPassed = gateResult.passedCount,
            stoppedAt = gateResult.stoppedAt
        )
    } else {
        GovernanceDecision(
            riskLevel = riskLevel.name,
            capabilities = capabilities,
            maxAllowed = maxRiskLevel.name,
            denied = false,
            deniedBy = null,
            reason = null,
            gateDecision = "APPROVED",
            gatesPassed = gateResult.passedCount,
            stoppedAt = null
        )
    }
}

private fun executorToScheme(executor: String): String? = when (executor) {
    "DB" -> "db"
    "LLM" -> "llm"
    "MQ" -> "mq"
    "FILE" -> "file"
    "COMMAND" -> "os"
    "API" -> "http"
    else -> null
}

private fun determineAction(executor: String, step: JsonObject): String {
    return when (executor) {
        "DB" -> {
            val mode = step["mode"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "QUERY"
            if (mode == "QUERY") "READ" else "WRITE"
        }
        "LLM" -> "READ"
        "MQ" -> {
            val action = step["action"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "PUBLISH"
            if (action == "CONSUME") "READ" else "SEND"
        }
        "FILE" -> step["action"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "READ"
        "COMMAND" -> "EXECUTE"
        else -> "EXECUTE"
    }
}

// === Helper: ExecutionStep 생성 ===

private fun buildExecutionStep(executor: String, stepId: String, step: JsonObject): ExecutionStep? {
    return when (executor) {
        "DB" -> {
            val sql = step["sql"]?.jsonPrimitive?.contentOrNull ?: "SELECT 1"
            val mode = step["mode"]?.jsonPrimitive?.contentOrNull?.let { DbMode.valueOf(it.uppercase()) } ?: DbMode.QUERY
            ExecutionStep.DbStep(stepId = stepId, sql = sql, mode = mode)
        }
        "LLM" -> {
            val prompt = step["prompt"]?.jsonPrimitive?.contentOrNull ?: "Say hello."
            val action = step["action"]?.jsonPrimitive?.contentOrNull?.let { LlmAction.valueOf(it.uppercase()) } ?: LlmAction.COMPLETE
            ExecutionStep.LlmCallStep(stepId = stepId, action = action, prompt = prompt)
        }
        "MQ" -> {
            val topic = step["topic"]?.jsonPrimitive?.contentOrNull ?: "chain-test"
            val message = step["message"]?.jsonPrimitive?.contentOrNull ?: """{"event":"chain-test"}"""
            val action = step["action"]?.jsonPrimitive?.contentOrNull?.let { MessageQueueAction.valueOf(it.uppercase()) } ?: MessageQueueAction.PUBLISH
            ExecutionStep.MessageQueueStep(stepId = stepId, action = action, topic = topic, message = message)
        }
        "FILE" -> {
            val path = step["path"]?.jsonPrimitive?.contentOrNull ?: "/tmp/wiiiv-chain-test.txt"
            val action = step["action"]?.jsonPrimitive?.contentOrNull?.let { FileAction.valueOf(it.uppercase()) } ?: FileAction.READ
            val content = step["content"]?.jsonPrimitive?.contentOrNull
            ExecutionStep.FileStep(stepId = stepId, action = action, path = path, content = content)
        }
        else -> null
    }
}

// === Helper: 템플릿 치환 ===

private val templatePattern = Regex("""\{\{(\w+)\.(.+?)\}\}""")

/**
 * step 정의의 문자열 필드에서 {{stepId.jsonPath}} 패턴을 이전 step 결과값으로 치환.
 * 예: {{step1.rows[0].total}} → "42"
 */
private fun resolveTemplates(stepObj: JsonObject, outputs: Map<String, Map<String, JsonElement>>): JsonObject {
    if (outputs.isEmpty()) return stepObj
    return JsonObject(stepObj.mapValues { (key, value) ->
        if (key == "id" || key == "executor") value  // 메타 필드는 건드리지 않음
        else if (value is JsonPrimitive && value.isString) {
            JsonPrimitive(resolveTemplateString(value.content, outputs))
        } else value
    })
}

private fun resolveTemplateString(template: String, outputs: Map<String, Map<String, JsonElement>>): String {
    return templatePattern.replace(template) { match ->
        val stepId = match.groupValues[1]
        val path = match.groupValues[2]
        val output = outputs[stepId] ?: return@replace match.value
        val resolved = resolveJsonPath(output, path)
        if (resolved == null || resolved is JsonNull) match.value
        else if (resolved is JsonPrimitive) resolved.content
        else resolved.toString()
    }
}

/**
 * JSON path 해석: "rows[0].total" → json["rows"][0]["total"]
 * 지원 패턴: dot notation + array index
 */
private fun resolveJsonPath(json: Map<String, JsonElement>, path: String): JsonElement? {
    val segments = mutableListOf<Any>() // String=key, Int=array index
    for (part in path.split(".")) {
        val arrayMatch = Regex("""(\w+)\[(\d+)]""").matchEntire(part)
        if (arrayMatch != null) {
            segments.add(arrayMatch.groupValues[1])
            segments.add(arrayMatch.groupValues[2].toInt())
        } else {
            segments.add(part)
        }
    }

    var current: JsonElement = JsonObject(json)
    for (segment in segments) {
        current = when {
            segment is String && current is JsonObject -> (current as JsonObject)[segment] ?: return null
            segment is Int && current is JsonArray -> (current as JsonArray).getOrNull(segment) ?: return null
            else -> return null
        }
    }
    return current
}

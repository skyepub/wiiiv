package io.wiiiv.hlx.runner

import io.wiiiv.blueprint.BlueprintStep
import io.wiiiv.blueprint.BlueprintStepType
import io.wiiiv.execution.*
import io.wiiiv.execution.impl.LlmProvider
import io.wiiiv.execution.impl.LlmRequest
import io.wiiiv.gate.Gate
import io.wiiiv.gate.GateChain
import io.wiiiv.gate.GateContext
import io.wiiiv.gate.GateResult
import io.wiiiv.hlx.model.HlxContext
import io.wiiiv.hlx.model.HlxNode
import io.wiiiv.hlx.model.TransformHint
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * 노드 실행 결과 (Observe, Transform, Act)
 */
sealed class NodeExecutionResult {
    abstract val gate: JsonElement?
    data class Success(val output: JsonElement, override val gate: JsonElement? = null) : NodeExecutionResult()
    data class Failure(val error: String, override val gate: JsonElement? = null) : NodeExecutionResult()
}

/**
 * Decide 노드 실행 결과
 */
sealed class DecideResult {
    data class BranchSelected(val branchKey: String) : DecideResult()
    data class Failure(val error: String) : DecideResult()
}

/**
 * HLX Node Executor - 개별 노드 LLM 실행기
 *
 * 각 노드 타입에 맞는 프롬프트를 구성하고, LLM을 호출하여 결과를 반환한다.
 * HLX의 핵심: LLM이 description을 해석하고 판단하며 실행한다.
 */
class HlxNodeExecutor(
    private val llmProvider: LlmProvider,
    private val model: String? = null,
    private val executor: Executor? = null,
    private val gate: Gate? = null,
    private val gateChain: GateChain? = null,
    private val executorMetaRegistry: ExecutorMetaRegistry? = null
) {

    /**
     * Observe 노드 실행
     */
    suspend fun executeObserve(node: HlxNode.Observe, context: HlxContext): NodeExecutionResult {
        val prompt = HlxPrompt.observe(node, context)
        return callLlmAndExtractResult(prompt)
    }

    /**
     * Transform 노드 실행
     *
     * Phase 1: 코드 추출 가능하면 먼저 시도, 실패 시 LLM 폴백
     * - hint=EXTRACT
     * - description에 "extract" 포함
     * - description에 "Parse the body ... as JSON" 포함
     */
    suspend fun executeTransform(node: HlxNode.Transform, context: HlxContext): NodeExecutionResult {
        // BUG-004: 입력 데이터가 실패한 노드에서 온 것인지 확인
        val inputVar = node.input
        if (inputVar != null) {
            val inputValue = context.variables[inputVar]
            if (inputValue is JsonObject && inputValue["_error"]?.jsonPrimitive?.booleanOrNull == true) {
                val failedNode = inputValue["_nodeId"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val errorMsg = inputValue["_message"]?.jsonPrimitive?.contentOrNull ?: "upstream node failed"
                return NodeExecutionResult.Failure(
                    "Transform '${node.id}' cannot proceed: input '$inputVar' from failed node '$failedNode': $errorMsg"
                )
            }
        }

        // Phase 0: SET hint — 즉시 값 세팅 (cachedTokens 주입 등)
        if (node.hint == TransformHint.SET && node.value != null) {
            val setResult = CodeExtractor.trySet(node, context)
            if (setResult != null) {
                return NodeExecutionResult.Success(setResult)
            }
        }

        // Phase 1: 코드 추출 가능 여부 판단
        val descLower = node.description.lowercase()
        val canTryCode = node.hint in setOf(
                TransformHint.EXTRACT,
                TransformHint.AGGREGATE,
                TransformHint.SORT,
                TransformHint.FILTER,
                TransformHint.MAP,
                TransformHint.MERGE,
                TransformHint.SET
            )
            || descLower.contains("extract")
            || descLower.contains("parse")
            || descLower.contains("aggregate")
            || descLower.contains("sort")
            || descLower.contains("filter where")
            || descLower.contains("select ")
            || descLower.contains("merge")

        if (canTryCode) {
            val codeResult = CodeExtractor.trySet(node, context)
                ?: CodeExtractor.tryMerge(node, context)
                ?: CodeExtractor.tryExtract(node, context)
                ?: CodeExtractor.tryCompute(node, context)
            if (codeResult != null) {
                println("[HLX-CODE] Code transform succeeded for '${node.id}': ${codeResult.toString().take(200)}")
                return NodeExecutionResult.Success(codeResult)
            }
            println("[HLX-CODE] Code transform failed for '${node.id}' (hint=${node.hint}), falling back to LLM")
        }
        // 기존 LLM 경로
        val prompt = HlxPrompt.transform(node, context)
        return callLlmAndExtractResult(prompt)
    }

    /**
     * Decide 노드 실행
     */
    suspend fun executeDecide(node: HlxNode.Decide, context: HlxContext): DecideResult {
        val prompt = HlxPrompt.decide(node, context)
        return try {
            val response = callLlm(prompt)
            val json = extractJson(response)
            val branch = json.jsonObject["branch"]?.jsonPrimitive?.content
                ?: return DecideResult.Failure("LLM response missing 'branch' field: $response")
            DecideResult.BranchSelected(branch)
        } catch (e: Exception) {
            DecideResult.Failure("Decide node execution failed: ${e.message}")
        }
    }

    /**
     * Act 노드 실행
     *
     * executor가 없으면 기존 LLM-only 경로, 있으면 Phase 4 Executor 연동 경로.
     */
    suspend fun executeAct(node: HlxNode.Act, context: HlxContext): NodeExecutionResult {
        return if (executor == null) {
            // LLM-only 경로 (기존 동작 유지)
            val prompt = HlxPrompt.act(node, context)
            callLlmAndExtractResult(prompt)
        } else {
            // Phase 4: Executor 연동 경로
            executeActWithExecutor(node, context)
        }
    }

    /**
     * Act 노드 Executor 연동 실행 (Phase 4)
     *
     * 1. LLM에게 actExecution 프롬프트로 구조화된 Step 요청
     * 2. JSON 응답에서 step.type + step.params 추출 → BlueprintStep 생성
     * 3. Gate 체크 (gate가 null이면 스킵)
     * 4. BlueprintStep.toExecutionStep() → executor.execute()
     * 5. ExecutionResult → NodeExecutionResult로 변환
     */
    private suspend fun executeActWithExecutor(node: HlxNode.Act, context: HlxContext): NodeExecutionResult {
        return try {
            // 1. LLM에게 구조화된 Step 요청
            val prompt = HlxPrompt.actExecution(node, context)
            val response = callLlm(prompt)
            println("[HLX-ACT] Node: ${node.id}, LLM response: ${response.take(500)}")
            val json = extractJson(response)

            // 2. step.type + step.params 추출
            val stepObj = json.jsonObject["step"]?.jsonObject
                ?: return NodeExecutionResult.Failure("LLM response missing 'step' field: $response")

            val typeStr = stepObj["type"]?.jsonPrimitive?.content
                ?: return NodeExecutionResult.Failure("LLM response missing 'step.type' field: $response")

            val paramsObj = stepObj["params"]?.jsonObject ?: buildJsonObject { }
            val rawParams = paramsObj.mapValues { entry ->
                when (val v = entry.value) {
                    is JsonPrimitive -> v.content
                    else -> v.toString() // JsonObject/JsonArray → serialize to string
                }
            }

            // 3. 템플릿 변수 해결: {변수} 및 {변수.필드} → 실제 값
            val params = rawParams.mapValues { (_, value) ->
                resolveTemplateVariables(value, context)
            }

            // BlueprintStepType 파싱
            val stepType = try {
                BlueprintStepType.valueOf(typeStr.uppercase())
            } catch (_: IllegalArgumentException) {
                return NodeExecutionResult.Failure("Unknown step type: $typeStr")
            }

            // BlueprintStep 생성
            val blueprintStep = BlueprintStep(
                stepId = "hlx-act-${node.id}-${UUID.randomUUID().toString().take(8)}",
                type = stepType,
                params = params
            )

            // 3. Governed Gate 체크 (Phase D2: GateChain + RiskLevel + Role 기반)
            val executionStep = blueprintStep.toExecutionStep()
            val governanceResult = evaluateGovernance(executionStep, context)
            val gateTrace = governanceResult?.toJson()
            println("[HLX-ACT] node=${node.id} stepType=$stepType userId=${context.userId} role=${context.role} governance=${governanceResult}")
            if (governanceResult != null && !governanceResult.approved) {
                return NodeExecutionResult.Failure(
                    "Governance denied: ${governanceResult.reason} (riskLevel=${governanceResult.riskLevel}, role=${context.role})",
                    gate = gateTrace
                )
            }

            // 4. executor.execute() (executionStep은 Step 3에서 이미 생성됨)
            val executionContext = ExecutionContext.create(
                executionId = "hlx-exec-${UUID.randomUUID().toString().take(8)}",
                blueprintId = "hlx-${node.id}",
                instructionId = "hlx-act-${node.id}"
            )
            val executionResult = executor!!.execute(executionStep, executionContext)

            // 5. ExecutionResult → NodeExecutionResult (Phase D3: gate trace 첨부)
            when (executionResult) {
                is ExecutionResult.Success -> {
                    // HTTP 4xx/5xx 에러 감지 — 빈 body가 다음 노드로 전달되는 것을 방지
                    val statusCode = executionResult.output.json["statusCode"]
                    if (statusCode is JsonPrimitive) {
                        val code = statusCode.intOrNull ?: 0
                        if (code in 400..599) {
                            return NodeExecutionResult.Failure(
                                "HTTP $code error from ACT node '${node.id}': ${executionResult.output.stdout ?: "no body"}",
                                gate = gateTrace
                            )
                        }
                    }

                    val output = if (executionResult.output.json.isNotEmpty()) {
                        buildJsonObject {
                            executionResult.output.json.forEach { (k, v) -> put(k, v) }
                        }
                    } else {
                        JsonPrimitive(executionResult.output.stdout ?: "executed")
                    }
                    NodeExecutionResult.Success(output, gate = gateTrace)
                }
                is ExecutionResult.Failure -> {
                    NodeExecutionResult.Failure("Executor failed: ${executionResult.error.message}", gate = gateTrace)
                }
                is ExecutionResult.Cancelled -> {
                    NodeExecutionResult.Failure("Executor cancelled: ${executionResult.reason}", gate = gateTrace)
                }
            }
        } catch (e: Exception) {
            NodeExecutionResult.Failure("Act execution with executor failed: ${e.message}")
        }
    }

    /**
     * LLM 호출 후 result 필드 추출
     */
    private suspend fun callLlmAndExtractResult(prompt: String): NodeExecutionResult {
        return try {
            val response = callLlm(prompt)
            val json = extractJson(response)
            val result = json.jsonObject["result"]
                ?: return NodeExecutionResult.Failure("LLM response missing 'result' field: $response")
            NodeExecutionResult.Success(result)
        } catch (e: Exception) {
            NodeExecutionResult.Failure("Node execution failed: ${e.message}")
        }
    }

    /**
     * LLM 호출
     */
    private suspend fun callLlm(prompt: String): String {
        val request = LlmRequest(
            action = LlmAction.COMPLETE,
            prompt = prompt,
            model = model ?: llmProvider.defaultModel,
            maxTokens = llmProvider.defaultMaxTokens
        )
        val response = llmProvider.call(request)
        return response.content
    }

    /**
     * 템플릿 변수 해결
     *
     * {변수}, {변수.필드} 패턴을 context variables의 실제 값으로 치환한다.
     * LLM이 변수 참조를 해결하지 못하는 경우의 fallback 처리.
     */
    private fun resolveTemplateVariables(value: String, context: HlxContext): String {
        val templateRegex = Regex("""\{(\w+)(?:\.(\w+))?\}""")
        var hasUnresolved = false

        val resolved = templateRegex.replace(value) { match ->
            val varName = match.groupValues[1]
            val fieldName = match.groupValues[2].takeIf { it.isNotEmpty() }

            val varValue = context.variables[varName]
            if (varValue == null) {
                hasUnresolved = true
                match.value // 미해결 변수는 그대로 유지
            } else if (fieldName != null) {
                // {변수.필드} → JSON 객체에서 필드 추출
                try {
                    val obj = varValue.jsonObject
                    // 직접 매칭
                    val field = obj[fieldName]
                        // case-insensitive 폴백
                        ?: obj.entries.firstOrNull { it.key.equals(fieldName, ignoreCase = true) }?.value
                    when {
                        field == null -> {
                            hasUnresolved = true
                            println("[HLX-WARN] Template {$varName.$fieldName}: field '$fieldName' not found. Available: ${obj.keys}")
                            match.value
                        }
                        field is JsonPrimitive && field.isString -> field.content
                        else -> field.toString()
                    }
                } catch (_: Exception) {
                    hasUnresolved = true
                    match.value
                }
            } else {
                // {변수} → 전체 값
                when {
                    varValue is JsonPrimitive && varValue.isString -> varValue.content
                    else -> varValue.toString()
                }
            }
        }

        if (hasUnresolved) {
            println("[HLX-WARN] Unresolved template variables in: ${value.take(200)}")
        }

        return resolved
    }

    // === Governance 평가 (Phase D2) ===

    private data class GovernanceResult(
        val approved: Boolean,
        val riskLevel: String?,
        val maxRiskLevel: String?,
        val role: String?,
        val scheme: String?,
        val action: String?,
        val reason: String?,
        val deniedBy: String? = null,
        val gatesPassed: Int = 0
    ) {
        override fun toString(): String = if (approved) "APPROVED(risk=$riskLevel)" else "DENIED($reason)"

        fun toJson(): JsonElement = buildJsonObject {
            put("approved", JsonPrimitive(approved))
            riskLevel?.let { put("riskLevel", JsonPrimitive(it)) }
            maxRiskLevel?.let { put("maxRiskLevel", JsonPrimitive(it)) }
            role?.let { put("role", JsonPrimitive(it)) }
            scheme?.let { put("scheme", JsonPrimitive(it)) }
            action?.let { put("action", JsonPrimitive(it)) }
            reason?.let { put("reason", JsonPrimitive(it)) }
            deniedBy?.let { put("deniedBy", JsonPrimitive(it)) }
            put("gatesPassed", JsonPrimitive(gatesPassed))
        }
    }

    /**
     * Executor 실행 전 거버넌스 평가
     *
     * 1. ExecutorMetaRegistry에서 StepType 기반으로 RiskLevel 조회
     * 2. Role → maxRiskLevel 매핑
     * 3. RiskLevel 정책 검사
     * 4. GateChain 검사
     *
     * Phase D3: 결과에 전체 평가 내역(gate trace)을 포함한다.
     */
    private fun evaluateGovernance(
        step: ExecutionStep,
        context: HlxContext
    ): GovernanceResult? {
        // GateChain과 gate 모두 없으면 거버넌스 스킵 (기존 동작 호환)
        if (gateChain == null && gate == null) return null

        // 1. ExecutorMeta에서 RiskLevel 조회
        //    PluginStep → scheme 기반 조회 + 액션별 riskLevel
        //    기타 → StepType 기반 조회
        val meta = if (step is ExecutionStep.PluginStep) {
            executorMetaRegistry?.getByScheme(step.pluginId)
        } else {
            executorMetaRegistry?.getByStepType(step.type)
        }
        val riskLevel = if (step is ExecutionStep.PluginStep && meta != null) {
            meta.riskLevelFor(step.action)
        } else {
            meta?.riskLevel ?: RiskLevel.MEDIUM
        }
        val scheme = meta?.scheme

        // 2. Role → maxRiskLevel 매핑
        val role = context.role
        val maxRisk = roleToMaxRisk(role)

        // 3. Capability 기반 action 결정
        val action = when {
            meta?.capabilities?.contains(Capability.EXECUTE) == true -> "EXECUTE"
            meta?.capabilities?.contains(Capability.WRITE) == true -> "WRITE"
            meta?.capabilities?.contains(Capability.SEND) == true -> "SEND"
            meta?.capabilities?.contains(Capability.DELETE) == true -> "DELETE"
            else -> "READ"
        }

        // 4. RiskLevel 정책 검사
        if (riskLevel > maxRisk) {
            return GovernanceResult(
                approved = false,
                riskLevel = riskLevel.name,
                maxRiskLevel = maxRisk.name,
                role = role,
                scheme = scheme,
                action = action,
                reason = "Risk level $riskLevel exceeds maximum $maxRisk for role ${role ?: "default"}",
                deniedBy = "RiskLevelPolicy"
            )
        }

        // 5. Gate 검사
        val gateContext = GateContext(
            requestId = "hlx-${context.meta.workflowId}-${step.stepId}",
            blueprintId = context.meta.workflowId,
            dacsConsensus = "YES",
            userApproved = true,
            executorId = "${scheme ?: "unknown"}-executor",
            action = action
        )

        if (gateChain != null) {
            // Phase D2: GateChain 검사
            val chainResult = gateChain.check(gateContext)
            if (chainResult.isDeny) {
                val deny = chainResult.finalResult as GateResult.Deny
                return GovernanceResult(
                    approved = false,
                    riskLevel = riskLevel.name,
                    maxRiskLevel = maxRisk.name,
                    role = role,
                    scheme = scheme,
                    action = action,
                    reason = "Gate denied: ${deny.code} at ${chainResult.stoppedAt}",
                    deniedBy = "GateChain:${chainResult.stoppedAt}",
                    gatesPassed = chainResult.passedCount
                )
            }
            return GovernanceResult(
                approved = true,
                riskLevel = riskLevel.name,
                maxRiskLevel = maxRisk.name,
                role = role,
                scheme = scheme,
                action = action,
                reason = null,
                gatesPassed = chainResult.passedCount
            )
        } else if (gate != null) {
            // Phase 4 레거시: 단일 Gate 검사
            val gateResult = gate.check(gateContext)
            if (gateResult is GateResult.Deny) {
                return GovernanceResult(
                    approved = false,
                    riskLevel = riskLevel.name,
                    maxRiskLevel = maxRisk.name,
                    role = role,
                    scheme = scheme,
                    action = action,
                    reason = "Gate denied: ${gateResult.code}",
                    deniedBy = "Gate",
                    gatesPassed = 0
                )
            }
            return GovernanceResult(
                approved = true,
                riskLevel = riskLevel.name,
                maxRiskLevel = maxRisk.name,
                role = role,
                scheme = scheme,
                action = action,
                reason = null,
                gatesPassed = 1
            )
        }

        // gate/gateChain 모두 null (위에서 이미 리턴되어야 하지만 안전장치)
        return null
    }

    private fun roleToMaxRisk(role: String?): RiskLevel = when (role?.uppercase()) {
        "ADMIN" -> RiskLevel.HIGH
        "OPERATOR" -> RiskLevel.MEDIUM
        "VIEWER" -> RiskLevel.LOW
        else -> RiskLevel.MEDIUM  // default = OPERATOR 수준
    }

    companion object {
        /**
         * LLM 응답에서 JSON 추출
         *
         * ```json 블록 또는 raw JSON 파싱 시도
         */
        fun extractJson(response: String): JsonElement {
            val trimmed = response.trim()

            // ```json ... ``` 블록 추출
            val jsonBlockRegex = Regex("""```json\s*\n?(.*?)\n?\s*```""", RegexOption.DOT_MATCHES_ALL)
            val match = jsonBlockRegex.find(trimmed)
            if (match != null) {
                return Json.parseToJsonElement(match.groupValues[1].trim())
            }

            // ``` ... ``` 블록 추출 (json 없는 경우)
            val codeBlockRegex = Regex("""```\s*\n?(.*?)\n?\s*```""", RegexOption.DOT_MATCHES_ALL)
            val codeMatch = codeBlockRegex.find(trimmed)
            if (codeMatch != null) {
                return Json.parseToJsonElement(codeMatch.groupValues[1].trim())
            }

            // raw JSON 파싱 시도
            return Json.parseToJsonElement(trimmed)
        }
    }
}

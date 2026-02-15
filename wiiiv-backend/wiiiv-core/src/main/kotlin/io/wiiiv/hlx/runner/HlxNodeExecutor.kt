package io.wiiiv.hlx.runner

import io.wiiiv.blueprint.BlueprintStep
import io.wiiiv.blueprint.BlueprintStepType
import io.wiiiv.execution.*
import io.wiiiv.execution.impl.LlmProvider
import io.wiiiv.execution.impl.LlmRequest
import io.wiiiv.gate.Gate
import io.wiiiv.gate.GateContext
import io.wiiiv.gate.GateResult
import io.wiiiv.hlx.model.HlxContext
import io.wiiiv.hlx.model.HlxNode
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * 노드 실행 결과 (Observe, Transform, Act)
 */
sealed class NodeExecutionResult {
    data class Success(val output: JsonElement) : NodeExecutionResult()
    data class Failure(val error: String) : NodeExecutionResult()
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
    private val gate: Gate? = null
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
     */
    suspend fun executeTransform(node: HlxNode.Transform, context: HlxContext): NodeExecutionResult {
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
            val json = extractJson(response)

            // 2. step.type + step.params 추출
            val stepObj = json.jsonObject["step"]?.jsonObject
                ?: return NodeExecutionResult.Failure("LLM response missing 'step' field: $response")

            val typeStr = stepObj["type"]?.jsonPrimitive?.content
                ?: return NodeExecutionResult.Failure("LLM response missing 'step.type' field: $response")

            val paramsObj = stepObj["params"]?.jsonObject ?: buildJsonObject { }
            val params = paramsObj.mapValues { it.value.jsonPrimitive.content }

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

            // 3. Gate 체크
            if (gate != null) {
                val gateContext = GateContext.forPermission(
                    executorId = "hlx-executor",
                    action = stepType.name
                )
                val gateResult = gate.check(gateContext)
                if (gateResult is GateResult.Deny) {
                    return NodeExecutionResult.Failure("Gate denied: ${gateResult.code}")
                }
            }

            // 4. BlueprintStep → ExecutionStep → executor.execute()
            val executionStep = blueprintStep.toExecutionStep()
            val executionContext = ExecutionContext.create(
                executionId = "hlx-exec-${UUID.randomUUID().toString().take(8)}",
                blueprintId = "hlx-${node.id}",
                instructionId = "hlx-act-${node.id}"
            )
            val executionResult = executor!!.execute(executionStep, executionContext)

            // 5. ExecutionResult → NodeExecutionResult
            when (executionResult) {
                is ExecutionResult.Success -> {
                    val output = if (executionResult.output.json.isNotEmpty()) {
                        buildJsonObject {
                            executionResult.output.json.forEach { (k, v) -> put(k, v) }
                        }
                    } else {
                        JsonPrimitive(executionResult.output.stdout ?: "executed")
                    }
                    NodeExecutionResult.Success(output)
                }
                is ExecutionResult.Failure -> {
                    NodeExecutionResult.Failure("Executor failed: ${executionResult.error.message}")
                }
                is ExecutionResult.Cancelled -> {
                    NodeExecutionResult.Failure("Executor cancelled: ${executionResult.reason}")
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

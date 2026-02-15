package io.wiiiv.hlx.runner

import io.wiiiv.execution.LlmAction
import io.wiiiv.execution.impl.LlmProvider
import io.wiiiv.execution.impl.LlmRequest
import io.wiiiv.hlx.model.HlxContext
import io.wiiiv.hlx.model.HlxNode
import kotlinx.serialization.json.*

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
    private val model: String? = null
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
     */
    suspend fun executeAct(node: HlxNode.Act, context: HlxContext): NodeExecutionResult {
        val prompt = HlxPrompt.act(node, context)
        return callLlmAndExtractResult(prompt)
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

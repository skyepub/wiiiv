package io.wiiiv.governor

import io.wiiiv.blueprint.*
import io.wiiiv.dacs.DACS
import io.wiiiv.dacs.DACSRequest
import io.wiiiv.dacs.DACSResult
import io.wiiiv.dacs.Consensus
import io.wiiiv.execution.LlmAction
import io.wiiiv.execution.impl.LlmProvider
import io.wiiiv.execution.impl.LlmRequest
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

/**
 * LLM Governor - LLM 기반 Governor 구현
 *
 * Canonical: Governor 역할 정의서 v1.1
 *
 * ## 핵심 흐름
 *
 * 1. Spec에 intent가 있고 allowedOperations가 비어있으면 → LLM으로 Spec 보강
 * 2. DACS 합의 요청 → YES/NO/REVISION
 * 3. YES → Blueprint 생성 (SpecSnapshot.dacsResult = "YES")
 * 4. NO → GovernorResult.Denied
 * 5. REVISION → GovernorResult.Failed("REVISION - ...")
 *
 * ## FAIL-CLOSED 설계
 *
 * | 상황 | 결과 |
 * |------|------|
 * | LLM Provider 없음 | Spec 보강 생략, DACS 규칙 기반으로 평가 |
 * | LLM 호출 실패 | 원본 Spec 유지, sparse Spec → DACS REVISION |
 * | DACS 호출 실패 | GovernorResult.Failed |
 * | DACS NO | GovernorResult.Denied |
 * | DACS REVISION | GovernorResult.Failed("REVISION") |
 *
 * ## Degraded Mode (키 없는 환경)
 *
 * llmProvider=null, dacs=SimpleDACS → 개발 모드(느슨한 허용)
 * description이 있는 Spec은 RuleBasedReviewer가 APPROVE → DACS YES → Blueprint 생성
 */
class LlmGovernor(
    override val id: String,
    private val dacs: DACS,
    private val llmProvider: LlmProvider? = null,
    private val model: String? = null
) : Governor {

    override suspend fun createBlueprint(request: GovernorRequest, spec: Spec): GovernorResult {
        // 1. 요청 유효성 검사
        if (request.type == RequestType.CUSTOM) {
            return GovernorResult.Failed("CUSTOM type requires explicit step definition")
        }

        // 2. Spec 보강 (intent가 있고 allowedOperations가 비어있을 때)
        val enrichedSpec = if (spec.intent.isNotBlank() && spec.allowedOperations.isEmpty() && llmProvider != null) {
            enrichSpecFromIntent(spec)
        } else {
            spec
        }

        // 3. DACS 합의 요청
        val dacsResult = try {
            dacs.evaluate(
                DACSRequest(
                    requestId = request.requestId,
                    spec = enrichedSpec,
                    context = request.intent
                )
            )
        } catch (e: Exception) {
            return GovernorResult.Failed("DACS evaluation failed: ${e.message}")
        }

        // 4. 결과 분기
        return when (dacsResult.consensus) {
            Consensus.YES -> {
                val step = createStep(request)
                    ?: return GovernorResult.Failed("Failed to create step for request type: ${request.type}")

                val now = Instant.now().toString()
                val blueprint = Blueprint(
                    id = "bp-${UUID.randomUUID()}",
                    version = "1.0",
                    specSnapshot = SpecSnapshot(
                        specId = enrichedSpec.id,
                        specVersion = enrichedSpec.version,
                        snapshotAt = now,
                        governorId = id,
                        dacsResult = "YES"
                    ),
                    steps = listOf(step),
                    metadata = BlueprintMetadata(
                        createdAt = now,
                        createdBy = id,
                        description = "Blueprint for ${request.type} operation",
                        tags = listOf("llm-governor", request.type.name.lowercase())
                    )
                )

                GovernorResult.BlueprintCreated(blueprint)
            }

            Consensus.NO -> {
                GovernorResult.Denied(
                    reason = "DACS consensus: NO - ${dacsResult.reason}",
                    specId = enrichedSpec.id
                )
            }

            Consensus.REVISION -> {
                GovernorResult.Failed("REVISION - ${dacsResult.reason}")
            }
        }
    }

    /**
     * LLM을 사용하여 intent에서 Spec 보강
     *
     * allowedOperations, allowedPaths를 LLM이 추론
     * LLM 실패 시 원본 Spec 그대로 반환 (→ DACS가 REVISION 처리)
     */
    private suspend fun enrichSpecFromIntent(spec: Spec): Spec {
        return try {
            val prompt = buildString {
                appendLine("Analyze the following user intent and determine the required file operations and paths.")
                appendLine()
                appendLine("Intent: ${spec.intent}")
                appendLine()
                appendLine("Respond ONLY with a JSON object in this exact format:")
                appendLine("""{"operations": ["FILE_READ", "FILE_WRITE", ...], "paths": ["/tmp/**", ...]}""")
                appendLine()
                appendLine("Valid operations: FILE_READ, FILE_WRITE, FILE_COPY, FILE_MOVE, FILE_DELETE, FILE_MKDIR, COMMAND")
                appendLine("Use glob patterns for paths (e.g., /tmp/**, /var/log/**)")
                appendLine("Be conservative - only include operations and paths that are clearly needed.")
                appendLine("No additional text outside the JSON object.")
            }

            val response = llmProvider!!.call(
                LlmRequest(
                    action = LlmAction.ANALYZE,
                    prompt = prompt,
                    model = model ?: llmProvider.defaultModel,
                    maxTokens = 500
                )
            )

            val jsonStr = extractJson(response.content)
            val json = Json.parseToJsonElement(jsonStr).jsonObject

            val operations = json["operations"]?.jsonArray
                ?.mapNotNull { element ->
                    try {
                        RequestType.valueOf(element.jsonPrimitive.content)
                    } catch (_: Exception) {
                        null
                    }
                } ?: emptyList()

            val paths = json["paths"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()

            spec.copy(
                allowedOperations = operations,
                allowedPaths = paths
            )
        } catch (_: Exception) {
            // LLM 실패 시 원본 Spec 반환 → sparse Spec → DACS REVISION
            spec
        }
    }

    /**
     * 요청을 BlueprintStep으로 변환
     *
     * SimpleGovernor의 createBlueprintStep() 로직 재사용
     */
    private fun createStep(request: GovernorRequest): BlueprintStep? {
        val stepId = "step-${UUID.randomUUID().toString().take(8)}"

        return when (request.type) {
            RequestType.FILE_READ -> {
                val path = request.targetPath ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.FILE_READ,
                    params = mapOf("path" to path)
                )
            }
            RequestType.FILE_WRITE -> {
                val path = request.targetPath ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.FILE_WRITE,
                    params = mapOf(
                        "path" to path,
                        "content" to (request.content ?: "")
                    )
                )
            }
            RequestType.FILE_COPY -> {
                val source = request.targetPath ?: return null
                val target = request.params["target"] ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.FILE_COPY,
                    params = mapOf("source" to source, "target" to target)
                )
            }
            RequestType.FILE_MOVE -> {
                val source = request.targetPath ?: return null
                val target = request.params["target"] ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.FILE_MOVE,
                    params = mapOf("source" to source, "target" to target)
                )
            }
            RequestType.FILE_DELETE -> {
                val path = request.targetPath ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.FILE_DELETE,
                    params = mapOf("path" to path)
                )
            }
            RequestType.FILE_MKDIR -> {
                val path = request.targetPath ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.FILE_MKDIR,
                    params = mapOf("path" to path)
                )
            }
            RequestType.COMMAND -> {
                val command = request.params["command"] ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.COMMAND,
                    params = mapOf(
                        "command" to command,
                        "args" to (request.params["args"] ?: ""),
                        "workingDir" to (request.params["workingDir"] ?: ""),
                        "timeoutMs" to (request.params["timeoutMs"] ?: "60000")
                    )
                )
            }
            RequestType.CUSTOM -> null
        }
    }

    /**
     * JSON 추출 (마크다운 코드 블록 등 제거)
     *
     * LlmPersona의 패턴 재사용
     */
    private fun extractJson(response: String): String {
        val codeBlockRegex = """```(?:json)?\s*([\s\S]*?)```""".toRegex()
        val codeBlockMatch = codeBlockRegex.find(response)
        if (codeBlockMatch != null) {
            return codeBlockMatch.groupValues[1].trim()
        }

        val jsonRegex = """\{[\s\S]*\}""".toRegex()
        val jsonMatch = jsonRegex.find(response)
        if (jsonMatch != null) {
            return jsonMatch.value
        }

        return response.trim()
    }

    /**
     * Governor가 내부적으로 사용한 DACS 결과를 외부에서 접근할 수 있도록
     * 마지막 DACS 결과를 저장 (DecisionRoutes에서 DTO 생성용)
     */
    @Volatile
    var lastDacsResult: DACSResult? = null
        private set

    /**
     * DACS 결과 포함 Blueprint 생성 (DecisionRoutes용)
     *
     * createBlueprint와 동일하지만, DACS 결과도 함께 반환
     */
    suspend fun createBlueprintWithDacsResult(request: GovernorRequest, spec: Spec): Pair<GovernorResult, DACSResult?> {
        // 1. 요청 유효성 검사
        if (request.type == RequestType.CUSTOM) {
            return GovernorResult.Failed("CUSTOM type requires explicit step definition") to null
        }

        // 2. Spec 보강
        val enrichedSpec = if (spec.intent.isNotBlank() && spec.allowedOperations.isEmpty() && llmProvider != null) {
            enrichSpecFromIntent(spec)
        } else {
            spec
        }

        // 3. DACS 합의 요청
        val dacsResult = try {
            dacs.evaluate(
                DACSRequest(
                    requestId = request.requestId,
                    spec = enrichedSpec,
                    context = request.intent
                )
            )
        } catch (e: Exception) {
            return GovernorResult.Failed("DACS evaluation failed: ${e.message}") to null
        }

        lastDacsResult = dacsResult

        // 4. 결과 분기
        val govResult = when (dacsResult.consensus) {
            Consensus.YES -> {
                val step = createStep(request)
                    ?: return GovernorResult.Failed("Failed to create step for request type: ${request.type}") to dacsResult

                val now = Instant.now().toString()
                val blueprint = Blueprint(
                    id = "bp-${UUID.randomUUID()}",
                    version = "1.0",
                    specSnapshot = SpecSnapshot(
                        specId = enrichedSpec.id,
                        specVersion = enrichedSpec.version,
                        snapshotAt = now,
                        governorId = id,
                        dacsResult = "YES"
                    ),
                    steps = listOf(step),
                    metadata = BlueprintMetadata(
                        createdAt = now,
                        createdBy = id,
                        description = "Blueprint for ${request.type} operation",
                        tags = listOf("llm-governor", request.type.name.lowercase())
                    )
                )

                GovernorResult.BlueprintCreated(blueprint)
            }

            Consensus.NO -> {
                GovernorResult.Denied(
                    reason = "DACS consensus: NO - ${dacsResult.reason}",
                    specId = enrichedSpec.id
                )
            }

            Consensus.REVISION -> {
                GovernorResult.Failed("REVISION - ${dacsResult.reason}")
            }
        }

        return govResult to dacsResult
    }

    companion object {
        /**
         * LlmGovernor 생성 (팩토리)
         */
        fun create(
            id: String = "gov-llm-${UUID.randomUUID().toString().take(8)}",
            dacs: DACS,
            llmProvider: LlmProvider? = null,
            model: String? = null
        ): LlmGovernor {
            return LlmGovernor(id, dacs, llmProvider, model)
        }
    }
}

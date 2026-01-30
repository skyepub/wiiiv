package io.wiiiv.dacs

import io.wiiiv.execution.LlmAction
import io.wiiiv.execution.impl.LlmProvider
import io.wiiiv.execution.impl.LlmRequest
import io.wiiiv.execution.impl.LlmProviderException
import io.wiiiv.governor.Spec
import kotlinx.serialization.json.*

/**
 * LLM Persona - LLM 기반 페르소나
 *
 * Canonical: DACS v2 인터페이스 정의서 v2.1 §5
 *
 * ## LLM 페르소나의 원칙
 *
 * - 각 페르소나는 동일한 Spec을 서로 다른 관점에서 평가한다
 * - LLM은 "도구"로만 사용된다 (판단은 DACS 전체의 책임)
 * - LLM 응답은 정형화된 형식으로 파싱된다
 * - LLM 오류 시 ABSTAIN으로 폴백한다
 *
 * ## 응답 형식 (JSON)
 *
 * ```json
 * {
 *   "vote": "APPROVE" | "REJECT" | "ABSTAIN",
 *   "summary": "평가 요약",
 *   "concerns": ["우려사항1", "우려사항2"]
 * }
 * ```
 */
abstract class LlmPersona(
    private val provider: LlmProvider,
    private val model: String? = null,
    private val maxTokens: Int = 1000
) : Persona {

    /**
     * 페르소나별 시스템 프롬프트
     */
    abstract val systemPrompt: String

    /**
     * 페르소나별 평가 관점 설명
     */
    abstract val perspective: String

    override suspend fun evaluate(spec: Spec, context: String?): PersonaOpinion {
        return try {
            val prompt = buildPrompt(spec, context)
            val response = callLlm(prompt)
            parseResponse(response)
        } catch (e: LlmProviderException) {
            // LLM 오류 시 ABSTAIN으로 폴백
            PersonaOpinion(
                persona = type,
                vote = Vote.ABSTAIN,
                summary = "LLM evaluation failed: ${e.message}",
                concerns = listOf("LLM provider error - manual review required")
            )
        } catch (e: Exception) {
            // 파싱 오류 등
            PersonaOpinion(
                persona = type,
                vote = Vote.ABSTAIN,
                summary = "Failed to parse LLM response: ${e.message}",
                concerns = listOf("Response parsing error - manual review required")
            )
        }
    }

    /**
     * 프롬프트 생성
     */
    protected open fun buildPrompt(spec: Spec, context: String?): String {
        return buildString {
            appendLine(systemPrompt)
            appendLine()
            appendLine("## Your Perspective")
            appendLine(perspective)
            appendLine()
            appendLine("## Spec to Evaluate")
            appendLine("```")
            appendLine("ID: ${spec.id}")
            appendLine("Name: ${spec.name}")
            appendLine("Description: ${spec.description}")
            appendLine("Allowed Operations: ${spec.allowedOperations.joinToString(", ")}")
            appendLine("Allowed Paths: ${spec.allowedPaths.joinToString(", ")}")
            appendLine("```")
            if (context != null) {
                appendLine()
                appendLine("## Additional Context")
                appendLine(context)
            }
            appendLine()
            appendLine("## Response Format")
            appendLine("Respond ONLY with a JSON object in this exact format:")
            appendLine("```json")
            appendLine("""{"vote": "APPROVE|REJECT|ABSTAIN", "summary": "brief summary", "concerns": ["concern1", "concern2"]}""")
            appendLine("```")
            appendLine()
            appendLine("Rules:")
            appendLine("- vote must be exactly one of: APPROVE, REJECT, ABSTAIN")
            appendLine("- summary should be 1-2 sentences")
            appendLine("- concerns is an array of strings (can be empty [])")
            appendLine("- No additional text outside the JSON object")
        }
    }

    /**
     * LLM 호출
     */
    private suspend fun callLlm(prompt: String): String {
        val request = LlmRequest(
            action = LlmAction.ANALYZE,
            prompt = prompt,
            model = model ?: provider.defaultModel,
            maxTokens = maxTokens
        )

        val response = provider.call(request)
        return response.content
    }

    /**
     * LLM 응답 파싱
     */
    protected open fun parseResponse(response: String): PersonaOpinion {
        // JSON 추출 (코드 블록 제거)
        val jsonStr = extractJson(response)

        val json = Json.parseToJsonElement(jsonStr).jsonObject

        val voteStr = json["vote"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'vote' field")

        val vote = when (voteStr.uppercase()) {
            "APPROVE" -> Vote.APPROVE
            "REJECT" -> Vote.REJECT
            "ABSTAIN" -> Vote.ABSTAIN
            else -> throw IllegalArgumentException("Invalid vote: $voteStr")
        }

        val summary = json["summary"]?.jsonPrimitive?.content
            ?: "No summary provided"

        val concerns = json["concerns"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()

        return PersonaOpinion(
            persona = type,
            vote = vote,
            summary = summary,
            concerns = concerns
        )
    }

    /**
     * JSON 추출 (마크다운 코드 블록 등 제거)
     */
    private fun extractJson(response: String): String {
        // ```json ... ``` 블록에서 추출
        val codeBlockRegex = """```(?:json)?\s*([\s\S]*?)```""".toRegex()
        val codeBlockMatch = codeBlockRegex.find(response)
        if (codeBlockMatch != null) {
            return codeBlockMatch.groupValues[1].trim()
        }

        // { ... } 추출
        val jsonRegex = """\{[\s\S]*\}""".toRegex()
        val jsonMatch = jsonRegex.find(response)
        if (jsonMatch != null) {
            return jsonMatch.value
        }

        // 그대로 반환 (JSON 파싱 시도)
        return response.trim()
    }
}

/**
 * LLM Architect - LLM 기반 Architect 페르소나
 *
 * 관점: 구조적 타당성
 */
class LlmArchitect(
    provider: LlmProvider,
    model: String? = null,
    maxTokens: Int = 1000
) : LlmPersona(provider, model, maxTokens) {

    override val type = PersonaType.ARCHITECT

    override val systemPrompt = """
        You are the ARCHITECT persona in a multi-agent consensus system.
        Your role is to evaluate Specs from a STRUCTURAL perspective.

        You assess:
        - Is the Spec well-structured and complete?
        - Are the required fields properly defined?
        - Is the scope clear and bounded?
        - Are the operations logically consistent?

        You do NOT assess security, ethics, or business requirements.
        Focus ONLY on structural validity.
    """.trimIndent()

    override val perspective = """
        As the Architect, evaluate the structural integrity of this Spec:
        - Are all required fields present and valid?
        - Is the scope well-defined?
        - Are the allowed operations consistent with the description?
        - Is the Spec complete enough to create a Blueprint?

        Vote APPROVE if structurally sound.
        Vote REJECT if fundamentally malformed.
        Vote ABSTAIN if more information is needed.
    """.trimIndent()
}

/**
 * LLM Reviewer - LLM 기반 Reviewer 페르소나
 *
 * 관점: 요구사항 충족 여부
 */
class LlmReviewer(
    provider: LlmProvider,
    model: String? = null,
    maxTokens: Int = 1000
) : LlmPersona(provider, model, maxTokens) {

    override val type = PersonaType.REVIEWER

    override val systemPrompt = """
        You are the REVIEWER persona in a multi-agent consensus system.
        Your role is to evaluate Specs from a REQUIREMENTS perspective.

        You assess:
        - Are the requirements clearly stated?
        - Is the intent understandable?
        - Are the constraints reasonable?
        - Does the description match the allowed operations?

        You do NOT assess security or implementation details.
        Focus ONLY on requirements clarity and completeness.
    """.trimIndent()

    override val perspective = """
        As the Reviewer, evaluate the requirements in this Spec:
        - Is the intent of the Spec clear?
        - Do the allowed operations match the stated description?
        - Are the constraints (paths, operations) appropriate for the goal?
        - Is there enough context to understand what is being requested?

        Vote APPROVE if requirements are clear and consistent.
        Vote REJECT if requirements are contradictory or impossible.
        Vote ABSTAIN if requirements need clarification.
    """.trimIndent()
}

/**
 * LLM Adversary - LLM 기반 Adversary 페르소나
 *
 * 관점: 위험, 보안, 악용 가능성
 *
 * ## REVISION 우선 원칙
 *
 * - 위험/민감 요소 → ABSTAIN (→ REVISION)
 * - 명백한 악의적 패턴만 → REJECT (→ NO)
 */
class LlmAdversary(
    provider: LlmProvider,
    model: String? = null,
    maxTokens: Int = 1000
) : LlmPersona(provider, model, maxTokens) {

    override val type = PersonaType.ADVERSARY

    override val systemPrompt = """
        You are the ADVERSARY persona in a multi-agent consensus system.
        Your role is to evaluate Specs from a SECURITY and RISK perspective.

        You are the "red team" - looking for potential abuse, security risks,
        and dangerous patterns.

        You assess:
        - Could this Spec be abused for malicious purposes?
        - Does it access sensitive system resources?
        - Are there obvious security vulnerabilities?
        - Is the scope too broad or dangerous?

        IMPORTANT - REVISION-first principle:
        - ABSTAIN (→REVISION) for risks that need clarification/approval
        - REJECT only for EXPLICITLY prohibited patterns:
          - /etc/passwd, /etc/shadow, /etc/sudoers
          - ~/.ssh, /root/.ssh
          - /** (full system access)
          - Clear malicious intent
    """.trimIndent()

    override val perspective = """
        As the Adversary, evaluate the security risks in this Spec:
        - Could these operations be abused?
        - Are sensitive paths being accessed (system files, credentials)?
        - Is the scope dangerously broad?
        - Does this look like a malicious request?

        IMPORTANT:
        - Vote ABSTAIN for risks that need more context or approval
        - Vote REJECT ONLY for explicitly prohibited patterns:
          - Accessing /etc/passwd, /etc/shadow, /etc/sudoers
          - Accessing SSH keys (~/.ssh, /root/.ssh)
          - Full system access (/**)
          - Clear malicious intent
        - Vote APPROVE if no security concerns

        Err on the side of ABSTAIN rather than REJECT.
        Gates will enforce final security policies.
    """.trimIndent()
}

/**
 * LLM DACS - LLM 기반 DACS 구현
 *
 * SimpleDACS와 동일한 구조지만 LLM 페르소나 사용
 */
class LlmDACS(
    private val provider: LlmProvider,
    private val model: String? = null,
    private val consensusEngine: ConsensusEngine = VetoConsensusEngine()
) : DACS {

    private val personas: List<Persona> = listOf(
        LlmArchitect(provider, model),
        LlmReviewer(provider, model),
        LlmAdversary(provider, model)
    )

    override suspend fun evaluate(request: DACSRequest): DACSResult {
        // 1. 각 페르소나 독립 평가
        val opinions = personas.map { persona ->
            persona.evaluate(request.spec, request.context)
        }

        // 2. 합의 도출
        val (consensus, reason) = consensusEngine.derive(opinions)

        // 3. 결과 반환
        return DACSResult(
            requestId = request.requestId,
            consensus = consensus,
            reason = reason,
            personaOpinions = opinions
        )
    }

    companion object {
        /**
         * LLM Provider로 DACS 생성
         */
        fun create(
            provider: LlmProvider,
            model: String? = null,
            consensusEngine: ConsensusEngine = VetoConsensusEngine()
        ): LlmDACS {
            return LlmDACS(provider, model, consensusEngine)
        }
    }
}

/**
 * Hybrid DACS - 규칙 기반 + LLM 혼합 DACS
 *
 * 규칙 기반 검사를 먼저 수행하고, 통과하면 LLM 평가 수행
 * (비용 절감 및 명백한 케이스 빠른 처리)
 */
class HybridDACS(
    private val provider: LlmProvider,
    private val model: String? = null,
    private val consensusEngine: ConsensusEngine = VetoConsensusEngine()
) : DACS {

    private val ruleBasedDACS = SimpleDACS()
    private val llmDACS = LlmDACS(provider, model, consensusEngine)

    override suspend fun evaluate(request: DACSRequest): DACSResult {
        // 1. 규칙 기반 검사 먼저 수행
        val ruleResult = ruleBasedDACS.evaluate(request)

        // 2. NO면 바로 반환 (명백한 거부)
        if (ruleResult.isNo) {
            return ruleResult
        }

        // 3. YES 또는 REVISION이면 LLM 평가 수행
        val llmResult = llmDACS.evaluate(request)

        // 4. 두 결과를 종합
        // - 둘 다 YES면 YES
        // - 하나라도 NO면 NO
        // - 그 외는 REVISION
        val finalConsensus = when {
            ruleResult.isYes && llmResult.isYes -> Consensus.YES
            llmResult.isNo -> Consensus.NO
            else -> Consensus.REVISION
        }

        val allOpinions = ruleResult.personaOpinions + llmResult.personaOpinions

        return DACSResult(
            requestId = request.requestId,
            consensus = finalConsensus,
            reason = if (finalConsensus == ruleResult.consensus) {
                ruleResult.reason
            } else {
                llmResult.reason
            },
            personaOpinions = allOpinions
        )
    }
}

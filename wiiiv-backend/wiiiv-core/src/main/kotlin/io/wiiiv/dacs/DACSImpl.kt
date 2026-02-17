package io.wiiiv.dacs

import io.wiiiv.governor.RequestType
import io.wiiiv.governor.Spec

/**
 * Simple DACS - 규칙 기반 DACS 구현
 *
 * v2.0 최소 구현:
 * - LLM 없이 규칙 기반으로 동작
 * - 추후 LLM 페르소나로 교체 가능
 *
 * Canonical: DACS v2 인터페이스 정의서 v2.1
 */
class SimpleDACS(
    private val personas: List<Persona> = defaultPersonas(),
    private val consensusEngine: ConsensusEngine = VetoConsensusEngine()
) : DACS {

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
         * 기본 페르소나 목록
         */
        fun defaultPersonas(): List<Persona> = listOf(
            RuleBasedArchitect(),
            RuleBasedReviewer(),
            RuleBasedAdversary()
        )

        /**
         * 기본 DACS 인스턴스
         */
        val DEFAULT = SimpleDACS()
    }
}

/**
 * Rule-Based Architect - 규칙 기반 Architect 페르소나
 *
 * 관점: 구조적 타당성
 */
class RuleBasedArchitect : Persona {
    override val type = PersonaType.ARCHITECT

    override suspend fun evaluate(spec: Spec, context: String?): PersonaOpinion {
        val concerns = mutableListOf<String>()

        // 구조적 검사
        if (spec.id.isBlank()) {
            concerns.add("Spec ID is empty")
        }

        if (spec.name.isBlank()) {
            concerns.add("Spec name is empty")
        }

        // 투표 결정
        val vote = when {
            concerns.any { it.contains("empty") } -> Vote.ABSTAIN
            concerns.isNotEmpty() -> Vote.REJECT
            else -> Vote.APPROVE
        }

        return PersonaOpinion(
            persona = type,
            vote = vote,
            summary = if (vote == Vote.APPROVE) {
                "Spec structure is valid"
            } else {
                "Structural issues found"
            },
            concerns = concerns
        )
    }
}

/**
 * Rule-Based Reviewer - 규칙 기반 Reviewer 페르소나
 *
 * 관점: 요구사항 충족 여부
 */
class RuleBasedReviewer : Persona {
    override val type = PersonaType.REVIEWER

    override suspend fun evaluate(spec: Spec, context: String?): PersonaOpinion {
        val concerns = mutableListOf<String>()

        // 요구사항 검사
        if (spec.allowedOperations.isEmpty() && spec.allowedPaths.isEmpty()) {
            // 모든 것이 허용됨 - 의도적인지 확인 필요
            if (spec.description.isBlank()) {
                concerns.add("No restrictions defined and no description provided")
            }
        }

        // 투표 결정
        val vote = when {
            concerns.any { it.contains("No restrictions") } -> Vote.ABSTAIN
            concerns.isNotEmpty() -> Vote.REJECT
            else -> Vote.APPROVE
        }

        return PersonaOpinion(
            persona = type,
            vote = vote,
            summary = if (vote == Vote.APPROVE) {
                "Requirements are clear"
            } else {
                "Requirements need clarification"
            },
            concerns = concerns
        )
    }
}

/**
 * Rule-Based Adversary - 규칙 기반 Adversary 페르소나
 *
 * 관점: 위험, 보안, 악용 가능성
 *
 * ## REVISION 우선 원칙 (Canonical)
 *
 * DACS는 Gate가 아니다. DACS의 역할은:
 * - "이 Spec으로는 안전성 검증이 불가능하다" → REVISION (추가 맥락/승인 요구)
 * - "요청 자체가 명백히 금지되어야 한다" → NO (악의적 패턴만)
 *
 * 위험/민감 요소 발견 시 기본값은 **REVISION**이다.
 * NO는 명백한 악의/금지 패턴에만 사용한다.
 *
 * 이렇게 해야 Gate의 존재 이유가 유지된다.
 */
class RuleBasedAdversary : Persona {
    override val type = PersonaType.ADVERSARY

    // 명백히 금지되는 패턴 (NO 반환) - 정확한 매칭만
    private val explicitlyProhibitedExactPaths = listOf(
        "/etc/passwd",
        "/etc/shadow",
        "/etc/sudoers",
        "/**"  // 전체 시스템 접근 (정확히 이 패턴만)
    )

    // 금지 경로 접두사 (이 경로로 시작하면 금지)
    private val prohibitedPathPrefixes = listOf(
        "~/.ssh",
        "/root/.ssh"
    )

    override suspend fun evaluate(spec: Spec, context: String?): PersonaOpinion {
        val concerns = mutableListOf<String>()
        var hasExplicitProhibition = false

        // workspace를 context에서 파싱 (동적 safe path)
        val workspacePath = context?.lines()
            ?.find { it.startsWith("workspace:") }
            ?.substringAfter("workspace:")?.trim()

        // 1. 명백한 금지 패턴 검사 (NO 대상)
        val hasProhibitedPath = spec.allowedPaths.any { path ->
            // 정확히 일치하는 금지 패턴
            val exactMatch = explicitlyProhibitedExactPaths.any { prohibited ->
                path == prohibited
            }
            // 금지 접두사로 시작
            val prefixMatch = prohibitedPathPrefixes.any { prefix ->
                path.startsWith(prefix)
            }
            exactMatch || prefixMatch
        }
        if (hasProhibitedPath) {
            concerns.add("Explicitly prohibited path pattern detected")
            hasExplicitProhibition = true
        }

        // 2. 위험 작업 검사 (REVISION 대상 - 추가 맥락 요구)
        val dangerousOps = listOf(RequestType.FILE_DELETE, RequestType.COMMAND)
        val hasDangerousOps = spec.allowedOperations.any { it in dangerousOps }

        if (hasDangerousOps && spec.allowedPaths.isEmpty()) {
            concerns.add("Dangerous operations without path restrictions - needs scope clarification")
        }

        // 3. 명령어 실행 검사 (REVISION 대상)
        if (RequestType.COMMAND in spec.allowedOperations) {
            concerns.add("Command execution requires additional review and approval context")
        }

        // 4. 민감 경로 검사 (REVISION 대상 - Gate에서 최종 판단)
        val sensitivePaths = listOf("/etc", "/root", "/usr", "/bin", "/sbin")
        val dynamicSafePaths = buildList {
            add("/tmp")
            add("/var/tmp")
            add("/var/folders")
            add(System.getProperty("java.io.tmpdir"))
            workspacePath?.let { add(it) }
        }

        val allowsSensitivePaths = spec.allowedPaths.any { path ->
            val isSensitive = sensitivePaths.any { sensitive ->
                path.startsWith(sensitive)
            }
            val isSafe = dynamicSafePaths.any { safe ->
                path.startsWith(safe)
            }
            isSensitive && !isSafe
        }

        if (allowsSensitivePaths) {
            concerns.add("Sensitive system paths detected - needs explicit approval")
        }

        // 투표 결정: REVISION 우선 원칙
        val vote = when {
            // 명백한 금지 패턴만 REJECT
            hasExplicitProhibition -> Vote.REJECT
            // 그 외 우려사항은 ABSTAIN (→ REVISION)
            concerns.isNotEmpty() -> Vote.ABSTAIN
            // 우려 없음
            else -> Vote.APPROVE
        }

        return PersonaOpinion(
            persona = type,
            vote = vote,
            summary = when (vote) {
                Vote.APPROVE -> "No security concerns identified"
                Vote.ABSTAIN -> "Risks identified - needs clarification or approval"
                Vote.REJECT -> "Explicitly prohibited pattern detected"
            },
            concerns = concerns
        )
    }
}

/**
 * Veto Consensus Engine - 거부권 합의 엔진
 *
 * Canonical: 이 엔진은 다수결이 아니라 "거부권 + 만장일치" 방식이다.
 *
 * ## 합의 규칙 (고정)
 *
 * | 조건 | 결과 |
 * |------|------|
 * | REJECT 1개 이상 | NO (거부권 발동) |
 * | ABSTAIN 1개 이상 | REVISION (정보 부족) |
 * | APPROVE 전원 | YES (만장일치) |
 *
 * ## 왜 "Majority"가 아닌가?
 *
 * "다수결"이라면 2:1로 APPROVE가 이겨야 하지만,
 * 이 엔진은 REJECT 1개가 전체를 거부하고 (veto),
 * ABSTAIN 1개도 REVISION을 유발한다 (보수적 접근).
 *
 * 이는 안전성을 위한 의도적 설계이다.
 */
class VetoConsensusEngine : ConsensusEngine {

    override fun derive(opinions: List<PersonaOpinion>): Pair<Consensus, String> {
        if (opinions.isEmpty()) {
            return Consensus.REVISION to "No persona opinions provided"
        }

        val rejectCount = opinions.count { it.vote == Vote.REJECT }
        val abstainCount = opinions.count { it.vote == Vote.ABSTAIN }
        val approveCount = opinions.count { it.vote == Vote.APPROVE }

        val allConcerns = opinions.flatMap { it.concerns }

        return when {
            // 하나라도 REJECT면 NO
            rejectCount > 0 -> {
                val rejectReasons = opinions
                    .filter { it.vote == Vote.REJECT }
                    .map { "${it.persona}: ${it.summary}" }
                    .joinToString("; ")
                Consensus.NO to "Rejected by personas: $rejectReasons"
            }
            // ABSTAIN이 과반수면 REVISION
            abstainCount >= opinions.size / 2 + 1 -> {
                val abstainReasons = opinions
                    .filter { it.vote == Vote.ABSTAIN }
                    .map { "${it.persona}: ${it.summary}" }
                    .joinToString("; ")
                Consensus.REVISION to "Needs clarification: $abstainReasons"
            }
            // ABSTAIN이 있으면 REVISION (보수적 접근)
            abstainCount > 0 -> {
                val concerns = if (allConcerns.isNotEmpty()) {
                    allConcerns.joinToString("; ")
                } else {
                    "Some personas abstained from voting"
                }
                Consensus.REVISION to concerns
            }
            // 모두 APPROVE면 YES
            approveCount == opinions.size -> {
                Consensus.YES to "All personas approved"
            }
            // 그 외 APPROVE가 다수면 YES
            approveCount > opinions.size / 2 -> {
                Consensus.YES to "Majority approved"
            }
            // 기본은 REVISION
            else -> {
                Consensus.REVISION to "No clear consensus reached"
            }
        }
    }
}

/**
 * Strict Consensus Engine - 엄격한 합의 엔진
 *
 * 합의 규칙:
 * - 모든 페르소나가 APPROVE해야 YES
 * - REJECT가 하나라도 있으면 NO
 * - 그 외는 REVISION
 */
class StrictConsensusEngine : ConsensusEngine {

    override fun derive(opinions: List<PersonaOpinion>): Pair<Consensus, String> {
        if (opinions.isEmpty()) {
            return Consensus.REVISION to "No persona opinions provided"
        }

        val hasReject = opinions.any { it.vote == Vote.REJECT }
        val allApprove = opinions.all { it.vote == Vote.APPROVE }

        return when {
            hasReject -> {
                val rejectReasons = opinions
                    .filter { it.vote == Vote.REJECT }
                    .flatMap { it.concerns }
                    .joinToString("; ")
                Consensus.NO to "Rejected: $rejectReasons"
            }
            allApprove -> {
                Consensus.YES to "Unanimous approval"
            }
            else -> {
                val abstainConcerns = opinions
                    .filter { it.vote == Vote.ABSTAIN }
                    .flatMap { it.concerns }
                    .joinToString("; ")
                Consensus.REVISION to "Not all personas approved: $abstainConcerns"
            }
        }
    }
}

/**
 * Always Yes DACS - 항상 YES 반환 (테스트/개발용)
 *
 * 주의: 프로덕션에서 사용 금지
 */
class AlwaysYesDACS : DACS {
    override suspend fun evaluate(request: DACSRequest): DACSResult {
        return DACSResult(
            requestId = request.requestId,
            consensus = Consensus.YES,
            reason = "Auto-approved (DEV ONLY)"
        )
    }
}

/**
 * Always No DACS - 항상 NO 반환 (테스트용)
 */
class AlwaysNoDACS(private val reason: String = "Auto-rejected") : DACS {
    override suspend fun evaluate(request: DACSRequest): DACSResult {
        return DACSResult(
            requestId = request.requestId,
            consensus = Consensus.NO,
            reason = reason
        )
    }
}

/**
 * Configurable DACS - 설정 가능한 DACS (테스트용)
 */
class ConfigurableDACS(
    private var nextResult: Consensus = Consensus.YES,
    private var nextReason: String = "Configured result"
) : DACS {

    fun setNextResult(consensus: Consensus, reason: String) {
        this.nextResult = consensus
        this.nextReason = reason
    }

    override suspend fun evaluate(request: DACSRequest): DACSResult {
        return DACSResult(
            requestId = request.requestId,
            consensus = nextResult,
            reason = nextReason
        )
    }
}

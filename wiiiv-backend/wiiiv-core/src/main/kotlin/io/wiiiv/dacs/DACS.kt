package io.wiiiv.dacs

import io.wiiiv.governor.Spec
import java.time.Instant
import java.util.UUID

/**
 * DACS - Dynamic Agent Consensus System
 *
 * Canonical: DACS v2 인터페이스 정의서 v2.1
 *
 * ## DACS의 정의
 *
 * "DACS는 wiiiv에서 사용되는 다중 페르소나 합의 엔진이다."
 *
 * DACS의 유일한 목적:
 * > "이 Spec에 대해, 합의된 관점에서 실행을 허용할 수 있는가?"
 *
 * ## DACS의 역할 (What DACS Does)
 *
 * 1. 다중 페르소나(LLM)를 호출한다
 * 2. 동일한 Spec을 서로 다른 관점에서 검토한다
 * 3. 의견을 종합하여 합의 상태를 도출한다
 * 4. 그 결과를 정형화된 출력으로 반환한다
 *
 * ## DACS가 하지 않는 것 (What DACS Does NOT Do)
 *
 * - 흐름 결정 ❌
 * - 실행 허용/거부 결정 ❌
 * - Spec 수정 ❌
 * - Blueprint 생성 ❌
 * - 실행 지시 ❌
 *
 * > "DACS는 의견 제공자이며, 지휘자도, 판사도 아니다."
 */
interface DACS {
    /**
     * Spec에 대한 합의 수행
     *
     * @param request DACS 요청
     * @return 합의 결과 (YES / NO / REVISION)
     */
    suspend fun evaluate(request: DACSRequest): DACSResult
}

/**
 * DACS Request - DACS 입력
 *
 * Canonical: DACS v2 인터페이스 정의서 v2.1 §4
 *
 * DACS는 반드시 **Spec 단위**로만 호출된다.
 */
data class DACSRequest(
    /**
     * 요청 ID
     */
    val requestId: String = UUID.randomUUID().toString(),

    /**
     * 실행 대상 Spec (전체를 그대로 전달)
     */
    val spec: Spec,

    /**
     * Governor가 제공하는 판단 맥락 (선택)
     */
    val context: String? = null,

    /**
     * 요청 시각
     */
    val requestedAt: Instant = Instant.now()
)

/**
 * DACS Result - DACS 출력
 *
 * Canonical: DACS v2 인터페이스 정의서 v2.1 §6
 *
 * DACS는 반드시 **YES, NO, REVISION** 세 가지 결과 중 하나만 반환한다.
 */
data class DACSResult(
    /**
     * 요청 ID
     */
    val requestId: String,

    /**
     * 합의 결과
     */
    val consensus: Consensus,

    /**
     * 합의에 도달한 핵심 이유 요약
     */
    val reason: String,

    /**
     * 각 페르소나의 의견 (디버깅/감사용)
     */
    val personaOpinions: List<PersonaOpinion> = emptyList(),

    /**
     * 평가 완료 시각
     */
    val evaluatedAt: Instant = Instant.now()
) {
    val isYes: Boolean get() = consensus == Consensus.YES
    val isNo: Boolean get() = consensus == Consensus.NO
    val isRevision: Boolean get() = consensus == Consensus.REVISION

    /**
     * Blueprint 생성 가능 여부
     *
     * Canonical: DACS v2 인터페이스 정의서 v2.1 §7.3
     * "YES만 Blueprint 생성을 허용한다"
     */
    val canCreateBlueprint: Boolean get() = consensus == Consensus.YES
}

/**
 * Consensus - 합의 상태
 *
 * Canonical: DACS v2 인터페이스 정의서 v2.1 §6.2
 */
enum class Consensus {
    /**
     * YES - 현재 Spec 상태로 실행 가능
     *
     * "이 Spec을 근거로 Blueprint를 생성하는 것에 합의한다."
     *
     * - 조건부가 아니다
     * - 추가 요구 사항은 존재하지 않는다
     * - YES ≠ 실행 허가 (Gate 통과 필요)
     */
    YES,

    /**
     * NO - 현재 Spec은 실행 불가
     *
     * "이 Spec을 근거로 한 실행은 허용될 수 없다."
     *
     * - 해당 Spec으로 Blueprint 생성 불가
     * - 동일 Spec 보완 후 재시도 불가
     * - 완전히 새로운 Spec으로 새 요청만 가능
     */
    NO,

    /**
     * REVISION - Spec의 보완 또는 명확화 필요
     *
     * "현재의 Spec은 판단에 충분하지 않다."
     *
     * - 부분 승인 아님
     * - 조건부 승인 아님
     * - 중립 상태 (승인도 거부도 아님)
     */
    REVISION
}

/**
 * Persona Opinion - 페르소나 의견
 *
 * 각 페르소나의 독립적인 평가 결과
 */
data class PersonaOpinion(
    /**
     * 페르소나 유형
     */
    val persona: PersonaType,

    /**
     * 페르소나의 투표
     */
    val vote: Vote,

    /**
     * 의견 요약
     */
    val summary: String,

    /**
     * 우려 사항 (있는 경우)
     */
    val concerns: List<String> = emptyList()
)

/**
 * Persona Type - 페르소나 유형
 *
 * Canonical: DACS v2 인터페이스 정의서 v2.1 §5
 */
enum class PersonaType {
    /**
     * Architect - 구조적 타당성 관점
     */
    ARCHITECT,

    /**
     * Reviewer - 요구사항 충족 여부 관점
     */
    REVIEWER,

    /**
     * Adversary - 위험, 보안, 악용 가능성 관점
     */
    ADVERSARY
}

/**
 * Vote - 페르소나 투표
 */
enum class Vote {
    /**
     * 승인
     */
    APPROVE,

    /**
     * 거부
     */
    REJECT,

    /**
     * 보류 (정보 부족)
     */
    ABSTAIN
}

/**
 * Persona - 페르소나 인터페이스
 *
 * Canonical: DACS v2 인터페이스 정의서 v2.1 §5
 *
 * 각 페르소나는:
 * - 동일한 Spec을 입력으로 받는다
 * - 독립적으로 평가한다
 * - 서로의 결과를 알지 못한다
 */
interface Persona {
    /**
     * 페르소나 유형
     */
    val type: PersonaType

    /**
     * Spec 평가
     *
     * @param spec 평가할 Spec
     * @param context 판단 맥락 (선택)
     * @return 페르소나 의견
     */
    suspend fun evaluate(spec: Spec, context: String?): PersonaOpinion
}

/**
 * Consensus Engine - 합의 도출 엔진
 *
 * 페르소나 의견들을 종합하여 최종 합의를 도출
 */
interface ConsensusEngine {
    /**
     * 페르소나 의견들로부터 합의 도출
     *
     * @param opinions 페르소나 의견 목록
     * @return 합의 결과와 이유
     */
    fun derive(opinions: List<PersonaOpinion>): Pair<Consensus, String>
}

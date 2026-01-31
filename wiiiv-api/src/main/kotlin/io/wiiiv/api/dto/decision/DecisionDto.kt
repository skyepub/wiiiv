package io.wiiiv.api.dto.decision

import kotlinx.serialization.Serializable

/**
 * Decision Request - Governor에게 판단 요청
 *
 * Governor는 판단 주체다. Service가 아니다.
 * 이 요청은 "판단해달라"는 요청이지, "실행해달라"는 요청이 아니다.
 */
@Serializable
data class DecisionRequest(
    val spec: SpecInput,
    val context: DecisionContext? = null
)

@Serializable
data class SpecInput(
    val intent: String,
    val constraints: List<String>? = null,
    val metadata: Map<String, String>? = null
)

@Serializable
data class DecisionContext(
    val userId: String? = null,
    val sessionId: String? = null,
    val previousDecisionId: String? = null
)

/**
 * Decision Response - Governor의 판단 결과
 *
 * DACS 합의 결과와 Blueprint 참조를 포함한다.
 */
@Serializable
data class DecisionResponse(
    val decisionId: String,
    val status: DecisionStatus,
    val consensus: ConsensusResult,
    val blueprintId: String? = null,
    val requiresApproval: Boolean = false,
    val message: String? = null
)

@Serializable
enum class DecisionStatus {
    APPROVED,
    REJECTED,
    NEEDS_REVISION,
    PENDING_APPROVAL
}

@Serializable
data class ConsensusResult(
    val outcome: String,  // YES, NO, REVISION
    val votes: List<PersonaVote>,
    val rationale: String? = null
)

@Serializable
data class PersonaVote(
    val persona: String,
    val vote: String,  // APPROVE, REJECT, ABSTAIN
    val reason: String? = null
)

/**
 * User Approval Response
 */
@Serializable
data class ApprovalResponse(
    val decisionId: String,
    val approved: Boolean,
    val blueprintId: String? = null,
    val message: String? = null
)

/**
 * User Rejection Response
 */
@Serializable
data class RejectionResponse(
    val decisionId: String,
    val rejected: Boolean,
    val message: String? = null
)

package io.wiiiv.gate

/**
 * DACS Gate - 합의 없는 실행 차단
 *
 * Canonical: Gate 최소 스펙 정의서 v1.0 §3.1
 *
 * 규칙:
 * ```
 * IF dacs_consensus != "YES"
 * THEN DENY
 * ```
 *
 * - 예외 없음
 * - 관리자 우회 없음
 * - "임시 실행" 없음
 * - **합의 없으면 실행 없음**
 */
class DACSGate(
    override val id: String = "gate-dacs"
) : Gate {
    override val name: String = "DACS Gate"

    override fun check(context: GateContext): GateResult {
        val consensus = context.dacsConsensus

        // IF dacs_consensus != "YES" THEN DENY
        return if (consensus == "YES") {
            GateResult.Allow(gateId = id)
        } else {
            GateResult.Deny(
                gateId = id,
                code = when (consensus) {
                    null -> "NO_CONSENSUS"
                    "NO" -> "CONSENSUS_NO"
                    "REVISION" -> "CONSENSUS_REVISION"
                    else -> "INVALID_CONSENSUS"
                }
            )
        }
    }

    companion object {
        val INSTANCE = DACSGate()
    }
}

/**
 * User Approval Gate - 사용자 승인 없는 실행 차단
 *
 * Canonical: Gate 최소 스펙 정의서 v1.0 §3.2
 *
 * 규칙:
 * ```
 * IF user_approved != true
 * THEN DENY
 * ```
 *
 * - 승인 기록은 반드시 로그로 남는다
 * - 승인 UI/UX는 Gate의 관심사가 아니다
 */
class UserApprovalGate(
    override val id: String = "gate-user-approval"
) : Gate {
    override val name: String = "User Approval Gate"

    override fun check(context: GateContext): GateResult {
        val approved = context.userApproved

        // IF user_approved != true THEN DENY
        return if (approved == true) {
            GateResult.Allow(gateId = id)
        } else {
            GateResult.Deny(
                gateId = id,
                code = if (approved == null) "NO_APPROVAL_INFO" else "NOT_APPROVED"
            )
        }
    }

    companion object {
        val INSTANCE = UserApprovalGate()
    }
}

/**
 * Execution Permission Gate - Executor 권한 검증
 *
 * Canonical: Gate 최소 스펙 정의서 v1.0 §3.3
 *
 * 규칙:
 * ```
 * IF executor NOT permitted for action
 * THEN DENY
 * ```
 *
 * - 권한 매핑은 정적 정의
 * - 런타임 판단 없음
 */
class ExecutionPermissionGate(
    override val id: String = "gate-permission",
    private val permissions: Map<String, Set<String>> = emptyMap()
) : Gate {
    override val name: String = "Execution Permission Gate"

    override fun check(context: GateContext): GateResult {
        val executorId = context.executorId
        val action = context.action

        // 필수 정보 없으면 DENY
        if (executorId == null || action == null) {
            return GateResult.Deny(
                gateId = id,
                code = "MISSING_INFO"
            )
        }

        // 빈 permissions는 모두 허용 (개발/테스트용)
        if (permissions.isEmpty()) {
            return GateResult.Allow(gateId = id)
        }

        // 권한 검사
        val allowedActions = permissions[executorId]
        return if (allowedActions != null && action in allowedActions) {
            GateResult.Allow(gateId = id)
        } else {
            GateResult.Deny(
                gateId = id,
                code = "NOT_PERMITTED"
            )
        }
    }

    companion object {
        /**
         * 모든 권한 허용 (테스트용)
         */
        val PERMISSIVE = ExecutionPermissionGate()

        /**
         * 권한 매핑으로 생성
         */
        fun withPermissions(permissions: Map<String, Set<String>>) =
            ExecutionPermissionGate(permissions = permissions)
    }
}

/**
 * Cost Gate - 비용/정책 한도 초과 차단
 *
 * Canonical: Gate 최소 스펙 정의서 v1.0 §3.4
 *
 * 규칙:
 * ```
 * IF estimated_cost > cost_limit
 * THEN DENY
 * ```
 *
 * - 계산 방식은 Gate 외부 책임
 * - Gate는 비교만 수행
 */
class CostGate(
    override val id: String = "gate-cost",
    private val defaultLimit: Double = Double.MAX_VALUE
) : Gate {
    override val name: String = "Cost Gate"

    override fun check(context: GateContext): GateResult {
        val estimatedCost = context.estimatedCost ?: 0.0
        val costLimit = context.costLimit ?: defaultLimit

        // IF estimated_cost > cost_limit THEN DENY
        return if (estimatedCost <= costLimit) {
            GateResult.Allow(gateId = id)
        } else {
            GateResult.Deny(
                gateId = id,
                code = "COST_EXCEEDED"
            )
        }
    }

    companion object {
        /**
         * 한도 무제한 (테스트용)
         */
        val UNLIMITED = CostGate()

        /**
         * 기본 한도 설정
         */
        fun withLimit(limit: Double) = CostGate(defaultLimit = limit)
    }
}

/**
 * Gate Chain - Gate 체인
 *
 * Canonical: Gate 최소 스펙 정의서 v1.0 §4
 *
 * Gate는 반드시 체인 형태로 평가된다:
 * ```
 * DACS Gate → User Approval Gate → Permission Gate → Cost Gate
 * ```
 *
 * - **하나라도 DENY → 전체 DENY**
 * - **모든 Gate 통과 → ALLOW**
 * - Gate 체인 순서는 **고정**이다
 */
class GateChain private constructor(
    private val gates: List<Gate>,
    private val logger: GateLogger?
) {
    /**
     * 체인 내 모든 Gate 검사
     *
     * @param context Gate 검사 컨텍스트
     * @return 체인 검사 결과
     */
    fun check(context: GateContext): GateChainResult {
        val results = mutableListOf<GateResult>()

        for (gate in gates) {
            val result = gate.check(context)

            // 로깅
            logger?.log(createLogEntry(gate, context, result))

            results.add(result)

            // 하나라도 DENY면 즉시 중단
            if (result.isDeny) {
                return GateChainResult(
                    finalResult = result,
                    allResults = results.toList(),
                    stoppedAt = gate.id
                )
            }
        }

        // 모두 ALLOW
        return GateChainResult(
            finalResult = results.lastOrNull() ?: GateResult.Allow("empty-chain"),
            allResults = results.toList(),
            stoppedAt = null
        )
    }

    private fun createLogEntry(gate: Gate, context: GateContext, result: GateResult): GateLogEntry {
        return GateLogEntry(
            gateName = gate.name,
            gateId = gate.id,
            requestId = context.requestId,
            inputSummary = createInputSummary(gate, context),
            result = if (result.isAllow) "ALLOW" else "DENY",
            denyCode = (result as? GateResult.Deny)?.code
        )
    }

    private fun createInputSummary(gate: Gate, context: GateContext): String {
        return when (gate) {
            is DACSGate -> "dacs_consensus=${context.dacsConsensus}"
            is UserApprovalGate -> "user_approved=${context.userApproved}"
            is ExecutionPermissionGate -> "executor=${context.executorId}, action=${context.action}"
            is CostGate -> "cost=${context.estimatedCost}, limit=${context.costLimit}"
            else -> "context=${context.requestId}"
        }
    }

    /**
     * Builder for GateChain
     */
    class Builder {
        private val gates = mutableListOf<Gate>()
        private var logger: GateLogger? = null

        fun add(gate: Gate): Builder {
            gates.add(gate)
            return this
        }

        fun withLogger(logger: GateLogger): Builder {
            this.logger = logger
            return this
        }

        fun build(): GateChain = GateChain(gates.toList(), logger)
    }

    companion object {
        fun builder() = Builder()

        /**
         * 표준 Gate 체인 생성
         *
         * Canonical 순서: DACS → User Approval → Permission → Cost
         */
        fun standard(logger: GateLogger? = null): GateChain {
            return builder()
                .add(DACSGate.INSTANCE)
                .add(UserApprovalGate.INSTANCE)
                .add(ExecutionPermissionGate.PERMISSIVE)
                .add(CostGate.UNLIMITED)
                .apply { logger?.let { withLogger(it) } }
                .build()
        }

        /**
         * 최소 Gate 체인 (DACS만)
         */
        fun minimal(logger: GateLogger? = null): GateChain {
            return builder()
                .add(DACSGate.INSTANCE)
                .apply { logger?.let { withLogger(it) } }
                .build()
        }

        /**
         * 개발용 Gate 체인 (모두 허용)
         */
        fun permissive(): GateChain {
            return builder()
                .add(AlwaysAllowGate())
                .build()
        }
    }
}

/**
 * Gate Chain Result - 체인 검사 결과
 */
data class GateChainResult(
    /**
     * 최종 결과
     */
    val finalResult: GateResult,

    /**
     * 각 Gate 검사 결과
     */
    val allResults: List<GateResult>,

    /**
     * DENY로 중단된 Gate ID (null이면 모두 통과)
     */
    val stoppedAt: String?
) {
    val isAllow: Boolean get() = finalResult.isAllow
    val isDeny: Boolean get() = finalResult.isDeny

    /**
     * 통과한 Gate 수
     */
    val passedCount: Int get() = allResults.count { it.isAllow }
}

/**
 * Always Allow Gate - 항상 허용 (테스트/개발용)
 *
 * 주의: 프로덕션에서 사용 금지
 */
class AlwaysAllowGate(
    override val id: String = "gate-always-allow"
) : Gate {
    override val name: String = "Always Allow Gate (DEV ONLY)"

    override fun check(context: GateContext): GateResult {
        return GateResult.Allow(gateId = id)
    }
}

/**
 * Always Deny Gate - 항상 거부 (테스트용)
 */
class AlwaysDenyGate(
    override val id: String = "gate-always-deny",
    private val code: String = "ALWAYS_DENY"
) : Gate {
    override val name: String = "Always Deny Gate"

    override fun check(context: GateContext): GateResult {
        return GateResult.Deny(gateId = id, code = code)
    }
}

package io.wiiiv.gate

import java.time.Instant
import java.util.UUID

/**
 * Gate - 통제 계층
 *
 * Canonical: Gate 최소 스펙 정의서 v1.0
 *
 * ## Gate의 위상 (절대 규칙)
 *
 * "Gate는 wiiiv에서 유일한 통제 주체다."
 *
 * - Gate는 판단하지 않는다
 * - Gate는 해석하지 않는다
 * - Gate는 설명하지 않는다
 * - **Gate는 강제한다**
 *
 * ## Gate의 결정
 *
 * - **최종적**이며
 * - **즉시 적용**되고
 * - **어떠한 경우에도 우회될 수 없다**
 *
 * ## Gate의 공통 특성
 *
 * | 항목 | 규칙 |
 * |------|------|
 * | 판단 | ❌ 하지 않음 |
 * | 입력 | 구조화된 요청 |
 * | 출력 | **ALLOW / DENY** |
 * | 상태 | **Stateless (필수)** |
 * | 우회 | **불가능** |
 * | 예외 | **없음** |
 *
 * > **Gate는 if–else 이상의 지능을 가지면 안 된다.**
 */
interface Gate {
    /**
     * Gate 식별자
     */
    val id: String

    /**
     * Gate 이름
     */
    val name: String

    /**
     * Gate 검사 수행
     *
     * @param context Gate 검사에 필요한 컨텍스트
     * @return ALLOW 또는 DENY
     */
    fun check(context: GateContext): GateResult
}

/**
 * Gate Result - Gate 검사 결과
 *
 * Canonical: Gate 최소 스펙 정의서 v1.0 §2
 *
 * Gate는 오직 ALLOW 또는 DENY만 반환한다.
 */
sealed class GateResult {
    /**
     * 검사 시각
     */
    abstract val checkedAt: Instant

    /**
     * Gate 식별자
     */
    abstract val gateId: String

    /**
     * ALLOW - 통과
     */
    data class Allow(
        override val gateId: String,
        override val checkedAt: Instant = Instant.now()
    ) : GateResult()

    /**
     * DENY - 거부
     *
     * Gate는 설명하지 않는다. reason은 로그용 코드일 뿐이다.
     */
    data class Deny(
        override val gateId: String,
        /**
         * 거부 코드 (로그용, 설명 아님)
         */
        val code: String,
        override val checkedAt: Instant = Instant.now()
    ) : GateResult()

    val isAllow: Boolean get() = this is Allow
    val isDeny: Boolean get() = this is Deny
}

/**
 * Gate Context - Gate 검사용 컨텍스트
 *
 * Gate에 전달되는 구조화된 입력
 */
data class GateContext(
    /**
     * 요청 ID
     */
    val requestId: String = UUID.randomUUID().toString(),

    /**
     * Blueprint ID
     */
    val blueprintId: String? = null,

    /**
     * DACS 합의 결과
     */
    val dacsConsensus: String? = null,

    /**
     * 사용자 승인 여부
     */
    val userApproved: Boolean? = null,

    /**
     * Executor ID
     */
    val executorId: String? = null,

    /**
     * 요청 액션
     */
    val action: String? = null,

    /**
     * 예상 비용
     */
    val estimatedCost: Double? = null,

    /**
     * 비용 한도
     */
    val costLimit: Double? = null,

    /**
     * 추가 속성
     */
    val attributes: Map<String, Any> = emptyMap()
) {
    companion object {
        /**
         * DACS 검사용 컨텍스트 생성
         */
        fun forDacs(consensus: String, blueprintId: String? = null) = GateContext(
            dacsConsensus = consensus,
            blueprintId = blueprintId
        )

        /**
         * 사용자 승인 검사용 컨텍스트 생성
         */
        fun forUserApproval(approved: Boolean, blueprintId: String? = null) = GateContext(
            userApproved = approved,
            blueprintId = blueprintId
        )

        /**
         * 권한 검사용 컨텍스트 생성
         */
        fun forPermission(executorId: String, action: String) = GateContext(
            executorId = executorId,
            action = action
        )

        /**
         * 비용 검사용 컨텍스트 생성
         */
        fun forCost(estimatedCost: Double, costLimit: Double) = GateContext(
            estimatedCost = estimatedCost,
            costLimit = costLimit
        )
    }
}

/**
 * Gate Log Entry - Gate 로그 항목
 *
 * Canonical: Gate 최소 스펙 정의서 v1.0 §7
 *
 * 모든 Gate는 다음을 기록해야 한다:
 * - Gate 이름
 * - 입력 요약
 * - 결과 (ALLOW / DENY)
 * - 타임스탬프
 */
data class GateLogEntry(
    /**
     * 로그 ID
     */
    val logId: String = UUID.randomUUID().toString(),

    /**
     * Gate 이름
     */
    val gateName: String,

    /**
     * Gate ID
     */
    val gateId: String,

    /**
     * 요청 ID
     */
    val requestId: String,

    /**
     * 입력 요약
     */
    val inputSummary: String,

    /**
     * 결과
     */
    val result: String, // "ALLOW" or "DENY"

    /**
     * DENY 코드 (DENY인 경우)
     */
    val denyCode: String? = null,

    /**
     * 타임스탬프
     */
    val timestamp: Instant = Instant.now()
)

/**
 * Gate Logger - Gate 로그 기록기
 *
 * Canonical: Gate 최소 스펙 정의서 v1.0 §7
 *
 * Gate 로그는:
 * - **수정 불가**
 * - **삭제 불가**
 * - **Governor 접근 불가**
 */
interface GateLogger {
    /**
     * 로그 기록
     */
    fun log(entry: GateLogEntry)

    /**
     * 로그 조회 (감사용)
     */
    fun getEntries(requestId: String): List<GateLogEntry>
}

/**
 * In-Memory Gate Logger - 메모리 기반 로거 (테스트/개발용)
 */
class InMemoryGateLogger : GateLogger {
    private val entries = mutableListOf<GateLogEntry>()

    override fun log(entry: GateLogEntry) {
        entries.add(entry)
    }

    override fun getEntries(requestId: String): List<GateLogEntry> {
        return entries.filter { it.requestId == requestId }.toList()
    }

    /**
     * 전체 로그 조회
     */
    fun getAllEntries(): List<GateLogEntry> = entries.toList()

    /**
     * 로그 개수
     */
    val size: Int get() = entries.size
}

package io.wiiiv.execution

/**
 * Executor Capability - Executor가 수행할 수 있는 행위의 종류
 *
 * Canonical: Executor Governance Spec v1.0 §1
 *
 * Gate와 Smart Router가 Executor의 능력을 사전 판단할 때 사용한다.
 * Executor 자체는 변경하지 않고, 외부에서 선언하는 거버넌스 정보이다.
 *
 * ## 설계 원칙
 *
 * | 원칙 | 설명 |
 * |------|------|
 * | 실행과 판단의 분리 | Executor는 실행만, Capability는 판단 정보만 |
 * | LLM 불필요 | Gate가 Capability만으로 기본 안전 판단 가능 |
 * | Executor 코드 변경 없음 | Meta는 외부에서 등록 |
 */
enum class Capability {
    /** 외부 상태를 변경하지 않는 읽기 */
    READ,
    /** 외부 상태를 변경하는 쓰기 */
    WRITE,
    /** 외부로 데이터를 발송 (메일, 메시지, 알림) */
    SEND,
    /** 외부 데이터/리소스를 삭제 */
    DELETE,
    /** OS 명령 또는 스크립트를 실행 */
    EXECUTE
}

/**
 * Risk Level - Executor 행위의 본질적 위험 수준
 *
 * Canonical: Executor Governance Spec v1.0 §2
 *
 * 개별 요청의 위험도가 아닌, Executor 자체의 본질적 위험도이다.
 * Gate는 이 값을 참조하여 LLM 없이도 기본적인 안전 판단을 수행한다.
 *
 * 순서가 의미를 가진다: LOW < MEDIUM < HIGH (Comparable 활용)
 */
enum class RiskLevel {
    /** 부작용 없음 (읽기 전용, 계산, 분석) */
    LOW,
    /** 제한적 부작용 (파일 쓰기, API 호출, 메시지 발송) */
    MEDIUM,
    /** 높은 부작용 (OS 명령, DB DDL, 대량 삭제) */
    HIGH
}

/**
 * Executor Meta - Executor의 거버넌스 메타데이터
 *
 * Canonical: Executor Governance Spec v1.0 §3
 *
 * 실행 로직이 아닌 "판단 정보"이다.
 * Executor가 무엇을 할 수 있는지, 얼마나 위험한지를 선언한다.
 *
 * ## Executor 코드와의 관계
 *
 * - Executor 인터페이스는 변경하지 않는다
 * - Meta는 별도 레지스트리에서 관리한다
 * - CompositeExecutor는 그대로 유지한다
 * - Meta는 거버넌스 레이어(Gate, Router)에서만 참조한다
 *
 * @property scheme URI scheme (file, http, os, db, llm, ws, mq, grpc, ...)
 * @property name 표시 이름
 * @property capabilities 수행 가능한 행위
 * @property idempotent 동일 요청 반복 시 동일 결과 보장 여부
 * @property riskLevel 본질적 위험 수준
 * @property stepType 대응하는 StepType (기존 시스템 연결)
 * @property description 설명
 */
data class ExecutorMeta(
    val scheme: String,
    val name: String,
    val capabilities: Set<Capability>,
    val idempotent: Boolean,
    val riskLevel: RiskLevel,
    val stepType: StepType,
    val description: String = "",
    /** 액션별 riskLevel 오버라이드 (플러그인용). 키: 액션 이름, 값: 해당 액션의 riskLevel */
    val actionRiskLevels: Map<String, RiskLevel> = emptyMap()
) {
    /** READ만 가능한 Executor인지 */
    val isReadOnly: Boolean get() =
        capabilities == setOf(Capability.READ)

    /** 외부에 영향을 줄 수 있는 행위(WRITE, SEND, DELETE, EXECUTE)를 포함하는지 */
    val hasSideEffect: Boolean get() =
        capabilities.any { it != Capability.READ }

    /** 액션별 riskLevel 조회. 없으면 플러그인 기본값 반환 */
    fun riskLevelFor(action: String?): RiskLevel {
        return action?.let { actionRiskLevels[it] } ?: riskLevel
    }
}

/**
 * Executor Meta Registry - Executor 거버넌스 메타데이터 저장소
 *
 * Canonical: Executor Governance Spec v1.0 §4
 *
 * 기존 CompositeExecutor의 실행 라우팅과 별도로,
 * "이 Executor는 무엇을 할 수 있고, 얼마나 위험한가"를 관리한다.
 *
 * ## 위치
 *
 * | 관심사 | 담당 |
 * |--------|------|
 * | 실행 라우팅 | CompositeExecutor (canHandle 기반) |
 * | 거버넌스 판단 | ExecutorMetaRegistry (capability/risk 기반) |
 *
 * ## 사용처
 *
 * - Smart Router: Direct vs HLX 판단 시 capability/risk 참조
 * - Gate: Executor의 위험도를 사전 판단
 * - HLX: ACT 노드의 target scheme으로 Executor 능력 확인
 * - 감사: 실행 이력에 Executor meta 기록
 */
class ExecutorMetaRegistry {
    private val byScheme = LinkedHashMap<String, ExecutorMeta>()
    private val byStepType = LinkedHashMap<StepType, ExecutorMeta>()

    /**
     * Executor 메타데이터 등록
     */
    fun register(meta: ExecutorMeta) {
        byScheme[meta.scheme] = meta
        byStepType[meta.stepType] = meta
    }

    /**
     * URI scheme으로 메타 조회
     *
     * @param scheme "file", "http", "os", "db" 등
     */
    fun getByScheme(scheme: String): ExecutorMeta? = byScheme[scheme]

    /**
     * URI target에서 scheme을 추출하여 메타 조회
     *
     * 예: "file:///path/to/file" → scheme "file" → FileExecutor meta
     *     "http://host/api"      → scheme "http" → ApiExecutor meta
     *
     * @param target URI 형식의 대상 (scheme://...)
     */
    fun resolveByTarget(target: String): ExecutorMeta? {
        val scheme = target.substringBefore("://", missingDelimiterValue = "")
        return if (scheme.isNotBlank()) byScheme[scheme] else null
    }

    /**
     * StepType으로 메타 조회 (기존 시스템 연결)
     *
     * CompositeExecutor의 step-based 라우팅 결과에
     * 거버넌스 메타를 추가로 조회할 때 사용한다.
     */
    fun getByStepType(stepType: StepType): ExecutorMeta? = byStepType[stepType]

    /**
     * 특정 capability를 가진 Executor 메타 목록
     */
    fun findByCapability(capability: Capability): List<ExecutorMeta> =
        byScheme.values.filter { capability in it.capabilities }

    /**
     * 특정 위험 수준 이하의 Executor 메타 목록
     *
     * 예: findByMaxRisk(RiskLevel.LOW) → 읽기 전용, 무부작용 Executor만
     */
    fun findByMaxRisk(maxRisk: RiskLevel): List<ExecutorMeta> =
        byScheme.values.filter { it.riskLevel <= maxRisk }

    /**
     * 등록된 모든 메타 조회 (등록 순서 유지)
     */
    fun all(): List<ExecutorMeta> = byScheme.values.toList()

    /**
     * 등록된 메타 수
     */
    val size: Int get() = byScheme.size
}

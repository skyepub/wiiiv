package io.wiiiv.plugin

import io.wiiiv.execution.Capability
import io.wiiiv.execution.Executor
import io.wiiiv.execution.ExecutorMeta
import io.wiiiv.execution.RiskLevel

/**
 * Wiiiv Plugin Interface
 *
 * 플러그인이 구현해야 하는 핵심 인터페이스.
 * 코드가 진실 (single source of truth) — actions, risk, capability는 여기서만 제공한다.
 * plugin.yaml은 표시용 메타 정보(displayName, description, vendor)만 담는다.
 */
interface WiiivPlugin {
    /** 플러그인 고유 식별자 (예: "webhook", "slack") */
    val pluginId: String

    /** 표시 이름 */
    val displayName: String

    /** 버전 */
    val version: String

    /** Executor 인스턴스 생성 */
    fun createExecutor(config: PluginConfig): Executor

    /** 거버넌스 메타데이터 생성 (capabilities, riskLevel, actionRiskLevels 포함) */
    fun executorMeta(): ExecutorMeta

    /** 이 플러그인이 제공하는 액션 목록 */
    fun actions(): List<PluginAction>
}

/**
 * 플러그인 액션 정의
 *
 * 같은 플러그인 안에서도 액션마다 riskLevel이 다를 수 있다.
 * (예: ping=LOW, send=MEDIUM)
 */
data class PluginAction(
    val name: String,
    val description: String,
    val riskLevel: RiskLevel,
    val capabilities: Set<Capability>,
    val requiredParams: List<String>,
    val optionalParams: List<String> = emptyList()
)

/**
 * 플러그인 설정
 *
 * 실제 값만 담는다. 설명/스키마는 plugin.yaml의 configSchema에 문서화.
 * 우선순위: System.getenv → defaults
 */
data class PluginConfig(
    val env: Map<String, String> = emptyMap()
)

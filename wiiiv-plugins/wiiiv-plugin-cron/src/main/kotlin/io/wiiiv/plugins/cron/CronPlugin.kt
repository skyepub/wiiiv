package io.wiiiv.plugins.cron

import io.wiiiv.execution.*
import io.wiiiv.plugin.PluginAction
import io.wiiiv.plugin.PluginConfig
import io.wiiiv.plugin.WiiivPlugin

/**
 * Cron/Scheduler Plugin — 워크플로우 지연 및 크론 스케줄링
 *
 * 코드가 진실 (single source of truth):
 * actions, risk, capability는 여기서 선언한다.
 */
class CronPlugin : WiiivPlugin {
    override val pluginId = "cron"
    override val displayName = "Cron/Scheduler"
    override val version = "1.0.0"

    override fun createExecutor(config: PluginConfig): Executor = CronExecutor(config)

    override fun executorMeta(): ExecutorMeta = ExecutorMeta(
        scheme = "cron",
        name = "CronExecutor",
        capabilities = setOf(Capability.EXECUTE, Capability.SEND),
        idempotent = false,
        riskLevel = RiskLevel.MEDIUM,
        stepType = StepType.PLUGIN,
        description = "워크플로우 지연(delay) 및 크론 기반 반복 실행 스케줄링",
        actionRiskLevels = mapOf(
            "delay" to RiskLevel.LOW,
            "schedule" to RiskLevel.MEDIUM,
            "list" to RiskLevel.LOW,
            "cancel" to RiskLevel.MEDIUM
        )
    )

    override fun actions(): List<PluginAction> = listOf(
        PluginAction(
            name = "delay",
            description = "워크플로우 실행을 N ms 일시정지",
            riskLevel = RiskLevel.LOW,
            capabilities = setOf(Capability.EXECUTE),
            requiredParams = listOf("duration_ms")
        ),
        PluginAction(
            name = "schedule",
            description = "크론 표현식 기반 반복 실행 등록 (callback_url로 POST)",
            riskLevel = RiskLevel.MEDIUM,
            capabilities = setOf(Capability.EXECUTE, Capability.SEND),
            requiredParams = listOf("cron_expr", "callback_url"),
            optionalParams = listOf("job_id", "payload")
        ),
        PluginAction(
            name = "list",
            description = "활성 스케줄 목록 반환",
            riskLevel = RiskLevel.LOW,
            capabilities = setOf(Capability.READ),
            requiredParams = emptyList()
        ),
        PluginAction(
            name = "cancel",
            description = "스케줄된 작업 취소",
            riskLevel = RiskLevel.MEDIUM,
            capabilities = setOf(Capability.DELETE),
            requiredParams = listOf("job_id")
        )
    )
}

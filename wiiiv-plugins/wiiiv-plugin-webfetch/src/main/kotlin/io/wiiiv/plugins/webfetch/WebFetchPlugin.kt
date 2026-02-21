package io.wiiiv.plugins.webfetch

import io.wiiiv.execution.*
import io.wiiiv.plugin.PluginAction
import io.wiiiv.plugin.PluginConfig
import io.wiiiv.plugin.WiiivPlugin

/**
 * WebFetch Plugin — URL 텍스트/JSON 추출 및 웹 검색
 *
 * 코드가 진실 (single source of truth):
 * actions, risk, capability는 여기서 선언한다.
 */
class WebFetchPlugin : WiiivPlugin {
    override val pluginId = "webfetch"
    override val displayName = "WebFetch"
    override val version = "1.0.0"

    override fun createExecutor(config: PluginConfig): Executor = WebFetchExecutor(config)

    override fun executorMeta(): ExecutorMeta = ExecutorMeta(
        scheme = "webfetch",
        name = "WebFetchExecutor",
        capabilities = setOf(Capability.READ),
        idempotent = true,
        riskLevel = RiskLevel.LOW,
        stepType = StepType.PLUGIN,
        description = "URL 텍스트/JSON 추출 및 웹 검색 (SSRF 보호 포함)",
        actionRiskLevels = mapOf(
            "fetch" to RiskLevel.LOW,
            "fetch_json" to RiskLevel.LOW,
            "search" to RiskLevel.LOW
        )
    )

    override fun actions(): List<PluginAction> = listOf(
        PluginAction(
            name = "fetch",
            description = "URL → 텍스트/HTML 추출 (CSS selector 지원)",
            riskLevel = RiskLevel.LOW,
            capabilities = setOf(Capability.READ),
            requiredParams = listOf("url"),
            optionalParams = listOf("selector", "max_length", "header:*")
        ),
        PluginAction(
            name = "fetch_json",
            description = "URL → JSON 파싱 (점 표기법 경로 추출)",
            riskLevel = RiskLevel.LOW,
            capabilities = setOf(Capability.READ),
            requiredParams = listOf("url"),
            optionalParams = listOf("jq_path", "header:*")
        ),
        PluginAction(
            name = "search",
            description = "웹 검색 API 호출 (SerpAPI/Google CSE)",
            riskLevel = RiskLevel.LOW,
            capabilities = setOf(Capability.READ),
            requiredParams = listOf("query"),
            optionalParams = listOf("engine", "limit")
        )
    )
}

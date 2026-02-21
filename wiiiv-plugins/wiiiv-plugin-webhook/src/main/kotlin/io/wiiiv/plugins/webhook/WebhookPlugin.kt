package io.wiiiv.plugins.webhook

import io.wiiiv.execution.*
import io.wiiiv.plugin.PluginAction
import io.wiiiv.plugin.PluginConfig
import io.wiiiv.plugin.WiiivPlugin

/**
 * Webhook Plugin — HTTP Webhook 전송 및 헬스체크
 *
 * 코드가 진실 (single source of truth):
 * actions, risk, capability는 여기서 선언한다.
 */
class WebhookPlugin : WiiivPlugin {
    override val pluginId = "webhook"
    override val displayName = "Webhook Executor"
    override val version = "1.0.0"

    override fun createExecutor(config: PluginConfig): Executor = WebhookExecutor(config)

    override fun executorMeta(): ExecutorMeta = ExecutorMeta(
        scheme = "webhook",
        name = "WebhookExecutor",
        capabilities = setOf(Capability.READ, Capability.SEND),
        idempotent = false,
        riskLevel = RiskLevel.MEDIUM,
        stepType = StepType.PLUGIN,
        description = "HTTP Webhook 전송 (JSON/Form) 및 헬스체크",
        actionRiskLevels = mapOf(
            "ping" to RiskLevel.LOW,
            "send" to RiskLevel.MEDIUM,
            "send_form" to RiskLevel.MEDIUM
        )
    )

    override fun actions(): List<PluginAction> = listOf(
        PluginAction(
            name = "ping",
            description = "GET 요청으로 대상 URL 헬스체크",
            riskLevel = RiskLevel.LOW,
            capabilities = setOf(Capability.READ),
            requiredParams = listOf("url"),
            optionalParams = listOf("header:*")
        ),
        PluginAction(
            name = "send",
            description = "JSON body를 URL에 POST 전송",
            riskLevel = RiskLevel.MEDIUM,
            capabilities = setOf(Capability.SEND),
            requiredParams = listOf("url", "body"),
            optionalParams = listOf("header:*", "content_type")
        ),
        PluginAction(
            name = "send_form",
            description = "form-encoded body를 URL에 POST 전송",
            riskLevel = RiskLevel.MEDIUM,
            capabilities = setOf(Capability.SEND),
            requiredParams = listOf("url", "form_data"),
            optionalParams = listOf("header:*")
        )
    )
}

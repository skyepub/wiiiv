package io.wiiiv.plugins.mail

import io.wiiiv.execution.*
import io.wiiiv.plugin.PluginAction
import io.wiiiv.plugin.PluginConfig
import io.wiiiv.plugin.WiiivPlugin

/**
 * Mail Plugin — SMTP 발송 및 IMAP 수신
 *
 * 코드가 진실 (single source of truth):
 * actions, risk, capability는 여기서 선언한다.
 */
class MailPlugin : WiiivPlugin {
    override val pluginId = "mail"
    override val displayName = "Mail (SMTP/IMAP)"
    override val version = "1.0.0"

    override fun createExecutor(config: PluginConfig): Executor = MailExecutor(config)

    override fun executorMeta(): ExecutorMeta = ExecutorMeta(
        scheme = "mail",
        name = "MailExecutor",
        capabilities = setOf(Capability.READ, Capability.SEND, Capability.DELETE),
        idempotent = false,
        riskLevel = RiskLevel.MEDIUM,
        stepType = StepType.PLUGIN,
        description = "SMTP 이메일 발송 및 IMAP 수신함 읽기/검색/삭제",
        actionRiskLevels = mapOf(
            "send" to RiskLevel.MEDIUM,
            "read_inbox" to RiskLevel.LOW,
            "search" to RiskLevel.LOW,
            "delete" to RiskLevel.HIGH
        )
    )

    override fun actions(): List<PluginAction> = listOf(
        PluginAction(
            name = "send",
            description = "SMTP 이메일 발송 (TLS, HTML, 첨부파일 지원)",
            riskLevel = RiskLevel.MEDIUM,
            capabilities = setOf(Capability.SEND),
            requiredParams = listOf("to", "subject", "body"),
            optionalParams = listOf("cc", "bcc", "html", "attachment_path", "header:*")
        ),
        PluginAction(
            name = "read_inbox",
            description = "IMAP 수신함 읽기",
            riskLevel = RiskLevel.LOW,
            capabilities = setOf(Capability.READ),
            requiredParams = emptyList(),
            optionalParams = listOf("folder", "limit", "unread_only")
        ),
        PluginAction(
            name = "search",
            description = "IMAP 이메일 검색",
            riskLevel = RiskLevel.LOW,
            capabilities = setOf(Capability.READ),
            requiredParams = listOf("query"),
            optionalParams = listOf("folder", "limit", "since", "from")
        ),
        PluginAction(
            name = "delete",
            description = "이메일 삭제 (위험 - HIGH riskLevel)",
            riskLevel = RiskLevel.HIGH,
            capabilities = setOf(Capability.DELETE),
            requiredParams = listOf("message_id"),
            optionalParams = listOf("folder")
        )
    )
}

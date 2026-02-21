package io.wiiiv.plugins.mail

import io.wiiiv.plugin.PluginConfig

/**
 * SMTP/IMAP 설정 추출
 *
 * 환경변수 규칙: WIIIV_PLUGIN_MAIL_{KEY} → config.env["KEY"]
 */
class MailConfig(config: PluginConfig) {
    // SMTP
    val smtpHost: String? = config.env["SMTP_HOST"]
    val smtpPort: Int = config.env["SMTP_PORT"]?.toIntOrNull() ?: 587
    val smtpUser: String? = config.env["SMTP_USER"]
    val smtpPassword: String? = config.env["SMTP_PASSWORD"]

    // IMAP
    val imapHost: String? = config.env["IMAP_HOST"]
    val imapPort: Int = config.env["IMAP_PORT"]?.toIntOrNull() ?: 993
    val imapUser: String? = config.env["IMAP_USER"]
    val imapPassword: String? = config.env["IMAP_PASSWORD"]

    fun validateSmtp(): String? {
        if (smtpHost.isNullOrBlank()) return "SMTP_HOST not configured"
        if (smtpUser.isNullOrBlank()) return "SMTP_USER not configured"
        if (smtpPassword.isNullOrBlank()) return "SMTP_PASSWORD not configured"
        return null
    }

    fun validateImap(): String? {
        if (imapHost.isNullOrBlank()) return "IMAP_HOST not configured"
        if (imapUser.isNullOrBlank()) return "IMAP_USER not configured"
        if (imapPassword.isNullOrBlank()) return "IMAP_PASSWORD not configured"
        return null
    }
}

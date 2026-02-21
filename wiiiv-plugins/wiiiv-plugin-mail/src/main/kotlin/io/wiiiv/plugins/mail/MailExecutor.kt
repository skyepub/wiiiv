package io.wiiiv.plugins.mail

import io.wiiiv.execution.*
import io.wiiiv.plugin.PluginConfig
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.SubjectTerm
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.util.Properties

/**
 * Mail Executor — SMTP 발송 및 IMAP 수신
 *
 * 액션:
 * - send: SMTP 이메일 발송 (TLS, HTML, 첨부파일 지원)
 * - read_inbox: IMAP 수신함 읽기
 * - search: IMAP 이메일 검색
 * - delete: 이메일 삭제
 */
class MailExecutor(config: PluginConfig) : Executor {

    private val mailConfig = MailConfig(config)

    override fun canHandle(step: ExecutionStep): Boolean =
        step is ExecutionStep.PluginStep && step.pluginId == "mail"

    override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
        val ps = step as ExecutionStep.PluginStep
        val startedAt = Instant.now()

        return try {
            when (ps.action) {
                "send" -> executeSend(ps, startedAt)
                "read_inbox" -> executeReadInbox(ps, startedAt)
                "search" -> executeSearch(ps, startedAt)
                "delete" -> executeDelete(ps, startedAt)
                else -> contractViolation(ps.stepId, "UNKNOWN_ACTION", "Unknown mail action: ${ps.action}")
            }
        } catch (e: AuthenticationFailedException) {
            ExecutionResult.failure(
                error = ExecutionError(
                    category = ErrorCategory.PERMISSION_DENIED,
                    code = "AUTH_FAILED",
                    message = "Mail authentication failed: ${e.message}"
                ),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )
        } catch (e: MessagingException) {
            ExecutionResult.failure(
                error = ExecutionError.externalServiceError("MAIL_ERROR", "Mail error: ${e.message}"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )
        } catch (e: Exception) {
            ExecutionResult.failure(
                error = ExecutionError.unknown("Mail executor error: ${e.message}"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )
        }
    }

    private fun executeSend(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val configError = mailConfig.validateSmtp()
        if (configError != null) {
            return contractViolation(ps.stepId, "SMTP_NOT_CONFIGURED", configError)
        }

        val to = ps.params["to"] ?: return contractViolation(ps.stepId, "MISSING_TO", "send requires 'to' param")
        val subject = ps.params["subject"] ?: return contractViolation(ps.stepId, "MISSING_SUBJECT", "send requires 'subject' param")
        val body = ps.params["body"] ?: return contractViolation(ps.stepId, "MISSING_BODY", "send requires 'body' param")
        val isHtml = ps.params["html"]?.toBoolean() ?: false
        val attachmentPath = ps.params["attachment_path"]

        val props = Properties().apply {
            put("mail.smtp.host", mailConfig.smtpHost)
            put("mail.smtp.port", mailConfig.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(mailConfig.smtpUser, mailConfig.smtpPassword)
        })

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(mailConfig.smtpUser))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            ps.params["cc"]?.let { setRecipients(Message.RecipientType.CC, InternetAddress.parse(it)) }
            ps.params["bcc"]?.let { setRecipients(Message.RecipientType.BCC, InternetAddress.parse(it)) }
            setSubject(subject, "UTF-8")

            // 헤더
            ps.params.forEach { (k, v) ->
                if (k.startsWith("header:")) addHeader(k.removePrefix("header:"), v)
            }
        }

        if (attachmentPath != null) {
            val multipart = MimeMultipart().apply {
                addBodyPart(MimeBodyPart().apply {
                    if (isHtml) setContent(body, "text/html; charset=UTF-8")
                    else setText(body, "UTF-8")
                })
                addBodyPart(MimeBodyPart().apply {
                    attachFile(File(attachmentPath))
                })
            }
            message.setContent(multipart)
        } else {
            if (isHtml) message.setContent(body, "text/html; charset=UTF-8")
            else message.setText(body, "UTF-8")
        }

        Transport.send(message)

        val endedAt = Instant.now()
        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = ps.stepId,
                data = mapOf(
                    "action" to JsonPrimitive("send"),
                    "to" to JsonPrimitive(to),
                    "subject" to JsonPrimitive(subject),
                    "status" to JsonPrimitive("sent")
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt)
        )
    }

    private fun executeReadInbox(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val configError = mailConfig.validateImap()
        if (configError != null) {
            return contractViolation(ps.stepId, "IMAP_NOT_CONFIGURED", configError)
        }

        val folderName = ps.params["folder"] ?: "INBOX"
        val limit = ps.params["limit"]?.toIntOrNull() ?: 10
        val unreadOnly = ps.params["unread_only"]?.toBoolean() ?: false

        val store = connectImap()
        try {
            val folder = store.getFolder(folderName).apply { open(Folder.READ_ONLY) }
            try {
                val messages = if (unreadOnly) {
                    folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
                } else {
                    folder.messages
                }

                val result = messages.takeLast(limit).map { msg ->
                    "${msg.messageNumber}|${msg.from?.firstOrNull()}|${msg.subject}|${msg.sentDate}"
                }

                val endedAt = Instant.now()
                return ExecutionResult.success(
                    output = StepOutput.json(
                        stepId = ps.stepId,
                        data = mapOf(
                            "action" to JsonPrimitive("read_inbox"),
                            "folder" to JsonPrimitive(folderName),
                            "count" to JsonPrimitive(result.size),
                            "messages" to JsonPrimitive(result.joinToString("\n"))
                        ),
                        durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
                    ),
                    meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt)
                )
            } finally {
                folder.close(false)
            }
        } finally {
            store.close()
        }
    }

    private fun executeSearch(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val configError = mailConfig.validateImap()
        if (configError != null) {
            return contractViolation(ps.stepId, "IMAP_NOT_CONFIGURED", configError)
        }

        val query = ps.params["query"]
            ?: return contractViolation(ps.stepId, "MISSING_QUERY", "search action requires 'query' param")
        val folderName = ps.params["folder"] ?: "INBOX"
        val limit = ps.params["limit"]?.toIntOrNull() ?: 10

        val store = connectImap()
        try {
            val folder = store.getFolder(folderName).apply { open(Folder.READ_ONLY) }
            try {
                val messages = folder.search(SubjectTerm(query))
                val result = messages.takeLast(limit).map { msg ->
                    "${msg.messageNumber}|${msg.from?.firstOrNull()}|${msg.subject}|${msg.sentDate}"
                }

                val endedAt = Instant.now()
                return ExecutionResult.success(
                    output = StepOutput.json(
                        stepId = ps.stepId,
                        data = mapOf(
                            "action" to JsonPrimitive("search"),
                            "query" to JsonPrimitive(query),
                            "count" to JsonPrimitive(result.size),
                            "messages" to JsonPrimitive(result.joinToString("\n"))
                        ),
                        durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
                    ),
                    meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt)
                )
            } finally {
                folder.close(false)
            }
        } finally {
            store.close()
        }
    }

    private fun executeDelete(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val configError = mailConfig.validateImap()
        if (configError != null) {
            return contractViolation(ps.stepId, "IMAP_NOT_CONFIGURED", configError)
        }

        val messageId = ps.params["message_id"]?.toIntOrNull()
            ?: return contractViolation(ps.stepId, "MISSING_MESSAGE_ID", "delete action requires 'message_id' param (message number)")
        val folderName = ps.params["folder"] ?: "INBOX"

        val store = connectImap()
        try {
            val folder = store.getFolder(folderName).apply { open(Folder.READ_WRITE) }
            try {
                val msg = folder.getMessage(messageId)
                msg.setFlag(Flags.Flag.DELETED, true)
                folder.expunge()

                val endedAt = Instant.now()
                return ExecutionResult.success(
                    output = StepOutput.json(
                        stepId = ps.stepId,
                        data = mapOf(
                            "action" to JsonPrimitive("delete"),
                            "message_id" to JsonPrimitive(messageId),
                            "status" to JsonPrimitive("deleted")
                        ),
                        durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
                    ),
                    meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt)
                )
            } finally {
                folder.close(true)
            }
        } finally {
            store.close()
        }
    }

    private fun connectImap(): Store {
        val props = Properties().apply {
            put("mail.imap.host", mailConfig.imapHost)
            put("mail.imap.port", mailConfig.imapPort.toString())
            put("mail.imap.ssl.enable", "true")
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imap")
        store.connect(mailConfig.imapHost, mailConfig.imapUser, mailConfig.imapPassword)
        return store
    }

    private fun contractViolation(stepId: String, code: String, message: String): ExecutionResult =
        ExecutionResult.contractViolation(stepId = stepId, code = code, message = message)

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean = false
}

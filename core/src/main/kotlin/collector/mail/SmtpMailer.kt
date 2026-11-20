package collector.mail

import collector.port.SecretProvider
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import jakarta.mail.URLName
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.eclipse.angus.mail.smtp.SMTPTransport
import java.util.Properties
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.Logger

internal data class MailMessage(val subject: String, val html: String, val text: String)

internal fun interface Mailer {
    fun send(message: MailMessage)
}

internal class SmtpMailer private constructor(
    private val host: String,
    private val port: String,
    private val username: String,
    private val password: String,
    private val from: String,
    private val recipients: List<String>,
    private val readTimeoutMillis: Int
) : Mailer {
    val recipientCount: Int get() = recipients.size

    override fun send(message: MailMessage) {
        try {
            require(port.toIntOrNull() in 1..65535)
            restrictLibraryLogs()
            val session = Session.getInstance(Properties().apply {
                setProperty("mail.debug", "false")
                setProperty("mail.smtp.host", host)
                setProperty("mail.smtp.port", port)
                setProperty("mail.smtp.auth", "true")
                setProperty("mail.smtp.starttls.enable", "true")
                setProperty("mail.smtp.starttls.required", "true")
                setProperty("mail.smtp.ssl.checkserveridentity", "true")
                setProperty("mail.smtp.connectiontimeout", "30000")
                setProperty("mail.smtp.timeout", readTimeoutMillis.toString())
                setProperty("mail.smtp.writetimeout", "30000")
            })
            val mime = MimeMessage(session)
            mime.setFrom(InternetAddress(from, true))
            mime.setRecipients(Message.RecipientType.TO, recipients.map { InternetAddress(it, true) }.toTypedArray())
            mime.setSubject(message.subject, "UTF-8")
            mime.setContent(MimeMultipart("alternative").apply {
                addBodyPart(MimeBodyPart().apply { setText(message.text, "UTF-8") })
                addBodyPart(MimeBodyPart().apply { setText(message.html, "UTF-8", "html") })
            })
            mime.saveChanges()
            val transport = MailTransport(session)
            try {
                transport.connect(username, password)
                try {
                    transport.sendMessage(mime, mime.allRecipients)
                } catch (e: Exception) {
                    val failedBeforeData = !transport.dataCommandAttempted &&
                        generateSequence<Throwable>(e) { it.cause }
                            .filterIsInstance<SendFailedException>()
                            .any { it.validSentAddresses.isNullOrEmpty() }
                    val rejected = failedBeforeData ||
                        transport.lastReturnCode in 400..599 || transport.rejectedBeforeFailedReset
                    throw MailSendException(uncertain = !rejected)
                }
            } finally {
                try {
                    transport.close()
                } catch (_: Exception) {
                    Logger.getLogger(SmtpMailer::class.java.name).warning("SMTP 연결 종료 실패")
                }
            }
        } catch (e: MailSendException) {
            throw e
        } catch (_: Exception) {
            // SMTP replies may echo credentials and both configured and unknown addresses.
            throw MailSendException()
        }
    }

    private class MailTransport(session: Session) : SMTPTransport(session, URLName("smtp", null, -1, null, null, null)) {
        var dataCommandAttempted = false
            private set
        var rejectedBeforeFailedReset = false
            private set

        override fun sendCommand(command: String) {
            // Record DATA before writing, since a write failure can leave delivery uncertain.
            if (command == "DATA") dataCommandAttempted = true
            super.sendCommand(command)
        }

        override fun issueCommand(command: String, expectedCode: Int) {
            val rejected = command == "RSET" && lastReturnCode in 400..599
            try {
                super.issueCommand(command, expectedCode)
            } catch (e: MessagingException) {
                // Angus clears the last reply code on a read timeout before it can restore the rejection.
                rejectedBeforeFailedReset = rejected
                throw e
            }
        }
    }

    companion object {
        private val libraryLoggers = listOf("jakarta.mail", "org.eclipse.angus.mail").map(Logger::getLogger)

        private fun restrictLibraryLogs() {
            // 메일 발송 때마다 JVM 전역 JUL 설정을 초기화한다.
            // 앱 자체 출력은 JUL에 의존하지 않으며, 나중에 JUL을 쓰면 이 초기화를 재검토한다.
            val manager = LogManager.getLogManager()
            manager.reset()
            manager.getLogger("").addHandler(ConsoleHandler().apply {
                level = Level.WARNING
                filter = java.util.logging.Filter {
                    val name = it.loggerName.orEmpty()
                    !name.startsWith("jakarta.mail") && !name.startsWith("org.eclipse.angus")
                }
            })
            for (root in libraryLoggers) {
                // Library records can contain unredacted addresses or server replies at any level.
                val children = manager.loggerNames.toList()
                    .filter { it.startsWith("${root.name}.") }.mapNotNull(manager::getLogger)
                for (logger in listOf(root) + children) {
                    logger.level = Level.WARNING
                    logger.useParentHandlers = false
                    logger.filter = java.util.logging.Filter { false }
                }
            }
        }
        fun from(secrets: SecretProvider, readTimeoutMillis: Int = 30_000): SmtpMailer? {
            fun setting(name: String) = secrets.get(name)?.takeIf { it.isNotBlank() }
            val username = setting("SMTP_USERNAME") ?: return null
            val password = setting("SMTP_PASSWORD") ?: return null
            val recipients = setting("MAIL_TO")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
                ?.takeIf(List<String>::isNotEmpty) ?: return null
            return SmtpMailer(setting("SMTP_HOST") ?: "smtp.gmail.com", setting("SMTP_PORT") ?: "587",
                username, password, setting("MAIL_FROM") ?: username, recipients, readTimeoutMillis)
        }
    }
}

internal class MailSendException(val uncertain: Boolean = false) : Exception("SMTP 발송 실패")

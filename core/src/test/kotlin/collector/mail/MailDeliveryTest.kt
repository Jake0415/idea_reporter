package collector.mail

import collector.port.SecretProvider
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class MailDeliveryTest {
    @TempDir lateinit var out: File

    @Test fun `missing settings skip delivery`() {
        val messages = mutableListOf<String>()
        val mail = MailDelivery(out, SmtpMailer.from(SecretProvider { null }), print = messages::add)
        assertEquals(MailSendResult.FAILED, mail.send(MailKind.DAILY, "2026-11-20"))
        assertEquals(listOf("메일 설정 없음"), messages)
        assertFalse(out.resolve("mail/sent.json").exists())
    }

    @Test fun `daily report uses stored subject and is sent once unless forced`() {
        val sent = mutableListOf<MailMessage>()
        val messages = mutableListOf<String>()
        val mail = MailDelivery(out, Mailer { sent += it }, 2, print = messages::add)
        report("2026-11-20")
        assertEquals(MailSendResult.SENT, mail.send(MailKind.DAILY, "2026-11-20"))
        assertTrue(messages.single().endsWith("받는 사람 2명"))
        assertEquals(MailSendResult.SKIPPED_DUPLICATE, mail.send(MailKind.DAILY, "2026-11-20"))
        assertEquals(MailSendResult.SENT, mail.send(MailKind.DAILY, "2026-11-20", resend = true))
        assertEquals(List(2) { MailMessage("렌더러 제목", "<h1>HTML</h1>", "렌더러 제목\n본문\n") }, sent)
        assertTrue(out.resolve("mail/sent.json").readText().contains("\"recipientCount\": 2"))
    }

    @Test fun `failures are sanitized and only recent unsent reports are retried`() {
        val messages = mutableListOf<String>()
        val transport = Mailer { error("SMTP replied to hidden@example.invalid with fake-password") }
        for ((day, now) in listOf("2026-11-16" to clock(16), "2026-11-17" to clock(17))) {
            report(day)
            assertEquals(MailSendResult.FAILED, MailDelivery(out, transport, 1, now, messages::add).send(MailKind.DAILY, day))
        }
        val delivered = mutableListOf<MailMessage>()
        val next = MailDelivery(out, Mailer { delivered += it }, 1, clock(20), messages::add)
        assertEquals(setOf(MailKind.DAILY to "2026-11-17"), next.retry())
        assertTrue(next.retry().isEmpty())
        assertEquals(1, delivered.size)
        val records = out.resolve("mail/sent.json").readText() + messages.joinToString()
        assertTrue(records.contains("failed"))
        assertFalse(records.contains("hidden@example.invalid"))
        assertFalse(records.contains("fake-password"))
    }

    @Test fun `unresolved sending becomes unknown and requires resend`() {
        val key = "2026-11-20"
        report(key)
        val journal = out.resolve("mail/sent.json")
        journal.parentFile.mkdirs()
        journal.writeText("""[{"at":"2026-11-20T07:00:00Z","kind":"daily","key":"$key","result":"sending","recipientCount":1}]""")
        var sends = 0
        val messages = mutableListOf<String>()
        val mail = MailDelivery(out, Mailer { sends++ }, clock = clock(20), print = messages::add)
        assertTrue(mail.retry().isEmpty())
        assertEquals(MailSendResult.SKIPPED_DUPLICATE, mail.send(MailKind.DAILY, key))
        assertEquals(0, sends)
        assertTrue(journal.readText().contains("unknown"))
        assertEquals(MailSendResult.SENT, mail.send(MailKind.DAILY, key, resend = true))
        assertEquals(MailSendResult.SKIPPED_DUPLICATE, mail.send(MailKind.DAILY, key))
        assertTrue(messages.last().contains("이미 발송한 메일"))
        assertEquals(1, sends)
    }

    @Test fun `missing report terminates retry`() {
        val day = "2026-11-20"
        report(day)
        val mail = MailDelivery(out, Mailer { error("SMTP failure") }, clock = clock(20))
        assertEquals(MailSendResult.FAILED, mail.send(MailKind.DAILY, day))
        out.resolve("$day.html").delete()
        assertEquals(setOf(MailKind.DAILY to day), mail.retry())
        assertTrue(mail.retry().isEmpty())
        assertTrue(out.resolve("mail/sent.json").readText().contains("보고서 없음"))
    }

    @Test fun `failed sending save prevents SMTP delivery`() {
        val key = "2026-11-20"
        report(key)
        out.resolve("mail").writeText("blocks journal directory")
        var sent = 0
        val mail = MailDelivery(out, Mailer { sent++ }, clock = clock(20))
        assertEquals(MailSendResult.RECORD_FAILED, mail.send(MailKind.DAILY, key))
        assertEquals(0, sent)
    }

    private fun report(day: String) {
        out.resolve("$day.txt").writeText("렌더러 제목\n본문\n")
        out.resolve("$day.html").writeText("<h1>HTML</h1>")
    }

    private fun clock(day: Int) = Clock.fixed(Instant.parse("2026-11-${day}T07:00:00Z"), ZoneOffset.UTC)
}

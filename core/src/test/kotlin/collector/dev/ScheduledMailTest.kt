package collector.dev

import collector.mail.Mailer
import collector.port.SecretProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduledMailTest {
    @TempDir lateinit var out: File
    private val secrets = SecretProvider { null }
    private val zone = ZoneId.of("Asia/Seoul")

    @Test fun `analysis mail failure leaves analysis issues empty so the result stays reusable`() {
        val id = "20261120-120433420-d4475d94"
        val dir = out.resolve("analysis").apply { mkdirs() }
        val json = """{"id":"$id","issues":[]}"""
        dir.resolve("$id.json").writeText(json)
        dir.resolve("$id.html").writeText("<p>본문</p>")
        dir.resolve("$id.txt").writeText("사업성 분석 · 테스트\n본문")
        // Port 1 on loopback refuses the connection, so the SMTP send fails before any data is sent.
        val smtp = SecretProvider { key ->
            mapOf("SMTP_HOST" to "127.0.0.1", "SMTP_PORT" to "1", "SMTP_USERNAME" to "u@example.invalid",
                "SMTP_PASSWORD" to "p", "MAIL_TO" to "r@example.invalid")[key]
        }
        val result = mailDelivery(out, smtp).send(collector.mail.MailKind.ANALYSIS, id)
        assertEquals(collector.mail.MailSendResult.FAILED, result)
        assertEquals(json, dir.resolve("$id.json").readText())
    }

    @Test fun `weekday sends daily once and prunes old dated logs`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-11-19T22:00:00Z"), zone)
        val sent = mutableListOf<String>()
        val old = out.resolve("logs/2026-10-21.log").apply { writeAtomically("old") }
        val retained = out.resolve("logs/2026-10-22.log").apply { writeAtomically("recent") }
        repeat(2) {
            val status = runScheduled(out, secrets, clock, daily = { _, _ -> report("", "2026-11-20", "일일"); true },
                trend = { error("평일 트렌드 실행") }, mailer = Mailer { sent += it.subject })
            assertEquals(0, status)
        }
        assertEquals(listOf("일일"), sent)
        assertTrue(retained.exists())
        assertFalse(old.exists())
        assertFalse(out.resolve("logs/2026-11-20.log").readText().contains("트렌드 실패"))
        assertFalse(out.resolve("logs/2026-11-20.log").readText().contains("daily 메일 처리 실패"))
        assertFalse(out.resolve("runs/scheduled").exists())
    }

    @Test fun `individual retry failure makes the stage fail without changing the daily exit code`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-11-19T22:00:00Z"), zone)
        val key = "2026-11-20"
        report("", key, "일일")
        val failure = Mailer { error("SMTP failure") }
        assertEquals(0, runScheduled(out, secrets, clock, daily = { _, _ -> true }, mailer = failure))
        val logFile = out.resolve("logs/$key.log")
        val initialLog = logFile.readText()
        assertEquals(0, runScheduled(out, secrets, clock, daily = { _, _ -> true }, mailer = failure))
        val retryLog = logFile.readText().removePrefix(initialLog)
        assertContains(retryLog, "메일 재시도 실패")
        assertFalse(retryLog.contains("메일 재시도 완료"))
    }

    @Test fun `missing retry report has a distinct stage log`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-11-19T22:00:00Z"), zone)
        val key = "2026-11-20"
        report("", key, "일일")
        val failure = Mailer { error("SMTP failure") }
        assertEquals(0, runScheduled(out, secrets, clock, daily = { _, _ -> true }, mailer = failure))
        assertTrue(out.resolve("$key.txt").delete())
        val logFile = out.resolve("logs/$key.log")
        val initialLog = logFile.readText()
        assertEquals(0, runScheduled(out, secrets, clock, daily = { _, _ -> false }, mailer = failure))
        val retryLog = logFile.readText().removePrefix(initialLog)
        assertContains(retryLog, "메일 재시도 보고서 없음")
        assertFalse(retryLog.contains("메일 재시도 완료"))
    }

    @Test fun `Sunday attempts trend and mail after daily failure and returns exit code one`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-11-21T22:00:00Z"), zone)
        val order = mutableListOf<String>()
        val status = runScheduled(out, secrets, clock, daily = { _, _ ->
            report("", "2026-11-22", "기존 일일")
            order += "일일"
            error("hidden@example.invalid secret-password")
        }, trend = {
            assertEquals("2026-11-22", it.toString())
            order += "트렌드"
            report("trend", it.toString(), "트렌드 제목")
        }, mailer = Mailer { order += it.subject })
        assertEquals(1, status)
        assertEquals(listOf("일일", "트렌드", "트렌드 제목"), order)
        val log = out.resolve("logs/2026-11-22.log").readText()
        assertContains(log, "일일 파이프라인 실패")
        assertContains(log, "예약 실행 종료 · 1")
        val issues = out.resolve("runs/scheduled").listFiles()!!.single().readText()
        assertContains(issues, "\"issues\"")
        assertFalse((log + issues).contains("hidden@example.invalid"))
        assertFalse((log + issues).contains("secret-password"))
    }

    @Test fun `Sunday mail and trend failures leave successful daily exit code unchanged`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-11-21T22:00:00Z"), zone)
        val status = runScheduled(out, secrets, clock,
            daily = { _, _ ->
                report("", "2026-11-22", "일일")
                out.resolve("2026-11-22.json").writeText("""{"report":{"issues":[]}}""")
                true
            },
            trend = { error("trend unavailable") },
            mailer = Mailer { error("recipient@example.invalid") })
        assertEquals(0, status)
        val issues = out.resolve("runs/scheduled").listFiles()!!.single().readText()
        assertContains(issues, "트렌드 실패")
        assertContains(issues, "SMTP 발송 실패")
        assertContains(out.resolve("2026-11-22.json").readText(), "SMTP 발송 실패")
        assertFalse(issues.contains("recipient@example.invalid"))
        assertContains(out.resolve("logs/2026-11-22.log").readText(), "daily 메일 처리 실패")
    }

    @Test fun `stale daily and trend reports are skipped when not published this run`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-11-21T22:00:00Z"), zone)
        report("", "2026-11-22", "기존 일일")
        report("trend", "2026-11-22", "기존 트렌드")
        val sent = mutableListOf<String>()
        val status = runScheduled(out, secrets, clock, daily = { _, _ -> false },
            trend = { error("trend failure") }, mailer = Mailer { sent += it.subject })
        assertEquals(0, status)
        assertTrue(sent.isEmpty())
    }

    @Test fun `accepted message whose journal update fails gives exit code one and is not resent`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-11-19T22:00:00Z"), zone)
        val journal = out.resolve("mail/sent.json")
        val pending = out.resolve("mail/pending.json")
        val day = "2026-11-20"
        val status = runScheduled(out, secrets, clock, daily = { _, _ ->
            report("", day, "일일")
            true
        }, mailer = Mailer {
            assertContains(journal.readText(), "sending")
            java.nio.file.Files.move(journal.toPath(), pending.toPath())
            assertTrue(journal.mkdir())
            journal.resolve("blocked").writeText("blocks journal replacement")
        })
        assertEquals(1, status)
        val log = out.resolve("logs/$day.log").readText()
        assertContains(log, "발송은 됐으나 기록 실패")
        assertContains(log, "daily 메일 처리 실패")
        assertTrue(journal.resolve("blocked").delete())
        assertTrue(journal.delete())
        java.nio.file.Files.move(pending.toPath(), journal.toPath())
        var sent = 0
        assertEquals(0, runScheduled(out, secrets, clock, daily = { _, _ -> true }, mailer = Mailer { sent++ }))
        assertEquals(0, sent)
        assertContains(journal.readText(), "unknown")
    }

    @Test fun `scheduled CLI exits with code one on lock conflict`() = runTest {
        val working = out.resolve("cli").apply { mkdirs() }
        val target = working.resolve("out").apply { mkdirs() }
        val classpath = checkNotNull(System.getProperty("collector.cli.classpath"))
        withRunLock(target) {
            val command = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
                "-cp", classpath, "collector.dev.MainKt", "--scheduled")
                .directory(working).redirectErrorStream(true)
            command.environment().keys.removeAll { it.startsWith("SMTP_") || it.startsWith("MAIL_") || it.endsWith("_API_KEY") }
            val process = command.start()
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS))
                assertEquals(1, process.exitValue())
                assertContains(process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }, "run_locked")
            } finally {
                process.destroyForcibly()
            }
        }
    }

    private fun report(directory: String, key: String, title: String) {
        out.resolve(directory).resolve("$key.txt").writeAtomically("$title\n본문")
        out.resolve(directory).resolve("$key.html").writeAtomically("<h1>$title</h1>")
    }
}

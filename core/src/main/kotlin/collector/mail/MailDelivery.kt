package collector.mail

import collector.analysis.AnalysisReports
import collector.dev.outputJson
import collector.dev.writeAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

internal const val UNKNOWN_DELIVERY_NOTICE = "발송 여부 확인 필요 — 받은편지함 확인 후 필요하면 --resend"

enum class MailSendResult {
    SENT, SKIPPED_DUPLICATE, FAILED, UNKNOWN, RECORD_FAILED, MISSING_REPORT
}

enum class MailKind(val directory: String) {
    DAILY(""), TREND("trend"), ANALYSIS("analysis");

    val label: String get() = name.lowercase(Locale.ROOT)

    fun validateKey(key: String) {
        when (this) {
            ANALYSIS -> require(AnalysisReports.validId(key)) { "분석 ID 형식 오류" }
            else -> require(runCatching { LocalDate.parse(key).toString() == key && key.length == 10 }.getOrDefault(false)) {
                "메일 날짜 형식 오류"
            }
        }
    }

    fun file(out: File, key: String, extension: String): File {
        validateKey(key)
        return out.resolve(directory).resolve("$key.$extension")
    }
}

/** All operations on a delivery journal run under Main's existing run lock. */
internal class MailDelivery(
    private val out: File,
    private val mailer: Mailer?,
    private val recipientCount: Int = 0,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val print: (String) -> Unit = ::println,
    private val onIssue: (MailKind?, String?, String) -> Unit = { _, _, _ -> },
    private val publish: ((Attempt?) -> Boolean)? = null,
    private val beforeSend: () -> Unit = {}
) {
    @Serializable
    internal data class Attempt(
        val at: String, val kind: String, val key: String, val result: String, val recipientCount: Int,
        val issue: String? = null, val firstFailureAt: String? = null
    )

    private val journal = out.resolve("mail/sent.json")

    private fun save(history: List<Attempt>) =
        journal.writeAtomically(outputJson.encodeToString(history))

    private fun attempts(): List<Attempt> {
        if (!journal.exists()) return emptyList()
        val history = outputJson.decodeFromString<List<Attempt>>(journal.readText())
        val pending = history.filter { it.result == "sending" }
        if (pending.isEmpty()) return history
        val reason = UNKNOWN_DELIVERY_NOTICE
        val updated = history.map { if (it.result == "sending") it.copy(result = "unknown", issue = reason) else it }
        save(updated)
        pending.forEach { issue(MailKind.entries.first { kind -> kind.label == it.kind }, it.key, reason) }
        return updated
    }

    fun defer(kind: MailKind, key: String, reason: String = "메일 단계 시작 시간 부족") {
        kind.validateKey(key)
        val history = attempts()
        if (history.any { it.kind == kind.label && it.key == key && it.result in setOf("sent", "unknown", "failed") }) return
        save(history + Attempt(clock.instant().toString(), kind.label, key, "failed", recipientCount, reason))
    }

    fun send(kind: MailKind, key: String, resend: Boolean = false): MailSendResult {
        kind.validateKey(key)
        val transport = mailer ?: run {
            unavailable()
            return MailSendResult.FAILED
        }
        val history = try {
            attempts()
        } catch (e: Exception) {
        if (e is CancellationException) throw e
            issue(kind, key, "메일 기록 읽기 또는 복구 실패")
            return MailSendResult.RECORD_FAILED
        }
        val previous = history.filter { it.kind == kind.label && it.key == key }
        if (!resend && previous.takeLastWhile { it.result != "sent" }.any { it.result == "unknown" }) {
            print(UNKNOWN_DELIVERY_NOTICE)
            return MailSendResult.SKIPPED_DUPLICATE
        }
        if (!resend && previous.any { it.result == "sent" }) {
            print("이미 발송한 메일 · ${kind.label} · $key")
            return MailSendResult.SKIPPED_DUPLICATE
        }
        var at = clock.instant()
        while (previous.any { it.at == at.toString() }) at = at.plusNanos(1)
        val attempt = Attempt(at.toString(), kind.label, key, "sending", recipientCount)
        val textFile = kind.file(out, key, "txt")
        val htmlFile = kind.file(out, key, "html")
        if (!textFile.isFile || !htmlFile.isFile) {
            val reason = "메일 보고서 없음"
            return if (saveOutcome(history, attempt.copy(result = "missing", issue = reason), kind)) {
                issue(kind, key, reason)
                if (publishOutcome(kind, key)) MailSendResult.MISSING_REPORT else MailSendResult.RECORD_FAILED
            } else MailSendResult.RECORD_FAILED
        }
        val message = try {
            val body = textFile.readText()
            val subject = body.lineSequence().first().also { require(it.isNotBlank()) }
            MailMessage(subject, htmlFile.readText(), body)
        } catch (e: Exception) {
        if (e is CancellationException) throw e
            val reason = "메일 보고서 읽기 실패"
            issue(kind, key, reason)
            return if (saveOutcome(history, attempt.copy(result = "failed", issue = reason), kind)) {
                if (publishOutcome(kind, key)) MailSendResult.FAILED else MailSendResult.RECORD_FAILED
            } else MailSendResult.RECORD_FAILED
        }
        beforeSend()
        try {
            save(history + attempt)
        } catch (e: Exception) {
        if (e is CancellationException) throw e
            issue(kind, key, "메일 발송 시도 기록 저장 실패")
            return MailSendResult.RECORD_FAILED
        }
        if (!publishJournal(attempt)) {
            val reason = "메일 발송 시도 게시 실패"
            if (saveOutcome(history, attempt.copy(result = "failed", issue = reason), kind)) publishOutcome(kind, key)
            issue(kind, key, reason)
            return MailSendResult.RECORD_FAILED
        }
        val result = try {
            transport.send(message)
            MailSendResult.SENT
        } catch (e: Exception) {
        if (e is CancellationException) throw e
            if (e is MailSendException && e.uncertain) MailSendResult.UNKNOWN else MailSendResult.FAILED
        }
        val failure = when (result) {
            MailSendResult.UNKNOWN -> UNKNOWN_DELIVERY_NOTICE
            MailSendResult.FAILED -> "SMTP 발송 실패"
            else -> null
        }
        if (failure != null) issue(kind, key, failure)
        if (!saveOutcome(history, attempt.copy(result = result.name.lowercase(Locale.ROOT), issue = failure), kind)) {
            return MailSendResult.RECORD_FAILED
        }
        if (!publishOutcome(kind, key)) return MailSendResult.RECORD_FAILED
        if (result == MailSendResult.SENT) print("메일 발송 완료 · ${kind.label} · $key · 받는 사람 ${recipientCount}명")
        return result
    }

    private fun publishJournal(attempt: Attempt?): Boolean = try {
        publish?.invoke(attempt) != false
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        false
    }

    private fun publishOutcome(kind: MailKind, key: String): Boolean {
        if (publishJournal(null)) return true
        issue(kind, key, "메일 발송 결과 게시 실패")
        return false
    }

    private fun saveOutcome(history: List<Attempt>, attempt: Attempt, kind: MailKind): Boolean = try {
        save(history + attempt)
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        val accepted = attempt.result == "sent"
        issue(kind, attempt.key, if (accepted) "발송은 됐으나 기록 실패" else "메일 기록 저장 실패")
        false
    }

    fun retry(eligible: (MailKind, String) -> Boolean = { _, _ -> true },
              onResult: (MailKind?, String?, MailSendResult) -> Unit = { _, _, _ -> }): Set<Pair<MailKind, String>> {
        val targets = try {
            val history = attempts()
            if (mailer == null) return emptySet()
            val today = LocalDate.now(clock)
            history.groupBy { it.kind to it.key }.values.mapNotNull { entries ->
                if (entries.last().result != "failed" || entries.any { it.result == "sent" || it.result == "unknown" }) return@mapNotNull null
                val firstFailure = entries.first { it.result == "failed" }
                val date = Instant.parse(firstFailure.firstFailureAt ?: firstFailure.at).atZone(clock.zone).toLocalDate()
                if (date !in today.minusDays(3)..today) return@mapNotNull null
                val kind = MailKind.entries.first { it.label == firstFailure.kind }
                kind.validateKey(firstFailure.key)
                if (eligible(kind, firstFailure.key)) kind to firstFailure.key else null
            }.toSet()
        } catch (e: Exception) {
        if (e is CancellationException) throw e
            issue(null, null, "메일 재시도 기록 읽기 또는 복구 실패")
            onResult(null, null, MailSendResult.RECORD_FAILED)
            return emptySet()
        }
        for ((kind, key) in targets) {
            onResult(kind, key, send(kind, key))
        }
        return targets
    }

    fun test(): MailSendResult {
        val transport = mailer ?: run { unavailable(); return MailSendResult.FAILED }
        val title = "[AI 아이디어 수집기] 메일 설정 확인"
        try {
            transport.send(MailMessage(title, "<p>$title</p>", title))
            print("테스트 메일 발송 완료")
            return MailSendResult.SENT
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val reason = if (e is MailSendException && e.uncertain) UNKNOWN_DELIVERY_NOTICE else "테스트 메일 발송 실패"
            issue(null, null, reason)
            return if (reason == UNKNOWN_DELIVERY_NOTICE) MailSendResult.UNKNOWN else MailSendResult.FAILED
        }
    }

    private fun unavailable() {
        print("메일 설정 없음")
    }

    private fun issue(kind: MailKind?, key: String?, reason: String) {
        print(reason)
        onIssue(kind, key, reason)
    }
}

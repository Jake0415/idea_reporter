package collector.dev

import collector.analysis.AnalysisReports
import collector.runtime.FileRunLock
import collector.runtime.RunLock
import collector.llm.CLASSIFICATION_BATCH_PENDING
import collector.llm.batchPath
import collector.llm.isPendingBatch
import collector.mail.MailDelivery
import collector.mail.MailKind
import collector.mail.MailSendResult
import collector.mail.Mailer
import collector.mail.SmtpMailer
import collector.mail.UNKNOWN_DELIVERY_NOTICE
import collector.port.SecretProvider
import collector.trend.publishTrend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

internal fun runMailCommand(args: Array<String>, out: File, secrets: SecretProvider,
                            delivery: MailDelivery = mailDelivery(out, secrets),
                            clock: Clock = Clock.systemDefaultZone()) {
    if (args.first() == "--mail-test") {
        require(args.size == 1)
        delivery.test()
        return
    }
    val kind = requireNotNull(MailKind.entries.find { it.label == args.getOrNull(1) })
    var key: String? = null
    var resend = false
    var index = 2
    while (index < args.size) {
        when (args[index++]) {
            "--resend" -> resend = true
            if (kind == MailKind.ANALYSIS) "--id" else "--date" -> {
                require(key == null)
                key = requireNotNull(args.getOrNull(index++))
            }
            else -> throw IllegalArgumentException("지원하지 않는 메일 옵션입니다.")
        }
    }
    val reportKey = key ?: when (kind) {
        MailKind.ANALYSIS -> requireNotNull(AnalysisReports(out.resolve("analysis")).list().firstOrNull()?.id) {
            "저장된 분석이 없습니다."
        }
        else -> LocalDate.now(clock).toString()
    }
    sendReportMail(out, delivery, kind, reportKey, resend)
}

internal fun sendReportMail(out: File, delivery: MailDelivery, kind: MailKind,
                            reportKey: String, resend: Boolean): MailSendResult? {
    if (kind == MailKind.DAILY && isPendingBatch(batchPath(out, reportKey, "classify"))) {
        println(CLASSIFICATION_BATCH_PENDING)
        return null
    }
    return delivery.send(kind, reportKey, resend).also {
        if (it == MailSendResult.RECORD_FAILED) throw MailRecordFailure()
    }
}

internal fun mailDelivery(out: File, secrets: SecretProvider, publish: ((MailDelivery.Attempt?) -> Boolean)? = null,
                          mailer: Mailer? = SmtpMailer.from(secrets), clock: Clock = Clock.systemDefaultZone()): MailDelivery {
    return MailDelivery(out, mailer, (mailer as? SmtpMailer)?.recipientCount ?: 0, clock,
        onIssue = { kind, key, reason -> recordMailIssue(out, kind, key, reason, ::println) }, publish = publish)
}

private fun recordMailIssue(out: File, kind: MailKind?, key: String?, reason: String, print: (String) -> Unit) {
    if (kind == null || key == null) return
    // Analysis reuse requires empty issues; a mail-only failure must not force a paid re-run.
    // The mail outcome is already tracked in out/mail/sent.json.
    if (kind == MailKind.ANALYSIS) return
    try {
        val file = kind.file(out, key, "json")
        val result = readJson<JsonObject>(file) ?: return
        fun JsonObject.withIssue() = JsonObject(this + ("issues" to
            JsonArray((this["issues"] as? JsonArray).orEmpty() + JsonPrimitive(reason))))
        val updated = if (kind == MailKind.DAILY) {
            val report = result["report"] as JsonObject
            JsonObject(result + ("report" to report.withIssue()))
        } else result.withIssue()
        file.writeAtomically(outputJson.encodeToString(updated))
    } catch (_: Exception) {
        print("실행 기록에 메일 실패 사유 저장 실패")
    }
}

internal class ScheduledDailyFailure : Exception()
private class MailStageFailure : Exception()
internal class MailRecordFailure : Exception("메일 기록 처리 실패")

@Serializable
private data class ScheduledPublication(val startedAt: String, val day: String, val kinds: List<String>)

internal fun mailTimeLow(clock: Clock, startedAt: Instant) =
    clock.instant() >= startedAt.plusSeconds((150 - 10) * 60L)

internal fun sendScheduledPublication(out: File, secrets: SecretProvider, clock: Clock, startedAt: Instant,
                                      publish: ((MailDelivery.Attempt?) -> Boolean)? = null) {
    val publication = readJson<ScheduledPublication>(out.resolve("mail/scheduled.json")) ?: return
    require(publication.startedAt == startedAt.toString())
    val smtp = SmtpMailer.from(secrets) ?: return
    val delivery = MailDelivery(out, smtp, smtp.recipientCount, clock, publish = publish)
    val kinds = publication.kinds.map { label -> MailKind.entries.single { it.label == label } }
    if (mailTimeLow(clock, startedAt)) {
        for (kind in kinds) delivery.defer(kind, publication.day)
        return
    }
    var recordFailed = false
    val retried = delivery.retry(eligible = { kind, id ->
        !mailTimeLow(clock, startedAt) && (kind != MailKind.DAILY || !isPendingBatch(batchPath(out, id, "classify")))
    }) { _, _, result -> if (result == MailSendResult.RECORD_FAILED) recordFailed = true }
    for (kind in kinds) {
        if ((kind to publication.day) in retried) continue
        if (mailTimeLow(clock, startedAt)) delivery.defer(kind, publication.day)
        else if (delivery.send(kind, publication.day) == MailSendResult.RECORD_FAILED) recordFailed = true
    }
    if (recordFailed) throw MailRecordFailure()
}

internal suspend fun runScheduled(
    out: File,
    secrets: SecretProvider,
    clock: Clock,
    daily: suspend (Instant, (String) -> Unit) -> Boolean,
    trend: (LocalDate) -> Unit = { publishTrend(out, it) },
    mailer: Mailer? = SmtpMailer.from(secrets),
    startedAt: Instant = clock.instant(),
    deadlineMinutes: Int = collector.config.PipelineConfig.defaults.deadlineMinutes,
    deadlineClock: Clock = clock,
    noMail: Boolean = false,
    runLock: RunLock = FileRunLock,
    onMail: () -> Unit = {},
    propagateLocked: Boolean = false
): Int {
    val day = startedAt.atZone(clock.zone).toLocalDate()
    val key = day.toString()
    val sunday = day.dayOfWeek == DayOfWeek.SUNDAY
    out.mkdirs()
    var exitCode = 1
    try {
        runLock.withLock(out) {
            if (noMail) out.resolve("mail/scheduled.json").delete()
            val context = kotlin.coroutines.coroutineContext
            var mailStarted = false
            val logDir = out.resolve("logs")
            val logFile = logDir.resolve("$key.log")
            val issues = mutableListOf<String>()
            fun log(message: String) {
                try {
                    logDir.mkdirs()
                    logFile.appendText("${clock.instant()} · $message\n")
                } catch (_: Exception) {
                    println(message)
                    println("예약 실행 로그 저장 실패")
                }
            }
            fun issue(message: String) {
                issues += message
                log(message)
            }
            fun checkDeadline(stage: String) {
                if (deadlineClock.instant() >= startedAt.plusSeconds(deadlineMinutes * 60L)) {
                    val message = "$stage exceeded the execution deadline"
                    issue(message)
                    recordMailIssue(out, MailKind.DAILY, key, message, ::log)
                }
            }
            suspend fun stage(
                name: String,
                completion: () -> String = { "완료" },
                run: suspend () -> Unit
            ): Boolean = try {
                run()
                log("$name ${completion()}")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                issue("$name 실패 · ${safeFailure(e)}")
                false
            }

            log("예약 실행 시작 · $key")
            logDir.listFiles().orEmpty().filter { it.isFile && it.extension == "log" }.forEach { file ->
                val date = runCatching { LocalDate.parse(file.nameWithoutExtension) }.getOrNull()
                if (date != null && date <= day.minusDays(30) && !file.delete()) {
                    issue("보관 기간이 지난 로그 삭제 실패")
                }
            }
            val classifyPath = batchPath(out, key, "classify")
            var dailyPublished = false
            val dailyOk = stage("일일 파이프라인") { dailyPublished = daily(startedAt, ::log) }
            val trendPublished = sunday && stage("트렌드") { trend(day) }
            if (sunday) checkDeadline("Trend publication")
            val classificationPending = isPendingBatch(classifyPath)
            if (noMail) {
                val published = buildList {
                    if (dailyPublished && !classificationPending) add(MailKind.DAILY.label)
                    if (trendPublished) add(MailKind.TREND.label)
                }
                out.resolve("mail/scheduled.json").writeAtomically(outputJson.encodeToString(
                    ScheduledPublication(startedAt.toString(), key, published)))
                val journal = MailDelivery(out, null, clock = clock, print = ::log)
                published.forEach { label -> journal.defer(MailKind.entries.single { it.label == label }, key, "메일 발송 대기") }
            }
            val mail = MailDelivery(out, mailer, (mailer as? SmtpMailer)?.recipientCount ?: 0, clock, print = { message ->
                log(message)
                if (message == UNKNOWN_DELIVERY_NOTICE || message == "발송은 됐으나 기록 실패") println(message)
            }, onIssue = { kind, id, reason ->
                issues += reason
                recordMailIssue(out, kind, id, reason, ::log)
            }, beforeSend = {
                if (!mailStarted) {
                    onMail()
                    mailStarted = true
                }
                context.ensureActive()
            })
            var recordFailed = false
            var retried = emptySet<Pair<MailKind, String>>()
            var missingRetryReport = false
            if (!noMail) stage("메일 재시도", completion = { if (missingRetryReport) "보고서 없음" else "완료" }) {
                var retryFailed = false
                retried = mail.retry(eligible = { kind, id ->
                    kind != MailKind.DAILY || (if (id == key) !classificationPending else !isPendingBatch(batchPath(out, id, "classify")))
                }) { kind, id, result ->
                    when (result) {
                        MailSendResult.MISSING_REPORT -> missingRetryReport = true
                        MailSendResult.FAILED, MailSendResult.UNKNOWN, MailSendResult.RECORD_FAILED -> {
                            retryFailed = true
                            if (result == MailSendResult.RECORD_FAILED) recordFailed = true
                            if (kind != null) issue("${kind.label} 메일 재시도 실패 · $id")
                        }
                        MailSendResult.SENT, MailSendResult.SKIPPED_DUPLICATE -> Unit
                    }
                }
                if (retryFailed) throw MailStageFailure()
            }
            if (!noMail) checkDeadline("Mail retry")
            if (classificationPending) log(CLASSIFICATION_BATCH_PENDING)
            if (noMail) Unit
            else if (mailer == null) log("메일 설정 없음")
            else {
                for (kind in listOf(MailKind.DAILY, MailKind.TREND)) {
                    val published = if (kind == MailKind.DAILY) dailyPublished && !classificationPending else trendPublished
                    if (!published || (kind to key) in retried) continue
                    stage("${kind.label} 메일 처리") {
                        when (mail.send(kind, key)) {
                            MailSendResult.SENT, MailSendResult.SKIPPED_DUPLICATE -> Unit
                            MailSendResult.RECORD_FAILED -> {
                                recordFailed = true
                                throw MailStageFailure()
                            }
                            else -> throw MailStageFailure()
                        }
                    }
                    checkDeadline("${kind.label} mail delivery")
                }
            }
            if (issues.isNotEmpty()) {
                stage("예약 실행 기록 저장") {
                    val record = JsonObject(mapOf(
                        "startedAt" to JsonPrimitive(startedAt.toString()),
                        "issues" to JsonArray(issues.map(::JsonPrimitive))
                    ))
                    out.resolve("runs/scheduled/$key-${startedAt.toString().replace(':', '-')}.json")
                        .writeAtomically(outputJson.encodeToString(record))
                }
            }
            exitCode = if (dailyOk && !recordFailed) 0 else 1
            log("예약 실행 종료 · $exitCode")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (propagateLocked && e is RunLockedException) throw e
        println("예약 실행 실패 · ${safeFailure(e)}")
    }
    return exitCode
}

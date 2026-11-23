package collector.dev

import collector.analysis.AnalysisReports
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

internal fun runMailCommand(args: Array<String>, out: File, secrets: SecretProvider) {
    if (args.first() == "--mail-test") {
        require(args.size == 1)
        mailDelivery(out, secrets).test()
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
        else -> LocalDate.now().toString()
    }
    if (kind == MailKind.DAILY && isPendingBatch(batchPath(out, reportKey, "classify"))) {
        println(CLASSIFICATION_BATCH_PENDING)
        return
    }
    val delivery = mailDelivery(out, secrets)
    if (delivery.send(kind, reportKey, resend) == MailSendResult.RECORD_FAILED) throw MailRecordFailure()
}

internal fun mailDelivery(out: File, secrets: SecretProvider): MailDelivery {
    val smtp = SmtpMailer.from(secrets)
    return MailDelivery(out, smtp, smtp?.recipientCount ?: 0,
        onIssue = { kind, key, reason -> recordMailIssue(out, kind, key, reason, ::println) })
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

internal suspend fun runScheduled(
    out: File,
    secrets: SecretProvider,
    clock: Clock,
    daily: suspend (Instant, (String) -> Unit) -> Boolean,
    trend: (LocalDate) -> Unit = { publishTrend(out, it) },
    mailer: Mailer? = SmtpMailer.from(secrets),
    startedAt: Instant = clock.instant(),
    deadlineMinutes: Int = collector.config.PipelineConfig.defaults.deadlineMinutes,
    deadlineClock: Clock = clock
): Int {
    val day = LocalDate.ofInstant(startedAt, clock.zone)
    val key = day.toString()
    val sunday = day.dayOfWeek == DayOfWeek.SUNDAY
    out.mkdirs()
    var exitCode = 1
    try {
        withRunLock(out) {
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
            val mail = MailDelivery(out, mailer, (mailer as? SmtpMailer)?.recipientCount ?: 0, clock, print = { message ->
                log(message)
                if (message == UNKNOWN_DELIVERY_NOTICE || message == "발송은 됐으나 기록 실패") println(message)
            }) { kind, id, reason ->
                issues += reason
                recordMailIssue(out, kind, id, reason, ::log)
            }
            var recordFailed = false
            var retried = emptySet<Pair<MailKind, String>>()
            var missingRetryReport = false
            stage("메일 재시도", completion = { if (missingRetryReport) "보고서 없음" else "완료" }) {
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
            checkDeadline("Mail retry")
            if (classificationPending) log(CLASSIFICATION_BATCH_PENDING)
            if (mailer == null) log("메일 설정 없음")
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
        println("예약 실행 실패 · ${safeFailure(e)}")
    }
    return exitCode
}

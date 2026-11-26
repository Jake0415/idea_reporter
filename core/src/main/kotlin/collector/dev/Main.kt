package collector.dev

import collector.runtime.*
import collector.budget.BudgetSettings
import collector.config.DuplicateRssFeedNameException
import collector.config.SourcesFile
import collector.data.AnalysisMode
import collector.data.DataSync
import collector.data.DataBranch
import collector.data.InboxRequest
import collector.data.InboxResult
import collector.data.files
import collector.data.inboxJson
import collector.data.mergeMailJournals
import collector.data.publicMailJournal
import collector.data.requestIdPattern
import collector.data.resultFile
import collector.data.saveResult
import collector.config.PipelineConfig
import collector.llm.*
import collector.mail.MailDelivery
import collector.mail.MailKind
import collector.mail.MailSendResult
import collector.mail.Mailer
import collector.model.DropReason
import collector.model.Item
import collector.model.RunReport
import collector.pipeline.PipelineResult
import collector.report.HtmlRenderer
import collector.source.SourceAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val logs = java.util.logging.LogManager.getLogManager()
    logs.reset()
    logs.getLogger("").addHandler(java.util.logging.ConsoleHandler().apply {
        level = java.util.logging.Level.WARNING
    })
    val startedAt = System.getenv("COLLECTOR_JOB_STARTED_AT")?.let(Instant::parse) ?: Instant.now()
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8"))
    try {
        runBlocking { runMain(args, processStartedAt = startedAt) }
    } catch (e: Exception) {
        println("실행 실패 · ${safeFailure(e)}")
        when (e) {
            is DuplicateRssFeedNameException -> println("출처 설정 오류 · ${e.message}")
            is IllegalArgumentException -> println("사용법 · --keep-unseen --no-llm --budget <USD> | --trend [--date yyyy-MM-dd] | " +
                "--backfill <4w|Nd> [--dry-run] [--yes] [--force] [--budget <usd>] [--date yyyy-MM-dd] | " +
                "--analyze <테마ID…> [--date yyyy-MM-dd] [--yes] [--force] [--budget <usd>] [--fit <n>] | " +
                "--scheduled | --mail-test | --mail <daily|trend|analysis> [--date yyyy-MM-dd | --id 분석ID] [--resend] | " +
                "--data-pull <dataDir> | --data-push <dataDir> | " +
                "--analysis-detail <분석ID> <번호|기회 ID> | --analysis-list | --budget-status | --set-budget key=value [key=value…]")
            is LlmKeyMissingException -> println(e.message)
            is RunLockedException -> println("다른 실행이 진행 중입니다.")
        }
        exitProcess(1)
    }
}

internal suspend fun runMain(
    args: Array<String>,
    out: File = CollectorPaths.workingDirectory().outDir,
    dailySources: Pair<SourcesFile, List<SourceAdapter>>? = null,
    scheduledClock: Clock = Clock.systemDefaultZone(),
    scheduledMailer: (() -> Mailer?)? = null,
    processStartedAt: Instant? = null,
    dailyProviders: (() -> List<LlmProvider>)? = null
) {
    val workingPaths = CollectorPaths.workingDirectory()
    val paths = CollectorPaths(out, workingPaths.configDir, envFile = workingPaths.envFile)
    val promptsDir = paths.promptsDir
    val secrets by lazy { DotEnv.secretProvider(DotEnv.load(paths.envFile)) }
    val runtime by lazy {
        CollectorRuntime(paths, collector.port.SecretProvider { secrets.get(it) }, scheduledClock, dailySources?.second, dailyProviders, scheduledMailer,
            batchCheckpointHook = ::publishBatchCheckpoint)
    }
    val deadlineClock = if (processStartedAt == null) scheduledClock else Clock.systemUTC()
    when (args.firstOrNull()) {
        "--data-merge-mail" -> {
            require(args.size == 4)
            File(args[3]).writeAtomically(publicMailJournal(mergeMailJournals(*args.slice(1..2).map {
                File(it).takeIf(File::isFile)?.readText() ?: "[]"
            }.toTypedArray(), publishing = true)))
            return
        }
        "--data-pull", "--data-inbox", "--data-push", "--data-manifest", "--data-import" -> {
            val push = args[0] == "--data-push"
            val mailOnly = push && "--mail-only" in args.drop(2)
            val stateOnly = push && "--state-only" in args.drop(2)
            val underRunLock = (push || args[0] == "--data-manifest") && "--under-run-lock" in args.drop(2)
            require(args.size >= 2 && args.drop(2).size == listOf(mailOnly, stateOnly, underRunLock).count { it })
            require(!mailOnly || !stateOnly)
            val branch = File(args[1])
            require(branch.isDirectory)
            val sync = DataSync(out, promptsDir, pipelineConfig = paths.pipelineConfig)
            out.mkdirs()
            val update: suspend () -> Unit = {
                when (args[0]) {
                    "--data-push" -> when {
                        mailOnly -> sync.publishMail(branch)
                        stateOnly -> sync.publishState(branch)
                        else -> sync.push(branch)
                    }
                    "--data-inbox" -> sync.processInbox(branch)
                    "--data-manifest" -> sync.manifest(branch)
                    else -> sync.pull(branch, processInbox = false, importOnly = args[0] == "--data-import")
                }
            }
            if (underRunLock) update() else withRunLock(out, update)
            return
        }
        "--set-budget" -> {
            runtime.setBudget(args.drop(1)).forEach { (key, value) -> println("$key=$value") }
            return
        }
        "--budget-status" -> {
            require(args.size == 1)
            runtime.budgetStatus().forEach(::println)
            return
        }
    }
    if (args.firstOrNull() == "--trend") {
        val date = when {
            args.size == 1 -> LocalDate.now()
            args.size == 3 && args[1] == "--date" -> requireNotNull(
                runCatching { LocalDate.parse(args[2]) }.getOrNull()?.takeIf {
                    args[2].length == 10 && it.toString() == args[2]
                }
            )
            else -> throw IllegalArgumentException("지원하지 않는 트렌드 옵션입니다.")
        }
        println(runtime.publishTrend(date).subject)
        return
    }
    when (args.firstOrNull()) {
        "--mail", "--mail-test" -> {
            runtime.runMailCommand(args)
            return
        }
        "--mail-scheduled" -> {
            val publish = args.contentEquals(arrayOf("--mail-scheduled", "--publish"))
            require(args.size == 1 || publish)
            out.mkdirs()
            withRunLock(out) {
                sendScheduledPublication(out, secrets, scheduledClock, processStartedAt ?: scheduledClock.instant(),
                    if (publish) workflowMailPublisher(out, promptsDir) else null)
            }
            return
        }
        "--data-analysis-mail" -> {
            require(args.size == 1)
            val requestId = workflowRequestId()
            withRunLock(out) {
                val result = readJson<InboxResult>(resultFile(out, requestId))
                val reportId = result?.takeIf { it.ok }?.mailReportId ?: return@withRunLock
                if (mailTimeLow(scheduledClock, processStartedAt ?: scheduledClock.instant())) {
                    MailDelivery(out, null, clock = scheduledClock).defer(MailKind.ANALYSIS, reportId)
                    return@withRunLock
                }
                val delivery = mailDelivery(out, secrets, workflowMailPublisher(out, promptsDir))
                if (delivery.send(MailKind.ANALYSIS, reportId) == MailSendResult.RECORD_FAILED) throw MailRecordFailure()
            }
            return
        }
        "--scheduled" -> {
            val noMail = args.contentEquals(arrayOf("--scheduled", "--no-mail"))
            require(args.size == 1 || noMail)
            val status = try {
                runtime.runScheduled(
                    ScheduledOptions(mail = !noMail, deadline = cliDeadline(), deferMailDelivery = noMail),
                    dailySources?.first, processStartedAt ?: scheduledClock.instant(), deadlineClock
                ).exitCode
            } catch (e: RunLockedException) {
                println("예약 실행 실패 · ${safeFailure(e)}")
                1
            }
            if (status != 0) throw ScheduledDailyFailure()
            return
        }
    }
    if (args.contentEquals(arrayOf("--llm-smoke"))) {
        if (!llmSmoke(secrets)) exitProcess(1)
        return
    }
    if (args.firstOrNull() in setOf("--analyze", "--analysis-detail", "--analysis-list", "--data-analyze", "--data-analysis-queue")) {
        val dispatch = args.first() == "--data-analyze"
        val dailyQueue = args.first() == "--data-analysis-queue"
        val output = mutableListOf<String>()
        var mailReportId: String? = null
        var beforePaidRun: () -> Unit = {}
        val command = runtime.analysisCommand(
            print = if (dispatch || dailyQueue) { line -> output.add(line) } else ::println,
            sendMail = if (dispatch) { id -> mailReportId = id } else null,
            beforePaidRun = { beforePaidRun() }
        )
        if (dispatch || dailyQueue) {
            require(args.size == 1)
            out.mkdirs()
            withRunLock(out) {
                val queue = out.resolve(DataBranch.ANALYSIS_QUEUE)
                val id = if (dispatch) workflowRequestId() else null
                val runId = if (dispatch) requireNotNull(System.getenv("GITHUB_RUN_ID")).also {
                    require(it.matches(Regex("[0-9]+")))
                } else null
                val now = scheduledClock.instant()
                val queued = if (id == null) {
                    val stale = files(out.resolve(DataBranch.RESULTS)).filter { file ->
                        if (!requestIdPattern.matches(file.nameWithoutExtension)) return@filter false
                        val result = readJson<InboxResult>(file)
                        result?.status == "running" && result.processedAt?.let {
                            runCatching { Instant.parse(it) <= now.minusSeconds(6 * 3600L) }.getOrDefault(false)
                        } == true
                    }.map { queue.resolve(it.name) }
                    (files(queue).filter { it.parentFile == queue } + stale).distinct()
                } else listOf(queue.resolve("$id.json"))
                for (file in queued) {
                    val key = file.nameWithoutExtension
                    if (!requestIdPattern.matches(key)) continue
                    val previous = readJson<InboxResult>(resultFile(out, key))
                    if (previous?.status == "running") {
                        saveResult(out, previous.copy(ok = false, status = null, code = "internal_error",
                            reason = "확인 필요 — 새 requestId로 다시 요청", processedAt = now.toString()))
                        if (file.isFile) check(file.delete())
                        if (dispatch) error("확인 필요 — 새 requestId로 다시 요청")
                        continue
                    }
                    if (previous?.dispatchRunId != null || previous?.code in setOf("analysis_complete", "expired")) {
                        if (runId != null && previous != null && previous.dispatchRunId == null) {
                            saveResult(out, previous.copy(dispatchRunId = runId))
                        }
                        if (file.isFile) check(file.delete())
                        if (dispatch) check(previous?.ok == true)
                        continue
                    }
                    if (!file.isFile) {
                        if (previous != null) check(previous.ok) else error("Analysis request not found")
                        continue
                    }
                    output.clear()
                    mailReportId = null
                    beforePaidRun = {
                        check(dispatch)
                        publishAnalysisStart(out, key, runId, now) { workflowPublish("publish", "analysis-start") }
                    }
                    val result = try {
                        val request = inboxJson.decodeFromString<InboxRequest>(file.readText()) as InboxRequest.Analysis
                        require(request.requestId == key && request.schemaVersion == DataBranch.VERSION)
                        collector.data.validateAnalysis(request.themeIds, request.maxUsd)
                        when {
                            Instant.parse(request.createdAt) <= now.minusSeconds(7 * 86_400L) ->
                                InboxResult(requestId = key, ok = false, reason = "Analysis request expired", code = "expired")
                            dailyQueue && request.mode == AnalysisMode.RUN ->
                                InboxResult(requestId = key, ok = false, reason = "분석 실행 대기 — 앱에서 다시 요청", code = "analysis_pending")
                            else -> {
                                val budget = minOf(request.maxUsd, BudgetSettings.load(out) {}.analysisRequestUsd)
                                val option = if (request.mode == AnalysisMode.RUN) "--yes" else "--force"
                                val ok = command.run((listOf("--analyze") + request.themeIds +
                                    listOf("--budget", budget.toString(), option)).toTypedArray(), acquireLock = false)
                                if (ok) mailReportId?.let {
                                    MailDelivery(out, null, clock = scheduledClock).defer(MailKind.ANALYSIS, it, "메일 발송 대기")
                                }
                                InboxResult(requestId = key, ok = ok, reason = if (ok) null else "Analysis did not run successfully",
                                    output = output.toList(), code = "analysis_complete", mailReportId = mailReportId)
                            }
                        }
                    } catch (e: Exception) {
                        // A cancelled run must not be recorded as a failed request (the queue entry stays for the next run).
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        val interrupted = readJson<InboxResult>(resultFile(out, key))?.status == "running"
                        InboxResult(requestId = key, ok = false,
                            reason = if (interrupted) "확인 필요 — 새 requestId로 다시 요청" else "Analysis could not complete",
                            code = "internal_error")
                    }
                    if (result.code == "analysis_pending" && previous?.code == result.code) continue
                    saveResult(out, result.copy(dispatchRunId = runId, processedAt = now.toString()))
                    if (result.code != "analysis_pending") check(file.delete())
                    if (dispatch) check(result.ok)
                }
            }
            return
        }
        try {
            command.run(args)
        } catch (e: IllegalArgumentException) {
            e.message?.let { println("분석 입력 오류 · $it") }
            throw e
        }
        return
    }
    if (args.firstOrNull() == "--backfill") {
        runtime.runBackfill(args, processStartedAt ?: Instant.now())
        return
    }
    runtime.runDaily(args, DailyOptions(deadline = cliDeadline(), sources = dailySources?.first),
        processStartedAt ?: Instant.now(), Clock.systemUTC())
}

private fun workflowRequestId() = requireNotNull(System.getenv("REQUEST_ID")).also {
    require(requestIdPattern.matches(it))
}

internal fun publishAnalysisStart(out: File, id: String, runId: String?, now: Instant, publish: () -> Boolean) {
    saveResult(out, InboxResult(requestId = id, ok = false, status = "running",
        dispatchRunId = runId, processedAt = now.toString()))
    check(publish()) { "분석 시작 게시 실패" }
}

private fun workflowPublish(mode: String, label: String): Boolean =
    runPublicationCommand(Duration.ofMinutes(5), "bash", "scripts/actions-data.sh", mode, label, "--under-run-lock")

internal fun publishBatchCheckpoint(): Boolean =
    System.getenv("COLLECTOR_STATE_HOOK")?.takeIf { it.isNotBlank() }
        ?.let { runPublicationCommand(Duration.ofMinutes(8), it) } ?: true

internal fun runPublicationCommand(timeout: Duration, vararg command: String): Boolean {
    val child = ProcessBuilder(*command).inheritIO()
    child.environment()["COLLECTOR_PUBLICATION_JAVA"] = File(System.getProperty("java.home"), "bin/java").path
    child.environment()["COLLECTOR_PUBLICATION_CLASSPATH"] = System.getProperty("java.class.path")
    return try {
        val process = child.start()
        try {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) && process.exitValue() == 0
        } finally {
            if (process.isAlive) {
                try {
                    destroyPublicationDescendants(process)
                } finally {
                    process.destroyForcibly()
                }
            }
        }
    } catch (_: java.io.IOException) {
        false
    }
}

private fun destroyPublicationDescendants(process: Process) {
    // The desktop JVM exposes the process tree through APIs absent from Android.
    val descendants = try {
        Process::class.java.getMethod("descendants")
    } catch (_: NoSuchMethodException) {
        return
    }
    val destroy = Class.forName("java.lang.ProcessHandle").getMethod("destroyForcibly")
    (descendants.invoke(process) as java.util.stream.Stream<*>).use { children ->
        children.forEach { destroy.invoke(it) }
    }
}

private fun workflowMailPublisher(out: File, prompts: File): (MailDelivery.Attempt?) -> Boolean = { attempt ->
    val published = workflowPublish("publish-mail", "mail")
    if (!published && attempt == null) {
        runCatching { DataSync(out, prompts, pipelineConfig = CollectorPaths.workingDirectory().pipelineConfig).mailArtifact(out.resolve("mail/public-journal.json")) }
    }
    published && (attempt == null ||
        readJson<List<MailDelivery.Attempt>>(File("../data/out/mail/sent.json")).orEmpty().any {
            it.copy(firstFailureAt = null) == attempt
        })
}

private fun cliDeadline(): Duration? =
    if (System.getenv("COLLECTOR_JOB_STARTED_AT") != null) Duration.ofMinutes(150 - 20) else null

internal fun requestedCostLedger(out: File) = FileCostLedger(out.resolve("cost-ledger-requested"))

internal fun costLedger(out: File, history: FileHistorySearch) = FileCostLedger(out.resolve("cost-ledger")) { day ->
    val prior = history.runs(day.plusDays(1), 1).firstOrNull()
    history.archivedCost(day, day, reserved = true, skipStartedAt = prior?.report?.startedAt) +
        (prior?.report?.llmCalls?.sumOf { maxOf(it.costUsd, it.reservedUsd) } ?: 0.0)
}

internal typealias RunLockedException = collector.runtime.RunLockedException

internal fun safeFailure(e: Exception): String {
    val reason = when (e) {
        is InvalidSourceRoutingException -> "invalid_source_routing"
        is IllegalArgumentException -> "invalid_arguments_or_settings"
        is RunLockedException -> "run_locked"
        is java.io.IOException -> "io_error"
        else -> "run_failed"
    }
    return "${e.javaClass.simpleName} · $reason"
}

internal suspend fun <T> withRunLock(out: File, block: suspend () -> T): T =
    FileChannel.open(out.resolve("run.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } ?: throw RunLockedException()
        lock.use { block() }
    }

internal fun itemsToMarkSeen(result: PipelineResult): List<Item> {
    val failedKeys = result.dropped.filter {
        it.reason == DropReason.CLASSIFY_FAILED || it.reason == DropReason.BUDGET_EXCEEDED
    }.mapTo(hashSetOf()) { it.item.key }
    return result.items.filter { it.key !in result.trackingOnlyKeys && it.key !in failedKeys }
}

internal fun archiveFile(out: File, report: RunReport): File =
    out.resolve("runs/${report.runDate}-${report.startedAt.replace(':', '-')}.json")

internal fun archiveFailedRun(out: File, result: PipelineResult): File =
    out.resolve("runs/failed").resolve(archiveFile(out, result.report).name).apply {
        writeAtomically(outputJson.encodeToString(result))
    }

internal fun publishOrArchiveFailure(
    out: File, history: FileHistorySearch, renderer: HtmlRenderer, result: PipelineResult, keepUnseen: Boolean,
    seedHistoryDays: Int = PipelineConfig.defaults.expansion.seedHistoryDays, beforePublish: (LocalDate) -> Unit = {}
): HtmlRenderer.Rendered? = try {
    publishRun(out, history, renderer, result, keepUnseen, seedHistoryDays, beforePublish)
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    val failed = result.copy(report = result.report.copy(issues = result.report.issues + safeFailure(e)))
    try {
        val archived = archiveFailedRun(out, failed)
        println("결과 저장 실패 · out/runs/failed/${archived.name}")
    } catch (archiveFailure: Exception) {
        e.addSuppressed(archiveFailure)
    }
    throw e
}

internal fun publishRun(
    out: File, history: FileHistorySearch, renderer: HtmlRenderer, result: PipelineResult, keepUnseen: Boolean = false,
    seedHistoryDays: Int = PipelineConfig.defaults.expansion.seedHistoryDays, beforePublish: (LocalDate) -> Unit = {}
): HtmlRenderer.Rendered? {
    val day = LocalDate.parse(result.report.runDate)
    val current = out.resolve("$day.json")
    val prior = history.runs(day.plusDays(1), 1).firstOrNull()
    val preserveCards = result.cards.isEmpty() && prior?.cards?.isNotEmpty() == true
    val rendered = if (preserveCards) null else {
        val archivedCost = history.archivedCost(day, day, skipStartedAt = prior?.report?.startedAt)
        val todayCost = archivedCost + (prior?.report?.costUsd ?: 0.0) + result.report.costUsd
        val monthCost = history.runs(day, day.dayOfMonth - 1).sumOf { it.report.costUsd } + todayCost +
            history.archivedCost(day.withDayOfMonth(1), day.minusDays(1))
        renderer.render(result, monthCost, todayCost)
    }
    val output = result.copy(metrics = result.metrics.copy(snapshots = emptyList()), trackClassified = emptyMap())
    val files = buildMap {
        if (rendered == null) {
            put(archiveFile(out, result.report), outputJson.encodeToString(result.copy(trackClassified = emptyMap())))
        } else {
            if (current.isFile) put(archiveFile(out, prior?.report ?: result.report), current.readText())
            put(out.resolve("$day.html"), rendered.html)
            put(out.resolve("$day.txt"), rendered.text)
            put(current, outputJson.encodeToString(output))
        }
        val seedFile = out.resolve("expansion-seeds.json")
        val usedSeeds = if (rendered != null) result.usedExpansionSeeds else emptyList()
        if (seedFile.isFile || usedSeeds.isNotEmpty()) {
            val previous = readJson<List<collector.stage.UsedExpansionSeed>>(seedFile).orEmpty().filter { it.parsedDate != null }
            val newest = maxOf(day.toString(), previous.maxOfOrNull { it.date } ?: day.toString())
            val oldest = LocalDate.parse(newest).minusDays(seedHistoryDays.toLong()).toString()
            val retained = (previous + usedSeeds).distinct().filter { it.date >= oldest }
            if (retained != previous) put(seedFile, outputJson.encodeToString(retained))
        }
        put(out.resolve("metrics.json"), history.metricsContent(result.metrics.snapshots, Instant.parse(result.report.startedAt)))
        put(out.resolve("tracked.json"), outputJson.encodeToString(result.tracked))
        put(history.stateFile(day.toString()), outputJson.encodeToString(output.copy(
            items = emptyList(), dropped = emptyList(), cards = emptyList()
        )))
        put(out.resolve("track-classified.json"), outputJson.encodeToString(result.trackClassified))
        if (rendered != null && !keepUnseen) {
            val seenFile = out.resolve("seen.json")
            put(seenFile, JsonFileSeenStore(seenFile).markedContent(itemsToMarkSeen(result), day))
        }
    }
    val replaced = mutableListOf<Pair<File, String?>>()
    try {
        for ((file, content) in files) {
            val previous = if (file.isFile) file.readText() else null
            if (file == current) beforePublish(day)
            file.writeAtomically(content)
            replaced += file to previous
        }
    } catch (e: Exception) {
        for ((file, previous) in replaced.asReversed()) {
            try {
                if (previous == null) java.nio.file.Files.delete(file.toPath())
                else file.writeAtomically(previous)
            } catch (restoreFailure: Exception) {
                e.addSuppressed(restoreFailure)
            }
        }
        throw e
    }
    return rendered
}

package collector.dev

import collector.analysis.AnalysisCommand
import collector.analysis.AnalysisConfig
import collector.backfill.Backfill
import collector.budget.BudgetSettings
import collector.budget.MonthlyBudget
import collector.config.ConfigLoader
import collector.config.DuplicateRssFeedNameException
import collector.config.PipelineConfig
import collector.config.SourcesFile
import collector.data.AnalysisMode
import collector.data.DataSync
import collector.data.DataBranch
import collector.data.InboxRequest
import collector.data.InboxResult
import collector.data.ScheduleSettings
import collector.data.files
import collector.data.inboxJson
import collector.data.mergeMailJournals
import collector.data.publicMailJournal
import collector.data.requestIdPattern
import collector.data.resultFile
import collector.data.saveResult
import collector.llm.*
import collector.mail.MailDelivery
import collector.mail.MailKind
import collector.mail.MailSendResult
import collector.mail.Mailer
import collector.mail.SmtpMailer
import collector.model.DropReason
import collector.model.Item
import collector.model.RunReport
import collector.pipeline.FullPipeline
import collector.pipeline.Pipeline
import collector.pipeline.PipelineResult
import collector.port.SecretProvider
import collector.profile.ProfileLoader
import collector.prompt.FilePromptStore
import collector.prompt.PromptResolver
import collector.report.HtmlRenderer
import collector.source.GitHubAdapter
import collector.source.GitHubIssuesAdapter
import collector.source.HackerNewsAdapter
import collector.source.HuggingFaceAdapter
import collector.source.ProductHuntAdapter
import collector.source.RedditAdapter
import collector.source.RssAdapter
import collector.source.Http
import collector.source.SourceAdapter
import collector.stage.Classifier
import collector.stage.IdeaGenerator
import collector.trend.publishTrend
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
import java.util.Locale
import kotlin.system.exitProcess

fun main(args: Array<String>) {
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
    out: File = File("out"),
    dailySources: Pair<SourcesFile, List<SourceAdapter>>? = null,
    scheduledClock: Clock = Clock.systemDefaultZone(),
    scheduledMailer: (() -> Mailer?)? = null,
    processStartedAt: Instant? = null,
    dailyProviders: (() -> List<LlmProvider>)? = null
) {
    val promptsDir = File("config/prompts")
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
            val sync = DataSync(out, promptsDir)
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
            out.mkdirs()
            withRunLock(out) {
                val updated = BudgetSettings.update(out, args.drop(1))
                if ("analysisRequestUsd" in updated || "analysisMonthlyUsd" in updated) {
                    MonthlyBudget(out, BudgetSettings.load(out)).analysisBlockReason(LocalDate.now())
                }
                updated.forEach { (key, value) -> println("$key=$value") }
            }
            return
        }
        "--budget-status" -> {
            require(args.size == 1)
            out.mkdirs()
            withRunLock(out) {
                MonthlyBudget(out, BudgetSettings.load(out)).status(LocalDate.now()).forEach(::println)
            }
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
        out.mkdirs()
        withRunLock(out) {
            println(publishTrend(out, date))
        }
        return
    }
    val secrets = DotEnv.secretProvider(DotEnv.load(File(".env")))
    when (args.firstOrNull()) {
        "--mail", "--mail-test" -> {
            out.mkdirs()
            withRunLock(out) { runMailCommand(args, out, secrets) }
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
            if (noMail) out.resolve("mail/scheduled.json").delete()
            if (readJson<ScheduleSettings>(out.resolve("settings/schedule.json"))?.enabled == false) return
            val status = runScheduled(out, secrets, scheduledClock, daily = { startedAt, log ->
                var result: PipelineResult? = null
                try {
                    runDaily(emptyArray(), out, secrets, dailySources, dailyProviders, startedAt,
                        print = {}, summary = { result = it }, deadlineClock = if (processStartedAt == null) scheduledClock else Clock.systemUTC())
                } finally {
                    log("일일 요약 · 카드 ${result?.cards?.size ?: 0}개 · " +
                        String.format(Locale.ROOT, "추정 USD %.6f", result?.report?.costUsd ?: 0.0) +
                        " · 실패 출처 ${result?.report?.failedSources?.size ?: 0}개")
                }
            }, mailer = if (noMail) null else if (scheduledMailer == null) SmtpMailer.from(secrets) else scheduledMailer(),
                startedAt = processStartedAt ?: scheduledClock.instant(),
                deadlineMinutes = deadlineMinutes(PipelineConfig.load()),
                deadlineClock = if (processStartedAt == null) scheduledClock else Clock.systemUTC(),
                noMail = noMail)
            if (status != 0) throw ScheduledDailyFailure()
            return
        }
    }
    if (args.contentEquals(arrayOf("--llm-smoke"))) {
        if (!llmSmoke(secrets)) exitProcess(1)
        return
    }
    val defaults = PipelineConfig.defaults
    if (args.firstOrNull() in setOf("--analyze", "--analysis-detail", "--analysis-list", "--data-analyze", "--data-analysis-queue")) {
        val local = File("config/analysis.local.json")
        val config = AnalysisConfig.load(local, defaults.analysis)
        val prompts = PromptResolver(FilePromptStore(promptsDir.toPath()))
        val dispatch = args.first() == "--data-analyze"
        val dailyQueue = args.first() == "--data-analysis-queue"
        val output = mutableListOf<String>()
        var mailReportId: String? = null
        var beforePaidRun: () -> Unit = {}
        val command = AnalysisCommand(out, config, prompts, provider = {
            OpenAiProvider(secrets.get("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
                ?: throw LlmKeyMissingException(ProviderId.OPENAI))
        }, print = if (dispatch || dailyQueue) { line -> output.add(line) } else ::println, sendMail = { id ->
            if (dispatch) mailReportId = id
            else {
                val delivery = mailDelivery(out, secrets)
                if (delivery.send(MailKind.ANALYSIS, id) == MailSendResult.RECORD_FAILED) throw MailRecordFailure()
            }
        }, beforePaidRun = { beforePaidRun() })
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
                    } catch (_: Exception) {
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
        runBackfill(args, secrets, PipelineConfig.load(), out, processStartedAt ?: Instant.now())
        return
    }
    out.mkdirs()
    withRunLock(out) { runDaily(args, out, secrets, dailySources, dailyProviders, processStartedAt ?: Instant.now()) }
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
                process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
            }
        }
    } catch (_: java.io.IOException) {
        false
    }
}

private fun workflowMailPublisher(out: File, prompts: File): (MailDelivery.Attempt?) -> Boolean = { attempt ->
    val published = workflowPublish("publish-mail", "mail")
    if (!published && attempt == null) {
        runCatching { DataSync(out, prompts).mailArtifact(out.resolve("mail/public-journal.json")) }
    }
    published && (attempt == null ||
        readJson<List<MailDelivery.Attempt>>(File("../data/out/mail/sent.json")).orEmpty().any {
            it.copy(firstFailureAt = null) == attempt
        })
}

private fun deadlineMinutes(config: PipelineConfig): Int =
    if (System.getenv("COLLECTOR_JOB_STARTED_AT") != null) 150 - 20 else config.deadlineMinutes

private suspend fun runDaily(
    args: Array<String>,
    out: File,
    secrets: SecretProvider,
    dailySources: Pair<SourcesFile, List<SourceAdapter>>?,
    dailyProviders: (() -> List<LlmProvider>)?,
    runStartedAt: Instant? = null,
    print: (String) -> Unit = ::println,
    summary: (PipelineResult) -> Unit = {},
    deadlineClock: Clock = Clock.systemUTC()
): Boolean {
    val defaults = PipelineConfig.load()
    var budgetOverride: Double? = null
    var keepUnseen = false
    var noLlm = false
    var index = 0
    while (index < args.size) {
        when (args[index++]) {
            "--keep-unseen" -> keepUnseen = true
            "--no-llm" -> noLlm = true
            "--budget" -> budgetOverride = requireNotNull(args.getOrNull(index++)?.toDoubleOrNull())
            else -> throw IllegalArgumentException("지원하지 않는 실행 옵션입니다.")
        }
    }
    budgetOverride?.let { require(it.isFinite() && it >= 0.0) }
    val settings = BudgetSettings.load(out, print)
    val config = defaults.copy(dailyBudgetUsd = budgetOverride ?: settings.dailyCardUsd)
    val monthlyBudget = MonthlyBudget(out, settings)
    val sources = dailySources?.first ?: ConfigLoader.load(File("config/sources.local.json"))
    val profiles = ProfileLoader.loadDefaults()
    val routingIssues = ProfileLoader.validateRouting(sources, profiles)
    if (routingIssues.isNotEmpty()) {
        print("출처 설정 오류:\n- " + routingIssues.joinToString("\n- "))
        throw InvalidSourceRoutingException()
    }
    val prompts = PromptResolver(FilePromptStore(File("config/prompts").toPath()))
    for (id in profiles.ids) {
        prompts.resolve("profile.$id.match")
        prompts.resolve("profile.$id.analysis")
    }
    val history = FileHistorySearch(out, defaults.historyDays)
    val ledger = if (noLlm) null else costLedger(out, history)
    val startedAt = runStartedAt ?: Instant.now()
    val today = LocalDate.ofInstant(startedAt, java.time.ZoneId.systemDefault())
    val runClockStart = Instant.now()
    val generationCheckpoint = batchPath(out, today.toString(), "generate")
    val pendingBatches = out.resolve("batch").listFiles().orEmpty().any {
        it.isFile && it.extension == "json" && it.name != "index.json"
    } || out.resolve("batch/quarantine").listFiles().orEmpty().any { it.name.endsWith(".unsettled.json") }
    val resumingBatch = hasSavedBatch(out, today.toString(), "classify") || hasSavedBatch(out, today.toString(), "generate")
    fun budgetSkipReason(): String? {
        if (noLlm || resumingBatch) return null
        return try {
            monthlyBudget.dailySkipReason(today, config.dailyBudgetUsd)
        } catch (_: IllegalArgumentException) {
            "Cost ledger could not be read"
        } catch (_: java.io.IOException) {
            "Cost ledger could not be read"
        }
    }
    val initialSkip = if (pendingBatches) null else budgetSkipReason()
    val costs = CostGuard(config.dailyBudgetUsd, ledger = ledger, allowsReservation = monthlyBudget::allows)
    val http = Http.client()
    val providers = mutableListOf<LlmProvider>()
    try {
        if (!noLlm && (initialSkip == null || pendingBatches)) {
            if (dailyProviders != null) {
                providers += dailyProviders()
            } else {
                secrets.get("ANTHROPIC_API_KEY")?.let { providers += AnthropicProvider(it) }
                secrets.get("OPENAI_API_KEY")?.let { providers += OpenAiProvider(it) }
            }
        }
        val models = if (providers.any { it.id == ProviderId.ANTHROPIC } || providers.isEmpty()) {
            mapOf("classify" to defaults.models.classify, "generate" to defaults.models.generate, "track" to defaults.models.classify)
        } else emptyMap()
        val router = LlmRouter(providers, costs, models, defaults.models.fallback, preflightBudget = true)
        router.startRun(startedAt, deadlineMinutes(config), deadlineClock.instant())
        val recoveryIssues = if (noLlm) emptyList() else router.recoverBatches(out, config.batch, config.recentTopicDays) {
            it.name == "$today-classify.json" || it.name == "$today-generate.json"
        }
        val skipReason = budgetSkipReason()
        val tracked = JsonFileTrackedStore(out.resolve("tracked.json"))
        val seen = JsonFileSeenStore(out.resolve("seen.json"))
        val reddit = RedditAdapter(http, secrets)
        val full = FullPipeline(config, profiles, prompts, router, costs, http, history, tracked,
            collectOnly = noLlm, reddit = reddit, out = out)
        val adapters = dailySources?.second ?: listOf(HackerNewsAdapter(http), GitHubAdapter(http, secrets), HuggingFaceAdapter(http),
            GitHubIssuesAdapter(http, secrets), reddit, ProductHuntAdapter(http, secrets), RssAdapter(http))
        val pipeline = Pipeline(adapters, seen, clock = { startedAt },
            onStage = { print("· 단계 \u00b7 ${HtmlRenderer.stageName(it)}") }, full = full,
            paidStageSkipReason = skipReason)
        var result = try {
            val finished = kotlinx.coroutines.withTimeoutOrNull(router.remainingMillis()) { pipeline.run(sources) }
            if (finished == null) {
                val report = RunReport(today.toString(), startedAt.toString(), emptyList(), emptyList(), emptyMap(),
                    java.time.Duration.between(runClockStart, Instant.now()).toMillis(), llmCalls = costs.drainCalls(),
                    issues = recoveryIssues + "실행 마감")
                val expired = PipelineResult(emptyList(), emptyList(), report)
                summary(expired)
                archiveFile(out, report).writeAtomically(outputJson.encodeToString(expired))
                print("실행 마감 · 다음 실행에서 이어서")
                return false
            }
            finished.copy(report = finished.report.copy(
                issues = (recoveryIssues + finished.report.issues).distinct(),
                llmCalls = finished.report.llmCalls + costs.drainCalls()))
        } catch (e: Exception) {
            if (!noLlm) {
                val day = LocalDate.ofInstant(startedAt, java.time.ZoneId.systemDefault())
                val report = RunReport(day.toString(), startedAt.toString(), emptyList(), emptyList(),
                    emptyMap(), java.time.Duration.between(startedAt, Instant.now()).toMillis(),
                    llmCalls = costs.drainCalls(), issues = recoveryIssues + safeFailure(e))
                val failed = PipelineResult(emptyList(), emptyList(), report)
                summary(failed)
                val archived = archiveFailedRun(out, failed)
                print("실패 실행 보관 · out/runs/failed/${archived.name}")
                if (history.runs(day.plusDays(1), 1).firstOrNull()?.cards?.isNotEmpty() == true) {
                    print("기존 카드 결과 유지 · out/$day.json")
                }
            }
            throw e
        }
        summary(result)
        val day = LocalDate.parse(result.report.runDate)
        if (skipReason != null) {
            val archived = archiveFile(out, result.report)
            archived.writeAtomically(outputJson.encodeToString(result))
            print("$skipReason · ${archived.path}")
            return false
        }
        if (noLlm) {
            out.resolve("$day-collect.json").writeAtomically(outputJson.encodeToString(result))
            if (!keepUnseen) seen.markSeen(result.items, day)
            print("수집·정제 ${result.items.size}건 · out/$day-collect.json")
            return false
        }
        if (CLASSIFICATION_BATCH_PENDING in result.report.issues || isPendingBatch(generationCheckpoint)) {
            archiveFile(out, result.report).writeAtomically(outputJson.encodeToString(result))
            router.markAppliedBatchesConsumed()
            result.report.issues.forEach { print("· $it") }
            return false
        }
        val renderer = HtmlRenderer(IdeaGenerator(defaults, prompts, router).categories)
        val rendered = publishOrArchiveFailure(out, history, renderer, result, keepUnseen)
        router.markAppliedBatchesConsumed()
        if (router.remainingMillis() == 0L) {
            result = result.copy(report = result.report.copy(issues = result.report.issues + "Publication exceeded the execution deadline"))
            val file = if (rendered == null) archiveFile(out, result.report) else out.resolve("$day.json")
            file.writeAtomically(outputJson.encodeToString(result.copy(metrics = if (rendered == null) result.metrics else result.metrics.copy(snapshots = emptyList()),
                trackClassified = emptyMap())))
            summary(result)
        }
        print("${rendered?.subject ?: "카드 0개"} · 추적 ${result.trackingUpdates.size}건 · " +
            String.format(Locale.ROOT, "추정 USD %.6f", result.report.costUsd))
        result.metrics.profiles.forEach { (profile, counts) ->
            print("${profiles[profile]?.config?.name ?: profile} · 분류 ${counts.classified}건 · 기준 점수 통과 ${counts.thresholdPassed}건 · 최종 카드 후보 ${counts.selected}건")
        }
        result.report.failedSources.forEach { print("출처 실패 · ${it.source} · ${it.error}") }
        result.report.issues.forEach { print("· $it") }
        if (rendered == null) print("기존 카드 결과 유지 · 새 실행은 out/runs/에 보관")
        else print("결과 저장 · out/$day.json · out/$day.html · out/$day.txt")
        return rendered != null
    } finally {
        providers.filterIsInstance<AutoCloseable>().forEach { it.close() }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}

internal fun costLedger(out: File, history: FileHistorySearch) = FileCostLedger(out.resolve("cost-ledger")) { day ->
    val prior = history.runs(day.plusDays(1), 1).firstOrNull()
    history.archivedCost(day, day, reserved = true, skipStartedAt = prior?.report?.startedAt) +
        (prior?.report?.llmCalls?.sumOf { maxOf(it.costUsd, it.reservedUsd) } ?: 0.0)
}

private suspend fun runBackfill(args: Array<String>, secrets: SecretProvider, defaults: PipelineConfig, out: File, startedAt: Instant) {
    val period = args.getOrNull(1).orEmpty()
    val days = if (period == "4w") 28 else {
        val match = requireNotNull(Regex("([1-9][0-9]*)d").matchEntire(period))
        requireNotNull(match.groupValues[1].toIntOrNull())
    }
    var dryRun = false
    var yes = false
    var force = false
    var budgetOverride: Double? = null
    var date = LocalDate.now()
    var index = 2
    while (index < args.size) {
        when (args[index++]) {
            "--dry-run" -> dryRun = true
            "--yes" -> yes = true
            "--force" -> force = true
            "--budget" -> budgetOverride = requireNotNull(args.getOrNull(index++)?.toDoubleOrNull())
            "--date" -> {
                val value = args.getOrNull(index++)
                date = requireNotNull(runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf {
                    value?.length == 10 && it.toString() == value
                })
            }
            else -> throw IllegalArgumentException("지원하지 않는 백필 옵션입니다.")
        }
    }
    budgetOverride?.let { require(it.isFinite() && it >= 0.0) }
    out.mkdirs()
    withRunLock(out) {
        val settings = BudgetSettings.load(out)
        val monthlyBudget = MonthlyBudget(out, settings)
        val sources = ConfigLoader.load(File("config/sources.local.json"))
        val execute = yes && !dryRun
        val history = FileHistorySearch(out, defaults.historyDays)
        val costs = CostGuard(budgetOverride ?: settings.backfillRunUsd,
            ledger = if (execute) costLedger(out, history) else null, budgetPerRun = true,
            allowsReservation = monthlyBudget::allows)
        val anthropicKey = secrets.get("ANTHROPIC_API_KEY")
        val openAiKey = secrets.get("OPENAI_API_KEY")
        val model = if (anthropicKey != null || openAiKey == null) defaults.models.classify else
            checkNotNull(ModelCatalog.defaults.defaultModel(ProviderId.OPENAI, ModelTier.FAST))
        val providers = mutableListOf<LlmProvider>()
        val http = Http.client()
        try {
            var runRouter: LlmRouter? = null
            val classifier = if (execute) {
                anthropicKey?.let { providers += AnthropicProvider(it) }
                openAiKey?.let { providers += OpenAiProvider(it) }
                val profiles = ProfileLoader.loadDefaults()
                val routingIssues = ProfileLoader.validateRouting(sources, profiles)
                if (routingIssues.isNotEmpty()) {
                    println("출처 설정 오류:\n- " + routingIssues.joinToString("\n- "))
                    throw InvalidSourceRoutingException()
                }
                val router = LlmRouter(providers, costs, mapOf("classify" to model), defaults.models.fallback, preflightBudget = true)
                router.startRun(startedAt, defaults.deadlineMinutes)
                runRouter = router
                val activeDates = (1..days).map { "${date.minusDays(it.toLong())}-backfill.json" }.toSet()
                router.recoverBatches(out, defaults.batch, defaults.recentTopicDays) { it.name in activeDates }.forEach(::println)
                Classifier(defaults, profiles, PromptResolver(FilePromptStore(File("config/prompts").toPath())), router)
            } else null
            val backfill = Backfill(out, sources, HackerNewsAdapter(http), GitHubAdapter(http, secrets), model, costs,
                classifier, monthlyBudget = monthlyBudget)
            if (runRouter == null) {
                backfill.run(date, days, dryRun, yes, force)
            } else if (kotlinx.coroutines.withTimeoutOrNull(runRouter.remainingMillis()) {
                    backfill.run(date, days, dryRun, yes, force)
                } == null) {
                println("실행 마감 · 다음 실행에서 이어서")
            }
        } finally {
            providers.filterIsInstance<AutoCloseable>().forEach { it.close() }
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}

private class InvalidSourceRoutingException : RuntimeException()
internal class RunLockedException : RuntimeException()

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

private fun archiveFile(out: File, report: RunReport): File =
    out.resolve("runs/${report.runDate}-${report.startedAt.replace(':', '-')}.json")

private fun archiveFailedRun(out: File, result: PipelineResult): File =
    out.resolve("runs/failed").resolve(archiveFile(out, result.report).name).apply {
        writeAtomically(outputJson.encodeToString(result))
    }

internal fun publishOrArchiveFailure(
    out: File, history: FileHistorySearch, renderer: HtmlRenderer, result: PipelineResult, keepUnseen: Boolean
): HtmlRenderer.Rendered? = try {
    publishRun(out, history, renderer, result, keepUnseen)
} catch (e: Exception) {
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
    out: File, history: FileHistorySearch, renderer: HtmlRenderer, result: PipelineResult, keepUnseen: Boolean = false
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

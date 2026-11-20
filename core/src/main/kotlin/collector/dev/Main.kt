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
import collector.llm.*
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
import java.time.LocalDate
import java.util.Locale
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8"))
    try {
        runBlocking { runMain(args) }
    } catch (e: Exception) {
        println("실행 실패 · ${safeFailure(e)}")
        when (e) {
            is DuplicateRssFeedNameException -> println("출처 설정 오류 · ${e.message}")
            is IllegalArgumentException -> println("사용법 · --keep-unseen --no-llm --budget <USD> | --trend [--date yyyy-MM-dd] | " +
                "--backfill <4w|Nd> [--dry-run] [--yes] [--force] [--budget <usd>] [--date yyyy-MM-dd] | " +
                "--analyze <테마ID…> [--date yyyy-MM-dd] [--yes] [--force] [--budget <usd>] [--fit <n>] | " +
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
    dailyProviders: (() -> List<LlmProvider>)? = null
) {
    when (args.firstOrNull()) {
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
    if (args.contentEquals(arrayOf("--llm-smoke"))) {
        if (!llmSmoke(secrets)) exitProcess(1)
        return
    }
    val defaults = PipelineConfig.defaults
    if (args.firstOrNull() in setOf("--analyze", "--analysis-detail", "--analysis-list")) {
        val local = File("config/analysis.local.json")
        val config = AnalysisConfig.load(local, defaults.analysis)
        val prompts = PromptResolver(FilePromptStore(File("config/prompts").toPath()))
        try {
            AnalysisCommand(out, config, prompts, provider = {
                OpenAiProvider(secrets.get("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
                    ?: throw LlmKeyMissingException(ProviderId.OPENAI))
            }).run(args)
        } catch (e: IllegalArgumentException) {
            e.message?.let { println("분석 입력 오류 · $it") }
            throw e
        }
        return
    }
    if (args.firstOrNull() == "--backfill") {
        runBackfill(args, secrets, defaults, out)
        return
    }
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
    out.mkdirs()
    withRunLock(out) {
        val settings = BudgetSettings.load(out)
        val config = defaults.copy(dailyBudgetUsd = budgetOverride ?: settings.dailyCardUsd)
        val monthlyBudget = MonthlyBudget(out, settings)
        val sources = dailySources?.first ?: ConfigLoader.load(File("config/sources.local.json"))
        val profiles = ProfileLoader.loadDefaults()
        val routingIssues = ProfileLoader.validateRouting(sources, profiles)
        if (routingIssues.isNotEmpty()) {
            println("출처 설정 오류:\n- " + routingIssues.joinToString("\n- "))
            throw InvalidSourceRoutingException()
        }
        val prompts = PromptResolver(FilePromptStore(File("config/prompts").toPath()))
        for (id in profiles.ids) {
            prompts.resolve("profile.$id.match")
            prompts.resolve("profile.$id.analysis")
        }
        val history = FileHistorySearch(out, defaults.historyDays)
        val ledger = if (noLlm) null else costLedger(out, history)
        val startedAt = Instant.now()
        val today = LocalDate.ofInstant(startedAt, java.time.ZoneId.systemDefault())
        val skipReason = if (noLlm) null else monthlyBudget.dailySkipReason(today, config.dailyBudgetUsd)
        val costs = CostGuard(config.dailyBudgetUsd, ledger = ledger, allowsReservation = monthlyBudget::allows)
        val http = Http.client()
        val providers = mutableListOf<LlmProvider>()
        try {
            if (!noLlm && skipReason == null) {
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
            val tracked = JsonFileTrackedStore(out.resolve("tracked.json"))
            val seen = JsonFileSeenStore(out.resolve("seen.json"))
            val reddit = RedditAdapter(http, secrets)
            val full = FullPipeline(config, profiles, prompts, router, costs, http, history, tracked,
                collectOnly = noLlm, reddit = reddit)
            val adapters = dailySources?.second ?: listOf(HackerNewsAdapter(http), GitHubAdapter(http, secrets), HuggingFaceAdapter(http),
                GitHubIssuesAdapter(http, secrets), reddit, ProductHuntAdapter(http, secrets), RssAdapter(http))
            val pipeline = Pipeline(adapters, seen, clock = { startedAt },
                onStage = { println("· 단계 \u00b7 ${HtmlRenderer.stageName(it)}") }, full = full,
                paidStageSkipReason = skipReason)
            val result = try {
                pipeline.run(sources)
            } catch (e: Exception) {
                if (!noLlm) {
                    val day = LocalDate.ofInstant(startedAt, java.time.ZoneId.systemDefault())
                    val report = RunReport(day.toString(), startedAt.toString(), emptyList(), emptyList(),
                        emptyMap(), java.time.Duration.between(startedAt, Instant.now()).toMillis(),
                        llmCalls = costs.drainCalls(), issues = listOf(safeFailure(e)))
                    val archived = archiveFailedRun(out, PipelineResult(emptyList(), emptyList(), report))
                    println("실패 실행 보관 · out/runs/failed/${archived.name}")
                    if (history.runs(day.plusDays(1), 1).firstOrNull()?.cards?.isNotEmpty() == true) {
                        println("기존 카드 결과 유지 · out/$day.json")
                    }
                }
                throw e
            }
            val day = LocalDate.parse(result.report.runDate)
            if (skipReason != null) {
                val archived = archiveFile(out, result.report)
                archived.writeAtomically(outputJson.encodeToString(result))
                println("$skipReason · ${archived.path}")
                return@withRunLock
            }
            if (noLlm) {
                out.resolve("$day-collect.json").writeAtomically(outputJson.encodeToString(result))
                if (!keepUnseen) seen.markSeen(result.items, day)
                println("수집·정제 ${result.items.size}건 · out/$day-collect.json")
                return@withRunLock
            }
            val renderer = HtmlRenderer(IdeaGenerator(defaults, prompts, router).categories)
            val rendered = publishOrArchiveFailure(out, history, renderer, result, keepUnseen)
            println("${rendered?.subject ?: "카드 0개"} · 추적 ${result.trackingUpdates.size}건 · " +
                String.format(Locale.ROOT, "추정 USD %.6f", result.report.costUsd))
            result.metrics.profiles.forEach { (profile, counts) ->
                println("$profile · 분류 ${counts.classified}건 · 임계값 통과 ${counts.thresholdPassed}건 · 최종 후보 ${counts.selected}건")
            }
            result.report.failedSources.forEach { println("출처 실패 · ${it.source} · ${it.error}") }
            result.report.issues.forEach { println("· $it") }
            if (rendered == null) println("기존 카드 결과 유지 · 새 실행은 out/runs/에 보관")
            else println("결과 저장 · out/$day.json · out/$day.html · out/$day.txt")
        } finally {
            providers.filterIsInstance<AutoCloseable>().forEach { it.close() }
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}

internal fun costLedger(out: File, history: FileHistorySearch) = FileCostLedger(out.resolve("cost-ledger")) { day ->
    val prior = history.runs(day.plusDays(1), 1).firstOrNull()
    history.archivedCost(day, day, reserved = true, skipStartedAt = prior?.report?.startedAt) +
        (prior?.report?.llmCalls?.sumOf { maxOf(it.costUsd, it.reservedUsd) } ?: 0.0)
}

private suspend fun runBackfill(args: Array<String>, secrets: SecretProvider, defaults: PipelineConfig, out: File) {
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
            val classifier = if (execute) {
                anthropicKey?.let { providers += AnthropicProvider(it) }
                openAiKey?.let { providers += OpenAiProvider(it) }
                val profiles = ProfileLoader.loadDefaults()
                val routingIssues = ProfileLoader.validateRouting(sources, profiles)
                if (routingIssues.isNotEmpty()) {
                    println("출처 설정 오류:\n- " + routingIssues.joinToString("\n- "))
                    throw InvalidSourceRoutingException()
                }
                Classifier(defaults, profiles, PromptResolver(FilePromptStore(File("config/prompts").toPath())),
                    LlmRouter(providers, costs, mapOf("classify" to model), defaults.models.fallback, preflightBudget = true))
            } else null
            Backfill(out, sources, HackerNewsAdapter(http), GitHubAdapter(http, secrets), model, costs, classifier,
                monthlyBudget = monthlyBudget)
                .run(date, days, dryRun, yes, force)
        } finally {
            providers.filterIsInstance<AutoCloseable>().forEach { it.close() }
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}

private class InvalidSourceRoutingException : RuntimeException()
internal class RunLockedException : RuntimeException()

private fun safeFailure(e: Exception): String {
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

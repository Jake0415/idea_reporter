package collector.backfill

import collector.config.GitHubConfig
import collector.config.HnConfig
import collector.config.SourcesFile
import collector.dev.outputJson
import collector.dev.readJson
import collector.dev.writeAtomically
import collector.llm.CostGuard
import collector.llm.ModelCatalog
import collector.llm.StageModel
import collector.model.DropReason
import collector.model.Item
import collector.model.RunReport
import collector.model.SourceResult
import collector.pipeline.PipelineResult
import collector.pipeline.filterCollected
import collector.refine.Deduplicator
import collector.refine.Scorer
import collector.source.GitHubAdapter
import collector.source.HackerNewsAdapter
import collector.source.HttpStatusException
import collector.stage.Classifier
import collector.trend.hasDailyRun
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

internal class Backfill(
    private val out: File,
    private val sources: SourcesFile,
    private val hn: HackerNewsAdapter,
    private val github: GitHubAdapter,
    private val classifyModel: StageModel,
    private val costs: CostGuard,
    private val classifier: Classifier?,
    private val print: (String) -> Unit = ::println,
    private val clock: () -> Instant = Instant::now,
    private val writeCache: (File, String) -> Unit = File::writeAtomically
) {
    @Serializable
    private data class CollectedSource(
        val items: List<Item>, val millis: Long, val ok: Boolean = true, val complete: Boolean = false
    )

    @Serializable
    private data class CollectionCache(
        val sources: Map<String, CollectedSource> = emptyMap(),
        val replacing: Boolean = false,
        val replacementId: String? = null
    )

    suspend fun run(date: LocalDate, days: Int, dryRun: Boolean, yes: Boolean, force: Boolean) {
        val directory = out.resolve("backfill")
        val configs = sources.sources.filter { it.enabled && (it is HnConfig || it is GitHubConfig) }
        val configById = configs.associateBy { it.sourceId }
        val githubSearch by lazy { github.historicalSearch(warn = print) }
        val execute = yes && !dryRun
        var collected = 0
        var filled = 0
        var filledItems = 0
        var unfinished = 0
        var totalCost = 0.0
        for (offset in 1..days) {
            val day = date.minusDays(offset.toLong())
            val resultFile = directory.resolve("$day.json")
            if (hasDailyRun(out, day)) continue
            val cache = directory.resolve("collected/$day.json")
            val cached = readJson<CollectionCache>(cache)
            if (!force && resultFile.isFile && cached?.replacing != true) continue

            val checkpoint = directory.resolve("interrupted/$day.json")
            if (!force && cached?.replacing == true && resultFile.isFile &&
                readJson<JsonObject>(resultFile)?.get("collectionHash") ==
                    JsonPrimitive(collectionHash(outputJson.encodeToString(cached)))) {
                Files.deleteIfExists(checkpoint.toPath())
                writeCache(cache, outputJson.encodeToString(cached.copy(replacing = false)))
                continue
            }
            val startedAt = clock()
            val successful = cached?.takeUnless { force }?.sources.orEmpty()
                .filterValues { it.ok && it.complete }.toMutableMap()
            val replacing = cached?.replacing == true || (force && execute && resultFile.isFile)
            val sourceResults = configs.map { config ->
                val id = config.sourceId
                val before = clock()
                fun elapsed() = Duration.between(before, clock()).toMillis().coerceAtLeast(0)
                try {
                    val saved = successful[id] ?: run {
                        val fetched = when (config) {
                            is HnConfig -> hn.fetchDay(config, day)
                            is GitHubConfig -> githubSearch.fetch(config, day)
                            else -> error("Unsupported backfill source")
                        }
                        CollectedSource(fetched.map {
                            if (it.profileHint.isEmpty()) it.copy(profileHint = config.profile) else it
                        }, elapsed(), complete = true).also { successful[id] = it }
                    }
                    SourceResult(id, saved.items.size, millis = saved.millis)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val status = (e as? HttpStatusException)?.status?.let { " (HTTP $it)" }.orEmpty()
                    SourceResult(id, 0, "수집 요청 실패$status", elapsed())
                }
            }
            // Give each replacement its own hash even when the fetched items are identical.
            val replacementId = if (replacing && force && execute) UUID.randomUUID().toString() else cached?.replacementId
            val collection = CollectionCache(successful.filterKeys { it in configById }, replacing, replacementId)
            val collectionJson = outputJson.encodeToString(collection)
            if (force || collection != cached) {
                // Write the new cache first: if that fails, the old cache and its checkpoint stay paired and resumable.
                writeCache(cache, collectionJson)
                Files.deleteIfExists(checkpoint.toPath())
            }

            val start = day.atStartOfDay().toInstant(ZoneOffset.UTC)
            val end = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
            val filtered = filterCollected(configs.flatMap { successful[it.sourceId]?.items.orEmpty() },
                configById, sources.excludeKeywords, end, historicalStart = start)
            val deduped = Deduplicator().dedupe(filtered.kept)
            val items = Scorer.score(deduped.kept).sortedWith(compareByDescending<Item> { it.score }
                .thenByDescending { it.alsoSeenIn.size }.thenByDescending { it.rawScore })
            collected += items.size
            val failed = sourceResults.filter { it.error != null }
            if (failed.isNotEmpty()) {
                unfinished++
                failed.forEach { print("$day · 미완료 · ${it.source} · ${it.error}") }
            }
            if (!execute) {
                print("$day · ${items.size}건 · 추정 분류 ${usd(estimateCost(classifyModel, items.size))}")
                continue
            }
            if (failed.isNotEmpty()) continue

            val saved = readJson<PipelineResult>(checkpoint)
            val previousSignals = saved?.classified.orEmpty().associateBy { it.item.key }
            val remaining = items.filter { it.key !in previousSignals }
            val result = if (remaining.isEmpty()) null else checkNotNull(classifier)
                .classify(remaining, emptyList(), emptyList())
            val calls = saved?.report?.llmCalls.orEmpty() + costs.drainCalls()
            val signals = previousSignals + result?.signals.orEmpty().associateBy { it.item.key }
            val dropped = filtered.dropped + deduped.dropped + saved?.dropped.orEmpty().filter {
                it.reason == DropReason.NOT_RELEVANT && it.item.key in previousSignals
            } + result?.dropped.orEmpty()
            val budgetBlocked = result?.dropped.orEmpty().any { it.reason == DropReason.BUDGET_EXCEEDED }
            val report = RunReport(day.toString(), startedAt.toString(), sourceResults, emptyList(),
                dropped.groupingBy { it.reason }.eachCount(), Duration.between(startedAt, clock()).toMillis(),
                llmCalls = calls, issues = if (budgetBlocked) listOf("분류 예산 초과 · 다음 실행에서 이어서 처리") else emptyList())
            totalCost += report.costUsd
            print("$day · ${items.size}건 · ${usd(report.costUsd)}" +
                if (budgetBlocked) " · 미완료 · 분류 예산 초과" else "")
            val classified = PipelineResult(items, dropped, report, items.mapNotNull { signals[it.key] })
            if (budgetBlocked) {
                unfinished++
                checkpoint.writeAtomically(outputJson.encodeToString(classified))
                break
            }
            val resultJson = if (replacing) {
                val fields = outputJson.encodeToJsonElement(classified).jsonObject
                JsonObject(fields + ("collectionHash" to JsonPrimitive(collectionHash(collectionJson))))
            } else {
                outputJson.encodeToJsonElement(classified)
            }
            resultFile.writeAtomically(outputJson.encodeToString(resultJson))
            Files.deleteIfExists(checkpoint.toPath())
            if (replacing) writeCache(cache, outputJson.encodeToString(collection.copy(replacing = false)))
            filled++
            filledItems += items.size
        }
        if (!execute) {
            print("수집 합계 · ${collected}건 · 추정 분류 ${usd(estimateCost(classifyModel, collected))}")
            if (!yes) print("실행하려면 --yes")
        }
        print("트렌드 리포트는 --trend로 실행하세요.")
        print("과거 채우기 · ${filled}일 · ${filledItems}건 · ${usd(totalCost)} · 미완료 ${unfinished}일")
    }

    companion object {
        private const val INPUT_TOKENS_PER_ITEM = 1000
        private const val OUTPUT_TOKENS_PER_ITEM = 250

        private fun collectionHash(json: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.toByteArray()))

        internal fun estimateCost(model: StageModel, count: Int): Double =
            count * ModelCatalog.defaults.costUsd(model.provider, model.model, INPUT_TOKENS_PER_ITEM,
                OUTPUT_TOKENS_PER_ITEM, 0)

        private fun usd(value: Double) = String.format(Locale.ROOT, "USD %.6f", value)
    }
}

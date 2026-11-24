package app.ideacollector.data

import androidx.room.withTransaction
import app.ideacollector.github.contractJson
import app.ideacollector.github.sha256
import collector.data.InboxRequest
import collector.model.Feedback
import collector.model.RunReport
import collector.model.TrackedIdea
import collector.pipeline.PipelineResult
import collector.runtime.Backup
import collector.runtime.CollectorPaths
import collector.trend.TrendReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import java.time.Instant

internal const val LOCAL_REPO = "@phone"
private val daily = Regex("out/[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")

internal class RepositorySync(
    private val database: AppDatabase,
    private val paths: CollectorPaths,
    private val ready: () -> Boolean = { true }
) {
    private val dao = database.dao()
    private val mutex = Mutex()

    suspend fun refresh(requireComplete: Set<String> = emptySet()) = refresh(requireComplete, recovering = false)

    suspend fun refreshAfterRecovery() = refresh(emptySet(), recovering = true)

    private suspend fun refresh(requireComplete: Set<String>, recovering: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!recovering && !ready()) return@withLock
            val existing = dao.hashes(LOCAL_REPO).associate { it.path to it.sha256 }
            val seen = mutableSetOf<String>()
            val updates = mutableListOf<Entry>()
            val invalid = mutableListOf<String>()
            for ((prefix, root) in listOf("out" to paths.outDir, "config" to paths.configDir)) {
                for (file in root.walkTopDown().filter { it.isFile }) {
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    val path = if (prefix == "out" && relative.startsWith("settings/")) relative else "$prefix/$relative"
                    val kind = kind(path) ?: continue
                    seen += path
                    try {
                        require(file.length() <= Backup.MAX_BYTES)
                        val bytes = file.readBytes()
                        val hash = bytes.sha256()
                        if (existing[path] != hash) updates += decode(Artifact(LOCAL_REPO, path, kind, hash, bytes.decodeToString()))
                    } catch (_: IllegalArgumentException) {
                        invalid += path
                    } catch (_: java.io.IOException) {
                        invalid += path
                    }
                }
            }
            check(invalid.none { it in requireComplete || "out/$it" in requireComplete })
            database.withTransaction {
                for (path in (existing.keys - seen) + updates.map { it.artifact.path }) {
                    dao.deleteArtifact(LOCAL_REPO, path)
                    dao.deleteCards(LOCAL_REPO, path)
                    dao.deleteIndex(LOCAL_REPO, path)
                }
                for ((artifact, run) in updates) {
                    dao.saveArtifact(artifact)
                    if (artifact.kind == "daily" && run != null) {
                        dao.saveCards(run.cards.map { CardRow(LOCAL_REPO, artifact.path, it.id, contractJson.encodeToString(it)) })
                        dao.indexCards(run.cards.map { card ->
                            val evidence = (card.techSignals + card.demandSignals + card.competitors).map { it.note } +
                                card.supplementRefs.map { it.note }
                            CardSearch(LOCAL_REPO, artifact.path, card.id, card.title, card.oneLiner, evidence.joinToString(" "))
                        })
                    }
                }
                dao.saveState(SyncState(LOCAL_REPO, "", null, Instant.now().toString(),
                    if (invalid.isEmpty()) "OK" else "PARTIAL", invalid.joinToString("\n").ifEmpty { null }))
            }
        }
    }

    suspend fun trackingUpdates(date: java.time.LocalDate): Int = withContext(Dispatchers.IO) {
        val file = paths.outDir.resolve("$date.json")
        if (!file.isFile) 0 else contractJson.decodeFromString<PipelineResult>(file.readText()).trackingUpdates.size
    }

    // Keeps the existing debug preview interactions independent of the on-device data.
    suspend fun enqueueSample(repo: String, requests: List<InboxRequest>) = database.withTransaction {
        requests.forEach {
            val reaction = it as? InboxRequest.Reaction
            dao.insertRequest(RequestRow(repo, it.requestId, "", contractJson.encodeToString(it), it.createdAt,
                cardId = reaction?.cardId, reaction = reaction?.kind?.name))
        }
    }

    private data class Entry(val artifact: Artifact, val run: PipelineResult? = null)

    private fun decode(artifact: Artifact): Entry {
        if (artifact.kind == "prompt") return Entry(artifact)
        val json = artifact.json
        return when (artifact.kind) {
            "daily", "run" -> {
                val element = contractJson.parseToJsonElement(json)
                if (artifact.kind == "run" && element is kotlinx.serialization.json.JsonObject && "report" !in element) {
                    contractJson.decodeFromString<RunReport>(json)
                    Entry(artifact)
                } else Entry(artifact, run = contractJson.decodeFromString<PipelineResult>(json))
            }
            else -> {
                when (artifact.kind) {
                    "settings" -> when (artifact.path) {
                        "settings/budget.json" -> contractJson.decodeFromString<collector.budget.BudgetSettings>(json)
                        else -> contractJson.decodeFromString<collector.data.ScheduleSettings>(json)
                    }
                    "tracking" -> contractJson.decodeFromString<List<TrackedIdea>>(json)
                    "feedback" -> contractJson.decodeFromString<List<Feedback>>(json)
                    "trend" -> contractJson.decodeFromString<TrendReport>(json)
                    else -> contractJson.decodeFromString<JsonElement>(json)
                }
                Entry(artifact)
            }
        }
    }

    private fun kind(path: String): String? = when {
        path.removePrefix("config/prompts/").removeSuffix(".md") in collector.prompt.PromptCatalog.entries &&
            path.startsWith("config/prompts/") && path.endsWith(".md") -> "prompt"
        path.startsWith("config/prompts/") && path.endsWith(".versions.json") &&
            path.removePrefix("config/prompts/").removeSuffix(".versions.json") in collector.prompt.PromptCatalog.entries -> "prompt-history"
        !path.endsWith(".json") -> null
        daily.matches(path) -> "daily"
        path == "out/tracked.json" -> "tracking"
        path == "out/feedback.json" -> "feedback"
        path.startsWith("out/runs/scheduled/") -> "scheduled-log"
        path.startsWith("out/runs/") -> "run"
        path == "settings/budget.json" || path == "settings/schedule.json" -> "settings"
        path.startsWith("out/batch/public/") -> "batch"
        path == "out/trend/themes.json" -> "themes"
        path.startsWith("out/trend/") -> "trend"
        path.startsWith("out/analysis/") -> "analysis"
        path.startsWith("out/cost-ledger/") || path.startsWith("out/cost-ledger-analysis/") -> "cost"
        path.startsWith("config/") && path.endsWith(".local.json") -> "settings-local"
        else -> null
    }
}

package collector.data

import collector.budget.BudgetSettings
import collector.config.PipelineConfig
import collector.dev.FileHistorySearch
import collector.dev.JsonFileTrackedStore
import collector.dev.outputJson
import collector.dev.readJson
import collector.dev.writeAtomically
import collector.model.Feedback
import collector.model.TrackedIdea
import collector.prompt.FilePromptStore
import collector.prompt.PromptCatalog
import collector.prompt.PromptResolver
import collector.stage.TrackingKeywords
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Clock
import java.time.Instant
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

internal val requestIdPattern = Regex(UUID_PATTERN)
private val themeIdPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}")
internal val inboxJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

internal fun resultFile(out: File, id: String): File {
    require(requestIdPattern.matches(id) || invalidIdPattern.matches(id))
    return out.resolve("${DataBranch.RESULTS}/$id.json")
}

internal val invalidIdPattern = Regex("invalid-[0-9a-f]{16}")

internal fun saveResult(out: File, result: InboxResult) {
    resultFile(out, result.requestId).writeAtomically(inboxJson.encodeToString(result))
}

internal fun validateAnalysis(themes: List<String>, maxUsd: Double) {
    require(themes.size in 1..3 && themes.distinct().size == themes.size && themes.all(themeIdPattern::matches))
    require(maxUsd.isFinite() && maxUsd >= 0)
}

internal class InboxProcessor(private val out: File, prompts: File, private val clock: Clock) {
    private val promptStore = FilePromptStore(prompts.toPath())
    private val trackedStore = JsonFileTrackedStore(out.resolve("tracked.json"))

    fun process(inbox: File) {
        require(!Files.isSymbolicLink(inbox.toPath()))
        if (!inbox.exists()) return
        check(inbox.isDirectory)
        val namePattern = Regex("""[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}[:-]?[0-9]{2}[:-]?[0-9]{2}(?:\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:?[0-9]{2})-($UUID_PATTERN)\.json""")
        var failures = 0
        inbox.listFiles()?.sortedBy { it.name }?.forEach { file ->
            val namedId = namePattern.matchEntire(file.name)?.groupValues?.get(1)
            val regular = Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)
            try {
                val bytes = if (regular) file.readBytes() else byteArrayOf()
                val result = try {
                    require(regular && namedId != null)
                    val request = inboxJson.decodeFromString<InboxRequest>(bytes.toString(Charsets.UTF_8))
                    require(request.requestId == namedId && request.schemaVersion == DataBranch.VERSION)
                    if (resultFile(out, namedId).isFile) null else {
                        try {
                            apply(request, Instant.parse(request.createdAt))
                            InboxResult(requestId = namedId, ok = true)
                        } catch (e: IllegalArgumentException) {
                            InboxResult(requestId = namedId, ok = false, code = "rejected",
                                reason = if (e is PromptBaseMismatch) "Prompt baseHash does not match" else "Inbox request validation failed")
                        } catch (_: Exception) {
                            InboxResult(requestId = namedId, ok = false, code = "internal_error",
                                reason = "Inbox changes could not be saved")
                        }
                    }
                } catch (_: Exception) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    digest.update(bytes)
                    val hash = digest.digest(file.name.toByteArray(Charsets.UTF_8))
                        .take(8).joinToString("") { "%02x".format(it) }
                    InboxResult(requestId = "invalid-$hash", ok = false,
                        reason = "Inbox request could not be processed", code = "internal_error")
                }
                if (result != null && !resultFile(out, result.requestId).isFile) {
                    saveResult(out, result.copy(processedAt = clock.instant().toString()))
                }
                Files.walk(file.toPath()).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
                }
            } catch (_: Exception) {
                failures++
            }
        } ?: error("Could not list inbox")
        check(failures == 0) { "$failures inbox entries could not be recorded or removed" }
    }

    private fun apply(request: InboxRequest, at: Instant) {
        when (request) {
            is InboxRequest.Reaction -> {
                require(request.cardId.isNotBlank() && request.reason.isNotBlank() && (request.memo?.length ?: 0) <= 500)
                val file = out.resolve("feedback.json")
                val feedback = Feedback(request.cardId, request.kind, request.reason, request.memo, at)
                val prior = readJson<List<Feedback>>(file).orEmpty()
                file.writeAtomically(outputJson.encodeToString(listOf(feedback) + prior.filterNot { it == feedback }))
            }
            is InboxRequest.TrackStart -> {
                val ideas = trackedStore.load()
                val existing = ideas.firstOrNull { it.cardId == request.cardId }
                val config = PipelineConfig.load()
                if (existing == null || existing.status == TrackedIdea.Status.STOPPED) {
                    require(ideas.count { it.status != TrackedIdea.Status.STOPPED } < config.tracking.maxTracked)
                }
                if (existing != null) {
                    trackedStore.save(ideas.map { if (it.id == existing.id) it.copy(status = TrackedIdea.Status.TRACKING) else it })
                } else {
                    val history = FileHistorySearch(out, config.historyDays)
                    val run = history.cardRun(request.cardId)
                    val card = requireNotNull(run?.cards?.firstOrNull { it.id == request.cardId })
                    val competitors = run.classified.filter { signal -> card.competitors.any { it.itemId == signal.item.key } }
                        .map { it.classification.topicLabel }.distinct()
                    trackedStore.save(ideas + TrackedIdea(request.requestId, card.id, card.title, card.category,
                        TrackingKeywords.extract(card, run.classified), competitors, TrackedIdea.Status.TRACKING,
                        at, null, 0.0, TrackedIdea.Trend.FLAT))
                }
            }
            is InboxRequest.TrackStop -> editTrack(request.trackId) { it.copy(status = TrackedIdea.Status.STOPPED) }
            is InboxRequest.TrackKeywords -> {
                require(request.keywords.size <= 10 && request.keywords.all { it.isNotBlank() && it.length <= 50 })
                editTrack(request.trackId) { it.copy(keywords = request.keywords.distinct()) }
            }
            is InboxRequest.PromptSet -> {
                require(request.body.length <= 20_000)
                checkBase(request.promptId, request.baseHash)
                if (promptStore.get(request.promptId)?.text != request.body) promptStore.save(request.promptId, request.body)
            }
            is InboxRequest.PromptReset -> {
                checkBase(request.promptId, request.baseHash)
                promptStore.reset(request.promptId)
            }
            is InboxRequest.SettingsSet -> {
                require(request.budget != null || request.schedule != null)
                request.budget?.let { BudgetSettings.update(out, it) }
                request.schedule?.let {
                    out.resolve("settings/schedule.json").writeAtomically(inboxJson.encodeToString(it))
                }
            }
            is InboxRequest.Analysis -> {
                validateAnalysis(request.themeIds, request.maxUsd)
                out.resolve("${DataBranch.ANALYSIS_QUEUE}/${request.requestId}.json")
                    .writeAtomically(inboxJson.encodeToString<InboxRequest>(request))
            }
        }
    }

    private fun checkBase(id: String, hash: String) {
        val current = promptStore.get(id)?.text ?: PromptCatalog.defaultText(id)
        if (PromptResolver.hash(current) != hash) throw PromptBaseMismatch()
    }

    private class PromptBaseMismatch : IllegalArgumentException()

    private fun editTrack(id: String, edit: (TrackedIdea) -> TrackedIdea) {
        val ideas = trackedStore.load()
        require(ideas.any { it.id == id })
        trackedStore.save(ideas.map { if (it.id == id) edit(it) else it })
    }
}

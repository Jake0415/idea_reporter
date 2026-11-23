package app.ideacollector.data

import androidx.room.withTransaction
import app.ideacollector.github.ArtifactMismatch
import app.ideacollector.github.GitHubClient
import app.ideacollector.github.GitHubFailure
import app.ideacollector.github.FILE_LIMIT
import app.ideacollector.github.SYNC_LIMIT
import app.ideacollector.github.UnsupportedSchema
import kotlinx.coroutines.CancellationException
import app.ideacollector.github.FileSizeExceeded
import app.ideacollector.github.contractJson
import app.ideacollector.github.decodeVersioned
import app.ideacollector.github.inboxPath
import app.ideacollector.github.sha256
import collector.data.DataBranch
import collector.data.DataManifest
import collector.data.InboxRequest
import collector.data.InboxResult
import collector.model.Feedback
import collector.model.RunReport
import collector.model.TrackedIdea
import collector.pipeline.PipelineResult
import collector.trend.TrendReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.time.Clock
import java.time.Instant

private val daily = Regex("out/[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")
private val results = "${DataBranch.OUT}/${DataBranch.RESULTS}/"
private const val MAX_ARTIFACT_ATTEMPTS = 3

internal data class SyncNews(val cards: Int?, val updates: Int, val analysis: Boolean, val updateRequired: Boolean = false)

@Serializable
private data class ArtifactFailure(val sha256: String, val attempts: Int, val reason: String)

internal class RepositorySync(
    private val database: AppDatabase,
    private val github: GitHubClient,
    private val clock: Clock = Clock.systemUTC()
) {
    private val dao = database.dao()
    // Opening the app and periodic work can request a sync simultaneously.
    private val syncing = Mutex()
    private val sending = Mutex()

    suspend fun sync(repo: String, token: String?): SyncNews = syncing.withLock {
        withContext(Dispatchers.IO) {
            val oldState = dao.state(repo)
            val commitSha = github.dataHead(repo, token)
            val snapshot = oldState?.takeIf { it.commitSha == commitSha }
            val etag = oldState?.takeIf {
                it.status == "OK" && (snapshot != null || (it.commitSha != null && it.issue == null))
            }?.etag
            val remote = github.manifest(repo, token, etag, commitSha)
            val bytes = remote.bytes
            val now = clock.instant().toString()
            if (bytes == null) {
                dao.saveState(checkNotNull(oldState).copy(syncedAt = now, commitSha = commitSha))
                return@withContext SyncNews(null, 0, false)
            }

            val manifest = try {
                decodeVersioned<DataManifest>(bytes.decodeToString())
            } catch (_: UnsupportedSchema) {
                return@withContext updateRequired(repo)
            }
            require(manifest.schemaVersion == DataBranch.VERSION)
            require(manifest.files.distinctBy { it.path }.size == manifest.files.size)
            val manifestSha = bytes.sha256()
            val resume = snapshot?.takeIf { it.manifestSha == manifestSha }
            val pending = resume?.remainingFiles?.let { contractJson.decodeFromString<List<String>>(it) }
            val oldFiles = dao.hashes(repo).associateBy { it.path }
            val selected = manifest.files.mapNotNull { file -> kind(file.path)?.let { file to it } }
            val byPath = selected.associateBy { it.first.path }
            val failures = resume?.fileFailures?.let {
                contractJson.decodeFromString<Map<String, ArtifactFailure>>(it).toMutableMap()
            } ?: mutableMapOf()
            val candidates = pending?.mapNotNull(byPath::get) ?: selected
            var remaining = SYNC_LIMIT - bytes.size
            val deferred = mutableListOf<String>()
            val retry = mutableListOf<String>()
            val staged = mutableListOf<Pair<Artifact, java.io.File>>()
            try {
                for ((file, kind) in candidates) {
                    val previous = failures[file.path]?.takeIf { it.sha256 == file.sha256 }
                    if (oldFiles[file.path]?.sha256 == file.sha256 ||
                        (previous?.attempts ?: 0) >= MAX_ARTIFACT_ATTEMPTS) continue
                    if (file.size !in 0..FILE_LIMIT) {
                        failures[file.path] = ArtifactFailure(file.sha256, MAX_ARTIFACT_ATTEMPTS, "파일 크기 상한 초과")
                        continue
                    }
                    if (file.size > remaining) {
                        deferred += file.path
                        continue
                    }
                    val temp = java.io.File.createTempFile("collector-sync-", ".json")
                    remaining -= file.size
                    try {
                        github.file(repo, file.path, token, temp, file.size, file.sha256, commitSha)
                        staged += Artifact(repo, file.path, kind, file.sha256, "") to temp
                    } catch (e: Exception) {
                        temp.delete()
                        if (e !is FileSizeExceeded && e !is ArtifactMismatch && (e as? GitHubFailure)?.status != 404) throw e
                        val attempts = (previous?.attempts ?: 0) + 1
                        failures[file.path] = ArtifactFailure(file.sha256, attempts, checkNotNull(e.message))
                        if (attempts < MAX_ARTIFACT_ATTEMPTS) retry += file.path
                    }
                }
                var currentDaily: PipelineResult? = null
                var analysis = false
                sending.withLock {
                    database.withTransaction {
                        for (path in oldFiles.keys - byPath.keys) delete(repo, path)
                        for ((metadata, temp) in staged) {
                            val entry = try {
                                decode(metadata.copy(json = temp.readText()))
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                failures[metadata.path] =
                                    ArtifactFailure(metadata.sha256, MAX_ARTIFACT_ATTEMPTS, "디코딩 실패")
                                continue
                            }
                            val artifact = entry.artifact
                            failures.remove(artifact.path)
                            if (artifact.path == "out/" + manifest.latestDaily + ".json") currentDaily = entry.run
                            analysis = analysis || artifact.kind == "analysis" ||
                                (entry.result?.ok == true && entry.result.code == "analysis_complete")
                            delete(repo, artifact.path)
                            dao.saveArtifact(artifact)
                            if (artifact.kind == "daily") {
                                val cards = checkNotNull(entry.run).cards
                                dao.saveCards(cards.map { CardRow(repo, artifact.path, it.id, contractJson.encodeToString(it)) })
                                dao.indexCards(cards.map { card ->
                                    val evidence = (card.techSignals + card.demandSignals + card.competitors).map { it.note } +
                                        card.supplementRefs.map { it.note }
                                    CardSearch(repo, artifact.path, card.id, card.title, card.oneLiner, evidence.joinToString(" "))
                                })
                            }
                            entry.result?.let { applyResult(repo, it) }
                        }
                        val remainingFiles = deferred + retry
                        dao.saveState(SyncState(repo, manifestSha, remote.etag.takeIf { remainingFiles.isEmpty() }, now,
                            status = if (remainingFiles.isNotEmpty()) "PARTIAL" else "OK",
                            issue = failures.entries.takeIf { it.isNotEmpty() }
                                ?.joinToString("\n") { (path, failure) -> path + ": " + failure.reason },
                            remainingFiles = remainingFiles.takeIf { it.isNotEmpty() }?.let { contractJson.encodeToString(it) },
                            commitSha = commitSha,
                            fileFailures = failures.takeIf { it.isNotEmpty() }?.let { contractJson.encodeToString(it) }))
                    }
                }
                SyncNews(currentDaily?.cards?.size, currentDaily?.trackingUpdates?.size ?: 0, analysis)
            } finally {
                staged.forEach { (_, file) -> file.delete() }
            }
        }
    }

    suspend fun updateRequired(repo: String): SyncNews = database.withTransaction {
        val previous = dao.state(repo)
        val first = previous?.status != "APP_UPDATE_REQUIRED"
        val current = previous ?: SyncState(repo, "", null, clock.instant().toString())
        dao.saveState(current.copy(status = "APP_UPDATE_REQUIRED"))
        SyncNews(null, 0, false, updateRequired = first)
    }

    suspend fun enqueue(repo: String, request: InboxRequest) = sending.withLock {
        require(dao.settings()?.repository?.let { it == repo } != false)
        val payload = contractJson.encodeToString<InboxRequest>(request)
        val reaction = request as? InboxRequest.Reaction
        dao.insertRequest(RequestRow(repo, request.requestId, inboxPath(request), payload, request.createdAt,
            cardId = reaction?.cardId, reaction = reaction?.kind?.name))
    }

    fun reaction(repo: String, cardId: String) = combine(
        dao.reactions(repo, cardId), dao.observeArtifacts(repo, "feedback")
    ) { local, artifacts ->
        val latest = local.firstOrNull()
        val remote = artifacts.firstOrNull()?.let { contractJson.decodeFromString<List<Feedback>>(it.json) }
            ?.firstOrNull { it.cardId == cardId }
        val remoteAt = remote?.at
        if (latest != null && (latest.status != "APPLIED" || remoteAt == null || remoteAt < Instant.parse(latest.createdAt))) {
            latest.reaction?.let(Feedback.Kind::valueOf)
        } else remote?.kind
    }

    fun authCount(repo: String) = dao.authCount(repo)

    suspend fun changeRepository(settings: AppSettings): Int = sending.withLock {
        database.withTransaction {
            dao.saveSettings(settings)
            dao.cancelOtherRepositories(settings.repository)
        }
    }

    suspend fun restoreAuth() = sending.withLock { dao.restoreAuth() }

    suspend fun send(repo: String, token: String?, checkSent: Boolean = false): Boolean = send(repo, checkSent) { token }

    suspend fun send(repo: String, checkSent: Boolean = false, readToken: suspend () -> String?): Boolean = sending.withLock {
        withContext(Dispatchers.IO) {
            if (dao.settings()?.repository?.let { it != repo } == true) return@withContext true
            val token = readToken()
            var done = true
            for (candidate in dao.sendCandidates(repo)) {
                var row = candidate
                var postingDispatch = false
                val now = clock.instant().epochSecond
                if ((row.status == "SENT") != checkSent || (checkSent && row.dispatchedAt == null)) continue
                if (row.nextAttemptAt?.let { it > now } == true) {
                    if (row.status != "SENT") done = false
                    continue
                }
                if (token.isNullOrBlank()) {
                    dao.saveRequest(row.copy(status = "NEEDS_AUTH", reason = "토큰 확인 필요"))
                    continue
                }
                try {
                    val request = contractJson.decodeFromString<InboxRequest>(row.json)
                    val result = github.result(repo, row.requestId, token)
                    if (result != null) {
                        applyResult(repo, result)
                        if (request !is InboxRequest.Analysis || result.status == "running" || result.analysisFinished()) continue
                    }
                    val dispatched = row.dispatchedAt
                    if (dispatched != null) {
                        val wait = if (row.status == "SENT") 1800 else 600
                        if (now - dispatched < wait) {
                            if (row.status != "SENT") done = false
                            continue
                        }
                        val dispatchState = try {
                            github.analysisDispatchState(repo, row.requestId, token, dispatched)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            GitHubClient.DispatchState.UNKNOWN
                        }
                        if (dispatchState != GitHubClient.DispatchState.RETRYABLE) {
                            dao.saveRequest(row.copy(status = "SENT", nextAttemptAt = null,
                                reason = if (dispatchState == GitHubClient.DispatchState.UNKNOWN)
                                    "분석 요청 상태 확인 필요" else null))
                            continue
                        }
                    }
                    if (!row.uploaded && result == null) {
                        github.upload(repo, row.path, token, row.json, row.requestId)
                        dao.uploaded(repo, row.requestId)
                        row = row.copy(uploaded = true)
                    }
                    if (request is InboxRequest.Analysis) {
                        row = row.copy(status = "DISPATCHING", dispatchedAt = now)
                        dao.saveRequest(row)
                        postingDispatch = true
                        github.dispatchAnalysis(repo, row.requestId, token)
                        postingDispatch = false
                    }
                    dao.saveRequest(row.copy(status = "SENT", reason = null, nextAttemptAt = null))
                } catch (e: IOException) {
                    var failed = failureState(row, e, now)
                    // GitHub answered the dispatch with an explicit 4xx (401/403/429...), so no run exists.
                    // Clear dispatchedAt so the same request is POSTed again after re-auth or Retry-After,
                    // instead of polling forever for a run that was never created. Ambiguous failures
                    // (no response, 5xx) keep it so a possibly accepted dispatch is not sent twice.
                    if (postingDispatch && (e as? GitHubFailure)?.status in 400..499) {
                        failed = failed.copy(dispatchedAt = null)
                    }
                    dao.saveRequest(failed)
                    if (failed.status in setOf("PENDING", "DISPATCHING")) done = false
                } catch (e: UnsupportedSchema) {
                    // A result written by a newer server must not stall the requests queued behind it:
                    // park only this request (it is no longer a send candidate) and keep going.
                    dao.saveRequest(row.copy(status = "APP_UPDATE_REQUIRED", reason = "앱 업데이트 필요", nextAttemptAt = null))
                } catch (e: IllegalArgumentException) {
                    dao.complete(repo, row.requestId, "FAILED", e.message)
                }
            }
            done
        }
    }

    private fun failureState(row: RequestRow, error: IOException, now: Long): RequestRow {
        val response = error as? GitHubFailure
        val status = response?.status
        val limited = status == 429 || response?.rateLimited == true
        val attempts = row.attempts + 1
        val reason = error.message ?: "전송 실패"
        if (!limited && (status == 401 || status == 403))
            return row.copy(status = "NEEDS_AUTH", reason = "토큰 확인 필요")
        if ((!limited && status != null && status < 500) || attempts >= 5)
            return row.copy(status = "FAILED", attempts = attempts, reason = reason)
        val retryAfter = response?.retryAfter?.let { value ->
            value.toLongOrNull()?.let { now + it } ?: runCatching {
                java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
            }.getOrNull()
        }
        val retryAt = if (limited) maxOf(now + 30, retryAfter ?: now, response.reset?.toLongOrNull() ?: now) else null
        return row.copy(attempts = attempts, reason = reason, nextAttemptAt = retryAt)
    }

    private suspend fun applyResult(repo: String, result: InboxResult) {
        require(result.schemaVersion == DataBranch.VERSION)
        val request = dao.request(repo, result.requestId) ?: return
        if (request.status in setOf("CANCELLED", "FAILED")) return
        if (contractJson.decodeFromString<InboxRequest>(request.json) is InboxRequest.Analysis && !result.analysisFinished()) {
            // An accepted inbox entry is only an acknowledgement for analysis.
            if (result.status == "running") dao.saveRequest(request.copy(status = "SENT", dispatchedAt = null))
            return
        }
        dao.complete(repo, result.requestId, if (result.ok) "APPLIED" else "REJECTED", result.reason)
    }

    private fun InboxResult.analysisFinished() = status != "running" &&
        ((!ok && code != "analysis_pending") || code == "analysis_complete")

    private suspend fun delete(repo: String, path: String) {
        dao.deleteIndex(repo, path)
        dao.deleteCards(repo, path)
        dao.deleteArtifact(repo, path)
    }

    private data class Entry(val artifact: Artifact, val run: PipelineResult? = null, val result: InboxResult? = null)

    private fun decode(artifact: Artifact): Entry {
        val json = artifact.json
        return when (artifact.kind) {
            "daily", "run" -> {
                val element = contractJson.parseToJsonElement(json)
                if (artifact.kind == "run" && element is kotlinx.serialization.json.JsonObject && "report" !in element) {
                    contractJson.decodeFromString<RunReport>(json)
                    Entry(artifact)
                } else Entry(artifact, run = contractJson.decodeFromString<PipelineResult>(json))
            }
            "result" -> {
                val result = decodeVersioned<InboxResult>(json)
                require(artifact.path == "$results${result.requestId}.json" && result.schemaVersion == DataBranch.VERSION)
                Entry(artifact, result = result)
            }
            else -> {
                when (artifact.kind) {
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
        !path.endsWith(".json") -> null
        daily.matches(path) -> "daily"
        path == "out/tracked.json" -> "tracking"
        path == "out/feedback.json" -> "feedback"
        path.startsWith("out/runs/") -> "run"
        path.startsWith("out/trend/") && path != "out/trend/themes.json" -> "trend"
        path.startsWith("out/analysis/") -> "analysis"
        path.startsWith("out/cost-ledger/") || path.startsWith("out/cost-ledger-analysis/") -> "cost"
        path.startsWith(results) -> "result"
        else -> null
    }
}

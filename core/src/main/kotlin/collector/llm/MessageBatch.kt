package collector.llm

import collector.config.PipelineConfig
import collector.dev.writeAtomically
import collector.model.SupplementRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

internal data class BatchOutcome(
    val responses: Map<Int, Result<LlmResult>> = emptyMap(),
    val issues: List<String> = emptyList(),
    val waitMillis: Long = 0,
    val synchronousFallback: Boolean = false,
    val pending: Boolean = false,
    val itemResponses: List<Pair<List<String>, LlmResult>> = emptyList(),
    val supplementRefs: List<SupplementRef> = emptyList()
)

internal data class GenerationBatchResume(val requestWithoutSupplement: LlmRequest, val supplementRefs: List<SupplementRef>,
                                          val saveInput: Boolean = false)

internal const val CLASSIFICATION_BATCH_PENDING = "분류 Batch 대기 — 다음 실행에서 이어서"

@Serializable
private data class BatchCheckpoint(
    val batchId: String? = null,
    val requests: Map<String, SavedRequest>,
    val submittedAt: String,
    val call: LlmCall,
    val runId: String? = null,
    val firstMissingKeyAt: String? = null,
    val notFoundRuns: Int = 0,
    val rejected: Boolean = false,
    val settled: Boolean = false,
    // Keep reading the old timestamp for checkpoints written before firstSettleFailureAt.
    val settleRetry: String? = null,
    val firstSettleFailureAt: String? = null,
    val resultsIncomplete: Boolean = false,
    val quarantineReason: String? = null,
    val unsettledIds: Set<String>? = null,
    val supplementKeys: Set<String> = emptySet(),
    val supplementRefs: List<SupplementRef> = emptyList(),
    val generationInput: LlmRequest? = null
)

@Serializable
private data class SavedRequest(
    val fingerprint: String,
    val reservation: CostGuard.Reservation,
    val request: LlmRequest? = null,
    val itemKeys: List<String> = emptyList()
)

@Serializable
private data class ResultRow(
    val customId: String,
    val result: LlmResult? = null,
    val error: String? = null,
    val charge: LlmCall? = null
)

internal val publicBatchCheckpointName = Regex("""\d{4}-\d{2}-\d{2}-(classify|generate|backfill)\.json""")
internal val publicBatchRetryName = Regex(publicBatchCheckpointName.pattern + """\.retry\.json""")
internal val publicBatchUnsettledName = Regex("""quarantine/[A-Za-z0-9_-]+\.unsettled\.json""")

internal fun publicBatchCheckpoint(text: String): String {
    val checkpoint = Json.decodeFromString<BatchCheckpoint>(text)
    return Json.encodeToString(checkpoint.copy(
        requests = checkpoint.requests.mapValues { (_, saved) -> saved.copy(request = null) },
        call = checkpoint.call.copy(error = null),
        supplementKeys = emptySet(),
        generationInput = null,
        supplementRefs = checkpoint.supplementRefs.map { it.copy(note = "") }
    ))
}

private fun LlmRequest.fingerprint(): String = MessageDigest.getInstance("SHA-256")
    .digest(toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun batchPath(out: File, date: String, stage: String) =
    out.resolve("batch/${LocalDate.parse(date)}-$stage.json")

// A settled checkpoint stays here until its caller saves the report that uses the result.
private fun File.completed() = resolveSibling("$nameWithoutExtension.done")
private fun File.results(id: String): File {
    require(id.matches(Regex("[a-zA-Z0-9_-]+")))
    return resolveSibling("$id.results.jsonl")
}

@Serializable
private data class BatchIndexEntry(val batchId: String, val resultFile: String, val checkpoint: BatchCheckpoint)

private fun File.batchIndex() = resolveSibling("index.json")

private fun File.readBatchIndex(): Map<String, BatchIndexEntry> {
    if (!isFile) return emptyMap()
    return try {
        Json.decodeFromString(readText())
    } catch (_: kotlinx.serialization.SerializationException) {
        Files.move(toPath(), resolveSibling("$name.corrupt-${Instant.now().toEpochMilli()}").toPath())
        val files = parentFile.listFiles().orEmpty()
        val results = files.filter { it.isFile && it.name.endsWith(".results.jsonl") }.map { it.name }.toSet()
        // Sidecar entries cover results whose checkpoint pointer is already gone (the index was their only pointer).
        val sidecars = files.filter { it.isFile && it.name.endsWith(".index-entry.json") }
            .mapNotNull { runCatching { Json.decodeFromString<IndexSidecar>(it.readText()) }.getOrNull() }
            .filter { it.entry.resultFile in results }
            .associate { it.key to it.entry }
        val rebuilt = sidecars + files.filter { it.isFile && (it.name.endsWith("-backfill.done") || it.name.endsWith("-backfill.json")) }
            .sortedBy { it.extension == "json" }
            .mapNotNull { pointer ->
                val checkpoint = runCatching { Json.decodeFromString<BatchCheckpoint>(pointer.readText()) }.getOrNull()
                val id = checkpoint?.batchId ?: return@mapNotNull null
                val resultFile = "$id.results.jsonl"
                if (!checkpoint.settled || resultFile !in results) return@mapNotNull null
                pointer.indexKey() to checkpoint.indexEntry(id, resultFile)
            }.toMap()
        writeAtomically(Json.encodeToString(rebuilt))
        rebuilt
    }
}

private fun File.indexKey() = nameWithoutExtension.drop(11) + "/" + name.take(10)

@Serializable
private data class IndexSidecar(val key: String, val entry: BatchIndexEntry)

private fun File.indexSidecar(id: String) = resolveSibling("$id.index-entry.json")

private fun File.indexResult(id: String, checkpoint: BatchCheckpoint) {
    val index = batchIndex()
    val entry = checkpoint.indexEntry(id, results(id).name)
    val sidecar = indexSidecar(id)
    if (!sidecar.isFile) sidecar.writeAtomically(Json.encodeToString(IndexSidecar(indexKey(), entry)))
    val entries = index.readBatchIndex()
    if (entries[indexKey()] != entry) index.writeAtomically(Json.encodeToString(entries + (indexKey() to entry)))
}

private fun BatchCheckpoint.indexEntry(id: String, resultFile: String) =
    BatchIndexEntry(id, resultFile, copy(requests = requests.mapValues { (_, request) -> request.copy(request = null) }))

private fun BatchCheckpoint.settleFailureAt() = (firstSettleFailureAt ?: settleRetry)?.let(Instant::parse)

private fun File.savedResults(id: String): File {
    if (!name.endsWith("-backfill.json")) return results(id)
    val entry = runCatching { batchIndex().readBatchIndex()[indexKey()] }.getOrNull()
    return if (entry?.batchId == id && entry.resultFile == results(id).name) resolveSibling(entry.resultFile)
    else results(id)
}

internal fun markBatchConsumed(path: File) {
    if (!path.isFile) return
    val checkpoint = Json.decodeFromString<BatchCheckpoint>(path.readText())
    if (!checkpoint.settled || checkpoint.batchId?.let { path.savedResults(it).isFile } != true) return
    path.completed().writeAtomically(Json.encodeToString(checkpoint))
    Files.deleteIfExists(path.toPath())
}

private fun File.indexedCheckpoint(): BatchCheckpoint? {
    if (!name.endsWith("-backfill.json")) return null
    val entry = runCatching { batchIndex().readBatchIndex()[indexKey()] }.getOrNull() ?: return null
    return entry.checkpoint.takeIf { it.batchId == entry.batchId && savedResults(entry.batchId).isFile }
}

internal fun savedGenerationInput(out: File, date: String, withoutSupplement: (LlmRequest) -> LlmRequest): LlmRequest? {
    val path = batchPath(out, date, "generate")
    val saved = path.takeIf { it.isFile } ?: path.completed().takeIf { it.isFile } ?: return null
    return runCatching {
        val checkpoint = Json.decodeFromString<BatchCheckpoint>(saved.readText())
        checkpoint.generationInput ?: checkpoint.requests.values.singleOrNull()?.request?.let(withoutSupplement)
    }.getOrNull()
}

internal fun hasSavedBatch(out: File, date: String, stage: String): Boolean =
    batchPath(out, date, stage).let { it.isFile || it.completed().isFile || it.indexedCheckpoint() != null }

internal fun hasLocalBatchResults(path: File): Boolean =
    path.isFile && runCatching {
        val checkpoint = Json.decodeFromString<BatchCheckpoint>(path.readText())
        !checkpoint.rejected && !checkpoint.resultsIncomplete && checkpoint.quarantineReason == null &&
            checkpoint.batchId?.let { path.savedResults(it).isFile } == true
    }.getOrDefault(false)

internal fun hasSettledBatchResults(path: File): Boolean =
    listOf(path, path.completed()).any { file ->
        hasLocalBatchResults(file) && runCatching {
            Json.decodeFromString<BatchCheckpoint>(file.readText()).settled
        }.getOrDefault(false)
    }

internal fun isPendingBatch(path: File): Boolean =
    path.isFile && runCatching {
        val checkpoint = Json.decodeFromString<BatchCheckpoint>(path.readText())
        !checkpoint.rejected && (checkpoint.quarantineReason != null || checkpoint.resultsIncomplete ||
            checkpoint.batchId?.let { path.results(it).isFile } != true)
    }.getOrDefault(true)

internal suspend fun batchInProgress(path: File, provider: BatchCapableProvider): Boolean {
    if (!isPendingBatch(path)) return false
    val checkpoint = runCatching { Json.decodeFromString<BatchCheckpoint>(path.readText()) }.getOrNull()
        ?: return false
    val id = checkpoint.batchId ?: return false
    if (checkpoint.quarantineReason != null || checkpoint.resultsIncomplete) return false
    return try {
        !provider.retrieveBatch(id).ended
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false // Normal recovery handles provider errors and missing batches.
    }
}

internal suspend fun runMessageBatch(
    provider: BatchCapableProvider?, guard: CostGuard, call: LlmCall?, path: File,
    config: PipelineConfig.Batch, requests: List<LlmRequest>, projections: List<Double>,
    generationResume: GenerationBatchResume?, itemKeys: List<List<String>>,
    remainingMillis: () -> Long,
    pollRemainingMillis: () -> Long = { (remainingMillis() - 300_000L).coerceAtLeast(0) },
    recoveryRemainingMillis: () -> Long = { Long.MAX_VALUE },
    recovering: Boolean = false,
    retrySettlement: Boolean = true,
    stateHook: () -> Boolean = { true },
    deferBatch: Boolean = false
): BatchOutcome {
    val started = System.nanoTime()
    val completed = path.completed()
    val indexed = if (!path.isFile && !completed.isFile) path.indexedCheckpoint() else null
    val resumed = path.isFile || completed.isFile || indexed != null
    val issues = mutableListOf<String>()
    fun elapsed() = (System.nanoTime() - started) / 1_000_000
    suspend fun pending(issue: String, checkpoint: BatchCheckpoint): BatchOutcome {
        for (saved in checkpoint.requests.values) {
            try {
                guard.estimatePendingBatch(listOf(saved.reservation))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                issues += "Batch reservation settlement failed: " + saved.reservation.id
            }
        }
        return BatchOutcome(issues = issues + issue, waitMillis = elapsed(), pending = true, supplementRefs = checkpoint.supplementRefs)
    }
    fun fallback(issue: String) = BatchOutcome(issues = issues + issue, synchronousFallback = true)
    suspend fun <T> bounded(block: suspend () -> T): T =
        withTimeoutOrNull(minOf(remainingMillis(), recoveryRemainingMillis())) { block() } ?: throw LlmServiceException(reason = "timeout")

    val fingerprints = requests.map { request ->
        (generationResume?.requestWithoutSupplement?.copy(model = request.model) ?: request).fingerprint()
    }
    val retryFile = path.resolveSibling(path.name + ".retry.json")
    fun save(checkpoint: BatchCheckpoint) = path.writeAtomically(Json.encodeToString(checkpoint))
    fun quarantineLabel(checkpoint: BatchCheckpoint?) = checkpoint?.batchId
        ?: UUID.nameUUIDFromBytes((checkpoint?.runId ?: path.name).toByteArray()).toString()

    fun quarantineFiles(checkpoint: BatchCheckpoint?, reason: String): BatchOutcome {
        val directory = path.resolveSibling("quarantine").apply { mkdirs() }
        val label = quarantineLabel(checkpoint)
        val result = checkpoint?.batchId?.let(path::results)
        for (file in listOfNotNull(result, completed, path).filter { it.isFile }) {
            val target = directory.resolve("$label-" + file.name)
            Files.move(file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            target.setLastModified(Instant.now().toEpochMilli())
        }
        Files.deleteIfExists(retryFile.toPath())
        return BatchOutcome(issues = issues + "Batch quarantine ($reason): " + path.name, synchronousFallback = true)
    }

    fun deferSettlement(checkpoint: BatchCheckpoint, failed: List<CostGuard.Reservation>, reason: String? = null): Boolean {
        val first = checkpoint.settleFailureAt() ?: Instant.now()
        save(checkpoint.copy(
            settleRetry = first.toString(), firstSettleFailureAt = first.toString(), quarantineReason = reason,
            unsettledIds = failed.map { it.id }.toSet()))
        if (Duration.between(first, Instant.now()) < Duration.ofDays(7)) return true
        path.writeUnsettled(quarantineLabel(checkpoint), failed)
        return false
    }

    suspend fun quarantine(checkpoint: BatchCheckpoint?, reason: String): BatchOutcome {
        try {
            if (path.isFile && checkpoint?.settled != true) {
                if (checkpoint != null) {
                    val failed = finishBatch(checkpoint, guard, emptyList(), "batch_quarantine", issues)
                    if (failed.isNotEmpty() && deferSettlement(checkpoint, failed, reason)) {
                        return BatchOutcome(issues = issues, pending = true)
                    }
                    if (failed.isEmpty()) save(checkpoint.copy(quarantineReason = reason, unsettledIds = emptySet()))
                } else {
                    val first = if (retryFile.isFile) Json.decodeFromString<SettlementRetry>(retryFile.readText())
                        .firstSettleFailureAt.let(Instant::parse) else Instant.now()
                    val failed = try {
                        settleQuarantined(guard, guard.batchReservations(path.name), issues)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        issues += "Batch reservation lookup failed: " + path.name
                        null
                    }
                    if (failed == null || failed.isNotEmpty()) {
                        retryFile.writeAtomically(Json.encodeToString(SettlementRetry(first.toString())))
                        if (Duration.between(first, Instant.now()) < Duration.ofDays(7)) {
                            return BatchOutcome(issues = issues, pending = true)
                        }
                        path.writeUnsettled(quarantineLabel(null), failed, path.name)
                    }
                }
            }
            return quarantineFiles(checkpoint, reason)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return BatchOutcome(issues = issues + "Batch quarantine retry: " + path.name, pending = true)
        }
    }

    suspend fun missingKey(checkpoint: BatchCheckpoint, reason: String): BatchOutcome {
        val now = Instant.now()
        val missingSince = checkpoint.firstMissingKeyAt?.let(Instant::parse) ?: now
        if (Duration.between(missingSince, now) >= Duration.ofHours(48)) return quarantine(checkpoint, reason)
        save(checkpoint.copy(firstMissingKeyAt = missingSince.toString(), notFoundRuns = 0))
        if (checkpoint.resultsIncomplete) {
            return BatchOutcome(issues = issues + "Batch $reason", waitMillis = elapsed(), pending = true)
        }
        return pending("Batch $reason", checkpoint)
    }

    suspend fun reject(checkpoint: BatchCheckpoint) {
        val rejected = checkpoint.copy(rejected = true)
        save(rejected)
        val failed = finishBatch(rejected, guard, emptyList(), "batch_rejected", issues)
        if (failed.isEmpty()) {
            Files.deleteIfExists(path.toPath())
        } else if (!deferSettlement(rejected, failed)) {
            issues += quarantineFiles(rejected, "rejected settlement expired").issues.last()
        }
    }

    var state: BatchState? = null
    var checkpoint = when {
        resumed -> {
            val pointer = if (path.isFile) path else completed
            val saved = try {
                (indexed ?: Json.decodeFromString<BatchCheckpoint>(pointer.readText())).also {
                    Instant.parse(it.submittedAt)
                    it.firstMissingKeyAt?.let(Instant::parse)
                    it.settleFailureAt()
                    it.batchId?.let(path::results)
                    require(it.requests.isNotEmpty())
                    it.requests.values.forEach { request -> request.reservation.day }
                }
            } catch (_: IllegalArgumentException) {
                return quarantine(null, "invalid checkpoint")
            } catch (_: java.time.DateTimeException) {
                return quarantine(null, "invalid checkpoint date")
            }
            if (indexed != null) path.writeAtomically(Json.encodeToString(saved))
            saved
        }
        else -> {
            if (provider == null || call == null || requests.isEmpty()) return BatchOutcome()
            if (pollRemainingMillis() <= 0) return fallback("Batch polling deadline")
            val runId = UUID.randomUUID().toString()
            val prefix = path.nameWithoutExtension.drop(11) + "-" + path.name.take(10) + "-" + runId.take(8)
            val saved = projections.mapIndexed { index, amount ->
                "$prefix-" + (index + 1) to
                    SavedRequest(fingerprints[index], guard.prepareBatchReservation(path.name, amount),
                        requests[index], itemKeys.getOrNull(index).orEmpty())
            }.toMap()
            val intent = BatchCheckpoint(requests = saved, submittedAt = Instant.now().toString(), call = call,
                runId = runId, supplementRefs = generationResume?.supplementRefs.orEmpty(),
                generationInput = generationResume?.takeIf { it.saveInput }?.requestWithoutSupplement)
            val params = try {
                provider.prepareBatch(saved.keys.zip(requests).toMap())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return fallback("Batch request preparation failed")
            }
            try {
                path.writeAtomically(Json.encodeToString(intent))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return fallback("Batch intent could not be saved")
            }
            try {
                for (request in saved.values) guard.reserve(request.reservation.amount, batch = true, prepared = request.reservation)
            } catch (e: Exception) {
                withContext(NonCancellable) { reject(intent) }
                if (e is CancellationException) throw e
                return fallback("Batch reservation failed")
            }

            if (!stateHook()) {
                reject(intent)
                return fallback("Batch intent checkpoint publication failed · synchronous fallback")
            }
            try {
                state = bounded { params() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is LlmAuthException || (e as? LlmServiceException)?.status.let { it in setOf(400, 401, 403, 404, 413, 422) }) {
                    reject(intent)
                    return fallback("Batch 제출 실패 · ${requests.size}묶음 동기 실행")
                }
                return pending("Batch 제출 확인 필요", intent)
            }
            val submitted = try {
                intent.copy(batchId = checkNotNull(state).id).also {
                    path.writeAtomically(Json.encodeToString(it))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return pending("Batch 제출 확인 필요", intent)
            }
            if (!stateHook()) issues += "Batch ID checkpoint publication failed: " + path.name
            if (deferBatch && !checkNotNull(state).ended) throw collector.runtime.BatchDeferredException()
            submitted
        }
    }

    if (!path.isFile && checkpoint.batchId?.let { path.results(it).isFile } != true) {
        return quarantine(checkpoint, "completed results missing")
    }
    if (resumed) issues += "Batch 재개"
    checkpoint.quarantineReason?.let { return quarantine(checkpoint, it) }
    if (checkpoint.rejected) {
        reject(checkpoint)
        return fallback("Batch submission rejected")
    }
    if (recovering && checkpoint.settled && checkpoint.batchId?.let { path.results(it).isFile } == true) {
        if (path.name.endsWith("-backfill.json")) path.indexResult(checkNotNull(checkpoint.batchId), checkpoint)
        return BatchOutcome(issues = issues)
    }
    val now = Instant.now()
    val since = Instant.parse(checkpoint.submittedAt)
    if (checkpoint.batchId == null && checkpoint.runId == null) return quarantine(checkpoint, "legacy intent without run ID")
    if (checkpoint.batchId == null && checkpoint.firstMissingKeyAt == null && Duration.between(since, now) >= Duration.ofHours(48)) {
        return quarantine(checkpoint, "submission intent expired")
    }
    val localResults = checkpoint.batchId?.let { path.savedResults(it).isFile } == true
    if ((!localResults || checkpoint.resultsIncomplete) && provider == null) return missingKey(checkpoint, "provider key missing")
    if ((!localResults || checkpoint.resultsIncomplete) && recoveryRemainingMillis() <= 0) {
        return BatchOutcome(issues = issues + "Batch recovery deadline", pending = true)
    }
    if (path.isFile && !checkpoint.settled && retrySettlement) {
        try {
            guard.restoreBatchReservations(checkpoint.requests.values.map { it.reservation })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            issues += "Batch reservation restore failed: " + path.name
        }
    }

    suspend fun <T> lookup(block: suspend () -> T): T = block().also {
        if (checkpoint.firstMissingKeyAt != null) {
            checkpoint = checkpoint.copy(firstMissingKeyAt = null)
            save(checkpoint)
        }
    }

    suspend fun recheckMissing(api: BatchCapableProvider, id: String, downloaded: List<ResultRow>): List<ResultRow>? {
        val missing = checkpoint.requests.keys - downloaded.map { it.customId }.toSet()
        val resultFile = path.results(id)
        if (missing.isNotEmpty()) {
            if (!checkpoint.resultsIncomplete) {
                checkpoint = checkpoint.copy(resultsIncomplete = true)
                save(checkpoint)
            }
            if (!resultFile.isFile) resultFile.writeAtomically(downloaded.toJsonl())
        }
        val rows = if (missing.isEmpty()) downloaded else try {
            downloaded + bounded { lookup { api.download(id) } }.filter { it.customId in missing }
        } catch (e: Exception) {
            if (e is CancellationException || e is LlmAuthException) throw e
            // A recheck that keeps failing must end: after 48 hours, or when the results endpoint
            // answers 404, the downloaded rows are final and the missing ids follow the confirmed-missing
            // rule (estimated settlement, then synchronous classification) instead of waiting forever.
            val gone = (e as? LlmServiceException)?.status == 404
            if (!gone && Duration.between(since, Instant.now()) < Duration.ofHours(48)) {
                issues += "Batch retry failed: $id"
                return null
            }
            issues += "Batch retry gave up · missing results confirmed: $id"
            downloaded
        }
        resultFile.writeAtomically(rows.toJsonl())
        if (checkpoint.resultsIncomplete) {
            checkpoint = checkpoint.copy(resultsIncomplete = false)
            save(checkpoint)
        }
        return rows
    }

    fun incomplete() = BatchOutcome(issues = issues, waitMillis = elapsed(), pending = true)

    var timedOut = false
    val rows: List<ResultRow>
    try {
        if (checkpoint.batchId == null) {
            val api = checkNotNull(provider)
            val candidates = bounded { lookup { api.findBatches(since.minusSeconds(600)) } }
            for (candidate in candidates) {
                val id = candidate.id
                val resultFile = path.results(id)
                val downloaded = if (resultFile.isFile) resultFile.readResults() else try {
                    bounded { lookup { api.download(id) } }
                } catch (e: LlmServiceException) {
                    if (!candidate.ended && e.status in listOf(400, 404, 409, 422)) continue
                    if (e.status == 404) continue
                    throw e
                }
                val ids = downloaded.map { it.customId }.toSet()
                if (ids.isEmpty() || !checkpoint.requests.keys.containsAll(ids)) continue
                checkpoint = checkpoint.copy(batchId = id, notFoundRuns = 0)
                path.writeAtomically(Json.encodeToString(checkpoint))
                state = candidate
                if (candidate.ended) {
                    recheckMissing(api, id, downloaded) ?: return incomplete()
                }
                break
            }
            if (checkpoint.batchId == null) return pending("Batch submission unconfirmed", checkpoint)
        }
        val id = checkNotNull(checkpoint.batchId)
        val resultFile = path.savedResults(id)
        rows = if (resultFile.isFile) {
            val downloaded = resultFile.readResults()
            if (checkpoint.resultsIncomplete) {
                recheckMissing(checkNotNull(provider), id, downloaded) ?: return incomplete()
            } else downloaded
        } else {
            val api = provider ?: return pending("Batch 공급자 연결 필요", checkpoint)
            if (state == null) state = bounded { lookup { api.retrieveBatch(id) } }
            if (deferBatch && !state.ended) throw collector.runtime.BatchDeferredException()
            suspend fun waitUntilEnded(pollSeconds: Int) {
                while (state?.ended != true) {
                    delay(pollSeconds * 1000L)
                    state = lookup { api.retrieveBatch(id) }
                }
            }
            if (!state.ended) {
                val left = (config.maxWaitMinutes * 60_000L -
                    Duration.between(Instant.parse(checkpoint.submittedAt), Instant.now()).toMillis()).coerceAtLeast(0)
                if (withTimeoutOrNull(minOf(left, pollRemainingMillis(), recoveryRemainingMillis())) {
                        waitUntilEnded(config.pollSeconds); true
                    } == null) {
                    val stillAllowed = config.maxWaitMinutes * 60_000L - Duration.between(since, Instant.now()).toMillis()
                    if (stillAllowed > 0 && pollRemainingMillis() > 0) return pending("Batch recovery deadline", checkpoint)
                    timedOut = true
                    if (state?.canceling != true) {
                        state = bounded { lookup { api.cancelBatch(id) } }
                    }
                    if (!state.ended && withTimeoutOrNull(minOf(60_000L, pollRemainingMillis(), recoveryRemainingMillis())) { waitUntilEnded(config.pollSeconds.coerceAtMost(5)); true } == null) {
                        if (Duration.between(since, Instant.now()) >= Duration.ofHours(48)) {
                            return quarantine(checkpoint, "recovery expired")
                        }
                        if (checkpoint.notFoundRuns != 0 && path.isFile) {
                            checkpoint = checkpoint.copy(notFoundRuns = 0)
                            path.writeAtomically(Json.encodeToString(checkpoint))
                        }
                        return pending("Batch 시간 초과 · 결과 대기 중", checkpoint)
                    }
                }
            }
            val downloaded = bounded { lookup { api.download(id) } }
            recheckMissing(api, id, downloaded) ?: return incomplete()
        }
        if (path.isFile && !checkpoint.settled && retrySettlement) {
            val failed = finishBatch(checkpoint, guard, rows, issues = issues)
            if (failed.isEmpty()) {
                checkpoint = checkpoint.copy(settled = true, settleRetry = null, firstSettleFailureAt = null,
                    unsettledIds = null, firstMissingKeyAt = null)
                save(checkpoint)
            } else if (!deferSettlement(checkpoint, failed)) {
                // Keep the already-paid results usable; only the unsettled reservations move to quarantine
                // (writeUnsettled), where recoverQuarantined keeps retrying them.
                checkpoint = checkpoint.copy(settled = true, settleRetry = null, firstSettleFailureAt = null, unsettledIds = null)
                save(checkpoint)
                issues += "Batch settlement expired · unsettled reservations kept in quarantine: " + path.name
            }
        }
        if (checkpoint.settled && path.name.endsWith("-backfill.json")) path.indexResult(id, checkpoint)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (e is LlmAuthException) return missingKey(checkpoint, "provider authentication failed")
        if (checkpoint.resultsIncomplete) {
            if (e !is kotlinx.serialization.SerializationException) return incomplete()
        }
        if (e is kotlinx.serialization.SerializationException) return quarantine(checkpoint, "invalid result file")
        val status = (e as? LlmServiceException)?.status
        if (status == 404 && path.isFile && checkpoint.batchId != null) {
            checkpoint = checkpoint.copy(notFoundRuns = checkpoint.notFoundRuns + 1)
            if (checkpoint.notFoundRuns >= 2) return quarantine(checkpoint, "consecutive 404")
            path.writeAtomically(Json.encodeToString(checkpoint))
        } else if (status in listOf(400, 404, 410, 422)) {
            return quarantine(checkpoint, "HTTP $status")
        } else if (checkpoint.notFoundRuns != 0 && path.isFile) {
            checkpoint = checkpoint.copy(notFoundRuns = 0)
            path.writeAtomically(Json.encodeToString(checkpoint))
        }
        if (!localResults && recoveryRemainingMillis() > 0 &&
            Duration.between(since, now) >= Duration.ofHours(48)) {
            return quarantine(checkpoint, "recovery expired")
        }
        return pending("Batch 결과 조회 실패 · 다음 실행에서 재개", checkpoint)
    }

    val byId = rows.groupBy { it.customId }
    val successful = checkpoint.requests.mapNotNull { (id, saved) ->
        byId[id]?.firstNotNullOfOrNull { it.result }?.let { saved to it }
    }
    val byFingerprint = checkpoint.requests.entries.groupBy({ it.value.fingerprint }, { (id, _) ->
        byId[id]?.firstNotNullOfOrNull { it.result }
    }).mapValues { ArrayDeque(it.value) }
    val matches = fingerprints.mapIndexedNotNull { index, fingerprint ->
        val key = if (resumed && checkpoint.requests.isNotEmpty()) {
            (generationResume?.requestWithoutSupplement ?: requests[index]).copy(model = checkpoint.call.model).fingerprint()
        } else fingerprint
        byFingerprint[key]?.removeFirstOrNull()?.let { index to Result.success(it) }
    }.toMap()
    val missing = checkpoint.requests.keys - byId.keys
    if (missing.isNotEmpty()) issues += "Batch 결과 누락 · ${missing.size}건"
    val duplicates = byId.count { (id, entries) -> id in checkpoint.requests && entries.size > 1 }
    if (duplicates > 0) issues += "Batch 중복 ID · ${duplicates}건"
    if (itemKeys.isEmpty()) {
        val remaining = requests.size - matches.size
        if (timedOut) issues += "Batch 시간 초과 · ${remaining}묶음 동기 실행"
        else if (remaining > 0) issues += "Batch 미완료 · ${remaining}묶음 동기 실행"
    }
    return BatchOutcome(matches, issues, elapsed(), synchronousFallback = true,
        itemResponses = successful.map { (saved, result) -> saved.itemKeys to result },
        supplementRefs = checkpoint.supplementRefs)
}


private fun File.readResults() = useLines { lines ->
    lines.filter { it.isNotBlank() }.map { Json.decodeFromString<ResultRow>(it) }.toList()
}

private fun List<ResultRow>.toJsonl() = joinToString("\n", postfix = "\n") { Json.encodeToString(it) }

private suspend fun BatchCapableProvider.download(id: String): List<ResultRow> {
    val rows = mutableListOf<ResultRow>()
    batchResults(id) { customId, decoded ->
        val failure = decoded?.exceptionOrNull() as? LlmServiceException
        val usage = failure?.usage
        val charge = if (usage == null) null else LlmCall("", ProviderId.ANTHROPIC, failure.model.orEmpty(), "", "",
            inputTokens = usage.inputTokens, outputTokens = usage.outputTokens, cachedInputTokens = usage.cachedInputTokens,
            cacheWriteTokens = usage.cacheWriteTokens, costUsd = failure.costUsd,
            priceFallback = failure.priceFallback, priceUnverified = failure.priceUnverified)
        rows += ResultRow(customId, decoded?.getOrNull(),
            failure?.reason ?: if (decoded?.isFailure == true) "invalid_json" else null, charge)
    }
    return rows
}

private suspend fun finishBatch(
    checkpoint: BatchCheckpoint, guard: CostGuard, rows: List<ResultRow>, terminalError: String? = null,
    issues: MutableList<String>
): List<CostGuard.Reservation> = withContext(NonCancellable) {
    val failed = mutableListOf<CostGuard.Reservation>()
    val byId = rows.groupBy { it.customId }
    for ((id, saved) in checkpoint.requests) {
        if (checkpoint.unsettledIds?.contains(saved.reservation.id) == false) continue
        val entries = byId[id]
        val row = entries?.firstOrNull { it.result != null } ?: entries?.firstOrNull { it.charge != null }
            ?: entries?.firstOrNull()
        val result = row?.result
        val charge = row?.charge
        val original = checkpoint.call
        val uncertain = row == null && terminalError != "batch_rejected" || row?.error != null && charge == null
        try {
            guard.record(original.copy(
                model = result?.model ?: charge?.model?.ifEmpty { original.model } ?: original.model,
                inputTokens = result?.inputTokens ?: charge?.inputTokens ?: 0,
                outputTokens = result?.outputTokens ?: charge?.outputTokens ?: 0,
                cachedInputTokens = result?.cachedInputTokens ?: charge?.cachedInputTokens ?: 0,
                cacheWriteTokens = result?.cacheWriteTokens ?: charge?.cacheWriteTokens ?: 0,
                costUsd = result?.costUsd ?: charge?.costUsd ?: if (uncertain) saved.reservation.amount else 0.0,
                priceFallback = result?.priceFallback ?: charge?.priceFallback ?: false,
                priceUnverified = result?.priceUnverified ?: charge?.priceUnverified ?: (uncertain && original.priceUnverified),
                error = terminalError ?: row?.error ?: when {
                    row == null -> "batch_missing"
                    result == null -> "batch_incomplete"
                    result.refused -> "refused"
                    else -> null
                }
            ), saved.reservation)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            failed += saved.reservation
            issues += "Batch reservation settlement failed: " + saved.reservation.id
        }
    }
    failed
}

private suspend fun settleQuarantined(
    guard: CostGuard, reservations: List<CostGuard.Reservation>, issues: MutableList<String>
): List<CostGuard.Reservation> = reservations.filter { reservation ->
    try {
        guard.record(LlmCall("batch-recovery", ProviderId.ANTHROPIC, "", "", "",
            costUsd = reservation.amount, error = "batch_quarantine", batch = true), reservation)
        false
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        issues += "Batch reservation settlement failed: " + reservation.id
        true
    }
}

@Serializable
private data class SettlementRetry(val firstSettleFailureAt: String)

@Serializable
private data class UnsettledOwner(val owner: String)

internal fun publicBatchUnsettled(path: String, text: String, redact: (String) -> String): String = when {
    publicBatchRetryName.matches(path) -> {
        val retry = Json.decodeFromString<SettlementRetry>(text)
        Instant.parse(retry.firstSettleFailureAt)
        Json.encodeToString(retry)
    }
    text.trimStart().startsWith("{") -> {
        val owner = Json.decodeFromString<UnsettledOwner>(text)
        require(publicBatchCheckpointName.matches(owner.owner))
        Json.encodeToString(owner)
    }
    else -> {
        val reservations = Json.decodeFromString<List<CostGuard.Reservation>>(text)
        require(reservations.all { redact(it.id) == it.id && LocalDate.parse(it.date).toString() == it.date })
        Json.encodeToString(reservations)
    }
}

private fun File.writeUnsettled(label: String, failed: List<CostGuard.Reservation>?, owner: String? = null) {
    val directory = resolveSibling("quarantine").apply { mkdirs() }
    directory.resolve("$label.unsettled.json").writeAtomically(
        if (failed == null) Json.encodeToString(UnsettledOwner(checkNotNull(owner))) else Json.encodeToString(failed))
}

internal suspend fun recoverQuarantined(directory: File, guard: CostGuard, issues: MutableList<String>) {
    // A fresh runner does not receive the corrupt checkpoint that originally caused this retry.
    directory.listFiles().orEmpty().filter { publicBatchRetryName.matches(it.name) &&
        !it.resolveSibling(it.name.removeSuffix(".retry.json")).exists() }.forEach { file ->
        try {
            val owner = file.name.removeSuffix(".retry.json")
            if (settleQuarantined(guard, guard.batchReservations(owner), issues).isEmpty()) {
                Files.deleteIfExists(file.toPath())
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            issues += "Batch settlement retry failed: " + file.name
        }
    }
    directory.resolve("quarantine").listFiles().orEmpty().filter { it.name.endsWith(".unsettled.json") }.forEach { file ->
        try {
            val text = file.readText()
            val reservations = if (text.trimStart().startsWith("{")) {
                guard.batchReservations(Json.decodeFromString<UnsettledOwner>(text).owner)
            } else Json.decodeFromString<List<CostGuard.Reservation>>(text)
            val failed = settleQuarantined(guard, reservations, issues)
            if (failed.isEmpty()) Files.deleteIfExists(file.toPath())
            else if (failed != reservations) file.writeAtomically(Json.encodeToString(failed))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            issues += "Batch quarantine settlement retry failed: " + file.name
        }
    }
}

@Serializable
private data class ReusableClassification(val keys: List<String>, val result: LlmResult)

internal fun priorBatchClassifications(out: File, today: String, days: Int): List<Pair<List<String>, LlmResult>> {
    val end = LocalDate.parse(today)
    val start = end.minusDays(days.toLong())
    val directory = out.resolve("batch")
    return directory.listFiles().orEmpty().filter {
        it.name.endsWith("-classify.done") || it.name.endsWith("-classify.reuse") ||
            (it.name.endsWith("-classify.json") && !isPendingBatch(it))
    }
        .sortedByDescending { it.name }.flatMap { file ->
            val date = runCatching { LocalDate.parse(file.name.take(10)) }.getOrNull() ?: return@flatMap emptyList()
            if (date < start || date >= end) return@flatMap emptyList()
            runCatching {
                val rows = if (file.extension == "reuse") Json.decodeFromString<List<ReusableClassification>>(file.readText())
                    else classificationRows(file)
                rows.map { it.keys to it.result }
            }.getOrDefault(emptyList())
        }
}

private fun classificationRows(pointer: File): List<ReusableClassification> {
    val checkpoint = Json.decodeFromString<BatchCheckpoint>(pointer.readText())
    val id = checkpoint.batchId ?: return emptyList()
    val rows = pointer.results(id).readResults().groupBy { it.customId }
    return checkpoint.requests.mapNotNull { (customId, request) ->
        rows[customId]?.firstNotNullOfOrNull { it.result }?.let { ReusableClassification(request.itemKeys, it) }
    }
}

internal fun cleanBatchFiles(directory: File, recentTopicDays: Int, now: Instant = Instant.now()) {
    val reuseCutoff = now.atZone(java.time.ZoneId.systemDefault()).toLocalDate().minusDays(recentTopicDays.toLong()).toString()
    directory.listFiles().orEmpty().filter {
        it.isFile && it.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}-(classify|generate|backfill)\\.json")) &&
            it.name.take(10) < reuseCutoff
    }.forEach { pointer ->
        if (runCatching { Json.decodeFromString<BatchCheckpoint>(pointer.readText()).settled }.getOrDefault(false)) {
            markBatchConsumed(pointer)
            if (!pointer.isFile) pointer.completed().setLastModified(now.toEpochMilli())
        }
    }
    val files = directory.listFiles().orEmpty().filter { it.isFile }
    val cutoff = now.minus(Duration.ofDays(7)).toEpochMilli()
    val pointers = files.filter { it.extension in setOf("json", "done") }.groupBy { file ->
        runCatching { Json.decodeFromString<BatchCheckpoint>(file.readText()).batchId }.getOrNull()
    }
    for (result in files.filter { it.name.endsWith(".results.jsonl") }) {
        val references = pointers[result.name.removeSuffix(".results.jsonl")].orEmpty()
        if (references.any { it.extension != "done" }) continue
        val done = references.filter { it.extension == "done" }
        if (done.any { it.lastModified() > cutoff } || done.isEmpty() && result.lastModified() > cutoff) continue
        if (done.any { pointer ->
                if (pointer.name.endsWith("-classify.done") && pointer.name.take(10) >= reuseCutoff) {
                    val saved = runCatching { classificationRows(pointer) }.getOrDefault(emptyList())
                    if (saved.isNotEmpty()) pointer.resolveSibling(pointer.nameWithoutExtension + ".reuse")
                        .writeAtomically(Json.encodeToString(saved))
                }
                !pointer.delete()
            }) continue
        if (result.delete()) result.resolveSibling(result.name.removeSuffix(".results.jsonl") + ".index-entry.json").delete()
    }
    files.filter { it.name.matches(Regex("""\d{4}-\d{2}-\d{2}-(classify|generate)\.input""")) &&
        it.name.take(10) < reuseCutoff &&
        !hasSavedBatch(directory.parentFile, it.name.take(10), it.nameWithoutExtension.substring(11))
    }.forEach { it.delete() }
    files.filter { it.extension == "reuse" && it.name.take(10) < reuseCutoff }.forEach { it.delete() }
    val quarantineCutoff = now.minus(Duration.ofDays(30)).toEpochMilli()
    val index = directory.resolve("index.json")
    val entries = index.readBatchIndex()
    val retained = entries.filterValues { directory.resolve(it.resultFile).isFile }
    if (entries != retained) index.writeAtomically(Json.encodeToString(retained))
    directory.resolve("quarantine").listFiles().orEmpty().filter {
        it.isFile && !it.name.endsWith(".unsettled.json") && it.lastModified() <= quarantineCutoff
    }
        .sortedBy { it.name.endsWith(".results.jsonl") }.forEach { file ->
            if (file.name.endsWith(".results.jsonl") && directory.resolve("quarantine").listFiles().orEmpty().any {
                    it.name.take(36) == file.name.take(36) && !it.name.endsWith(".results.jsonl")
                }) return@forEach
            file.delete()
        }
}

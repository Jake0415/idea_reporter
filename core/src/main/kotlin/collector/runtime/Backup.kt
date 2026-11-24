@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package collector.runtime

import collector.analysis.AnalysisConfig
import collector.config.ConfigLoader
import collector.config.PipelineConfig
import collector.config.RssConfig
import collector.source.redactRssUrl
import collector.source.privateField
import collector.source.normalizePrivateText
import collector.source.hasPrivateRssQuery
import collector.source.rssPrivatePattern
import collector.source.rssPrivateValues
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import collector.data.ScheduleSettings
import collector.data.files
import collector.dev.outputJson
import collector.model.Feedback
import collector.model.RunReport
import collector.model.TrackedIdea
import collector.pipeline.PipelineResult
import collector.prompt.PromptCatalog
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object Backup {
    const val MAX_BYTES = 20 * 1024 * 1024
    class TooLarge : IllegalArgumentException()

    private val json = Json(outputJson) { prettyPrint = false; encodeDefaults = false }
    private val schemaJson = Json(json) { encodeDefaults = true }
    private val sourcesJson = Json(ConfigLoader.json) { encodeDefaults = false }
    private val webUrl = Regex("""(?i)https?://[^\s<>"']+""")
    private val daily = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")
    private val run = Regex("runs/(?:failed/|scheduled/)?[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9TtZz.+-]+\\.json")
    private val ledger = Regex("out/cost-ledger(?:-analysis)?/[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")
    private val marker = Regex("out/cost-ledger(?:-analysis)?/[0-9]{4}-[0-9]{2}\\.blocked")
    private val address = Regex("""(?:[\p{L}\p{M}\p{N}.!#$%&'*+/=?^_{}|~-]+|"(?:[^"\\\r\n]|\\.)+")@[\p{L}\p{M}\p{N}*-]+(?:\.[\p{L}\p{M}\p{N}*-]+)+""")
    private val credential = Regex("(?i)(?:sk-(?:ant-)?[a-z0-9_-]{16,}|gh[pousr]_[a-z0-9_]{16,}|github_pat_[a-z0-9_]{16,})")
    private val dataFields = setOf("keyword", "keywords", "excludeKeywords", "topicKey", "itemKey", "itemKeys",
        "trackingOnlyKeys", "inputTokens", "outputTokens", "cachedInputTokens", "cacheWriteTokens",
        "maxOutputTokens", "minFitOutputTokens")
    private val snapshotName = Regex("\\.backup-[0-9]+")

    @Serializable
    private data class Bundle(val schemaVersion: Int, val createdAt: String, val files: Map<String, String>)
    @Serializable
    private data class Journal(val directory: String, val state: String, val targets: List<String>,
        val snapshots: Map<String, String>? = null)
    @Serializable
    private data class Revision(val text: String, val version: Int, val baseHash: String, val savedAt: String? = null)

    data class Preview(val cards: Int, val tracked: Int, val from: LocalDate?, val to: LocalDate?)
    class Import internal constructor(
        internal val files: Map<String, String>,
        val preview: Preview,
        internal val cardsByPath: Map<String, List<String>>
    )

    private fun bounded(input: InputStream) = object : FilterInputStream(input) {
        private var size = 0L
        private fun count(n: Int): Int {
            if (n > 0) size += n
            if (size > MAX_BYTES) throw TooLarge()
            return n
        }
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = count(super.read(b, off, len))
    }

    fun copy(input: InputStream, destination: File) {
        destination.outputStream().use { bounded(input).copyTo(it) }
    }

    fun create(paths: CollectorPaths, now: Instant = Instant.now(), privateValues: Collection<String> = emptyList()): String =
        java.io.ByteArrayOutputStream().also { write(paths, it, now, privateValues) }.toString("UTF-8")

    fun write(paths: CollectorPaths, output: OutputStream, now: Instant = Instant.now(),
              privateValues: Collection<String> = emptyList()) {
        val secrets = privateValues.map(::normalizePrivateText).filter(String::isNotBlank).distinct().sortedByDescending(String::length)
        val short = secrets.filter { it.length < 8 }
        val long = secrets.filter { it.length >= 8 }
        fun omit(text: String) = short.any { it in normalizePrivateText(text) }
        fun scrub(text: String) = long.fold(redact(text)) { result, value ->
            val normalized = normalizePrivateText(result)
            if (value in normalized) normalized.replace(value, "[redacted]") else result
        }
        val writer = object : FilterOutputStream(output) {
            private var size = 0L
            override fun write(b: Int) {
                if (++size > MAX_BYTES) throw TooLarge()
                out.write(b)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                size += len
                if (size > MAX_BYTES) throw TooLarge()
                out.write(b, off, len)
            }
        }.bufferedWriter()
        writer.write("""{"schemaVersion":1,"createdAt":"$now","files":{""")
        val backupFiles = current(paths)
        val rssFeedValues = backupFiles["config/sources.local.json"]?.let {
            if (it.length() > MAX_BYTES) throw TooLarge()
            ConfigLoader.parse(it.readText()).sources.filterIsInstance<RssConfig>().flatMap { source ->
                source.feeds.flatMap { feed -> feed.url.toHttpUrlOrNull()?.rssPrivateValues().orEmpty() }
            }
        }.orEmpty()
        var first = true
        for ((path, file) in backupFiles) {
            if (omit(path) || scrub(path) != path) continue
            if (file.length() > MAX_BYTES) throw TooLarge()
            val original = file.readText()
            val content = if (path.endsWith(".md")) {
                original.takeUnless(::omit)?.let(::scrub)
            } else {
                val projected = project(path, original, exporting = true)
                val resultFile = path.startsWith("out/") && (daily.matches(file.name) || run.matches(path.removePrefix("out/")))
                val rssValues = if (resultFile) rssFeedValues + webUrl.findAll(original).flatMap { match ->
                    match.value.trimEnd('\\').toHttpUrlOrNull()?.rssPrivateValues().orEmpty()
                }.toList() else emptyList()
                val rssPattern = rssValues.filter { it.length > 2 }.rssPrivatePattern()
                val cleaned = clean(projected, { text ->
                    scrub(if (resultFile) {
                        val redacted = webUrl.replace(text) { match ->
                            val url = match.value.toHttpUrlOrNull() ?: return@replace match.value
                            val cleanedUrl = url.redactRssUrl(rssValues)
                            if (cleanedUrl == url.toString()) match.value else cleanedUrl
                        }
                        rssPattern?.let { pattern ->
                            redacted.split("[redacted]").joinToString("[redacted]") { pattern.replace(it, "[redacted]") }
                        } ?: redacted
                    } else text)
                }, ::omit)
                val encoded = if (json.parseToJsonElement(original) == cleaned) original else cleaned.toString()
                try {
                    project(path, encoded)
                    encoded
                } catch (e: IllegalArgumentException) {
                    if (cleaned == projected) throw e
                    null
                }
            } ?: continue
            if (content.toByteArray().size > MAX_BYTES) throw TooLarge()
            if (!first) writer.write(",")
            writer.write(json.encodeToString(path))
            writer.write(":")
            writer.write(json.encodeToString(content))
            first = false
        }
        writer.write("}}")
        writer.flush()
    }

    fun read(input: InputStream): Import {
        val bundle = json.decodeFromStream<Bundle>(bounded(input))
        require(bundle.schemaVersion == 1)
        Instant.parse(bundle.createdAt)
        val dates = mutableListOf<LocalDate>()
        val cards = mutableMapOf<String, List<String>>()
        var tracked = 0
        for ((path, content) in bundle.files) {
            if (legacyProtected(path)) continue
            require(allowed(path))
            if (content.toByteArray().size > MAX_BYTES) throw TooLarge()
            val relative = path.substringAfter('/')
            val resultFile = daily.matches(relative) || run.matches(relative)
            if (resultFile) dates += LocalDate.parse(relative.substringAfterLast('/').take(10))
            if (path.endsWith(".md")) {
                require(redact(content) == content)
                continue
            }
            val element = json.parseToJsonElement(content)
            require(clean(element, ::redact) == element)
            project(path, content)
            if (path == "out/tracked.json") tracked = element.jsonArray.size
            if (resultFile && "report" in element.jsonObject) {
                cards[path] = json.decodeFromString<PipelineResult>(content).cards.map { it.id }
            }
        }
        return Import(bundle.files.filterKeys(::allowed),
            Preview(cards.values.flatten().toSet().size, tracked, dates.minOrNull(), dates.maxOrNull()), cards)
    }

    private const val budgetPath = "out/settings/budget.json"

    private fun recordDate(path: String): String? {
        val relative = path.removePrefix("out/")
        return if (daily.matches(relative) || run.matches(relative)) relative.substringAfterLast('/').take(10) else null
    }

    private fun importFiles(paths: CollectorPaths, backup: Import, existing: Set<String>): Map<String, String> {
        val existingDates = existing.mapNotNull(::recordDate).toSet()
        val pendingDates = paths.outDir.resolve("batch").listFiles().orEmpty().filter { file ->
            file.isFile && Regex("""\d{4}-\d{2}-\d{2}-(classify|generate)\.(json|done)""").matches(file.name)
        }.mapTo(mutableSetOf()) { it.name.take(10) }
        return backup.files.filterKeys { path ->
            val date = recordDate(path)
            date == null || (date !in existingDates && date !in pendingDates)
        }
    }

    fun preview(paths: CollectorPaths, backup: Import): Preview {
        val writes = importFiles(paths, backup, current(paths).keys)
        val cards = backup.cardsByPath.filterKeys { it in writes }.values.flatten().toSet().size
        return backup.preview.copy(cards = cards)
    }

    suspend fun restore(paths: CollectorPaths, backup: Import, applied: suspend (Set<String>) -> Unit = {}) {
        check(!recoveryPending(paths))
        recover(paths)
        val old = current(paths)
        val writes = importFiles(paths, backup, old.keys)
        val deletes = old.keys.filter { recordDate(it) == null } - writes.keys
        val targets = writes.keys + deletes
        val parent = transactionRoot(paths).apply { mkdirs() }
        val temporary = Files.createTempDirectory(parent.toPath(), ".backup-").toFile()
        val journal = parent.resolve(".backup-journal.json")
        try {
            val snapshots = mutableMapOf<String, String>()
            for (path in targets) old[path]?.let { file ->
                val saved = temporary.resolve(path)
                saved.parentFile.mkdirs()
                Files.copy(file.toPath(), saved.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                java.io.RandomAccessFile(saved, "rw").use { it.fd.sync() }
                snapshots[path] = snapshotHash(saved)
            }
            temporary.walkBottomUp().filter(File::isDirectory).forEach(::syncDirectory)
            syncDirectory(parent)
            val staging = temporary.resolve("journal")
            staging.outputStream().use {
                it.write(json.encodeToString(Journal(temporary.name, "applying", targets.toList(), snapshots)).toByteArray())
                it.fd.sync()
            }
            Files.move(staging.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(parent)
            for ((path, content) in writes) writeBackupFile(destination(paths, path)) { Files.write(it, content.toByteArray()) }
            for (path in deletes) Files.deleteIfExists(destination(paths, path).toPath())
            applied(writes.keys)
            Files.delete(journal.toPath())
            syncDirectory(parent)
        } catch (failure: Exception) {
            try {
                recover(paths)
            } catch (rollback: Exception) {
                failure.addSuppressed(rollback)
            }
            throw failure
        } finally {
            if (!journal.exists()) temporary.deleteRecursively()
        }
    }

    private fun snapshotHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val size = input.read(buffer)
                if (size == -1) break
                digest.update(buffer, 0, size)
            }
        }
        return java.util.Base64.getEncoder().encodeToString(digest.digest())
    }

    private fun syncDirectory(directory: File) {
        // Windows FileChannel cannot open directories. Android and Unix support directory fsync.
        if (File.separatorChar != '\\') {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
    private const val backupTempPrefix = ".collector-backup-"

    private fun writeBackupFile(target: File, populate: (java.nio.file.Path) -> Unit) {
        target.parentFile.mkdirs()
        val staging = Files.createTempFile(target.parentFile.toPath(), backupTempPrefix, ".tmp")
        try {
            populate(staging)
            Files.move(staging, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(staging)
        }
    }

    private fun recoveryMarker(paths: CollectorPaths) = transactionRoot(paths).resolve(".backup-recovery-pending")
    fun recoveryPending(paths: CollectorPaths) = recoveryMarker(paths).exists() ||
        transactionRoot(paths).resolve(".backup-journal.json").exists()
    fun markRecoveryPending(paths: CollectorPaths) {
        val marker = recoveryMarker(paths)
        marker.parentFile.mkdirs()
        marker.outputStream().use { it.fd.sync() }
    }

    fun completeRecovery(paths: CollectorPaths) {
        Files.deleteIfExists(recoveryMarker(paths).toPath())
    }

    fun recover(paths: CollectorPaths, deferCompletion: Boolean = false, onUnavailable: () -> Unit = {}): Boolean = try {
        recoverFiles(paths, deferCompletion, onUnavailable)
    } catch (failure: Exception) {
        try {
            markRecoveryPending(paths)
        } catch (markerFailure: Exception) {
            failure.addSuppressed(markerFailure)
        }
        throw failure
    }

    private fun recoverFiles(paths: CollectorPaths, deferCompletion: Boolean, onUnavailable: () -> Unit): Boolean {
        val parent = transactionRoot(paths)
        val journal = parent.resolve(".backup-journal.json")
        val restored = journal.exists()
        if (restored) {
            val targets = try {
                val pending = json.decodeFromString<Journal>(journal.readText())
                require(pending.state == "applying" && snapshotName.matches(pending.directory))
                val temporary = parent.resolve(pending.directory).canonicalFile
                require(temporary.parentFile == parent && temporary.isDirectory)
                require(pending.snapshots.orEmpty().keys.all { it in pending.targets })
                pending.targets.associateWith { path ->
                    val saved = temporary.resolve(path).canonicalFile
                    require(saved != temporary && saved.toPath().startsWith(temporary.toPath()))
                    if (pending.snapshots != null) {
                        val expected = pending.snapshots[path]
                        require(if (expected == null) !saved.exists() else saved.isFile && snapshotHash(saved) == expected)
                    } else require(!saved.exists() || saved.isFile)
                    saved to destination(paths, path)
                }
            } catch (_: IllegalArgumentException) {
                null
            }
            if (targets == null) {
                Files.move(journal.toPath(), parent.resolve(".backup-recovery-unavailable").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                syncDirectory(parent)
            } else {
                for ((saved, target) in targets.values) {
                    if (saved.isFile) writeBackupFile(target) {
                        Files.copy(saved.toPath(), it, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    } else Files.deleteIfExists(target.toPath())
                }
                if (deferCompletion) markRecoveryPending(paths)
                Files.delete(journal.toPath())
                syncDirectory(parent)
            }
        }
        parent.listFiles().orEmpty().filter { it.isDirectory && snapshotName.matches(it.name) }
            .forEach { it.deleteRecursively() }
        for (root in listOf(paths.outDir, paths.configDir)) {
            files(root).filter { it.name.startsWith(backupTempPrefix) && it.extension == "tmp" }
                .forEach { Files.deleteIfExists(it.toPath()) }
        }
        val pending = recoveryMarker(paths).exists()
        if (pending && !deferCompletion) completeRecovery(paths)
        if (recoveryUnavailable(paths)) onUnavailable()
        return restored || pending
    }

    fun recoveryUnavailable(paths: CollectorPaths): Boolean =
        transactionRoot(paths).resolve(".backup-recovery-unavailable").isFile

    fun acknowledgeUnavailableRecovery(paths: CollectorPaths) {
        Files.deleteIfExists(transactionRoot(paths).resolve(".backup-recovery-unavailable").toPath())
    }

    private fun transactionRoot(paths: CollectorPaths) = paths.outDir.canonicalFile.parentFile

    private fun current(paths: CollectorPaths): Map<String, File> = buildMap {
        for ((prefix, root) in listOf("out" to paths.outDir, "config" to paths.configDir)) {
            for (file in files(root)) {
                val path = "$prefix/${file.relativeTo(root).invariantSeparatorsPath}"
                if (allowed(path)) put(path, destination(paths, path))
            }
        }
    }.toSortedMap()

    private fun legacyProtected(path: String): Boolean = path == budgetPath || ledger.matches(path) || marker.matches(path) ||
        (path.startsWith("out/runs/scheduled/") && run.matches(path.removePrefix("out/")))

    private fun allowed(path: String): Boolean {
        val name = path.substringAfter('/')
        return when (path.substringBefore('/')) {
            "out" -> daily.matches(name) || (run.matches(name) && !legacyProtected(path)) ||
                name in setOf("tracked.json", "feedback.json", "settings/schedule.json")
            "config" -> name in setOf("pipeline.local.json", "sources.local.json", "analysis.local.json") ||
                PromptCatalog.entries.keys.any { name == "prompts/$it.md" || name == "prompts/$it.versions.json" }
            else -> false
        }
    }

    private fun destination(paths: CollectorPaths, path: String): File {
        val root = when (path.substringBefore('/')) {
            "out" -> paths.outDir
            "config" -> paths.configDir
            else -> throw IllegalArgumentException()
        }.canonicalFile
        val file = root.resolve(path.substringAfter('/')).canonicalFile
        require(root.toPath().startsWith(transactionRoot(paths).toPath()))
        require(file != root && file.toPath().startsWith(root.toPath()))
        return file
    }

    private fun okhttp3.HttpUrl.hasPrivateCredentials() =
        username.isNotEmpty() || password.isNotEmpty() || (0 until querySize).any(::hasPrivateRssQuery)

    private fun project(path: String, content: String, exporting: Boolean = false): JsonElement {
        val name = path.substringAfter('/')
        return when {
            name == "tracked.json" -> typed<List<TrackedIdea>>(content)
            name == "feedback.json" -> typed<List<Feedback>>(content)
            name == "settings/schedule.json" -> typed<ScheduleSettings>(content)
            name == "pipeline.local.json" -> {
                val config = PipelineConfig.parse(content, if (exporting) json else Json)
                selectFields(json.parseToJsonElement(content), schemaJson.encodeToJsonElement(config))
            }
            name == "sources.local.json" -> {
                val config = ConfigLoader.parse(content)
                val sources = config.sources.map { source ->
                    if (!exporting || source !is RssConfig) source else source.copy(feeds = source.feeds.filter { feed ->
                        val url = feed.url.toHttpUrlOrNull() ?: return@filter false
                        !url.hasPrivateCredentials()
                    })
                }
                sourcesJson.encodeToJsonElement(config.copy(sources = sources))
            }
            name == "analysis.local.json" -> {
                val config = AnalysisConfig.parse(content, format = if (exporting) json else Json)
                val local = JsonObject(json.parseToJsonElement(content).jsonObject - setOf("requestBudgetUsd", "monthlyBudgetUsd"))
                selectFields(local, schemaJson.encodeToJsonElement(config))
            }
            name.endsWith(".versions.json") -> typed<List<Revision>>(content)
            daily.matches(name) -> typed<PipelineResult>(content)
            "report" in json.parseToJsonElement(content).jsonObject -> {
                val result = json.decodeFromString<PipelineResult>(content)
                json.encodeToJsonElement(PipelineResult(emptyList(), emptyList(), result.report, cards = result.cards))
            }
            else -> typed<RunReport>(content)
        }
    }

    private fun selectFields(value: JsonElement, schema: JsonElement): JsonElement =
        if (value is JsonObject && schema is JsonObject) JsonObject(value.mapNotNull { (key, child) ->
            schema[key]?.let { key to selectFields(child, it) }
        }.toMap()) else value

    private inline fun <reified T> typed(content: String): JsonElement =
        json.encodeToJsonElement(json.decodeFromString<T>(content))

    private fun clean(value: JsonElement, scrub: (String) -> String, omit: (String) -> Boolean = { false }): JsonElement =
        when (value) {
            is JsonObject -> JsonObject(value.mapNotNull { (key, child) ->
                if ((key !in dataFields && privateField.containsMatchIn(normalizePrivateText(key))) || omit(key) || scrub(key) != key ||
                    (child is JsonPrimitive && omit(child.content))) null
                else key to clean(child, scrub, omit)
            }.toMap())
            is JsonArray -> JsonArray(value.filterNot { it is JsonPrimitive && omit(it.content) }
                .map { clean(it, scrub, omit) })
            is JsonPrimitive -> if (value.isString) JsonPrimitive(scrub(value.content)) else value
        }

    private fun redact(content: String): String {
        val normalized = normalizePrivateText(content)
        val redacted = credential.replace(address.replace(normalized, "[redacted]"), "[redacted]")
        return if (redacted == normalized) content else redacted
    }
}

package collector.data

import collector.config.PipelineConfig
import collector.dev.outputJson
import collector.dev.readJson
import collector.dev.writeAtomically
import collector.llm.publicBatchCheckpoint
import collector.llm.publicBatchCheckpointName
import collector.llm.publicBatchUnsettled
import collector.llm.publicBatchUnsettledName
import collector.llm.publicBatchRetryName
import collector.mail.MailDelivery
import collector.mail.MailKind
import collector.model.TrackedIdea
import collector.prompt.PromptCatalog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Clock
import java.time.LocalDate
import java.time.Instant

private const val DATE = "[0-9]{4}-[0-9]{2}-[0-9]{2}"
internal const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
private val datedOutput = Regex(
    "(?:(?:trend/)?$DATE\\.(?:json|html|txt)|(?:state|backfill|cost-ledger|cost-ledger-analysis)/$DATE\\.json|" +
        "runs/(?:failed/|scheduled/)?$DATE-[0-9TZ.\\-]+\\.json|logs/$DATE\\.log)"
)
private val stateOutput = setOf("seen.json", "feedback.json", "tracked.json", "track-classified.json",
    "metrics.json", "trend/themes.json", "mail/sent.json")
private val otherOutput = Regex(
    "analysis/[0-9]{8}-[0-9]{9}-[0-9a-f]{8}\\.(?:json|html|txt)|" +
        "cost-ledger-analysis/[0-9]{4}-[0-9]{2}\\.blocked|analysis-queue/$UUID_PATTERN\\.json|" +
        "inbox-results/(?:$UUID_PATTERN|${invalidIdPattern.pattern})\\.json"
)

private fun allowedOut(path: String) = path in stateOutput || datedOutput.matches(path) || otherOutput.matches(path)
private fun allowedPrompt(path: String): Boolean =
    (path.endsWith(".md") || path.endsWith(".versions.json")) &&
        runCatching { PromptCatalog.requireEntry(path.removeSuffix(".md").removeSuffix(".versions.json")) }.isSuccess
private fun allowedSetting(path: String) = path == "budget.json" || path == "schedule.json"
private fun allowedBatch(path: String) = publicBatchCheckpointName.matches(path) ||
    publicBatchRetryName.matches(path) || publicBatchUnsettledName.matches(path)

private val summaryLine = Regex(
    "[0-9T:.Z+-]+ · (?:예약 실행 시작 · $DATE|예약 실행 종료 · [01]|" +
        "일일 요약 · 카드 [0-9]+개 · 추정 USD [0-9.]+ · 실패 출처 [0-9]+개)"
)

internal fun files(root: File): List<File> {
    if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) return emptyList()
    return root.walkTopDown().onEnter { Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }
        .filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }.toList()
}

internal fun File.dataPath(root: File) = relativeTo(root).invariantSeparatorsPath

private val publicMailJson = Json(outputJson) { encodeDefaults = false }
// Local part: dot-atom or RFC 5321 quoted string ("first last"@example.com).
private val email = Regex("""(?:[\p{L}\p{M}\p{N}.!#$%&'*+/=?^_{}|~-]+|"(?:[^"\\\r\n]|\\.)+")@[\p{L}\p{M}\p{N}.-]+\.[\p{L}\p{M}\p{N}]{2,}""")

private fun sha256(text: String) =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
private val grapheme = Regex("""\X""")
private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
private val secret = Regex("""(?:sk-ant-|sk-proj-|ghp_|github_pat_|xox[bp]-)[A-Za-z0-9_-]+|sk-[A-Za-z0-9_-]{32,}|\bBearer[ \t]+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)
private val entity = Regex("""&(?:#([0-9]{1,7});?|#[xX]([0-9a-fA-F]{1,6});?|(commat|colon|period|lowbar|hyphen|sol|percnt|nbsp|Tab|NewLine|amp);)""")
private val entities = mapOf("commat" to "@", "colon" to ":", "period" to ".", "lowbar" to "_", "hyphen" to "-",
    "sol" to "/", "percnt" to "%", "nbsp" to " ", "Tab" to "\t", "NewLine" to "\n", "amp" to "&")
private val percentEscapes = Regex("(?:%[0-9a-fA-F]{2})+")
private data class DecodedText(val value: String, val positions: List<IntRange>) {
    fun replace(pattern: Regex, decode: (MatchResult) -> String): DecodedText {
        val result = StringBuilder()
        val mapped = mutableListOf<IntRange>()
        var end = 0
        for (match in pattern.findAll(value)) {
            result.append(value, end, match.range.first)
            mapped.addAll(positions.subList(end, match.range.first))
            val decoded = decode(match)
            result.append(decoded)
            val origin = positions[match.range.first].first..positions[match.range.last].last
            repeat(decoded.length) { mapped.add(origin) }
            end = match.range.last + 1
        }
        if (end == 0) return this
        result.append(value, end, value.length)
        mapped.addAll(positions.subList(end, value.length))
        return DecodedText(result.toString(), mapped)
    }
}

private fun decodeForRedaction(text: String): DecodedText {
    var current = DecodedText(text, text.indices.map { it..it })
    while (true) {
        val next = current.replace(percentEscapes) { URLDecoder.decode(it.value, Charsets.UTF_8) }.replace(entity) {
            val code = it.groupValues[1].toIntOrNull() ?: it.groupValues[2].toIntOrNull(16)
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code))
            else entities[it.groupValues[3]] ?: it.value
        }
        if (next.value == current.value) return current
        current = next
    }
}

private fun redact(text: String): String {
    val plain = email.replace(secret.replace(normalize(text), "[비밀값]"), "[이메일]")
    if ('%' !in plain && '&' !in plain && '+' !in plain) return plain
    val decoded = decodeForRedaction(plain).replace(grapheme) { normalize(it.value) }
    val masks = listOf(decoded.value, decoded.value.replace('+', ' ')).distinct().flatMap { value ->
        listOf(secret to "[비밀값]", email to "[이메일]").flatMap { (pattern, label) ->
            pattern.findAll(value).map {
                (decoded.positions[it.range.first].first..decoded.positions[it.range.last].last) to label
            }.toList()
        }
    }.sortedBy { it.first.first }
    if (masks.isEmpty()) return plain
    return buildString {
        var end = 0
        for ((range, label) in masks) {
            if (range.first >= end) {
                append(plain, end, range.first)
                append(label)
            }
            end = maxOf(end, range.last + 1)
        }
        append(plain, end, plain.length)
    }
}

internal fun mergeMailJournals(vararg journals: String, publishing: Boolean = false): String {
    val priority = mapOf("missing" to 0, "failed" to 0, "sending" to 1, "unknown" to 2, "sent" to 3)
    val merged = journals.flatMap { outputJson.decodeFromString<List<MailDelivery.Attempt>>(it) }
        .groupBy { Triple(it.kind, it.key, it.at) }.values.flatMap { rows ->
            if (publishing && rows.any { it.result != "sending" }) rows.filterNot { it.result == "sending" }
            else rows
        }
        .map { if (!publishing && it.result == "sending") it.copy(result = "unknown") else it }
        .groupBy { it.kind to it.key }.values.map { rows ->
            val best = rows.maxWith(compareBy<MailDelivery.Attempt> { priority.getValue(it.result) }.thenBy { Instant.parse(it.at) })
            // Retry eligibility is relative to the first failure.
            if (best.result in setOf("sending", "failed")) {
                val firstFailure = rows.mapNotNull { it.firstFailureAt ?: it.at.takeIf { _ -> it.result == "failed" } }
                    .minByOrNull(Instant::parse)
                best.copy(firstFailureAt = firstFailure?.takeUnless { it == best.at })
            } else best
        }.sortedBy { Instant.parse(it.at) }
    return outputJson.encodeToString(merged)
}

internal fun publicMailJournal(text: String): String {
    val rows = outputJson.decodeFromString<List<MailDelivery.Attempt>>(text).map {
        Instant.parse(it.at)
        it.firstFailureAt?.let(Instant::parse)
        MailKind.entries.single { kind -> kind.label == it.kind }.validateKey(it.key)
        require(it.recipientCount >= 0 && it.result in setOf("sending", "failed", "missing", "sent", "unknown"))
        it.copy(issue = null)
    }
    return publicMailJson.encodeToString(rows)
}

internal class DataSync(
    private val out: File,
    private val prompts: File,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val historyDays: Int = PipelineConfig.load().historyDays
) {
    private val settings = out.resolve(DataBranch.SETTINGS)
    private val pullSnapshot = out.resolve(".data-pull-snapshot.json")
    private val publicationRoots = listOf(
        Triple(DataBranch.OUT, out, ::allowedOut),
        Triple("out/batch/public", out.resolve("batch"), ::allowedBatch),
        Triple(DataBranch.PROMPTS, prompts, ::allowedPrompt),
        Triple(DataBranch.SETTINGS, settings, ::allowedSetting)
    )

    fun pull(branch: File, processInbox: Boolean = true, importOnly: Boolean = false) {
        val updates = linkedMapOf<File, String?>()
        val branchOut = branch.resolve(DataBranch.OUT)
        copy(branchOut, out, ::allowedOut, transform = { path, text ->
            val local = out.resolve(path)
            when {
                path == "mail/sent.json" && local.isFile -> mergeMailJournals(local.readText(), text)
                path.startsWith("inbox-results/") -> {
                    val result = inboxJson.decodeFromString<InboxResult>(text)
                    inboxJson.encodeToString(result.copy(processedAt = result.processedAt ?: clock.instant().toString()))
                }
                else -> text
            }
        }, update = { file, text -> updates[file] = text })
        if (!importOnly) {
            copy(branch.resolve(DataBranch.PROMPTS), prompts, ::allowedPrompt, mirror = true,
                update = { file, text -> updates[file] = text })
            copy(branch.resolve(DataBranch.SETTINGS), settings, ::allowedSetting, mirror = true,
                update = { file, text -> updates[file] = text })
            copy(branchOut.resolve("batch/public"), out.resolve("batch"), ::allowedBatch,
                transform = ::publicBatch,
                update = { file, text -> updates[file] = text })
        }
        val applied = mutableListOf<Pair<File, String?>>()
        try {
            for ((file, content) in updates) {
                val previous = if (file.isFile) file.readText() else null
                replace(file, content)
                applied += file to previous
            }
            if (!importOnly) {
                // Path -> hash of the local copy written by this pull. Push uses it to tell files this run
                // changed from files it merely carried, so a republish never overwrites newer remote content
                // (e.g. a cost ledger updated by a mid-run state push) with a stale, untouched local copy.
                val snapshot = publicationRoots.flatMap { (prefix, local, allow) ->
                    val root = branch.resolve(prefix)
                    files(root).map { it.dataPath(root) }
                        .mapNotNull { path -> updates[local.resolve(path)]?.takeIf { allow(path) }?.let { "$prefix/$path" to sha256(it) } }
                }.toMap()
                pullSnapshot.writeAtomically(outputJson.encodeToString(snapshot))
            }
        } catch (failure: Exception) {
            for ((file, content) in applied.asReversed()) {
                try {
                    replace(file, content)
                } catch (restoreFailure: Exception) {
                    failure.addSuppressed(restoreFailure)
                }
            }
            throw failure
        }
        if (processInbox) processInbox(branch)
    }

    fun processInbox(branch: File) = InboxProcessor(out, prompts, clock).process(branch.resolve(DataBranch.INBOX))

    private fun readPullSnapshot(): Map<String, String?> {
        if (!pullSnapshot.isFile) return emptyMap()
        val text = pullSnapshot.readText()
        return runCatching { outputJson.decodeFromString<Map<String, String>>(text) }.getOrNull()
            ?: runCatching { outputJson.decodeFromString<List<String>>(text).associateWith { null } }.getOrDefault(emptyMap())
    }

    /** Local files unchanged since the pull, keyed by branch path; their remote copy is left as is. */
    private fun untouchedSincePull(snapshot: Map<String, String?>): Set<String> =
        publicationRoots.flatMap { (prefix, local, _) ->
            snapshot.mapNotNull { (key, hash) ->
                if (hash == null || !key.startsWith("$prefix/")) return@mapNotNull null
                val file = local.resolve(key.removePrefix("$prefix/"))
                key.takeIf { file.isFile && sha256(file.readText()) == hash }
            }
        }.toSet()

    fun push(branch: File) {
        val branchOut = branch.resolve(DataBranch.OUT)
        val journalFile = branchOut.resolve("mail/sent.json")
        val snapshot = readPullSnapshot()
        val untouched = untouchedSincePull(snapshot)
        val update: (File, String?) -> Unit = { file, text ->
            val key = file.relativeTo(branch).invariantSeparatorsPath
            val keepRemote = text != null && file.isFile && file != journalFile && key in untouched
            if (!keepRemote && (file != journalFile || text != null)) replaceBranchFile(branch, file, text)
        }
        val cutoff = LocalDate.now(clock).minusDays(historyDays.toLong())
        val logCutoff = LocalDate.now(clock).minusDays(30)
        val trackedFile = out.resolve("tracked.json").let {
            if (!it.isFile) branchOut.resolve("tracked.json") else it
        }
        val trackingStart = readJson<List<TrackedIdea>>(trackedFile).orEmpty()
            .filter { it.status != TrackedIdea.Status.STOPPED }
            .minOfOrNull { LocalDate.ofInstant(it.startedAt, clock.zone) }
        val resultsCutoff = clock.instant().minus(java.time.Duration.ofDays(90))
        fun retained(path: String, tree: File): Boolean {
            if (!allowedOut(path)) return false
            if (path.startsWith("inbox-results/")) {
                val file = tree.resolve(path)
                return readJson<InboxResult>(file)?.processedAt?.let(Instant::parse)?.isAfter(resultsCutoff)
                    ?: (file.lastModified() > resultsCutoff.toEpochMilli())
            }
            if (path.startsWith("cost-ledger")) return true
            val date = Regex(DATE).find(path)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (path.startsWith("logs/")) return date != null && date > logCutoff
            if (date != null && trackingStart != null && date >= trackingStart &&
                Regex("(?:state/)?$DATE\\.json").matches(path)) return true
            return !datedOutput.matches(path) || date == null || date >= cutoff
        }
        copy(out, branchOut, ::allowedOut, update = update, transform = { path, text ->
            val remote = branchOut.resolve(path)
            val merged = if (path == "mail/sent.json" && remote.isFile) {
                mergeMailJournals(remote.readText(), text, publishing = true)
            } else text
            publicOutput(path, merged)
        })
        files(branchOut).filter {
            val path = it.dataPath(branchOut)
            allowedOut(path) && !retained(path, branchOut)
        }.forEach { update(it, null) }
        publishBatch(branch, update)
        copy(prompts, branch.resolve(DataBranch.PROMPTS), ::allowedPrompt, transform = ::publicOutput, update = update)
        copy(settings, branch.resolve(DataBranch.SETTINGS), ::allowedSetting, transform = ::publicOutput, update = update)
        for ((prefix, local, allow) in publicationRoots) {
            val root = branch.resolve(prefix)
            files(root).filter {
                val path = it.dataPath(root)
                "$prefix/$path" in snapshot && allow(path) && !local.resolve(path).exists()
            }.forEach { update(it, null) }
        }
        manifest(branch)
    }

    fun publishState(branch: File) {
        val update: (File, String?) -> Unit = { file, text -> replaceBranchFile(branch, file, text) }
        val branchOut = branch.resolve(DataBranch.OUT)
        publishBatch(branch, update)
        for (ledger in listOf("cost-ledger", "cost-ledger-analysis")) {
            copy(out.resolve(ledger), branchOut.resolve(ledger), { allowedOut("$ledger/$it") },
                transform = { path, text -> publicOutput("$ledger/$path", text) }, update = update)
        }
        manifest(branch)
    }

    private fun publishBatch(branch: File, update: (File, String?) -> Unit) {
        copy(out.resolve("batch"), branch.resolve("out/batch/public"), ::allowedBatch,
            transform = ::publicBatch, update = update)
    }

    fun publishMail(branch: File) {
        val local = out.resolve("mail/sent.json")
        if (!local.isFile) return
        val published = branch.resolve("out/mail/sent.json")
        val previous = if (published.isFile) published.readText() else null
        val journal = if (previous == null) local.readText() else mergeMailJournals(previous, local.readText(), publishing = true)
        val sanitized = publicOutput("mail/sent.json", journal)
        if (sanitized == previous) return
        replaceBranchFile(branch, published, sanitized)
        manifest(branch)
    }

    fun mailArtifact(file: File) {
        file.writeAtomically(publicMailJournal(out.resolve("mail/sent.json").readText()))
    }

    fun manifest(branch: File) {
        val entries = listOf(DataBranch.OUT, DataBranch.PROMPTS, DataBranch.SETTINGS).flatMap { dir ->
            files(branch.resolve(dir))
        }.map { file ->
            val bytes = file.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            DataFile(file.dataPath(branch), bytes.size.toLong(), hash)
        }.sortedBy { it.path }
        fun latest(prefix: String) = entries.mapNotNull {
            Regex("${Regex.escape(prefix)}($DATE)\\.json").matchEntire(it.path)?.groupValues?.get(1)
        }.maxOrNull()
        val manifest = DataManifest(updatedAt = clock.instant().toString(), latestDaily = latest("out/"),
            latestTrend = latest("out/trend/"), files = entries)
        replaceBranchFile(branch, branch.resolve(DataBranch.MANIFEST),
            outputJson.encodeToString(DataManifest.serializer(), manifest))
    }

    private fun copy(source: File, target: File, allow: (String) -> Boolean,
                     mirror: Boolean = false,
                     transform: (String, String) -> String = { _, text -> text },
                     update: (File, String?) -> Unit = ::replace) {
        val selected = files(source).associateBy { it.dataPath(source) }.filterKeys(allow)
        for ((path, file) in selected) {
            val destination = target.resolve(path)
            var parent: File? = destination
            while (parent != null) {
                require(!Files.isSymbolicLink(parent.toPath()))
                if (parent == target) break
                parent = parent.parentFile
            }
            update(destination, transform(path, file.readText()))
        }
        if (mirror) files(target).filter {
            val path = it.dataPath(target)
            path !in selected && allow(path)
        }.forEach { update(it, null) }
    }

    private fun replace(file: File, content: String?) {
        if (content == null) Files.deleteIfExists(file.toPath()) else file.writeAtomically(content)
    }

    private fun replaceBranchFile(branch: File, file: File, content: String?) {
        if (content != null) {
            val parents = generateSequence(file.parentFile) { it.parentFile }.takeWhile { it != branch }.toList()
            for (parent in parents.asReversed()) {
                require(!Files.isSymbolicLink(parent.toPath()))
                if (parent.isFile) Files.delete(parent.toPath())
            }
            require(!Files.isSymbolicLink(file.toPath()))
            if (file.isDirectory) Files.walk(file.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
        replace(file, content)
    }

    private fun publicOutput(path: String, text: String): String = when {
        path.startsWith("logs/") -> text.lineSequence().filter(summaryLine::matches).joinToString("\n", transform = ::redact)
        path == "mail/sent.json" -> publicMailJournal(text)
        path.endsWith(".json") -> outputJson.parseToJsonElement(text).publicJson().toString()
        else -> redact(text)
    }

    private fun publicBatch(path: String, text: String) =
        if (publicBatchCheckpointName.matches(path)) publicOutput(path, publicBatchCheckpoint(text))
        else publicBatchUnsettled(path, text, ::redact)

    private fun JsonElement.publicJson(): JsonElement = when (this) {
        is JsonObject -> {
            val isItem = "source" in this && "title" in this && "url" in this
            val itemFields = setOf("source", "externalId", "title", "url", "rawScore", "score", "publishedAt", "snippet", "tags",
                "discussionUrl", "alsoSeenIn", "extra", "commentCount", "profileHint", "trackHint", "mergedUrls")
            val fields = filterKeys { redact(it) == it }.filterKeys {
                if (isItem) return@filterKeys it in itemFields
                it !in setOf("body", "comments", "enrichedComments", "hnContext", "author", "rawResponse", "rawBody", "rawContent")
            }
            JsonObject(fields.mapValues { (key, value) ->
                when {
                    isItem && key == "snippet" && value is JsonPrimitive && value.isString ->
                        JsonPrimitive(redact(value.content.take(300)).take(300))
                    key == "classification" && value is JsonObject -> {
                        val sanitized = value.publicJson() as JsonObject
                        val summary = sanitized["summary"] as? JsonPrimitive
                        if (summary?.isString == true) JsonObject(sanitized + ("summary" to JsonPrimitive(summary.content.take(300))))
                        else sanitized
                    }
                    else -> value.publicJson()
                }
            })
        }
        is JsonArray -> JsonArray(map { it.publicJson() })
        is JsonPrimitive -> if (isString) JsonPrimitive(redact(content)) else this
    }
}


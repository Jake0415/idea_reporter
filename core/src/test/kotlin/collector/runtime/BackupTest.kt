package collector.runtime

import collector.model.IdeaCard
import collector.model.RunReport
import collector.model.TrackedIdea
import collector.pipeline.PipelineResult
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class BackupTest {
    @TempDir lateinit var root: File
    private fun paths(name: String) = CollectorPaths(root.resolve("$name/out"), root.resolve("$name/config"))
    private fun File.put(text: String) = apply { parentFile.mkdirs(); writeText(text) }

    @Test fun roundTrip() = runBlocking {
        val source = paths("source")
        val target = paths("target")
        val day = LocalDate.parse("2026-01-24")
        val start = Instant.parse("${day}T00:00:00Z")
        val card = IdeaCard("card", day.toString(), "Card", "", "", "", emptyList(), emptyList(),
            emptyList(), emptyList(), "", "", "", "", emptyList(), "", tags = emptyList())
        val tracked = TrackedIdea("track", card.id, card.title, "", emptyList(), emptyList(),
            TrackedIdea.Status.TRACKING, start, null, 0.0, TrackedIdea.Trend.FLAT)
        val report = RunReport(day.toString(), start.toString(), emptyList(), emptyList(), emptyMap(), 0, cardCount = 1)
        val daily = Json.encodeToString(PipelineResult(emptyList(), emptyList(), report, cards = listOf(card)))
        source.outDir.resolve("$day.json").put(daily)
        val runPath = "runs/$day-2026-11-24T00-00-00Z.json"
        source.outDir.resolve(runPath).put(daily)
        source.outDir.resolve("tracked.json").put(Json.encodeToString(listOf(tracked)))
        source.outDir.resolve("cost-ledger/$day.json").put("""{"entry":{"reservedUsd":0.25,"actualUsd":0.25}}""")
        source.outDir.resolve("feedback.json").put("[]")
        source.configDir.resolve("pipeline.local.json").put("{}")
        source.promptsDir.resolve("criteria.md").put("criteria")
        val original = Backup.create(source, start)
        val backup = Backup.read(original.byteInputStream())
        assertEquals(Backup.Preview(1, 1, day, day), backup.preview)
        Backup.restore(target, backup)
        assertEquals(original, Backup.create(target, start))
        val summary = Json.decodeFromString<PipelineResult>(target.outDir.resolve(runPath).readText())
        assertEquals(report, summary.report)
        assertEquals(listOf(card), summary.cards)
        assertEquals("daily", summary.cards.single().kind)
        assertEquals("", summary.cards.single().targetCustomer)
        assertTrue(summary.cards.single().speculation.isEmpty())
        assertEquals("criteria", target.promptsDir.resolve("criteria.md").readText())
        assertEquals("[]", target.outDir.resolve("feedback.json").readText())
        assertEquals("{}", target.configDir.resolve("pipeline.local.json").readText())
    }

    @Test fun expansionHistoryIsBackedUpAndPreservedWhenRestoringAnOldBackup() = runBlocking {
        val source = paths("source")
        val target = paths("target")
        val seeds = """[{"date":"2026-11-26","techTopic":"speech","targetCustomer":"회계","lens":"invert"}]"""
        source.outDir.resolve("expansion-seeds.json").put(seeds)
        Backup.restore(target, Backup.read(Backup.create(source).byteInputStream()))
        val restored = target.outDir.resolve("expansion-seeds.json")
        assertEquals(Json.parseToJsonElement(seeds), Json.parseToJsonElement(restored.readText()))
        Backup.restore(target, Backup.read(Backup.create(paths("legacy")).byteInputStream()))
        assertEquals(Json.parseToJsonElement(seeds), Json.parseToJsonElement(restored.readText()))
    }

    @Test fun rejectsInvalidExpansionSeedDateOnImport() {
        val data = """[{"date":"2026-11-31","techTopic":"speech","targetCustomer":"회계","lens":"invert"}]"""
        val backup = """{"schemaVersion":1,"createdAt":"2026-11-26T00:00:00Z","files":{"out/expansion-seeds.json":${Json.encodeToString(data)}}}"""
        assertFailsWith<IllegalArgumentException> { Backup.read(backup.byteInputStream()) }
    }

    @Test fun excludesCredentialsAndAddresses() {
        val paths = paths("source")
        paths.envFile.put("excluded-env")
        paths.outDir.resolve("mail/scheduled.json").put("excluded-mail")
        val address = "fixture" + '@' + "example.invalid"
        paths.configDir.resolve("sources.local.json").put("""{"sources":[],"key":"excluded-value","note":"$address"}""")
        paths.configDir.resolve("pipeline.local.json").put("""{"note":"excluded-extra","key":"excluded-key"}""")
        val opaqueSecret = "opaque-fixture"
        paths.promptsDir.resolve("criteria.md").put("$address $opaqueSecret j***%40g***.invalid")
        paths.promptsDir.resolve("criteria.versions.json").put("""[{"text":"tiny","version":1,"baseHash":"fixture"}]""")
        val backup = Backup.create(paths, privateValues = listOf(opaqueSecret, "tiny"))
        assertFalse("excluded-" in backup)
        assertFalse(address in backup)
        assertFalse(opaqueSecret in backup)
        assertFalse("%40" in backup)
        assertFalse("tiny" in backup)
        Backup.read(backup.byteInputStream())
    }

    @Test fun excludesPrivateRssFeeds() {
        val paths = paths("rss")
        paths.configDir.resolve("sources.local.json").put("""{"sources":[{"id":"rss","feeds":[
            {"name":"public feed","url":"https://example.invalid/feed?n=1","profile":["tech-signal"]},
            {"name":"long query","url":"https://example.invalid/feed?n=123456","profile":["tech-signal"]},
            {"name":"private query","url":"https://example.invalid/feed?%74oken=x","profile":["tech-signal"]}
        ]}]}""")
        val backup = Backup.create(paths)
        assertTrue("public feed" in backup)
        assertFalse("long query" in backup)
        assertFalse("private query" in backup)
    }

    @Test fun redactsSensitiveQueriesInStoredResults() {
        val paths = paths("rss-results")
        val day = "2026-01-24"
        val start = Instant.parse("2026-01-24T00:00:00Z")
        val item = collector.model.Item("rss", "id", "example Zq9 and x",
            "https://example.invalid/article?%74oken=x&key=Zq9&code=abcdef&n=1", 0, publishedAt = start)
        val report = RunReport(day, start.toString(), emptyList(), emptyList(), emptyMap(), 0)
        paths.outDir.resolve("$day.json").put(Json.encodeToString(PipelineResult(listOf(item), emptyList(), report)))
        val backup = Backup.read(Backup.create(paths).byteInputStream())
        val result = Json.decodeFromString<PipelineResult>(backup.files.getValue("out/$day.json"))
        assertEquals("example [redacted] and x", result.items.single().title)
        assertEquals("https://example.invalid/article?%74oken=[redacted]&key=[redacted]&code=[redacted]&n=1", result.items.single().url)
    }

    @Test fun ignoresLegacyLedgerAndBudgetWithoutChangingBytes() = runBlocking {
        val paths = paths("target")
        val day = LocalDate.now()
        val ledgerPath = "out/cost-ledger/$day.json"
        val budgetPath = "out/settings/budget.json"
        val ledger = paths.outDir.parentFile.resolve(ledgerPath).put(""" { "prior-runs": {"reservedUsd":2.0} } """)
        val budget = paths.outDir.parentFile.resolve(budgetPath).put(""" { "monthlyTotalUsd":5 } """)
        val before = listOf(ledger, budget).map(File::readBytes)
        val legacy = kotlinx.serialization.json.buildJsonObject {
            put("schemaVersion", kotlinx.serialization.json.JsonPrimitive(1))
            put("createdAt", kotlinx.serialization.json.JsonPrimitive("2026-01-01T00:00:00Z"))
            put("files", kotlinx.serialization.json.JsonObject(mapOf(
                ledgerPath to kotlinx.serialization.json.JsonPrimitive("""{"other":{"reservedUsd":9.0}}"""),
                budgetPath to kotlinx.serialization.json.JsonPrimitive("""{"monthlyTotalUsd":8}""")
            )))
        }
        Backup.restore(paths, Backup.read(legacy.toString().byteInputStream()))
        listOf(ledger, budget).zip(before).forEach { (file, bytes) ->
            assertTrue(file.readBytes().contentEquals(bytes))
        }
    }

    @Test fun missingSnapshotLeavesCurrentFilesAndClosesJournal() {
        val paths = paths("missing")
        val current = paths.outDir.resolve("tracked.json").put("[]")
        val journal = paths.outDir.parentFile.resolve(".backup-journal.json").put(
            """{"directory":".backup-123","state":"applying","targets":["out/tracked.json"]}"""
        )
        var unavailable = false
        Backup.recover(paths, onUnavailable = { unavailable = true })
        assertTrue(unavailable)
        assertEquals("[]", current.readText())
        assertFalse(journal.exists())
        assertFalse(Backup.recoveryPending(paths))
    }

    @Test fun corruptSnapshotDoesNotPartiallyRestore() {
        val paths = paths("corrupt")
        val parent = paths.outDir.parentFile
        parent.resolve(".backup-123/out/tracked.json").put("corrupt")
        val tracked = paths.outDir.resolve("tracked.json").put("[]")
        val feedback = paths.outDir.resolve("feedback.json").put("[]")
        parent.resolve(".backup-journal.json").put(
            """{"directory":".backup-123","state":"applying","targets":["out/feedback.json","out/tracked.json"],"snapshots":{"out/tracked.json":"wrong-checksum"}}"""
        )
        var unavailable = false
        Backup.recover(paths, onUnavailable = { unavailable = true })
        assertTrue(unavailable)
        assertEquals("[]", tracked.readText())
        assertEquals("[]", feedback.readText())
        Backup.acknowledgeUnavailableRecovery(paths)
        Backup.recover(paths, onUnavailable = { fail("Recovery notice was already dismissed") })
    }

    @Test fun fillsOnlyMissingDatesAndSkipsPendingDaily() = runBlocking {
        val source = paths("source")
        val target = paths("target")
        val today = LocalDate.now()
        val pending = today.minusDays(1)
        fun result(day: LocalDate, millis: Long): String {
            val report = RunReport(day.toString(), "${day}T00:00:00Z", emptyList(), emptyList(), emptyMap(), millis)
            val card = IdeaCard("card-$day", day.toString(), "Card", "", "", "", emptyList(), emptyList(),
                emptyList(), emptyList(), "", "", "", "", emptyList(), "", tags = emptyList())
            return Json.encodeToString(PipelineResult(emptyList(), emptyList(), report, cards = listOf(card)))
        }
        val currentArchive = target.outDir.resolve("runs/$today-00.json").put(result(today, 1))
        target.outDir.resolve("batch/$pending-classify.json").put("{}")
        val existing = today.minusDays(2)
        val missing = today.minusDays(3)
        val oldDaily = target.outDir.resolve("$existing.json").put(result(existing, 6))
        val oldArchive = target.outDir.resolve("runs/$existing-00.json").put(result(existing, 7))
        source.outDir.resolve("$existing.json").put(result(existing, 8))
        source.outDir.resolve("runs/failed/$existing-01.json").put(result(existing, 9))
        source.outDir.resolve("runs/$missing-00.json").put(result(missing, 10))
        source.outDir.resolve("$pending.json").put(result(pending, 11))
        source.outDir.resolve("runs/$today-00.json").put(result(today, 3))
        source.outDir.resolve("$today.json").put(result(today, 12))
        source.outDir.resolve("$missing.json").put(result(missing, 4))
        val backup = Backup.read(Backup.create(source).byteInputStream())
        val daily = target.outDir.resolve("$missing.json")
        assertEquals(1, Backup.preview(target, backup).cards)
        Backup.restore(target, backup)
        assertEquals(0, Backup.preview(target, backup).cards)
        assertEquals(1L, Json.decodeFromString<PipelineResult>(currentArchive.readText()).report.totalMillis)
        assertEquals(result(existing, 6), oldDaily.readText())
        assertEquals(result(existing, 7), oldArchive.readText())
        assertFalse(target.outDir.resolve("runs/failed/$existing-01.json").exists())
        assertFalse(target.outDir.resolve("$today.json").exists())
        assertFalse(target.outDir.resolve("$pending.json").exists())
        assertTrue(target.outDir.resolve("runs/$missing-00.json").exists())
        assertEquals(4L, Json.decodeFromString<PipelineResult>(daily.readText()).report.totalMillis)
        daily.writeText(result(missing, 5))
        Backup.restore(target, backup)
        assertEquals(5L, Json.decodeFromString<PipelineResult>(daily.readText()).report.totalMillis)
    }

    @Test fun rejectsInvalidFiles() {
        for (content in listOf("not JSON", """{"schemaVersion":2}""",
            """{"schemaVersion":1,"createdAt":"2026-01-01T00:00:00Z","files":{"out/../outside.json":"{}"}}""",
            """{"schemaVersion":1,"createdAt":"2026-01-01T00:00:00Z","files":{"out/tracked.json":"{}"}}""",
            " ".repeat(Backup.MAX_BYTES + 1))) {
            assertFailsWith<IllegalArgumentException> { Backup.read(content.byteInputStream()) }
        }
    }

    @Test fun preservesSpendAfterOlderBackup() = runBlocking {
        val paths = paths("spend")
        val day = LocalDate.now()
        val directory = paths.outDir.resolve("cost-ledger")
        val ledger = collector.dev.FileCostLedger(directory)
        ledger.reserve(day, "first", 0.25)
        val old = Backup.read(Backup.create(paths, Instant.parse("2026-11-24T06:00:00Z")).byteInputStream())
        ledger.settle(day, "first", 0.5)
        ledger.reserve(day, "second", 0.75)
        val marker = paths.outDir.resolve("cost-ledger-analysis/${java.time.YearMonth.from(day)}.blocked").put("blocked")
        val today = paths.outDir.resolve("$day.json").put("today")
        val reservation = paths.outDir.resolve("runs/scheduled/$day-00.json").put("reservation")
        val mail = paths.outDir.resolve("mail/status.json").put("status")
        val batch = paths.outDir.resolve("batch/private/checkpoint.json").put("checkpoint")
        Backup.restore(paths, old)
        assertEquals(1.25, ledger.total(day))
        val budget = collector.budget.MonthlyBudget(paths.outDir, collector.budget.BudgetSettings(monthlyTotalUsd = 5.0))
        assertEquals(3.75, budget.remaining(day))
        assertEquals("blocked", marker.readText())
        assertEquals("today", today.readText())
        assertEquals("reservation", reservation.readText())
        assertEquals("status", mail.readText())
        assertEquals("checkpoint", batch.readText())
    }

    @Test fun preservesSpendWithoutLedgerForAnExistingDate() = runBlocking {
        val target = paths("historic-spend")
        val source = paths("older-backup")
        val day = LocalDate.of(2026, 1, 15)
        val report = RunReport(day.toString(), "${day}T00:00:00Z", emptyList(), emptyList(), emptyMap(), 0)
        fun result(cost: Double): String {
            val call = collector.llm.LlmCall("fixture", collector.llm.ProviderId.OPENAI, "fixture", "", "", costUsd = cost)
            return Json.encodeToString(PipelineResult(emptyList(), emptyList(), report.copy(llmCalls = listOf(call))))
        }
        target.outDir.resolve("$day.json").put(result(2.0))
        source.outDir.resolve("$day.json").put(result(0.5))
        val runtime = CollectorRuntime(target, { null }, java.time.Clock.systemUTC())
        val recordedDailyCost = runtime.dailyCost(day)
        fun dailySpend() = collector.dev.costLedger(target.outDir, collector.dev.FileHistorySearch(target.outDir, 30)).total(day)
        fun monthlyRemaining() = collector.budget.MonthlyBudget(target.outDir, collector.budget.BudgetSettings(monthlyTotalUsd = 5.0)).remaining(day)
        assertFalse(target.outDir.resolve("cost-ledger/$day.json").exists())
        assertEquals(2.0, dailySpend())
        assertEquals(3.0, monthlyRemaining())
        Backup.restore(target, Backup.read(Backup.create(source).byteInputStream()))
        assertEquals(recordedDailyCost, runtime.dailyCost(day))
        assertEquals(2.0, dailySpend())
        assertEquals(3.0, monthlyRemaining())
    }

    @Test fun recoversInterruptedRestoreAndClearsOrphans() {
        val paths = paths("recovery")
        val parent = paths.outDir.parentFile
        parent.resolve(".backup-123/out/tracked.json").put(" [ ] ")
        parent.resolve(".backup-123/out/previous-version.json").put("saved")
        parent.resolve(".backup-456").mkdirs()
        val staging = paths.outDir.resolve(".collector-backup-orphan.tmp").put("staged")
        parent.resolve(".backup-journal.json").put(
            """{"directory":".backup-123","state":"applying","targets":["out/tracked.json","out/feedback.json","out/previous-version.json"]}"""
        )
        val tracked = paths.outDir.resolve("tracked.json").put("[]")
        val feedback = paths.outDir.resolve("feedback.json").put("[]")
        Backup.recover(paths)
        assertEquals(" [ ] ", tracked.readText())
        assertFalse(feedback.exists())
        assertEquals("saved", paths.outDir.resolve("previous-version.json").readText())
        assertFalse(staging.exists())
        assertTrue(parent.listFiles().orEmpty().none { it.name.startsWith(".backup-") })
    }

    @Test fun rejectsInvalidConfigBeforePreview() {
        val bundle = kotlinx.serialization.json.buildJsonObject {
            put("schemaVersion", kotlinx.serialization.json.JsonPrimitive(1))
            put("createdAt", kotlinx.serialization.json.JsonPrimitive("2026-01-01T00:00:00Z"))
            put("files", kotlinx.serialization.json.JsonObject(mapOf(
                "config/pipeline.local.json" to kotlinx.serialization.json.JsonPrimitive("""{"batch":{"pollSeconds":-1}}""")
            )))
        }
        assertFailsWith<IllegalArgumentException> { Backup.read(bundle.toString().byteInputStream()) }
    }

    @Test fun rollsBackFailedApplication() = runBlocking {
        val source = paths("source")
        val target = paths("target")
        source.promptsDir.resolve("criteria.md").put("new criteria")
        source.outDir.resolve("feedback.json").put("[]")
        target.promptsDir.resolve("criteria.md").put("original criteria")
        val oldTracking = target.outDir.resolve("tracked.json").put(" [ ] ")
        val backup = Backup.read(Backup.create(source).byteInputStream())
        assertFailsWith<IOException> { Backup.restore(target, backup) { throw IOException() } }
        assertEquals("original criteria", target.promptsDir.resolve("criteria.md").readText())
        assertEquals(" [ ] ", oldTracking.readText())
        assertFalse(target.outDir.resolve("feedback.json").exists())
    }
}

package collector.data

import collector.budget.BudgetSettings
import collector.dev.outputJson
import collector.dev.runMain
import collector.dev.FileHistorySearch
import collector.dev.FileCostLedger
import collector.dev.runMailCommand
import collector.llm.CostGuard
import collector.llm.recoverQuarantined
import collector.mail.MailDelivery
import collector.mail.Mailer
import collector.model.ClassifiedItem
import collector.model.Comment
import collector.model.Feedback
import collector.model.IdeaCard
import collector.model.Item
import collector.model.Signal
import collector.model.RunReport
import collector.model.TrackedIdea
import collector.pipeline.PipelineResult
import collector.prompt.PromptCatalog
import collector.prompt.PromptResolver
import collector.stage.TrackingKeywords
import collector.trend.TrendInput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertTrue

class DataSyncTest {
    private val now = Instant.parse("2026-11-23T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test fun allowlistManifestAndRetention() = withWorkspace { out, branch, prompts ->
        val payload = "{}"
        write(out, "2026-11-23.json", payload)
        write(out, "trend/2026-11-22.json", payload)
        write(out, "2026-08-01.json", payload)
        write(out, "logs/2026-11-23.log", "$now \u00b7 \uc608\uc57d \uc2e4\ud589 \uc2dc\uc791 \u00b7 2026-11-23\nraw diagnostic")
        write(branch, "out/codex/old.log", "old local log")
        val excluded = listOf("debug/raw.json", "codex/dev.log", "batch/2026-11-23-classify.done",
            "batch/provider.results.jsonl", "backfill/collected/2026-11-23.json",
            "2026-11-23-collect.json", "run.lock", "2026-11-23.json.tmp")
        for (path in excluded) write(out, path, "excluded")
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json"), historyDays = 90).push(branch)

        val manifest = outputJson.decodeFromString<DataManifest>(branch.resolve("manifest.json").readText())
        assertEquals(now.toString(), manifest.updatedAt)
        assertEquals("2026-11-23", manifest.latestDaily)
        assertEquals("2026-11-22", manifest.latestTrend)
        val daily = manifest.files.single { it.path == "out/2026-11-23.json" }
        assertEquals(2L, daily.size)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") {
            "%02x".format(it)
        }, daily.sha256)
        assertFalse(branch.resolve("out/2026-08-01.json").exists())
        assertTrue(branch.resolve("out/codex/old.log").exists())
        for (path in excluded) assertFalse(branch.resolve("out/$path").exists(), path)
        assertFalse(branch.resolve("out/logs/2026-11-23.log").readText().contains("raw diagnostic"))
    }

    @Test fun feedbackPromptBudgetAndQueueRoundTrip() = withWorkspace { out, branch, prompts ->
        val feedbackId = id()
        val setId = id()
        val queueId = id()
        val budgetId = id()
        val resetId = id()
        val rejectedId = id()
        val time = now.toString()
        val base = PromptResolver.hash(PromptCatalog.defaultText("criteria"))
        enqueue(branch, InboxRequest.Reaction(1, feedbackId, time, "card-1", Feedback.Kind.STAR, "reason", "memo"))
        enqueue(branch, InboxRequest.PromptSet(1, setId, time, "criteria", "An override", base))
        enqueue(branch, InboxRequest.Analysis(1, queueId, time, listOf("theme-1"), AnalysisMode.ESTIMATE, 0.25))
        enqueue(branch, InboxRequest.SettingsSet(1, budgetId, time,
            inboxJson.parseToJsonElement("""{"dailyCardUsd":0.25}""") as kotlinx.serialization.json.JsonObject,
            ScheduleSettings(enabled = false)))
        val sync = DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json"))
        sync.pull(branch)
        assertEquals("An override", prompts.resolve("criteria.md").readText())
        assertEquals(0.25, BudgetSettings.load(out).dailyCardUsd)
        assertFalse(inboxJson.decodeFromString<ScheduleSettings>(out.resolve("settings/schedule.json").readText()).enabled)
        assertTrue(out.resolve("analysis-queue/$queueId.json").isFile)
        assertTrue(files(branch.resolve("inbox")).isEmpty())
        for (id in listOf(feedbackId, setId, queueId, budgetId)) assertTrue(result(out, id).ok)
        sync.push(branch)

        enqueue(branch, InboxRequest.Reaction(1, feedbackId, time, "card-1", Feedback.Kind.STAR, "reason", "duplicate"))
        enqueue(branch, InboxRequest.PromptReset(1, resetId, time, "criteria", PromptResolver.hash("An override")))
        enqueue(branch, InboxRequest.PromptSet(1, rejectedId, time, "track", "", PromptResolver.hash(PromptCatalog.defaultText("track"))))
        val secondOut = out.resolveSibling("second-out")
        val secondPrompts = prompts.resolveSibling("second-prompts")
        val second = DataSync(secondOut, secondPrompts, clock, pipelineConfig = out.resolve("pipeline.local.json"))
        second.pull(branch)
        assertEquals("memo", outputJson.decodeFromString<List<Feedback>>(secondOut.resolve("feedback.json").readText()).single().memo)
        assertFalse(secondPrompts.resolve("criteria.md").exists())
        assertTrue(result(secondOut, resetId).ok)
        assertEquals("rejected", result(secondOut, rejectedId).code)
        second.push(branch)
        assertFalse(branch.resolve("config/prompts/criteria.md").exists())
        assertTrue(branch.resolve("config/prompts/criteria.versions.json").isFile)
    }

    @Test fun invalidRequestsAreRejectedWithoutBlockingOthers() = withWorkspace { out, branch, prompts ->
        val malformed = id()
        val badBudget = id()
        val enormousBudget = id()
        val valid = id()
        val time = now.toString()
        write(branch, "inbox/$time-$malformed.json".replace(':', '-'), "{")
        for ((id, usd) in listOf(badBudget to "-0.5", enormousBudget to "1e999")) {
            write(branch, "inbox/${time.replace(":", "")}-$id.json", """
                {"type":"settings.set","schemaVersion":1,"requestId":"$id","createdAt":"$time",
                 "budget":{"dailyCardUsd":$usd}}
            """.trimIndent())
        }
        enqueue(branch, InboxRequest.Analysis(1, valid, time, listOf("theme"), AnalysisMode.RUN, 0.0))
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).pull(branch)
        assertEquals(1, files(out.resolve("inbox-results")).count { it.name.startsWith("invalid-") })
        assertEquals("rejected", result(out, badBudget).code)
        assertEquals("rejected", result(out, enormousBudget).code)
        assertTrue(result(out, valid).ok)
        assertFalse(out.resolve("settings/budget.json").exists())
    }

    @Test fun rejectedFileCannotClaimValidRequestId() = withWorkspace { out, branch, prompts ->
        val key = id()
        val name = "2026-11-22T000000Z-$key.json"
        val badRequest = """{"requestId":"$key"}"""
        write(branch, "inbox/$name", badRequest)
        enqueue(branch, InboxRequest.Reaction(1, key, now.toString(), "card", Feedback.Kind.STAR, "reason"))
        val sync = DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json"))
        sync.pull(branch)
        assertTrue(result(out, key).ok)
        sync.push(branch)
        val invalid = files(branch.resolve("out/inbox-results")).single { it.name.startsWith("invalid-") }
        val hash = MessageDigest.getInstance("SHA-256").digest((badRequest + name).toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        assertEquals("invalid-$hash", invalid.nameWithoutExtension)
    }

    @Test fun promptBaseMismatchIsRecordedUnderOriginalRequestId() = withWorkspace { out, branch, prompts ->
        val key = id()
        enqueue(branch, InboxRequest.PromptSet(1, key, now.toString(), "criteria", "Override", "stale"))
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).pull(branch)
        assertEquals("rejected", result(out, key).code)
        assertEquals("Prompt baseHash does not match", result(out, key).reason)
        assertFalse(prompts.resolve("criteria.md").exists())
    }

    @Test fun publicationRedactsEncodedValuesAndKeysAndBoundsSummary() = withWorkspace { out, branch, prompts ->
        val summary = "x".repeat(301)
        write(out, "2026-11-23.json", """{"classification":{"summary":"$summary"},"private@example.com":"drop","text":"me%40example.com and me&#64;example.com"}""")
        write(out, "2026-11-23.html", "<p>&#60;b&#62; and me&#64;example.com</p>")
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).push(branch)
        val published = branch.resolve("out/2026-11-23.json").readText()
        assertFalse(published.contains("example.com"))
        assertFalse(published.contains("drop"))
        assertTrue(published.contains("x".repeat(300)))
        assertFalse(published.contains(summary))
        assertEquals("<p>&#60;b&#62; and [이메일]</p>", branch.resolve("out/2026-11-23.html").readText())
    }

    @Test fun internationalEmailsAreRedactedAfterDecoding() = withWorkspace { out, branch, prompts ->
        write(out, "2026-11-23.txt",
            "ops@bücher.example ops%40b%C3%BCcher.example 운영@bücher.example e\u0301@bücher.example q%CC%81%40bücher.example")
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).push(branch)
        assertEquals("[이메일] [이메일] [이메일] [이메일] [이메일]", branch.resolve("out/2026-11-23.txt").readText())
    }

    @Test fun quotedLocalPartEmailsAreRedacted() = withWorkspace { out, branch, prompts ->
        write(out, "2026-11-23.txt", "\"first last\"@example.com and \"a\\\"b\"@example.org")
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).push(branch)
        assertEquals("[이메일] and [이메일]", branch.resolve("out/2026-11-23.txt").readText())
    }

    @Test fun republishKeepsNewerRemoteCopyOfAFileThisRunDidNotChange() = withWorkspace { out, branch, prompts ->
        // A file carried from the pull and left untouched locally must not overwrite a newer remote version
        // (for example a cost ledger updated by a mid-run state push).
        write(branch, "out/cost-ledger/2026-11-23.json", "{\"pulled\":true}")
        val sync = DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json"))
        sync.pull(branch, processInbox = false)
        branch.resolve("out/cost-ledger/2026-11-23.json").writeText("{\"newer\":true}")
        sync.push(branch)
        assertEquals("{\"newer\":true}", branch.resolve("out/cost-ledger/2026-11-23.json").readText())
        // A file this run did change is still published.
        out.resolve("cost-ledger/2026-11-23.json").writeText("{\"changed\":true}")
        sync.push(branch)
        assertTrue(branch.resolve("out/cost-ledger/2026-11-23.json").readText().contains("changed"))
    }

    @Test fun rebuildPreservesRemoteOnlyOutputAndReappliesRetention() = withWorkspace { out, branch, prompts ->
        val key = id()
        val paths = listOf("analysis/20261123-000000000-01234567.json", "analysis-queue/$key.json",
            "cost-ledger/2026-11-23.json", "batch/public/2026-11-23-classify.json")
        for (path in paths) write(branch, "out/$path", "{}")
        saveResult(branch.resolve("out"), InboxResult(requestId = key, ok = true, processedAt = now.toString()))
        write(branch, "out/2020-01-01.txt", "expired")
        write(out, "2026-11-23.txt", "local")
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).push(branch)
        for (path in paths) assertEquals("{}", branch.resolve("out/$path").readText())
        assertTrue(result(branch.resolve("out"), key).ok)
        assertFalse(branch.resolve("out/2020-01-01.txt").exists())
        assertEquals("local", branch.resolve("out/2026-11-23.txt").readText())
    }

    @Test fun publicationDeletesOnlyMissingSnapshotFiles() = withWorkspace { out, branch, prompts ->
        val sync = DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json"))
        val removed = "analysis-queue/" + id() + ".json"
        val remoteOnly = "analysis-queue/" + id() + ".json"
        write(branch, "out/$removed", "{}")
        sync.pull(branch, processInbox = false)
        assertTrue(out.resolve(removed).delete())
        write(branch, "out/$remoteOnly", "{}")
        sync.push(branch)
        assertFalse(branch.resolve("out/$removed").exists())
        assertTrue(branch.resolve("out/$remoteOnly").isFile)
    }

    @Test fun dailyRecoversOnlyStaleRunningResultsWithoutQueueFiles() = withWorkspace { out, _, _ ->
        val stale = id()
        val fresh = id()
        for ((key, age) in listOf(stale to 6L, fresh to 5L)) {
            saveResult(out, InboxResult(requestId = key, ok = false, status = "running",
                processedAt = now.minusSeconds(age * 3600).toString()))
        }
        runBlocking { runMain(arrayOf("--data-analysis-queue"), out, scheduledClock = clock) }
        assertEquals("확인 필요 — 새 requestId로 다시 요청", result(out, stale).reason)
        assertEquals("running", result(out, fresh).status)
    }

    @Test fun publicStateSupportsHistoryTrackingTrendsAndMailResend() = withWorkspace { out, branch, prompts ->
        val day = LocalDate.parse("2026-11-23")
        val item = Item("hn", "1", "Workflow automation", "https://example.com/tool", 55, publishedAt = now,
            snippet = "original snippet", author = "private-author", commentCount = 13,
            discussionUrl = "https://news.ycombinator.com/item?id=1", extra = mapOf("license" to "MIT", "contact" to "alice%40example.com"),
            alsoSeenIn = listOf("github"), profileHint = listOf("tech-signal"), comments = listOf(Comment("reader", "private comment")),
            trackHint = listOf("track"), mergedUrls = listOf("https://example.com/alias"))
        val signal = Signal(item, ClassifiedItem(item.externalId, "tech-signal", 8.0, "", "classification text",
            listOf("automation"), "workflow-automation", "Workflow automation", listOf("track")), body = "private body")
        val card = IdeaCard(id = "card", title = "Automation", oneLiner = "", whyToday = "", problem = "",
            techSignals = listOf(collector.model.SignalRef(item.key, item.url, "")), demandSignals = emptyList(),
            competitors = emptyList(), history = emptyList(), effort = "", differentiation = "", biggestRisk = "",
            validationPlan = "", caveats = emptyList(), category = "software", tags = emptyList())
        write(out, "$day.json", outputJson.encodeToString(PipelineResult(listOf(item), emptyList(),
            RunReport(day.toString(), now.toString(), emptyList(), emptyList(), emptyMap(), 0),
            classified = listOf(signal), cards = listOf(card))))
        write(out, "$day.txt", "Daily report\nStored body")
        write(out, "$day.html", "<p>Stored body</p>")
        write(out, "mail/sent.json", """[{"at":"$now","kind":"daily","key":"$day","result":"sent","recipientCount":1}]""")
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).push(branch)

        val resumed = out.resolveSibling("resumed")
        DataSync(resumed, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).pull(branch)
        val history = FileHistorySearch(resumed, 30)
        assertTrue(history.search(signal, day.plusDays(1)).any { it.summary == "original snippet" })
        val historyRun = requireNotNull(history.cardRun(card.id))
        assertTrue("workflow-automation" in TrackingKeywords.extract(historyRun.cards.single(), historyRun.classified))
        val restored = TrendInput.read(resumed, day).earlierClassifications.single()
        assertEquals(signal.classification, restored.classification)
        assertEquals(item.copy(author = null, comments = emptyList(), extra = mapOf("license" to "MIT", "contact" to "[이메일]")), restored.item)
        assertEquals("", restored.body)
        var sent = 0
        val delivery = MailDelivery(resumed, Mailer { message ->
            assertEquals("Daily report", message.subject)
            sent++
        }, clock = clock)
        runMailCommand(arrayOf("--mail", "daily", "--date", day.toString(), "--resend"), resumed, { null }, delivery)
        assertEquals(1, sent)
    }

    @Test fun publicJournalKeepsDeliveryStateAndRecipientCount() = withWorkspace { out, branch, prompts ->
        write(out, "mail/sent.json", """
            [{"at":"$now","kind":"daily","key":"2026-11-23","result":"sent","recipientCount":2,
              "recipient":"private-value","issue":"private-value"}]
        """.trimIndent())
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).push(branch)
        val journal = branch.resolve("out/mail/sent.json").readText()
        assertFalse(journal.contains("private-value"))
        assertFalse(journal.contains("\"issue\""))
        val artifact = out.resolve("mail/public-journal.json")
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).mailArtifact(artifact)
        assertEquals(journal, artifact.readText())
        val row = outputJson.decodeFromString<List<collector.mail.MailDelivery.Attempt>>(journal).single()
        assertEquals(2, row.recipientCount)
        assertEquals("sent", row.result)
        assertEquals("2026-11-23", row.key)
    }

    @Test fun publicationRebuildReplacesFileDirectoryCollisions() = withWorkspace { out, branch, prompts ->
        write(branch, "out/2026-11-23.json/old", "wrong shape")
        write(branch, "config", "wrong shape")
        write(branch, "manifest.json/old", "wrong shape")
        write(out, "2026-11-23.json", "{}")
        write(prompts, "criteria.md", "current")
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).push(branch)
        assertEquals("{}", branch.resolve("out/2026-11-23.json").readText())
        assertEquals("current", branch.resolve("config/prompts/criteria.md").readText())
        assertTrue(branch.resolve("manifest.json").isFile)
    }

    @Test fun trackingUpdatesAndExistingOutputsRoundTrip() = withWorkspace { out, branch, prompts ->
        val time = now.toString()
        val start = id()
        val keywords = id()
        val stop = id()
        val card = IdeaCard(id = "card-1", title = "Card title", oneLiner = "", whyToday = "",
            problem = "", techSignals = emptyList(), demandSignals = emptyList(), competitors = emptyList(),
            history = emptyList(), effort = "", differentiation = "", biggestRisk = "", validationPlan = "",
            caveats = emptyList(), category = "example", tags = emptyList())
        val report = PipelineResult(emptyList(), emptyList(),
            RunReport("2026-11-23", time, emptyList(), emptyList(), emptyMap(), 0), cards = listOf(card))
        write(out, "2026-11-23.json", outputJson.encodeToString(report))
        val sync = DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json"))
        sync.push(branch)
        enqueue(branch, InboxRequest.TrackStart(1, start, time, card.id))
        sync.pull(branch)
        assertTrue(result(out, start).ok)
        assertEquals(report, outputJson.decodeFromString<PipelineResult>(out.resolve("2026-11-23.json").readText()))
        sync.push(branch)
        enqueue(branch, InboxRequest.TrackKeywords(1, keywords, time, start, listOf("updated")))
        enqueue(branch, InboxRequest.TrackStop(1, stop, time, start))
        sync.pull(branch)
        val tracked = outputJson.decodeFromString<List<TrackedIdea>>(out.resolve("tracked.json").readText()).single()
        assertEquals(TrackedIdea.Status.STOPPED, tracked.status)
        assertEquals(listOf("updated"), tracked.keywords)
        assertTrue(result(out, keywords).ok)
        assertTrue(result(out, stop).ok)
    }

    @Test fun feedbackUndoPreservesNewerDismissalsAndOtherCards() = withWorkspace { out, branch, prompts ->
        val old = Feedback("card", Feedback.Kind.NOT_INTERESTED, "old", at = now.minusSeconds(2))
        val newer = old.copy(reason = "new", at = now.minusSeconds(1))
        val other = old.copy(cardId = "other")
        write(out, "feedback.json", outputJson.encodeToString(listOf(newer, old, other)))
        val sync = DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json"))
        sync.push(branch)
        val undo = id()
        enqueue(branch, InboxRequest.FeedbackUndo(1, undo, now.toString(), "card", old.at.toString()))
        sync.pull(branch)
        assertTrue(result(out, undo).ok)
        assertEquals(listOf(newer, other), outputJson.decodeFromString<List<Feedback>>(out.resolve("feedback.json").readText()))
    }

    @Test fun publicationRedactsFreeTextAndKeepsLocalCopies() = withWorkspace { out, branch, prompts ->
        // Fake values, split so the repository secret scan does not flag the fixture itself.
        val sensitive = "private@example.com " + "sk-" + "ant-example " + "ghp" + "_example Bearer example-value"
        write(out, "feedback.json", outputJson.encodeToString(
            listOf(Feedback("card", Feedback.Kind.STAR, sensitive, sensitive, now))))
        write(prompts, "criteria.md", sensitive)
        write(out, "2026-11-23.json", """{"classified":[{"body":"full-body-private","item":{"source":"hn","url":"https://example.com","title":"$sensitive","author":"author-private","comments":["comment-private"],"snippet":"summary"}}]}""")
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).push(branch)
        for (path in listOf("out/feedback.json", "out/2026-11-23.json", "config/prompts/criteria.md")) {
            val published = branch.resolve(path).readText()
            for (secret in sensitive.split(' ') + listOf("author-private", "comment-private", "full-body-private")) {
                assertFalse(published.contains(secret), path)
            }
        }
        assertEquals(sensitive, prompts.resolve("criteria.md").readText())
    }

    @Test fun invalidInboxPathsAreRejected() = withWorkspace { out, branch, prompts ->
        val inbox = branch.resolve("inbox")
        val invalid = listOf("../outside", out.absolutePath, "bad-uuid")
        for (requestId in invalid) {
            write(inbox, "${now.toString().replace(":", "")}-${id()}.json", inboxJson.encodeToString<InboxRequest>(
                InboxRequest.Analysis(1, requestId, now.toString(), listOf("theme"), AnalysisMode.RUN, 0.1)))
        }
        write(inbox, "nested/request.json", "{}")
        write(inbox, "invalid.txt", "{}")
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).pull(branch)
        assertTrue(inbox.listFiles().orEmpty().isEmpty())
        val results = files(out.resolve("inbox-results"))
        assertEquals(5, results.size)
        assertTrue(results.all {
            val result = inboxJson.decodeFromString<InboxResult>(it.readText())
            !result.ok && result.code == "internal_error"
        })
    }

    @Test fun localImportMergesMailByDeliveryState() = withWorkspace { out, branch, prompts ->
        val date = "2026-11-23"
        fun row(kind: String, state: String, at: Instant = now) =
            """{"at":"$at","kind":"$kind","key":"$date","result":"$state","recipientCount":1}"""
        write(out, "mail/sent.json", listOf(row("daily", "sent"), row("trend", "failed")).joinToString(",", "[", "]"))
        write(branch, "out/mail/sent.json",
            listOf(row("daily", "failed", now.plusSeconds(1)), row("trend", "unknown")).joinToString(",", "[", "]"))
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).pull(branch, processInbox = false, importOnly = true)
        val journal = out.resolve("mail/sent.json").readText()
        val states = outputJson.decodeFromString<List<collector.mail.MailDelivery.Attempt>>(journal)
            .associate { it.kind to it.result }
        assertEquals(mapOf("daily" to "sent", "trend" to "unknown"), states)
    }

    @Test fun expansionSeedHistoryRestoresInFreshWorkspace() = withWorkspace { out, branch, prompts ->
        val name = "expansion-seeds.json"
        val seeds = listOf(collector.stage.UsedExpansionSeed("2026-11-23", "speech", "회계", "invert"))
        write(out, name, outputJson.encodeToString(seeds))
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).push(branch)
        val fresh = out.resolveSibling("fresh")
        DataSync(fresh, prompts, clock, pipelineConfig = fresh.resolve("pipeline.local.json")).pull(branch)
        assertEquals(seeds, outputJson.decodeFromString<List<collector.stage.UsedExpansionSeed>>(fresh.resolve(name).readText()))
    }

    @Test fun batchCheckoutRestoresOnlyPublicCheckpoint() = withWorkspace { out, branch, prompts ->
        val checkpoint = """{"batchId":"msgbatch_test","runId":"test-run","submittedAt":"$now","requests":{
            "classify-2026-11-23-test-1":{"fingerprint":"fingerprint-test","itemKeys":["hn:1"],
            "reservation":{"date":"2026-11-23","id":"reservation-id","amount":0.1},
            "request":{"model":"test","system":"private system","user":"private user",
            "jsonSchema":{},"schemaName":"test","maxOutputTokens":100}}
            },"call":{"stage":"classify","provider":"ANTHROPIC","model":"test","promptId":"classify",
            "promptVersion":"1","error":"private response"},"settled":true,
            "supplementRefs":[{"url":"https://example.com/result","title":"Public title alice%40example.com","note":""}]}"""
        write(out, "batch/2026-11-23-classify.json", checkpoint)
        write(out, "batch/2026-11-23-generate.json", checkpoint)
        write(out, "batch/2026-11-23-backfill.json.retry.json", """{"firstSettleFailureAt":"$now"}""")
        val unsettledId = "2026-11-23-classify.json/reservation-id"
        write(out, "batch/quarantine/msgbatch_test.unsettled.json",
            """[{"date":"2026-11-23","id":"$unsettledId","amount":0.1}]""")
        val date = LocalDate.now(clock)
        val retryId = "2026-11-23-backfill.json/reservation-id"
        val ledger = FileCostLedger(out.resolve("cost-ledger"))
        ledger.reserve(date, unsettledId, 0.1)
        ledger.reserve(date, retryId, 0.2)
        write(out, "batch/msgbatch_test.results.jsonl", "private results")
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).push(branch)
        val published = branch.resolve("out/batch/public/2026-11-23-classify.json").readText()
        assertFalse(published.contains("private"))
        for (value in listOf("msgbatch_test", "test-run", "classify-2026-11-23-test-1", "hn:1", "fingerprint-test", "reservation-id", "settled")) {
            assertTrue(published.contains(value), value)
        }
        assertFalse(branch.resolve("out/batch/msgbatch_test.results.jsonl").exists())
        val generation = branch.resolve("out/batch/public/2026-11-23-generate.json").readText()
        assertTrue(generation.contains("https://example.com/result"))
        assertFalse(generation.contains("alice"))
        val resumed = out.resolveSibling("resumed")
        DataSync(resumed, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).pull(branch)
        assertEquals(published, resumed.resolve("batch/2026-11-23-classify.json").readText())
        assertTrue(resumed.resolve("batch/quarantine/msgbatch_test.unsettled.json").readText().contains("reservation-id"))
        assertTrue(resumed.resolve("batch/2026-11-23-backfill.json.retry.json").readText().contains(now.toString()))
        val resumedLedger = FileCostLedger(resumed.resolve("cost-ledger"))
        val issues = mutableListOf<String>()
        assertTrue(resumedLedger.hasReservation(date, unsettledId))
        assertTrue(resumedLedger.hasReservation(date, retryId))
        runBlocking { recoverQuarantined(resumed.resolve("batch"), CostGuard(1.0, clock, ledger = resumedLedger), issues) }
        assertTrue(issues.isEmpty())
        assertTrue(resumedLedger.batchReservations("2026-11-23-classify.json").values.all { it.isEmpty() })
        assertTrue(resumedLedger.batchReservations("2026-11-23-backfill.json").values.all { it.isEmpty() })
        assertFalse(resumed.resolve("batch/quarantine/msgbatch_test.unsettled.json").exists())
        assertFalse(resumed.resolve("batch/2026-11-23-backfill.json.retry.json").exists())
        assertEquals(checkpoint, out.resolve("batch/2026-11-23-classify.json").readText())
    }

    @Test fun runningAnalysisBecomesConfirmationRequired() = withWorkspace { out, _, _ ->
        val key = id()
        val request = InboxRequest.Analysis(1, key, now.toString(), listOf("theme"), AnalysisMode.RUN, 0.1)
        write(out, "analysis-queue/$key.json", inboxJson.encodeToString<InboxRequest>(request))
        saveResult(out, InboxResult(requestId = key, ok = false, dispatchRunId = "123", status = "running"))
        runBlocking { runMain(arrayOf("--data-analysis-queue"), out, scheduledClock = clock) }
        assertEquals("확인 필요 — 새 requestId로 다시 요청", result(out, key).reason)
        assertFalse(out.resolve("analysis-queue/$key.json").exists())
    }

    @Test fun failedPullPreservesPreviousState() = withWorkspace { out, branch, prompts ->
        write(out, "2026-11-23.json", "previous daily")
        write(branch, "out/2026-11-23.json", "new daily")
        write(branch, "out/batch/public/2026-11-23-classify.json", "invalid checkpoint")
        assertFails { DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).pull(branch) }
        assertEquals("previous daily", out.resolve("2026-11-23.json").readText())
    }

    @Test fun retentionKeepsActiveTrackingDaysAndExpiresInboxResults() = withWorkspace { out, branch, prompts ->
        val startedAt = Instant.parse("2026-07-01T00:00:00Z")
        val tracked = TrackedIdea("tracked", "card", "Title", "category", listOf("keyword"), emptyList(),
            TrackedIdea.Status.TRACKING, startedAt, null, 0.0, TrackedIdea.Trend.FLAT)
        write(out, "tracked.json", outputJson.encodeToString(listOf(tracked)))
        for (path in listOf("2026-07-01.json", "state/2026-07-01.json", "2026-06-30.json", "state/2026-06-30.json")) {
            write(out, path, "{}")
        }
        val expired = InboxResult(requestId = id(), ok = true, processedAt = startedAt.toString())
        val recent = InboxResult(requestId = id(), ok = true, processedAt = now.toString())
        saveResult(out, expired)
        saveResult(out, recent)
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json"), historyDays = 90).push(branch)
        for (prefix in listOf("out/", "out/state/")) {
            assertTrue(branch.resolve(prefix + "2026-07-01.json").isFile)
            assertFalse(branch.resolve(prefix + "2026-06-30.json").exists())
        }
        assertFalse(resultFile(branch.resolve("out"), expired.requestId).exists())
        assertTrue(resultFile(branch.resolve("out"), recent.requestId).isFile)
    }

    @Test fun dailyQueueDefersRunAndExpiresUnpairedRequest() = withWorkspace { out, branch, prompts ->
        val pending = id()
        val expired = id()
        enqueue(branch, InboxRequest.Analysis(1, pending, now.toString(), listOf("theme"), AnalysisMode.RUN, 0.1))
        enqueue(branch, InboxRequest.Analysis(1, expired, now.minusSeconds(7 * 86_400L).toString(),
            listOf("theme"), AnalysisMode.ESTIMATE, 0.1))
        DataSync(out, prompts, clock, pipelineConfig = out.resolve("pipeline.local.json")).pull(branch)
        runBlocking { runMain(arrayOf("--data-analysis-queue"), out, scheduledClock = clock) }
        assertEquals("analysis_pending", result(out, pending).code)
        assertTrue(out.resolve("analysis-queue/$pending.json").isFile)
        assertEquals("expired", result(out, expired).code)
        assertFalse(out.resolve("analysis-queue/$expired.json").exists())
    }

    private fun id() = UUID.randomUUID().toString()
    private fun result(out: File, id: String) = inboxJson.decodeFromString<InboxResult>(resultFile(out, id).readText())

    private fun enqueue(branch: File, request: InboxRequest) {
        val time = request.createdAt.replace(":", "")
        write(branch, "inbox/$time-${request.requestId}.json", inboxJson.encodeToString(request))
    }

    private fun write(root: File, path: String, content: String) {
        val file = root.resolve(path)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun withWorkspace(test: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory("data-sync").toFile()
        try {
            test(root.resolve("out"), root.resolve("branch"), root.resolve("prompts"))
        } finally {
            root.deleteRecursively()
        }
    }
}

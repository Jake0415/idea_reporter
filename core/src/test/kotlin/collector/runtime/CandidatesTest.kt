package collector.runtime

import collector.model.*
import collector.pipeline.PipelineResult
import collector.dev.outputJson
import collector.llm.*
import collector.config.PipelineConfig
import collector.budget.BudgetSettings
import collector.budget.MonthlyBudget
import collector.report.HtmlRenderer
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandidatesTest {
    @TempDir lateinit var root: File

    @Test
    fun savingRequestedCardLeavesDailyAndMailJournalAlone() = runBlocking {
        val fixture = fixture()
        val card = fixture.card
        val quote = fixture.runtime.estimateRequestedCards(fixture.date, fixture.keys, false)
        assertTrue(quote.allowed)
        val denied = fixture.runtime.runRequestedCards(fixture.date, fixture.keys, false, 0.0)
        assertTrue(denied.retryAllowed)
        assertEquals(quote, denied.requote)
        assertEquals(0, fixture.calls)
        var mailChecks = 0
        val result = fixture.runtime.runRequestedCards(fixture.date, fixture.keys, false, requireNotNull(quote.estimatedUsd),
            mailEnabled = { ++mailChecks == 1 })
        assertEquals(1, result.cardIds.size)
        assertEquals(1, fixture.calls)
        assertEquals(0, fixture.sent)
        assertEquals(0.0, fixture.runtime.dailyCost(fixture.date))
        assertFalse(fixture.runtime.dailyBudgetExceeded(fixture.date))
        val monthly = MonthlyBudget(fixture.paths.outDir, BudgetSettings(monthlyTotalUsd = 1.0))
        assertEquals(0.99, requireNotNull(monthly.remaining(fixture.date)), 1e-9)
        assertEquals(0.50, fixture.runtime.dailyCardBudgetRemaining(fixture.date), 1e-9)
        assertTrue(fixture.paths.outDir.resolve("cost-ledger-requested/${fixture.date}.json").isFile)
        assertEquals(2, mailChecks)
        assertEquals(fixture.dailyJson, fixture.dayFile.readText())
        assertFalse(fixture.paths.outDir.resolve("mail").exists())
        val saved = outputJson.decodeFromString<PipelineResult>(
            fixture.paths.outDir.resolve("requested").listFiles()!!.single().readText())
        assertEquals(card.title, saved.cards.single().title)
        assertEquals(listOf("내가 고른 카드"), saved.cards.single().badges)
        val backup = Backup.read(Backup.create(fixture.paths).byteInputStream())
        val target = CollectorPaths(root.resolve("restored/out"), root.resolve("restored/config"))
        assertEquals(1, Backup.preview(target, backup).cards)
        Backup.restore(target, backup)
        assertEquals(0, Backup.preview(target, backup).cards)
        Backup.restore(target, backup)
        val restored = outputJson.decodeFromString<PipelineResult>(target.outDir.resolve("requested").listFiles()!!.single().readText())
        assertEquals(saved.cards, restored.cards)
        assertTrue(restored.report.llmCalls.isEmpty())
        assertFalse(target.outDir.resolve("cost-ledger-requested/${fixture.date}.json").exists())
    }

    @Test
    fun requestingOlderCandidatesKeepsPaidRepeatWithActualCreationDate() = runBlocking {
        val fixture = fixture()
        val candidateDate = fixture.date.minusDays(40).toString()
        val priorCreationDate = fixture.date.minusDays(2)
        val priorCreatedAt = fixture.clock.instant().minusSeconds(2 * 86_400L).toString()
        val run = outputJson.decodeFromString<PipelineResult>(fixture.dailyJson)
        val source = run.copy(report = run.report.copy(runDate = candidateDate))
        fixture.paths.outDir.resolve("$candidateDate.json").writeText(outputJson.encodeToString(source))
        val directory = fixture.paths.outDir.resolve("requested").apply { mkdirs() }
        directory.resolve("$candidateDate-prior.json").writeText(outputJson.encodeToString(
            source.copy(report = source.report.copy(startedAt = priorCreatedAt),
                cards = listOf(fixture.card.copy(id = "prior", runDate = candidateDate,
                    title = "다른 회의 기록 앱", demandSignals = emptyList())))
        ))
        val result = fixture.runtime.runRequestedCards(LocalDate.parse(candidateDate), fixture.keys, false, 0.50)
        assertEquals(1, fixture.calls)
        assertEquals(1, result.cardIds.size)
        val saved = directory.listFiles()!!.map { outputJson.decodeFromString<PipelineResult>(it.readText()) }
            .single { it.report.startedAt == fixture.clock.instant().toString() }
        val warning = "최근 카드와 비슷함: 다른 회의 기록 앱 ($priorCreationDate)"
        assertEquals(listOf(warning), saved.cards.single().caveats)
        val rendered = HtmlRenderer(emptyList()).render(saved)
        assertTrue(warning in rendered.html && warning in rendered.text)
    }

    @Test
    fun requestingSameCandidatesAgainRejectsMatchingEvidenceOrTitle() = runBlocking {
        val fixture = fixture()
        suspend fun request() = fixture.runtime.runRequestedCards(fixture.date, fixture.keys, false, 0.50)
        assertEquals(1, request().cardIds.size)
        val original = fixture.card
        assertTrue(request().cardIds.isEmpty())
        fixture.card = original.copy(title = "다른 제목")
        assertTrue(request().cardIds.isEmpty())
        val run = outputJson.decodeFromString<PipelineResult>(fixture.dailyJson)
        val demand = run.classified.last()
        val alternativeItem = demand.item.copy(externalId = "alternative", url = "https://example.invalid/alternative")
        val alternative = demand.copy(item = alternativeItem)
        fixture.dayFile.writeText(outputJson.encodeToString(run.copy(classified = run.classified + alternative)))
        fixture.card = original.copy(demandSignals = listOf(SignalRef(alternativeItem.key, alternativeItem.url, "근거")))
        assertTrue(request().cardIds.isEmpty())
        assertEquals(4, fixture.calls)
        assertEquals(1, fixture.paths.outDir.resolve("requested").listFiles()!!.size)
    }

    @Test
    fun quoteRequiresLocalEvidenceEvenWhenResearchIsEnabled() = runBlocking {
        val fixture = fixture()
        val run = outputJson.decodeFromString<PipelineResult>(fixture.dailyJson)
        val tech = run.classified.first()
        val other = run.classified.last().let { it.copy(classification = it.classification.copy(profile = "tech-signal")) }
        fun write(signals: List<Signal>) {
            fixture.dayFile.writeText(outputJson.encodeToString(run.copy(classified = signals)))
        }
        write(listOf(tech))
        for (research in listOf(false, true)) {
            val quote = fixture.runtime.estimateRequestedCards(fixture.date, fixture.keys.take(1), research)
            assertFalse(quote.allowed)
            assertEquals(RequestedCardBlock.INSUFFICIENT_EVIDENCE, quote.block)
            assertTrue(fixture.runtime.runRequestedCards(fixture.date, fixture.keys.take(1), research, 0.50).cardIds.isEmpty())
        }
        write(listOf(tech, other))
        assertTrue(fixture.runtime.estimateRequestedCards(fixture.date, fixture.keys, false).allowed)
        assertEquals(0, fixture.calls)
    }

    @Test
    fun blocksRecoveryAndDoesNotRetryPaidFailures() = runBlocking {
        val fixture = fixture()
        val quote = fixture.runtime.estimateRequestedCards(fixture.date, fixture.keys, false)
        Backup.markRecoveryPending(fixture.paths)
        assertEquals(RequestedCardBlock.RECOVERY_PENDING,
            fixture.runtime.estimateRequestedCards(fixture.date, fixture.keys, false).block)
        assertFailsWith<IllegalStateException> {
            fixture.runtime.runRequestedCards(fixture.date, fixture.keys, false, requireNotNull(quote.estimatedUsd))
        }
        assertEquals(0, fixture.calls)
        Backup.completeRecovery(fixture.paths)
        fixture.fail = true
        val failure = fixture.runtime.runRequestedCards(fixture.date, fixture.keys, false, requireNotNull(quote.estimatedUsd))
        assertFalse(failure.retryAllowed)
        assertEquals(1, fixture.calls)
    }

    @Test
    fun restoresClassifiedMergedCitationsAndDropsUnverifiedUrls() = runBlocking {
        val fixture = fixture()
        val run = outputJson.decodeFromString<PipelineResult>(fixture.dailyJson)
        val original = run.classified.first()
        val member = original.item.copy(externalId = "merged", url = "https://example.invalid/merged")
        val unknown = "https://example.invalid/unknown"
        val representative = original.copy(item = original.item.copy(mergedUrls = listOf(member.url, unknown)))
        fixture.dayFile.writeText(outputJson.encodeToString(run.copy(
            dropped = listOf(DroppedItem(member, DropReason.SAME_TOPIC, original.item.key)),
            classified = listOf(representative, run.classified.last(),
                original.copy(item = member, classification = original.classification.copy(externalId = member.key)))
        )))
        val tech = fixture.card.techSignals.single()
        fixture.card = fixture.card.copy(techSignals = listOf(tech.copy(url = member.url), tech.copy(url = unknown)))
        val result = fixture.runtime.runRequestedCards(fixture.date, fixture.keys, false, 0.5)
        assertEquals(1, result.cardIds.size)
        val saved = outputJson.decodeFromString<PipelineResult>(
            fixture.paths.outDir.resolve("requested").listFiles()!!.single().readText())
        assertEquals(listOf(member.url), saved.cards.single().techSignals.map { it.url })
    }

    @Test
    fun privateQueryInSelectedUrlBlocksPaidCalls() = runBlocking {
        val fixture = fixture()
        val run = outputJson.decodeFromString<PipelineResult>(fixture.dailyJson)
        val original = run.classified.first()
        val privateSignal = original.copy(item = original.item.copy(url = "https://example.invalid/?token=fixture"))
        fixture.dayFile.writeText(outputJson.encodeToString(run.copy(classified = listOf(privateSignal, run.classified.last()))))
        assertEquals(RequestedCardBlock.PRIVATE_URL,
            fixture.runtime.estimateRequestedCards(fixture.date, fixture.keys, true).block)
        assertTrue(fixture.runtime.runRequestedCards(fixture.date, fixture.keys, true, 0.5).cardIds.isEmpty())
        assertEquals(0, fixture.calls)
    }

    @Test
    fun citedCandidatesAreExcludedFromOptionalMailSection() {
        val fixture = fixture()
        val run = outputJson.decodeFromString<PipelineResult>(fixture.dailyJson)
        assertFalse(HtmlRenderer(emptyList()).render(run).html.contains("오늘 후보(카드 안 된 것)"))
        val cited = run.copy(cards = listOf(fixture.card.copy(demandSignals = emptyList())))
        val view = candidateView(cited)
        assertEquals(listOf(fixture.card.id), view.candidates.first().cardIds)
        val rendered = HtmlRenderer(emptyList(), includeCandidates = true).render(cited)
        assertTrue(rendered.html.contains("오늘 후보(카드 안 된 것)"))
        assertTrue(rendered.text.contains("사람들의 요청·불편"))
        assertFalse(rendered.text.contains("쓸 수 있는 기술"))
    }

    @Test
    fun defaultQuotesAndSeparateRequestLimits() = runBlocking {
        val fixture = fixture()
        val run = outputJson.decodeFromString<PipelineResult>(fixture.dailyJson)
        val third = run.classified.first().let { signal ->
            val item = signal.item.copy(externalId = "third", url = "https://example.invalid/third")
            signal.copy(item = item, classification = signal.classification.copy(externalId = item.key))
        }
        fixture.dayFile.writeText(outputJson.encodeToString(run.copy(classified = run.classified + third)))
        for (keys in listOf(fixture.keys.take(1), fixture.keys + third.item.key)) {
            for (research in listOf(false, true)) {
                val quote = fixture.runtime.estimateRequestedCards(fixture.date, keys, research)
                val amount = requireNotNull(quote.estimatedUsd)
                println("C1 quote ${keys.size} research=$research USD=$amount max=${quote.researchMaxUsd}")
                assertTrue(quote.allowed)
                assertTrue(amount < 0.50)
            }
        }
        BudgetSettings.update(fixture.paths.outDir, listOf("requestCardUsd=0.01"))
        assertEquals(RequestedCardBlock.REQUEST_LIMIT, fixture.runtime.estimateRequestedCards(fixture.date, fixture.keys, false).block)
        BudgetSettings.update(fixture.paths.outDir, listOf("requestCardUsd=0.50", "monthlyTotalUsd=0.01"))
        assertEquals(RequestedCardBlock.MONTHLY_LIMIT, fixture.runtime.estimateRequestedCards(fixture.date, fixture.keys, false).block)
        BudgetSettings.update(fixture.paths.outDir, listOf("monthlyTotalUsd=null"))
        val ledger = collector.dev.requestedCostLedger(fixture.paths.outDir)
        ledger.reserve(fixture.date, "fixture", 0.20)
        ledger.settle(fixture.date, "fixture", 1.00)
        val blocked = fixture.runtime.estimateRequestedCards(fixture.date, fixture.keys, false)
        assertEquals(RequestedCardBlock.DAILY_LIMIT, blocked.block)
        assertEquals(0.0, blocked.maxUsd)
        assertEquals(0.50, fixture.runtime.dailyCardBudgetRemaining(fixture.date))
        assertFalse(fixture.runtime.dailyBudgetExceeded(fixture.date))
        assertEquals(0.50, MonthlyBudget(fixture.paths.outDir, BudgetSettings(monthlyTotalUsd = 1.50))
            .remaining(fixture.date))
        assertEquals(0, fixture.calls)
    }

    @Test
    fun requestedResearchUsesCheapestSearchModelAndBlocksAfterOversettlement() = runBlocking {
        val fixture = fixture()
        val strong = StageModel(ProviderId.ANTHROPIC, "claude-opus-5-5")
        fixture.paths.pipelineConfig.apply {
            parentFile.mkdirs()
            writeText(outputJson.encodeToString(PipelineConfig.defaults.copy(
                models = PipelineConfig.defaults.models.copy(classify = strong, generate = strong))))
        }
        var now = Instant.parse("2026-11-30T23:59:00Z")
        val clock = object : Clock() {
            override fun instant() = now
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): Clock = Clock.fixed(now, zone)
        }
        var searchedModel: String? = null
        val provider = object : WebSearchProvider {
            override val id = strong.provider
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest) =
                LlmResult("""{"cards":[]}""", id, req.model, 30, 30, 0, 1.00)

            override suspend fun search(req: LlmRequest, config: PipelineConfig.Search): SearchResult {
                searchedModel = req.model
                now = Instant.parse("2026-12-01T00:00:00Z")
                return SearchResult(emptyList(), LlmUsage(20, 20), 1, req.model, null)
            }
        }
        BudgetSettings.update(fixture.paths.outDir, listOf("requestCardUsd=0.90", "requestDailyUsd=5.0"))
        val runtime = CollectorRuntime(fixture.paths, { null }, clock, emptyList(),
            dailyProviders = { listOf(provider) })
        val quote = runtime.estimateRequestedCards(fixture.date, fixture.keys.take(1), true)
        assertTrue(quote.allowed)
        assertTrue(requireNotNull(quote.researchMaxUsd) >= requireNotNull(quote.estimatedUsd))
        runtime.runRequestedCards(fixture.date, fixture.keys.take(1), true, quote.maxUsd)
        assertEquals("claude-haiku-5-5", searchedModel)
        val month = java.time.YearMonth.of(2026, 12)
        assertFalse(fixture.paths.outDir.resolve("cost-ledger-requested/2026-11.blocked").exists())
        assertTrue(fixture.paths.outDir.resolve("cost-ledger-requested/2026-11-30.json").isFile)
        assertEquals(1.00, collector.dev.requestedCostLedger(fixture.paths.outDir).total(LocalDate.of(2026, 12, 1)))
        assertTrue(fixture.paths.outDir.resolve("cost-ledger-requested/$month.blocked").isFile)
        assertEquals(RequestedCardBlock.MONTHLY_BLOCK,
            runtime.estimateRequestedCards(fixture.date, fixture.keys, false).block)
        runtime.releaseRequestedCards()
        assertTrue(runtime.estimateRequestedCards(fixture.date, fixture.keys, false).allowed)
    }

    @Test
    fun searchModelSelectionSkipsCheaperUnsupportedModels() {
        val provider = ProviderId.ANTHROPIC
        val cheap = ModelPrice(provider, "no-search", ModelTier.FAST, 0.05, 0.5, 0.01)
        val supported = cheap.copy(model = "search", inputUsd = 0.10, webSearch = true)
        val pricey = supported.copy(model = "pricey", inputUsd = 4.0)
        val catalog = ModelCatalog(listOf(cheap, supported, pricey, cheap.copy(
            provider = ProviderId.OPENAI, webSearch = true)))
        assertEquals(StageModel(provider, "search"), catalog.cheapestWebSearchModel(provider))
        assertEquals(null, ModelCatalog(listOf(cheap)).cheapestWebSearchModel(provider))
    }

    @Test
    fun openAiResearchIsUnavailable() = runBlocking {
        val fixture = fixture()
        fixture.paths.pipelineConfig.apply {
            parentFile.mkdirs()
            writeText(outputJson.encodeToString(PipelineConfig.defaults.copy(
                models = PipelineConfig.defaults.models.copy(classify = StageModel(ProviderId.OPENAI, "gpt-5.4-mini")))))
        }
        val quote = fixture.runtime.estimateRequestedCards(fixture.date, fixture.keys, true)
        assertEquals(RequestedCardBlock.RESEARCH_UNSUPPORTED, quote.block)
        assertFalse(quote.allowed)
        assertTrue(fixture.runtime.estimateRequestedCards(fixture.date, fixture.keys, false).allowed)
        assertEquals(0, fixture.calls)

        fixture.paths.pipelineConfig.delete()
        val noSearch = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult = error("Unexpected completion")
        }
        val runtime = CollectorRuntime(fixture.paths, { null }, fixture.clock, emptyList(),
            dailyProviders = { listOf(noSearch) })
        assertEquals(RequestedCardBlock.RESEARCH_UNSUPPORTED,
            runtime.estimateRequestedCards(fixture.date, fixture.keys, true).block)
    }

    @Test
    fun requestedSpendAppearsInMonthlyWarningsAndStatus() {
        val fixture = fixture()
        val ledger = collector.dev.requestedCostLedger(fixture.paths.outDir)
        ledger.reserve(fixture.date, "fixture", 0.80)
        ledger.settle(fixture.date, "fixture", 0.80)
        val budget = MonthlyBudget(fixture.paths.outDir, BudgetSettings(monthlyTotalUsd = 1.0, warnAtPercent = 75))
        assertEquals(0.20, requireNotNull(budget.remaining(fixture.date)), 0.000001)
        assertTrue(budget.warnings(fixture.date).any { "80.0%" in it })
        assertTrue(budget.status(fixture.date).any { "80.0%" in it && "USD 0.800000" in it && "USD 0.200000" in it })
    }

    @Test
    fun storedCommentsAreBoundedInBothQuoteAndGeneration() = runBlocking {
        val fixture = fixture()
        val run = outputJson.decodeFromString<PipelineResult>(fixture.dailyJson)
        val limit = PipelineConfig.defaults.commentsPerItem
        val text = "a".repeat(500)
        val bounded = List(limit) { Comment("author", text) }
        val oversized = bounded.map { it.copy(text = it.text + "excluded-tail") } +
            Comment("author", "excluded-comment")
        fun write(comments: List<Comment>) {
            val first = run.classified.first()
            fixture.dayFile.writeText(outputJson.encodeToString(run.copy(classified =
                listOf(first.copy(item = first.item.copy(comments = comments))) + run.classified.drop(1))))
        }
        write(bounded)
        val boundedQuote = fixture.runtime.estimateRequestedCards(fixture.date, fixture.keys, false)
        write(oversized)
        val quote = fixture.runtime.estimateRequestedCards(fixture.date, fixture.keys, false)
        assertEquals(boundedQuote.estimatedUsd, quote.estimatedUsd)
        assertTrue(fixture.runtime.runRequestedCards(fixture.date, fixture.keys, false, quote.maxUsd).cardIds.isNotEmpty())
        val user = requireNotNull(fixture.generationUser)
        assertTrue(text in user)
        assertEquals(limit, Regex(text).findAll(user).count())
        assertFalse("excluded-" in user)
    }

    private fun fixture() = Fixture(CollectorPaths(root.resolve("out"), root.resolve("config")))

    private class Fixture(val paths: CollectorPaths) {
        val date = LocalDate.now()
        private val signals = listOf("tech-signal", "demand-signal").mapIndexed { index, profile ->
            val item = Item("hf", "$index", profile, "https://example.invalid/$index", 10, publishedAt = Instant.EPOCH)
            Signal(item, ClassifiedItem(item.key, profile, 8.0, "이유", "요약 $index", listOf("회의"), "$index", "회의"))
        }
        val keys = signals.map { it.item.key }
        private val refs = signals.map { SignalRef(it.item.key, it.item.url, "근거") }
        var card = IdeaCard("fixture", date.toString(), "회의 도구", "회의 요약", "새 사례", "회의 기록",
            refs.take(1), refs.drop(1), emptyList(), emptyList(), "하루", "로컬", "자료 부족", "실험",
            emptyList(), "dev-tools", tags = listOf("회의"),
            targetCustomer = "회의 진행자", coreTech = "음성 인식", pain = "회의 기록 누락")
        private val report = RunReport(date.toString(), Instant.now().toString(), emptyList(),
            listOf(StageResult("select", 2, 2, 0)), emptyMap(), 0)
        val dailyJson = outputJson.encodeToString(PipelineResult(signals.map { it.item }, emptyList(), report, classified = signals))
        val dayFile = paths.outDir.apply { mkdirs() }.resolve("$date.json").apply { writeText(dailyJson) }
        var calls = 0
        var sent = 0
        var fail = false
        var generationUser: String? = null
        val clock = Clock.fixed(date.atTime(6, 30).atZone(java.time.ZoneId.systemDefault()).toInstant(),
            java.time.ZoneId.systemDefault())
        val runtime = CollectorRuntime(paths, { null }, clock, emptyList(), dailyProviders = {
            listOf(object : WebSearchProvider {
                override val id = PipelineConfig.defaults.models.generate.provider
                override suspend fun search(req: LlmRequest, config: PipelineConfig.Search): SearchResult = error("Unexpected search")
                override suspend fun listModels() = emptyList<ModelInfo>()
                override suspend fun complete(req: LlmRequest): LlmResult {
                    calls++
                    generationUser = req.user
                    if (fail) error("fixture failure")
                    return LlmResult("""{"cards":[${outputJson.encodeToString(card)}]}""", id, req.model, 30, 30, 0, 0.01)
                }
            })
        }, mailer = { collector.mail.Mailer { sent++ } })
    }

    @Test
    fun addsRelatedEvidenceAndIgnoresUnrelatedItems() {
        fun signal(id: String, profile: String, tag: String): Signal {
            val item = Item("hf", id, id, "https://example.invalid/$id", 10, publishedAt = Instant.EPOCH)
            return Signal(item, ClassifiedItem(item.key, profile, 8.0, "", "", listOf(tag), id, id))
        }
        val selected = signal("selected", "tech-signal", "notes")
        val related = signal("related", "demand-signal", "notes")
        val unrelated = signal("unrelated", "market-signal", "music")
        val evidence = requestedEvidence(listOf(selected), listOf(unrelated, related))
        assertEquals(listOf(related), evidence.added)
        assertTrue(evidence.sufficient())
        assertFalse(requestedEvidence(listOf(selected), listOf(unrelated)).sufficient())
    }

    @Test
    fun usesSelectionDropsAndExcludesTrackingOnlyItems() {
        val signals = listOf("kept", "low", "tracked", "unrelated").mapIndexed { index, id ->
            val item = Item("hf", id, id, "https://example.invalid/$id", 10, publishedAt = Instant.EPOCH)
            Signal(item, ClassifiedItem(item.key, if (id == "unrelated") "none" else "tech-signal",
                9.0 - index, "", "", emptyList(), id, id))
        }
        val report = RunReport("2026-11-25", "", emptyList(), listOf(StageResult("select", 2, 1, 0)),
            emptyMap(), 0)
        val run = PipelineResult(signals.map { it.item },
            listOf(DroppedItem(signals[0].item, DropReason.DUPLICATE), DroppedItem(signals[1].item, DropReason.BELOW_PROFILE_THRESHOLD)), report,
            classified = signals, trackingOnlyKeys = setOf(signals[2].item.key))
        val view = candidateView(run)
        assertEquals(report.stages.single().output, view.candidates.size)
        assertEquals(signals[0].item.key, view.candidates.single().key)
        assertEquals(DropReason.BELOW_PROFILE_THRESHOLD, view.belowStandard.single().dropReason)
    }
}

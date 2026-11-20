package collector.trend

import collector.dev.outputJson
import collector.model.*
import collector.pipeline.PipelineResult
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import kotlin.test.*

class TrendTest {
    @TempDir lateinit var out: File
    private val date = LocalDate.parse("2026-11-20")
    private val now = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
    private val singleItemThemes = TrendConfig.defaults.copy(minThemeItems = 1)

    private fun signal(id: String, topic: String, source: String = "hn", profile: String = "tech-signal",
                       label: String = topic, tags: List<String> = listOf(topic),
                       topicKey: String = "product-$topic"): Signal {
        val item = Item(source, id, id, "https://example.com/$id", 40, publishedAt = now.minusSeconds(4 * 86400),
            extra = mapOf("forks" to "10", "language" to "Kotlin", "license" to "MIT"))
        return Signal(item, ClassifiedItem(id, profile, 80.0, "", "", tags, topicKey, label))
    }

    private fun run(day: LocalDate, signals: List<Signal> = emptyList(), drops: List<DroppedItem> = emptyList()) =
        PipelineResult(signals.map { it.item }, drops, RunReport(day.toString(), now.toString(),
            emptyList(), emptyList(), emptyMap(), 0), signals +
            (1..(signals.size + drops.size) * 4).map { signal("background-$day-$it", "", tags = emptyList()) })

    private fun save(path: String, run: PipelineResult) {
        out.resolve(path).apply { parentFile.mkdirs(); writeText(outputJson.encodeToString(run)) }
    }

    @Test fun newThemesAndCandidatesRequireThreeItemsAcrossTwoDays() {
        val config = TrendConfig.defaults.copy(minThemeItems = 2)
        fun analyze(count: Int, spread: Boolean): TrendReport {
            val runs = (0L..27L).associate { offset ->
                val day = date.minusDays(offset)
                val signals = when (offset) {
                    0L -> (1..(count - if (spread) 1 else 0)).map {
                        signal("current-$it", "product-$it", profile = "demand-signal", tags = listOf("target"))
                    }
                    1L -> if (spread) listOf(signal("repo", "repo", "github", tags = listOf("target"))) else emptyList()
                    else -> emptyList()
                }
                day to run(day, signals)
            }
            return TrendAnalyzer.analyze(TrendInput(runs), date, config)
        }
        val two = analyze(2, true)
        assertEquals("—", TrendAnalyzer.growthLabel(two.themes.single()))
        assertTrue(two.rising.isEmpty())
        val oneDay = analyze(3, false).rising.single()
        assertEquals("—", TrendAnalyzer.growthLabel(oneDay))
        assertFalse(oneDay.candidate)
        val twoDays = analyze(3, true).rising.single()
        assertEquals("신규", TrendAnalyzer.growthLabel(twoDays))
        assertTrue(twoDays.candidate)
    }

    @Test fun themesExcludeSparseAndProductTagsAndApplyNewSynonyms() {
        val repo = signal("repo", "product", "github",
            tags = listOf("product", "GPT-5", "qwen3", "gpt-tools", "mcp-server", "rare"), topicKey = "product")
        val runs = (0L..2L).associate { offset ->
            val day = date.minusDays(offset)
            day to if (offset == 2L) run(day, listOf(repo) + (1..2).map {
                signal("tool-$it", "tool-$it", tags = listOf("mcp-tool"))
            }) else run(day, drops = listOf(DroppedItem(repo.item, DropReason.ALREADY_SEEN)))
        }
        val report = TrendAnalyzer.analyze(TrendInput(runs), date)
        assertEquals(listOf("gpt-tool", "mcp", "rare"), report.themes.map { it.id })
        assertEquals(1, report.themes.single { it.id == "gpt-tool" }.weeklyCounts.last())
        assertEquals(3, report.themes.single { it.id == "mcp" }.weeklyCounts.last())
        val singleDay = TrendAnalyzer.analyze(TrendInput(mapOf(date to runs.getValue(date.minusDays(2)))), date)
        assertEquals(listOf("mcp"), singleDay.themes.map { it.id })
        val rendered = TrendReportRenderer().render(singleDay)
        assertContains(rendered.text, "희소 태그 2개 제외")
        assertContains(rendered.html, "희소 태그 2개 제외")
    }

    @Test fun defaultSynonymsIncludeNoiseReductionAliases() {
        val config = TrendConfig.defaults.copy(excludeThemes = emptyList())
        val normalizer = TagNormalizer(config, emptyMap())
        for ((alias, theme) in mapOf(
            "llm-benchmark" to "benchmark", "claude-model" to "claude", "mcp-server" to "mcp",
            "mcp-tool" to "mcp", "llm-inference" to "inference-optimization", "ai-coding" to "coding-agent",
            "coding-assistant" to "coding-agent", "model-compression" to "quantization",
            "quantized-model" to "quantization", "ai-agents" to "ai-agent"
        )) assertEquals(setOf(theme), normalizer.themes(listOf(alias)))
        assertTrue(TagNormalizer(TrendConfig.defaults, emptyMap()).themes(listOf("ai-agents")).isEmpty())
    }

    @Test fun backfillUsesActualRunsFirstAndMatchesSourcesForGrowth() {
        val older = date.minusDays(14)
        val recent = date.minusDays(7)
        val repeat = signal("repeat", "target")
        val olderRepeats = (1..3).map { signal("repeat-$it", "target", source = "github") }
        val outsideWindow = date.minusDays(30)
        save("$outsideWindow.json", run(outsideWindow, olderRepeats))
        save("backfill/$older.json", run(older, listOf(repeat)))
        save("backfill/$recent.json", run(recent, listOf(signal("shadow", "target"))))
        save("runs/$recent-archive.json", run(recent, listOf(
            signal("prior-hn", "target"), signal("prior-rss", "target", source = "rss")
        )))
        save("$date.json", run(date, listOf(
            signal("current-gh", "target", source = "github", profile = "demand-signal"),
            signal("current-market", "target", source = "rss", profile = "market-signal"),
            signal("current-demand", "target", source = "rss", profile = "demand-signal")
        ), (olderRepeats + repeat).map { DroppedItem(it.item, DropReason.ALREADY_SEEN) }))
        val config = TrendConfig.defaults.copy(minimumDataDays = 3, risingCount = 1, marketCount = 1,
            candidateDemand = 1, candidateGrowth = 2.0, candidateSources = 2)
        val input = TrendInput.read(out, date, config)
        assertEquals(setOf(older), input.backfillDates)
        assertEquals(setOf("prior-hn", "prior-rss"), input.runs.getValue(recent).items.map { it.externalId }.toSet())
        val report = TrendAnalyzer.analyze(input, date, config)
        val theme = report.themes.single()
        assertEquals(1, report.backfillDays)
        assertEquals(listOf(0, 1, 2, 7), theme.weeklyCounts)
        assertEquals(0.25, theme.growth)
        assertTrue(input.earlierClassifications.none { it.item.externalId == "shadow" })
        assertEquals(0, report.unmappedRepeatCount)
        assertFalse(theme.overheated)
        assertFalse(theme.candidate)
        val rendered = TrendReportRenderer().render(report)
        for (body in listOf(rendered.html, rendered.text)) {
            assertContains(body, "7 (비교 1)")
            assertContains(body, "HN·GH 새 항목 기준")
            assertContains(body, "과거 채우기 1일(HN·GitHub만, 점수는 채운 시점 기준)")
        }
    }

    @Test fun matchedSourcesCandidateUsesAllCurrentSources() {
        val older = date.minusDays(14)
        val recent = date.minusDays(7)
        save("backfill/$older.json", run(older, listOf(signal("older", "target"))))
        save("$recent.json", run(recent, listOf(signal("recent", "target"))))
        val first = date.minusDays(1)
        save("$first.json", run(first, listOf(signal("earlier-current", "target"))))
        save("$date.json", run(date, listOf(
            signal("first", "target", profile = "demand-signal"),
            signal("second", "target"),
            signal("rss", "target", source = "rss")
        )))
        val config = TrendConfig.defaults.copy(minimumDataDays = 3, candidateGrowth = 0.5)
        val theme = TrendAnalyzer.analyze(TrendInput.read(out, date, config), date, config).themes.single()
        assertTrue(theme.candidate)
    }

    @Test fun matchedSourcesCountsGitHubItemOnlyOnFirstObservation() {
        val older = date.minusDays(14)
        val recent = date.minusDays(7)
        val repo = signal("repo", "target", source = "github")
        save("backfill/$older.json", run(older, listOf(repo)))
        save("$recent.json", run(recent, listOf(signal("prior", "target"))))
        save("$date.json", run(date, listOf(repo, signal("current", "target"))))
        val config = TrendConfig.defaults.copy(minimumDataDays = 3)
        val theme = TrendAnalyzer.analyze(TrendInput.read(out, date, config), date, config).themes.single()
        assertEquals(listOf(0, 1, 1, 2), theme.weeklyCounts)
        assertEquals(0.25, theme.growth)
    }

    @Test fun matchedSourcesFiltersBeforeDeduplicating() {
        val older = date.minusDays(14)
        val recent = date.minusDays(7)
        val first = date.minusDays(1)
        val rss = signal("rss", "target", source = "rss")
        save("backfill/$older.json", run(older, listOf(signal("older", "target"))))
        save("$recent.json", run(recent, listOf(signal("recent", "target"))))
        save("$first.json", run(first, listOf(rss)))
        save("$date.json", run(date, listOf(rss.copy(item = rss.item.copy(alsoSeenIn = listOf("hn"))))))
        val config = TrendConfig.defaults.copy(minimumDataDays = 3)
        val theme = TrendAnalyzer.analyze(TrendInput.read(out, date, config), date, config).themes.single()
        assertEquals(listOf(0, 1, 1, 1), theme.weeklyCounts)
        assertEquals(0.25, theme.growth)
    }

    @Test fun candidateUsesSameDeduplicatedSourcesAsTable() {
        val older = date.minusDays(14)
        val recent = date.minusDays(7)
        val first = date.minusDays(1)
        val demand = signal("demand", "target", profile = "demand-signal")
        save("$older.json", run(older))
        save("$recent.json", run(recent))
        val earlier = signal("earlier-tech", "target")
        val later = signal("later-tech", "target")
        save("$first.json", run(first, listOf(earlier, demand.copy(item = demand.item.copy(alsoSeenIn = listOf("github"))))))
        save("$date.json", run(date, listOf(later, demand)))
        val config = TrendConfig.defaults.copy(minimumDataDays = 3)
        fun theme() = TrendAnalyzer.analyze(TrendInput.read(out, date, config), date, config).themes.single()
        assertEquals(listOf("hn"), theme().sources)
        assertFalse(theme().candidate)
        save("$date.json", run(date, listOf(later, demand.copy(item = demand.item.copy(alsoSeenIn = listOf("rss"))))))
        assertEquals(listOf("hn", "rss"), theme().sources)
        assertTrue(theme().candidate)
    }

    @Test fun matchedSourcesCandidateIgnoresSourcesFromDiscardedObservations() {
        val older = date.minusDays(14)
        val recent = date.minusDays(7)
        val first = date.minusDays(1)
        val demand = signal("demand", "target", profile = "demand-signal")
        save("backfill/$older.json", run(older))
        save("$recent.json", run(recent))
        val earlier = signal("earlier-tech", "target")
        val later = signal("later-tech", "target")
        save("$first.json", run(first, listOf(earlier, demand.copy(item = demand.item.copy(alsoSeenIn = listOf("rss"))))))
        save("$date.json", run(date, listOf(later, demand)))
        val config = TrendConfig.defaults.copy(minimumDataDays = 3)
        fun theme() = TrendAnalyzer.analyze(TrendInput.read(out, date, config), date, config).themes.single()
        assertEquals(listOf("hn"), theme().sources)
        assertFalse(theme().candidate)
        save("$date.json", run(date, listOf(later, demand.copy(item = demand.item.copy(alsoSeenIn = listOf("rss"))))))
        assertTrue(theme().candidate)
    }

    @Test fun history() {
        val old = signal("old", "rising")
        val fading = (1..2).map { signal("fade$it", "fading") }
        for (offset in 0L..27L) {
            val day = date.minusDays(offset)
            val signals = when (offset) {
                21L -> fading + old
                14L, 7L -> fading
                0L -> listOf(signal("now", "rising"), signal("new1", "fresh"), signal("new2", "fresh"))
                1L -> listOf(signal("new3", "fresh"))
                else -> emptyList()
            }
            val dropped = if (offset == 0L) listOf(DroppedItem(old.item, DropReason.ALREADY_SEEN)) else emptyList()
            save("$day.json", run(day, signals, dropped))
            if (offset == 0L) save("state/$day.json", run(day, signals + signal("state", "rising"), dropped))
        }
        for (suffix in listOf("collect", "run1")) save("$date-$suffix.json", run(date, listOf(signal(suffix, suffix))))
        val report = TrendAnalyzer.analyze(TrendInput.read(out, date), date)
        val rows = report.themes.associateBy { it.id }
        assertEquals(28, report.dataDays)
        assertFalse(report.insufficientData)
        assertEquals(listOf(1, 0, 0, 3), rows.getValue("rising").weeklyCounts)
        assertEquals("—", TrendAnalyzer.growthLabel(rows.getValue("rising")))
        assertEquals(2, rows.getValue("rising").newCount)
        assertTrue(rows.getValue("fresh").isNew)
        assertEquals(listOf("fading"), report.falling.map { it.id })
        assertEquals(setOf("rising", "fresh", "fading"), rows.keys)
    }

    @Test fun compactStateAndOlderReappearance() {
        val oldDay = date.minusDays(30)
        val old = signal("old", "original", source = "github")
        val missing = signal("missing", "unknown", source = "github")
        save("state/$oldDay.json", run(oldDay, listOf(old)))
        val dropped = listOf(old, missing).map { DroppedItem(it.item, DropReason.ALREADY_SEEN) }
        val ref = SignalRef(old.item.key, old.item.url, "")
        val card = IdeaCard(id = "card", title = "", oneLiner = "", whyToday = "", problem = "",
            techSignals = listOf(ref), demandSignals = listOf(ref), competitors = emptyList(), history = emptyList(),
            effort = "", differentiation = "", biggestRisk = "", validationPlan = "", caveats = emptyList(),
            category = "", tags = emptyList())
        val full = run(date, drops = dropped).copy(cards = listOf(card), items = listOf(old.item))
        save("$date.json", run(date))
        save("runs/$date-latest.json", full.copy(report = full.report.copy(startedAt = now.plusSeconds(60).toString())))
        save("state/$date.json", run(date).copy(classified = full.classified,
            report = full.report.copy(startedAt = now.plusSeconds(120).toString())))
        assertEquals(listOf(card), TrendInput.read(out, date).runs.getValue(date).cards)
        val publishedCard = card.copy(id = "published")
        save("$date.json", run(date).copy(cards = listOf(publishedCard)))
        val representative = TrendInput.read(out, date).runs.getValue(date)
        assertEquals(listOf(publishedCard), representative.cards)
        assertEquals(listOf(old.item), representative.items)
        out.resolve("feedback.json").writeText(outputJson.encodeToString(listOf(Feedback("published", Feedback.Kind.STAR))))
        val report = TrendAnalyzer.analyze(TrendInput.read(out, date), date, singleItemThemes)
        assertEquals(1, report.dataDays)
        assertEquals(1, report.unmappedRepeatCount)
        assertEquals(setOf(old.item.key, missing.item.key), report.repositories.map { it.itemKey }.toSet())
        assertNull(report.repositories.single { it.itemKey == missing.item.key }.themeId)
        assertEquals(mapOf("Kotlin" to 2), report.languages)
        assertEquals(mapOf("MIT" to 2), report.licenses)
        val theme = report.themes.single()
        assertEquals("original · 예: original", theme.name)
        assertEquals(listOf(0, 0, 0, 1), theme.weeklyCounts)
        assertEquals(0, theme.newCount)
        assertEquals(1, theme.stars)
        assertEquals(old.item.url, theme.evidence.getValue("tech-signal").single().url)
        assertEquals(report, outputJson.decodeFromString<TrendReport>(outputJson.encodeToString(report)))
    }

    @Test fun publishedRunSuppliesObservationsWithoutStateEvenWhenArchiveIsNewer() {
        val original = signal("repeat", "original")
        val fresh = signal("published", "published")
        val dropped = DroppedItem(original.item, DropReason.ALREADY_SEEN)
        val ref = SignalRef(fresh.item.key, fresh.item.url, "")
        val card = IdeaCard(id = "published-card", title = "", oneLiner = "", whyToday = "", problem = "",
            techSignals = listOf(ref), demandSignals = emptyList(), competitors = emptyList(), history = emptyList(),
            effort = "", differentiation = "", biggestRisk = "", validationPlan = "", caveats = emptyList(),
            category = "", tags = emptyList())
        val published = run(date, listOf(fresh), listOf(dropped)).copy(cards = listOf(card))
        val archived = run(date, listOf(original)).copy(
            report = published.report.copy(startedAt = now.plusSeconds(60).toString()))
        save("$date.json", published)
        save("runs/$date-later.json", archived)
        out.resolve("feedback.json").writeText(outputJson.encodeToString(listOf(Feedback(card.id, Feedback.Kind.STAR))))

        val report = TrendAnalyzer.analyze(TrendInput.read(out, date), date, singleItemThemes)
        val rows = report.themes.associateBy { it.id }
        assertEquals(1, rows.getValue("published").newCount)
        assertEquals(1, rows.getValue("published").stars)
        assertEquals(1, rows.getValue("original").weeklyCounts.last())
        assertEquals(0, rows.getValue("original").newCount)
        assertEquals(0, report.unmappedRepeatCount)
    }

    @Test fun sameDayReappearanceUsesEarliestArchivedClassification() {
        val original = signal("repeat", "original")
        val morning = run(date, listOf(original, signal("archived-only", "archived-only")))
        val noon = run(date, listOf(signal("repeat", "changed")))
        val afternoon = run(date, drops = listOf(DroppedItem(original.item, DropReason.ALREADY_SEEN)))
        save("runs/$date-morning.json", morning)
        save("runs/$date-noon.json", noon.copy(report = noon.report.copy(startedAt = now.plusSeconds(60).toString())))
        save("$date.json", afternoon.copy(report = afternoon.report.copy(startedAt = now.plusSeconds(120).toString())))

        val report = TrendAnalyzer.analyze(TrendInput.read(out, date), date, singleItemThemes)
        val theme = report.themes.single()
        assertEquals("original", theme.id)
        assertEquals(1, theme.weeklyCounts.last())
        assertEquals(0, theme.newCount)
        assertEquals(0, report.unmappedRepeatCount)
    }

    @Test fun reappearanceUsesEarliestArchivedClassificationOutsideWindow() {
        val oldDay = date.minusDays(28)
        val futureDay = date.plusDays(1)
        val original = signal("repeat", "original")
        val future = signal("future", "future")
        val archived = run(oldDay, listOf(original))
        save("runs/$oldDay-morning.json", archived)
        save("runs/$oldDay-noon.json", run(oldDay, listOf(signal("repeat", "changed"))).copy(
            report = archived.report.copy(startedAt = now.plusSeconds(60).toString())))
        save("runs/$futureDay-future.json", run(futureDay, listOf(future)))
        save("$date.json", run(date, drops = listOf(original, future).map {
            DroppedItem(it.item, DropReason.ALREADY_SEEN)
        }))

        val report = TrendAnalyzer.analyze(TrendInput.read(out, date), date, singleItemThemes)
        assertEquals(1, report.dataDays)
        assertEquals(1, report.unmappedRepeatCount)
        val theme = report.themes.single()
        assertEquals("original", theme.id)
        assertEquals(listOf(0, 0, 0, 1), theme.weeklyCounts)
        assertEquals(0, theme.newCount)
    }

    @Test fun singularizationPreservesSuffixesAndExceptions() {
        val normalizer = TagNormalizer(TrendConfig.defaults, emptyMap())
        val unchanged = listOf("loss", "status", "iris", "analysis", "robotics", "daily-news", "mobile-ios",
            "platform-ops", "saas", "news", "ios", "devops", "mlops", "kubernetes", "aws", "gas",
            "canvas", "analytics", "physics", "windows", "postgres", "redis", "nodejs", "js",
            "script-analysis", "managed-kubernetes", "server-redis", "runtime-nodejs")
        assertEquals(unchanged.toSet() + "agents-tool", normalizer.themes(unchanged + "agents-tools"))
    }

    @Test fun fallingUsesWeeklyEquivalentOfPriorDailyAverage() {
        val runs = (0L..7L).plus(14L).associate { offset ->
            val day = date.minusDays(offset)
            day to run(day, if (offset in listOf(0L, 7L, 14L)) listOf(signal("item-$offset", "fading")) else emptyList())
        }
        val report = TrendAnalyzer.analyze(TrendInput(runs), date, TrendConfig.defaults.copy(minimumDataDays = 9))
        val fading = report.falling.single()
        assertEquals("fading", fading.id)
        assertEquals(0.25, fading.growth)
    }

    @Test fun spamNameMatchingIgnoresOwnerCaseAndSeparators() {
        val drops = listOf("one/Repo-Name", "two/repo_name", "three/Other").mapIndexed { index, title ->
            val item = signal("spam-$index", "", "github").item.copy(title = title, snippet = "short")
            DroppedItem(item, DropReason.SUSPECTED_SPAM)
        }
        val report = TrendAnalyzer.analyze(TrendInput(mapOf(date to run(date, drops = drops))), date,
            TrendConfig.defaults.copy(spamClusterSize = 2))
        assertEquals(2, report.spam.single().count)
    }

    @Test fun spamClustersUnionNameAndDescriptionTransitively() {
        val drops = listOf(
            "one/shared" to "first unique description",
            "two/shared" to "  0123456789ABCDEFGHIJ  ",
            "three/different" to "0123456789abcdefghij"
        ).mapIndexed { index, (title, description) ->
            val item = signal("spam-$index", "", "github").item.copy(title = title, snippet = description)
            DroppedItem(item, DropReason.SUSPECTED_SPAM)
        }
        val report = TrendAnalyzer.analyze(TrendInput(mapOf(date to run(date, drops = drops))), date,
            TrendConfig.defaults.copy(spamClusterSize = 3))
        assertEquals(SpamCluster("shared", 3, listOf(date.toString())), report.spam.single())
    }

    @Test fun spamDescriptionsOfNineteenCharactersDoNotLinkDifferentNames() {
        val drops = listOf("one/first", "two/second").mapIndexed { index, title ->
            val item = signal("spam-$index", "", "github").item.copy(title = title, snippet = "0123456789abcdefghi")
            DroppedItem(item, DropReason.SUSPECTED_SPAM)
        }
        val report = TrendAnalyzer.analyze(TrendInput(mapOf(date to run(date, drops = drops))), date,
            TrendConfig.defaults.copy(spamClusterSize = 1))
        assertEquals(listOf(SpamCluster("first", 1, listOf(date.toString())),
            SpamCluster("second", 1, listOf(date.toString()))), report.spam)
    }

    @Test fun textTableCellsKeepPipesAndLineBreaksFromBreakingRows() {
        val report = TrendReport(date = date.toString(), repositories = listOf(
            RepositoryTrend(name = "owner/repo", description = "one|two\r\nthree\nfour\rfive")
        ))
        val rendered = TrendReportRenderer().render(report)
        assertEquals("owner/repo | one/two three four five | 0 | — | — | — | —",
            rendered.text.lineSequence().single { it.startsWith("owner/repo") })
        assertContains(rendered.html, "one|two")
    }

    @Test fun tagNormalization() {
        val normalizer = TagNormalizer(TrendConfig.defaults, mapOf("local-llm" to "EDGE_AI"))
        assertEquals(setOf("edge-ai", "text-to-speech", "image-generation", "vector-index", "loss"),
            normalizer.themes(listOf(" -ON_DEVICE_LLMS- ", "tts", "text-to-image", "Vector Indexs",
                "LOSS", "agents", "LLMs", "open_source_models", "HF Spaces")))
    }

    @Test fun defaultPluralSynonyms() {
        val expected = mapOf("gpus" to "gpu", "apis" to "api", "loras" to "lora", "llamas" to "llama")
        val config = TrendConfig.defaults
        assertEquals(expected, config.synonyms.filterKeys { it in expected })
        val normalizer = TagNormalizer(config, emptyMap())
        expected.forEach { (plural, singular) ->
            assertEquals(setOf(singular), normalizer.themes(listOf(plural)))
        }
    }

    @Test fun broadTagsAtThirtyPercentAndSourceTagsIgnored() {
        val signals = (1..10).map { index ->
            val tags = buildList {
                if (index <= 3) add("wide")
                if (index <= 2) add("narrow")
                add("agents")
            }
            val signal = signal("$index", "topic", tags = tags)
            signal.copy(item = signal.item.copy(tags = listOf("source-only")))
        }
        val day = run(date, signals).copy(classified = signals)
        val (report, themes) = TrendAnalyzer.analyzeWithThemes(TrendInput(mapOf(date to day)), date, singleItemThemes)
        assertEquals(listOf("wide"), report.broadTags)
        assertEquals(report.broadTags, themes.broadTags)
        assertEquals("narrow", report.themes.single().id)
        assertEquals(2, report.themes.single().weeklyCounts.last())
        val rendered = TrendReportRenderer().render(report)
        assertContains(rendered.text, "너무 넓어 제외한 태그 · wide")
        assertContains(rendered.html, "너무 넓어 제외한 태그 · wide")
    }

    @Test fun topicExamplesMergeProductKeys() {
        val signals = listOf(
            signal("a", "product-a", label = "Acme Speech", tags = listOf("tts")),
            signal("b", "product-b", label = "Acme Speech Model", tags = listOf("text-to-speech")),
            signal("c", "other", label = "Other Product " + "Z".repeat(30), tags = listOf("tts")),
            signal("d", "unique", label = "Voice Engine", tags = listOf("tts")),
            signal("e", "extra", label = "Zeta", tags = listOf("tts"))
        )
        val report = TrendAnalyzer.analyze(TrendInput(mapOf(date to run(date, signals))), date)
        val row = report.rising.single()
        assertEquals(listOf("Acme Speech", signals[2].classification.topicLabel, "Voice Engine"), row.examples)
        assertEquals("text-to-speech · 예: Acme Speech, ${signals[2].classification.topicLabel}", row.name)
        assertEquals(5, row.weeklyCounts.last())
        val rendered = TrendReportRenderer().render(report.copy(summary = emptyList()))
        for (output in listOf(rendered.text, rendered.html)) {
            assertContains(output, "예시")
            assertContains(output, "Acme Speech, Other Product " + "Z".repeat(16))
            assertFalse("Z".repeat(17) in output)
            assertFalse("Voice Engine" in output)
        }
    }

    @Test fun insufficientAndRendering() {
        val runs = (0L..12L).associate { offset ->
            val day = date.minusDays(offset)
            day to run(day, if (offset == 0L) listOf(
                signal("1", "topic", label = "<script>", profile = "demand-signal"),
                signal("2", "topic", source = "github", label = "<script>"),
                signal("3", "topic")
            ) else emptyList())
        }
        val report = TrendAnalyzer.analyze(TrendInput(runs), date)
        assertEquals(13, report.dataDays)
        assertTrue(report.insufficientData)
        assertNull(report.themes.single().growth)
        assertFalse(report.themes.single().candidate)
        val rendered = TrendReportRenderer().render(report)
        assertEquals("[기술 트렌드] 11월 3주 — 테마 1 · 데이터 부족", rendered.subject)
        assertContains(rendered.html, "&lt;script&gt;")
        assertFalse("<script>" in rendered.html)
        assertContains(rendered.html, "데이터 부족")
        assertContains(rendered.html, "—")
    }

    @Test fun ossAndOverheating() {
        val fast = signal("fast", "theme", "github", "market-signal")
        val fallback = signal("fallback", "theme", "github", "market-signal")
        val market = signal("market", "theme", profile = "market-signal")
        val drops = (1..39).map {
            DroppedItem(signal("$it", "spam", "github").item.copy(title = "$it/" +
                if (it <= 20) "spam" else "small", snippet = "same long description"), DropReason.SUSPECTED_SPAM)
        }
        val runs = (0L..27L).associate { offset ->
            val day = date.minusDays(offset)
            day to when (offset) {
                0L -> run(day, listOf(fast, fallback, market), drops)
                7L, 14L, 21L -> run(day, listOf(signal("prior$offset", "theme", profile = "market-signal")))
                else -> run(day)
            }
        }
        val snapshots = listOf(MetricSnapshot(fast.item.key, 10, now.minusSeconds(2 * 86400)),
            MetricSnapshot(fast.item.key, 70, now),
            MetricSnapshot(fallback.item.key, 0, now.minusSeconds(21600)),
            MetricSnapshot(fallback.item.key, 40, now))
        val report = TrendAnalyzer.analyze(TrendInput(runs, snapshots), date, zone = java.time.ZoneOffset.UTC)
        assertEquals(listOf(30.0, 10.0), report.repositories.map { it.starsPerDay })
        assertEquals(20.0, report.themes.single().medianStarsPerDay)
        assertEquals(0.25, report.themes.single().medianForkRatio)
        assertEquals(mapOf("MIT" to 2), report.licenses)
        assertEquals(mapOf("Kotlin" to 2), report.languages)
        assertEquals(39, report.spam.single().count)
        assertTrue(report.themes.single().overheated)
    }

    @Test fun ossFallbackUsesFractionalAgeWithOneDayMinimum() {
        val signals = listOf("fractional" to 216000L, "new" to 900L).map { (id, age) ->
            val signal = signal(id, "topic", source = "github")
            signal.copy(item = signal.item.copy(publishedAt = now.minusSeconds(age)))
        }
        val report = TrendAnalyzer.analyze(TrendInput(mapOf(date to run(date, signals))), date,
            zone = java.time.ZoneOffset.UTC)
        assertEquals(mapOf("fractional" to 16.0, "new" to 40.0),
            report.repositories.associate { it.name to it.starsPerDay })
    }

    @Test fun weeklyObservations() {
        val original = signal("repeat", "original", tags = listOf("shared", "old"))
        val repeatOnly = signal("repeat-only", "original", tags = listOf("original"))
        val ignored = signal("ignored", "ignored", profile = "none")
        val tracked = signal("tracked", "tracked", source = "github")
        val current = run(date, listOf(signal("repeat", "current", tags = listOf("current", "shared", "Shared")), ignored, tracked),
            listOf(original, repeatOnly, ignored, tracked).map { DroppedItem(it.item, DropReason.ALREADY_SEEN) })
            .copy(trackingOnlyKeys = setOf(tracked.item.key))
        val report = TrendAnalyzer.analyze(TrendInput(mapOf(date to current),
            earlierClassifications = listOf(original, repeatOnly, ignored)), date, singleItemThemes)
        assertEquals(listOf("current", "original", "shared"), report.themes.map { it.id })
        assertTrue(report.themes.all { it.weeklyCounts.last() == 1 })
        assertTrue(report.repositories.isEmpty())
        assertEquals(0, report.unmappedRepeatCount)
    }

    @Test fun sparseHistoryDoesNotCreateCandidate() {
        val offsets = (0L..14L)
        val runs = offsets.associate { offset ->
            val day = date.minusDays(offset)
            val signals = when (offset) {
                0L -> listOf(signal("a", "topic", profile = "demand-signal"), signal("b", "topic", "github"))
                14L -> listOf(signal("prior", "topic"))
                else -> emptyList()
            }
            day to run(day, signals)
        }
        val report = TrendAnalyzer.analyze(TrendInput(runs), date)
        assertFalse(report.insufficientData)
        assertEquals(2.0 / 3.0, report.themes.single().growth)
        assertFalse(report.themes.single().candidate)
        val insufficient = TrendAnalyzer.analyze(TrendInput(runs.keys.sorted().takeLast(14)
            .associateWith { runs.getValue(it) }), date, singleItemThemes.copy(minimumDataDays = 14))
        assertTrue(insufficient.insufficientData)
        assertNull(insufficient.themes.single().growth)
        assertFalse(insufficient.themes.single().isNew)
    }

    @Test fun topicRepresentativesAndCommonTags() {
        val unrelated = listOf("astronomy", "cooking", "forestry", "volcanoes", "orchestras").map {
            signal(it, it, tags = listOf("mcp", "agent", "python"))
        }
        assertEquals(5, ThemeMapper.groupTopics(unrelated, TrendConfig.defaults).values.toSet().size)
        val chain = listOf(signal("a", "a", label = "voice speech"), signal("b", "b", label = "voice speech audio"),
            signal("c", "c", label = "speech audio"))
        val mapped = ThemeMapper.groupTopics(chain, TrendConfig.defaults)
        val representatives = chain.map { mapped.getValue(it.classification.topicKey) }
        assertEquals(representatives[0], representatives[1])
        assertNotEquals(representatives[0], representatives[2])
        val same = (1..9).map { signal("$it", "topic$it", label = "voice speech") }
        assertEquals(listOf(1, 8), ThemeMapper.groupTopics(same, TrendConfig.defaults).values.groupingBy { it }.eachCount().values.sorted())
        val tags = listOf("waveform", "phoneme")
        val tagged = listOf(signal("a", "a", label = "voice speech", tags = tags),
            signal("b", "b", label = "voice audio", tags = tags))
        assertEquals(1, ThemeMapper.groupTopics(tagged, TrendConfig.defaults).values.toSet().size)
        assertEquals(3, ThemeMapper.groupTopics(tagged + signal("d", "d", tags = tags), TrendConfig.defaults).values.toSet().size)
    }

    @Test fun publishesVersionThreeAndPreservesOverrides() {
        val file = out.resolve("trend/themes.json")
        file.parentFile.mkdirs()
        file.writeText("""{"aliases":{"topic":"stale"},"overrides":{"manual-topic":"manual"},"version":2}""")
        save("$date.json", run(date, listOf(signal("old", "topic"), signal("manual", "manual-topic", label = "manual astronomy"))))
        publishTrend(out, date)
        val migrated = outputJson.decodeFromString<ThemeMap>(file.readText())
        assertEquals(3, migrated.version)
        assertEquals(mapOf("manual-topic" to "manual"), migrated.overrides)
        assertEquals(listOf("manual", "topic"),
            TrendAnalyzer.analyze(TrendInput.read(out, date), date, singleItemThemes).themes.map { it.id })
        assertFalse("stale" in file.readText())
        val next = date.plusDays(28)
        save("$next.json", run(next, listOf(signal("new", "topic", label = "Changed label"))))
        publishTrend(out, next)
        assertEquals(migrated, outputJson.decodeFromString<ThemeMap>(file.readText()))
    }

    @Test fun failedRenderPreservesPublishedFiles() {
        publishTrend(out, date)
        val trend = out.resolve("trend")
        val before = trend.listFiles().orEmpty().associate { it.name to it.readText() }
        save("$date.json", run(date, listOf(signal("new", "topic"))))
        assertFailsWith<IllegalStateException> { publishTrend(out, date) { error("render failure") } }
        assertEquals(before, trend.listFiles().orEmpty().associate { it.name to it.readText() })
    }


    @Test fun officialJsonIsReplacedLastAndThirdFailureRestoresFiles() {
        publishTrend(out, date)
        val trend = out.resolve("trend")
        val before = trend.listFiles().orEmpty().associate { it.name to it.readText() }
        save("$date.json", run(date, listOf(signal("new", "topic"))))
        val order = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            publishTrend(out, date, beforeReplace = { file ->
                order += file.name
                if (order.size == 3) {
                    assertEquals(before.getValue("$date.json"), trend.resolve("$date.json").readText())
                    error("third replacement failure")
                }
            })
        }
        assertEquals(listOf("$date.html", "$date.txt", "themes.json"), order)
        assertEquals(before, trend.listFiles().orEmpty().associate { it.name to it.readText() })

        order.clear()
        publishTrend(out, date, beforeReplace = { order += it.name })
        assertEquals(listOf("$date.html", "$date.txt", "themes.json", "$date.json"), order)
        assertNotEquals(before.getValue("$date.json"), trend.resolve("$date.json").readText())
    }

    @Test fun candidatesPrecedeOtherRisingRows() {
        val candidates = (1..11).flatMap { index ->
            val topic = "candidate-$index"
            listOf(signal("demand-$index", topic, profile = "demand-signal"),
                signal("repo-$index", topic, source = "github"), signal("tech-$index", topic))
        }
        val demand = signal("single", "single", profile = "demand-signal")
        val signals = candidates + demand.copy(item = demand.item.copy(alsoSeenIn = listOf("github"))) +
            listOf(signal("other1", "aaa-other"), signal("other2", "aaa-other"), signal("other3", "aaa-other"))
        val runs = (0L..27L).associate { offset ->
            val day = date.minusDays(offset)
            day to run(day, when (offset) {
                0L -> signals.filterNot { it.item.externalId.startsWith("tech-") }
                1L -> candidates.filter { it.item.externalId.startsWith("tech-") }
                else -> emptyList()
            })
        }
        val report = TrendAnalyzer.analyze(TrendInput(runs), date)
        assertTrue(report.themes.none { it.id == "single" })
        assertEquals(11, report.themes.count { it.candidate })
        assertEquals(10, report.rising.size)
        assertTrue(report.rising.all { it.candidate })
        assertTrue(report.rising.zipWithNext().all { (left, right) -> left.id < right.id })
        val rendered = TrendReportRenderer().render(report)
        assertContains(rendered.subject, "뜨는 테마 10 · 분석 후보 ◆10 외 1")
        assertEquals(10, rendered.text.lineSequence().count { it.startsWith("◆ ") })
        assertEquals(10, Regex(">◆ candidate-").findAll(rendered.html).count())
    }

    @Test fun summaryRanksByGrowthWhileTablePrioritizesCandidates() {
        val runs = (0L..27L).associate { offset ->
            val day = date.minusDays(offset)
            val signals = when (offset) {
                0L -> listOf(signal("demand", "candidate", profile = "demand-signal"),
                    signal("repo", "candidate", source = "github"), signal("tech", "candidate")) +
                    (1..5).map { signal("fast-$it", "fast") }
                1L -> listOf(signal("second-day", "candidate"))
                7L, 14L, 21L -> listOf(signal("prior-$offset-a", "candidate"),
                    signal("prior-$offset-b", "candidate"), signal("prior-$offset-fast", "fast"))
                else -> emptyList()
            }
            day to run(day, signals)
        }
        val report = TrendAnalyzer.analyze(TrendInput(runs), date)
        val candidate = report.rising.first()
        val fastest = report.themes.single { it.id == "fast" }
        assertEquals("candidate", candidate.id)
        assertTrue(candidate.candidate)
        assertEquals(5.0 / 3.0, candidate.growth)
        assertFalse(fastest.candidate)
        assertEquals(0, fastest.demandCount)
        assertEquals(3.0, fastest.growth)
        assertContains(report.summary.first(), "${fastest.name} ×3.00")
    }

    @Test fun summaryRanksNewThemesFirstAndUsesWeeklyCountWhenHistoryIsInsufficient() {
        val current = run(date, (1..5).map { signal("rapid-$it", "rapid") } +
            (1..2).map { signal("new-$it", "fresh") })
        val runs = (0L..27L).associate { offset ->
            val day = date.minusDays(offset)
            day to when (offset) {
                0L -> current
                1L -> run(day, listOf(signal("new-3", "fresh")))
                7L, 14L, 21L -> run(day, listOf(signal("prior-$offset", "rapid")))
                else -> run(day)
            }
        }
        val complete = TrendAnalyzer.analyze(TrendInput(runs), date)
        assertFalse(complete.insufficientData)
        assertEquals(3.0, complete.themes.single { it.id == "rapid" }.growth)
        val fresh = complete.themes.single { it.id == "fresh" }
        assertTrue(fresh.isNew)
        assertEquals("가장 뜬 테마 · ${fresh.name} 신규", complete.summary.first())

        val sparse = TrendAnalyzer.analyze(TrendInput(mapOf(date to current)), date)
        assertTrue(sparse.insufficientData)
        val fastest = sparse.themes.single { it.id == "rapid" }
        assertEquals("가장 뜬 테마 · ${fastest.name} —", sparse.summary.first())
    }

    @Test fun defaultSpamClusterThresholdIsTwentyRepositories() {
        val drops = (1..20).map {
            DroppedItem(signal("spam-$it", "", "github").item.copy(title = "owner-$it/spam"),
                DropReason.SUSPECTED_SPAM)
        }
        val below = TrendAnalyzer.analyze(TrendInput(mapOf(date to run(date, drops = drops.take(19)))), date)
        assertTrue(below.spam.isEmpty())
        val atThreshold = TrendAnalyzer.analyze(TrendInput(mapOf(date to run(date, drops = drops))), date)
        assertEquals(SpamCluster("spam", 20, listOf(date.toString())), atThreshold.spam.single())
    }

    @Test fun marketOverheatBoundaries() {
        fun overheated(current: Int, prior: Int): Boolean {
            val runs = (0L..27L).associate { offset ->
                val day = date.minusDays(offset)
                val count = when (offset) {
                    0L -> current
                    7L, 14L, 21L -> prior
                    else -> 0
                }
                day to run(day, (1..count).map { signal("$offset-$it", "market", profile = "market-signal") })
            }
            return TrendAnalyzer.analyze(TrendInput(runs), date, singleItemThemes).themes.single().overheated
        }
        assertFalse(overheated(2, 0))
        assertFalse(overheated(3, 2))
        assertTrue(overheated(3, 1))
    }

    @Test fun repositoryDisplayBoundariesAndIsoWeek() {
        val description = "a".repeat(81)
        val repos = listOf(Triple("suspect", 200, "0"), Triple("low", 199, "0"), Triple("threshold", 200, "1"))
            .map { (name, stars, forks) ->
                val signal = signal(name, "topic", "github")
                signal.copy(item = signal.item.copy(rawScore = stars, snippet = description,
                    extra = signal.item.extra + ("forks" to forks)))
            }
        val report = TrendAnalyzer.analyze(TrendInput(mapOf(date to run(date, repos))), date,
            zone = java.time.ZoneOffset.UTC)
        assertEquals(listOf("suspect"), report.repositories.filter { it.suspected }.map { it.name })
        val rendered = TrendReportRenderer().render(report.copy(date = "2026-11-01"))
        assertContains(rendered.subject, "10월 5주")
        assertContains(rendered.text, "suspect (의심) | ${"a".repeat(79)}… | 50")
        assertContains(rendered.text, "low | ${"a".repeat(79)}… | 50")
        assertContains(rendered.text, "지는 테마\n해당 없음")
        assertContains(rendered.html, "<h2>지는 테마</h2>\n<p>해당 없음</p>")
    }

    @Test fun candidateBoundaries() {
        assertTrue(CandidateMarker.matches(1.5, false, 1, 2, false, 3, 2))
        assertFalse(CandidateMarker.matches(1.5, false, 1, 2, false, 2, 2))
        assertFalse(CandidateMarker.matches(1.5, false, 1, 2, false, 3, 1))
        assertTrue(CandidateMarker.matches(null, true, 1, 2, false, 3, 2))
        assertFalse(CandidateMarker.matches(null, false, 1, 2, false, 3, 2))
        assertFalse(CandidateMarker.matches(1.49, false, 1, 2, false, 3, 2))
        assertFalse(CandidateMarker.matches(1.5, false, 0, 2, false, 3, 2))
        assertFalse(CandidateMarker.matches(1.5, false, 1, 1, false, 3, 2))
        assertFalse(CandidateMarker.matches(1.5, false, 1, 2, true, 3, 2))
    }

    @Test fun cliWithoutKeys() {
        val javaExecutable = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path").split(File.pathSeparator)
            .joinToString(File.pathSeparator) { File(it).absolutePath }
        val process = ProcessBuilder(javaExecutable, "-cp", classpath, "collector.dev.MainKt", "--trend", "--date", date.toString())
            .directory(out).redirectErrorStream(true).apply {
                environment().keys.removeAll { it.endsWith("_API_KEY", ignoreCase = true) }
            }.start()
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.exitValue(), output)
        assertEquals(1, output.lines().size)
        assertTrue(output.startsWith("[기술 트렌드]"))
        assertEquals(setOf("$date.json", "$date.html", "$date.txt", "themes.json"),
            out.resolve("out/trend").list().orEmpty().toSet())
    }
}

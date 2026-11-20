package collector.budget

import collector.dev.FileCostLedger
import collector.dev.readJson
import collector.dev.runMain
import collector.config.GitHubConfig
import collector.config.SourceConfig
import collector.config.SourcesFile
import collector.llm.*
import collector.model.Item
import collector.pipeline.PipelineResult
import collector.source.SourceAdapter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.LocalDate
import java.time.Instant
import kotlin.test.*

class BudgetSettingsTest {
    @TempDir lateinit var out: File

    @Test fun unreadableSettingsUseDefaultsAndReportError() {
        assertTrue(out.resolve("settings/budget.json").mkdirs())
        val messages = mutableListOf<String>()
        assertEquals(BudgetSettings(), BudgetSettings.load(out, messages::add))
        assertContains(messages.single(), "budget.json")
    }

    @Test fun dailyMainArchivesMonthlyCapSkipAndCallsProviderWithoutCap() = runTest {
        val adapter = object : SourceAdapter {
            override val id = "github"
            override suspend fun fetch(config: SourceConfig, now: Instant) = listOf(
                Item(id, "1", "Local AI tool", "https://example.test/tool", 250, publishedAt = now)
            )
        }
        val sources = SourcesFile(listOf(GitHubConfig(profile = listOf("tech-signal")))) to listOf(adapter)
        val requests = mutableListOf<String>()
        val provider = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                requests += req.schemaName
                val json = """{"items":[{"itemId":"github:1","profile":"none","score":null,"reason":"unrelated","summary":"","tags":[],"topicKey":"","topicLabel":""}]}"""
                return LlmResult(json,
                    id, req.model, 100, 10, 0, 0.0001)
            }
        }
        FileCostLedger(out.resolve("cost-ledger")).reserve(LocalDate.now(), "earlier", 0.20)
        runMain(arrayOf("--set-budget", "monthlyTotalUsd=0.10"), out)
        val args = arrayOf("--keep-unseen")
        runMain(args, out, sources) { error("paid stages should be skipped") }
        val archived = assertNotNull(readJson<PipelineResult>(out.resolve("runs").listFiles()!!.single()))
        assertEquals(listOf(MONTHLY_TOTAL_EXCEEDED), archived.report.issues)
        assertTrue(archived.items.isNotEmpty())
        assertTrue(archived.report.llmCalls.isEmpty())

        runMain(arrayOf("--set-budget", "monthlyTotalUsd=null"), out)
        runMain(args, out, sources) { listOf(provider) }
        assertContains(requests, "classify")
    }

    @Test fun settingsDefaultAndValidateIndividualFields() {
        val messages = mutableListOf<String>()
        val file = out.resolve("settings/budget.json")
        assertEquals(BudgetSettings(), BudgetSettings.load(out, messages::add))
        file.parentFile.mkdirs()
        val original = """{"dailyCardUsd":-1,"analysisRequestUsd":1.001,"backfillRunUsd":0.25,"unknown":"ignored"}"""
        file.writeText(original)
        val settings = BudgetSettings.load(out, messages::add)
        assertEquals(BudgetSettings(backfillRunUsd = 0.25), settings)
        assertEquals(2, messages.size)
        assertEquals(original, file.readText())
    }

    @Test fun validatesAllUpdatesBeforeReplacingFile() {
        BudgetSettings.update(out, listOf("dailyCardUsd=0.25", "overBudget=AUTO_FIT"))
        val file = out.resolve("settings/budget.json")
        val original = file.readText()
        assertFailsWith<IllegalArgumentException> {
            BudgetSettings.update(out, listOf("dailyCardUsd=0.75", "warnAtPercent=101"))
        }
        assertEquals(original, file.readText())
        assertEquals(0.25, BudgetSettings.load(out).dailyCardUsd)
        assertEquals(OverBudget.AUTO_FIT, BudgetSettings.load(out).overBudget)
    }

    @Test fun commandsSaveSettingsAndSummarizeBothMonthlyLedgers() = runTest {
        val today = LocalDate.now()
        FileCostLedger(out.resolve("cost-ledger")).reserve(today, "cards", 0.25)
        FileCostLedger(out.resolve("cost-ledger-analysis")).reserve(today, "analysis", 0.50)
        FileCostLedger(out.resolve("cost-ledger")).reserve(today.withDayOfMonth(1).minusDays(1), "old", 1.00)
        val original = System.out
        val output = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(output, true, Charsets.UTF_8))
            runMain(arrayOf("--set-budget", "monthlyTotalUsd=1.50"), out)
            runMain(arrayOf("--budget-status"), out)
        } finally {
            System.setOut(original)
        }
        val lines = output.toString(Charsets.UTF_8)
        assertContains(lines, "monthlyTotalUsd=1.5")
        assertContains(lines, "일일 카드 원장 USD 0.250000")
        assertContains(lines, "분석 원장 · 사용 USD 0.500000")
        assertContains(lines, "전체 · 사용 USD 0.750000 / USD 1.500000 · 남은 예산 USD 0.750000 · 사용률 50.0%")
        assertContains(lines, nextBudgetMonth(today).toString())
    }

    @Test fun overallMonthlyCapAllowsExactEquality() {
        val today = LocalDate.of(2026, 11, 20)
        FileCostLedger(out.resolve("cost-ledger")).reserve(today, "cards", 0.10)
        val monthly = MonthlyBudget(out, BudgetSettings(monthlyTotalUsd = 0.30))
        assertTrue(monthly.allows(today, 0.20))
        assertFalse(monthly.allows(today, 0.200001))
    }
}

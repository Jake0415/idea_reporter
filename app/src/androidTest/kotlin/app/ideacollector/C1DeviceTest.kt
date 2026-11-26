package app.ideacollector

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ideacollector.github.contractJson
import app.ideacollector.data.LOCAL_REPO
import collector.config.PipelineConfig
import collector.llm.*
import collector.model.*
import collector.pipeline.PipelineResult
import collector.report.HtmlRenderer
import collector.runtime.CollectorRuntime
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class C1DeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun allowNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After fun resetOperation() { app.operationRunning.value = false }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as CollectorApp

    @Test fun todayCandidatesQuoteAndArchive() {
        compose.waitUntil(30_000) { app.recovery.value == BackupRecovery.READY && !app.operationRunning.value }
        val date = java.time.LocalDate.now().toString()
        val prefs = app.getSharedPreferences("first-run", 0)
        val finished = prefs.getBoolean("finished", false)
        val settings = runBlocking { app.settings() }
        val file = app.paths.outDir.apply { mkdirs() }.resolve("$date.json")
        val original = file.takeIf { it.isFile }?.readBytes()
        val requested = app.paths.outDir.resolve("requested")
        val existing = requested.listFiles().orEmpty().toSet()
        val ledger = app.paths.outDir.resolve("cost-ledger-requested/$date.json")
        val originalLedger = ledger.takeIf { it.isFile }?.readBytes()
        val marker = ledger.parentFile.resolve("${java.time.YearMonth.from(java.time.LocalDate.parse(date))}.blocked")
        val originalMarker = marker.takeIf { it.isFile }?.readBytes()
        val budgetFile = app.paths.outDir.resolve("settings/budget.json")
        val originalBudget = budgetFile.takeIf { it.isFile }?.readBytes()
        val configFile = app.paths.pipelineConfig
        val originalConfig = configFile.takeIf { it.isFile }?.readBytes()
        val signals = listOf("tech-signal", "demand-signal", "market-signal", "tech-signal", "market-signal").mapIndexed { index, profile ->
            val item = Item("hf", "c1-$index", "후보 원문 $index", "https://example.invalid/$index", 10, publishedAt = Instant.EPOCH)
            Signal(item, ClassifiedItem(item.key, profile, 9.0 - index, "선정 이유 $index", "오늘 후보 요약 $index",
                listOf(if (index < 2) "회의" else "주제-$index"), "c1-$index", "후보 주제 $index"))
        }
        val refs = signals.map { SignalRef(it.item.key, it.item.url, "확인한 근거") }
        val card = IdeaCard(title = "회의 기록 검토 도구", oneLiner = "회의 요약 검토", whyToday = "새 도구 등장",
            problem = "회의 기록을 확인하기 어렵습니다.", techSignals = refs.take(1), demandSignals = refs.slice(1..1),
            competitors = emptyList(), history = emptyList(), effort = "하루", differentiation = "로컬 처리",
            biggestRisk = "정확도", validationPlan = "회의 기록으로 비교", caveats = emptyList(), category = "dev-tools",
            tags = listOf("회의"), targetCustomer = "회의 진행자", coreTech = "음성 인식", pain = "회의 기록 누락")
        val report = RunReport(date, Instant.now().toString(), emptyList(),
            listOf(StageResult("select", 5, 4, 0)), emptyMap(), 0)
        val run = PipelineResult(signals.map { it.item }, listOf(DroppedItem(signals.last().item, DropReason.OVER_QUOTA)),
            report, classified = signals)
        val provider = object : WebSearchProvider {
            override val id = PipelineConfig.defaults.models.generate.provider
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest) =
                LlmResult("""{"cards":[${contractJson.encodeToString(card)}]}""", id, req.model, 20, 20, 0, 0.01)
            override suspend fun search(req: LlmRequest, config: PipelineConfig.Search) =
                SearchResult(emptyList(), LlmUsage(20, 20), 0, req.model, null)
        }
        try {
            prefs.edit().putBoolean("finished", true).commit()
            ledger.delete()
            marker.delete()
            budgetFile.delete()
            configFile.delete()
            file.writeText(contractJson.encodeToString(run))
            app.runtimeOverride = CollectorRuntime(app.paths, { null }, Clock.systemDefaultZone(), emptyList(),
                dailyProviders = { listOf(provider) })
            runBlocking {
                app.database.dao().saveSettings(settings.copy(repository = LOCAL_REPO))
                app.sync.refresh()
            }
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                await(app.getString(R.string.candidates_count, 4))
                val largeText = app.resources.configuration.fontScale >= 2f
                if (!largeText) capture("today-entry", scenario)
                click(app.getString(R.string.candidates_count, 4))
                compose.onNodeWithText(app.getString(R.string.candidates_title)).assertIsDisplayed()
                val firstCandidate = compose.onNodeWithText("오늘 후보 요약 0")
                if (!firstCandidate.isDisplayed()) firstCandidate.performScrollTo()
                firstCandidate.assertIsDisplayed()
                capture(if (largeText) "list-200" else "list")
                if (largeText) return@use
                click("오늘 후보 요약 0")
                compose.onNodeWithText("선정 이유 0").performScrollTo().assertIsDisplayed()
                capture("expanded")
                click("오늘 후보 요약 0")
                click(app.getString(R.string.candidates_dropped, 1))
                compose.onNodeWithText(app.getString(R.string.candidates_over_quota)).performScrollTo().assertIsDisplayed()
                capture("below-standard")
                compose.onNodeWithText("오늘 후보 요약 2").performScrollTo().performTouchInput { longClick() }
                await(app.getString(R.string.request_card_selected, 1))
                click(app.getString(R.string.request_card_create))
                await(app.getString(R.string.request_card_insufficient))
                capture("insufficient")
                click(app.getString(R.string.cancel))
                compose.onNodeWithText("오늘 후보 요약 2").performScrollTo().performTouchInput { longClick() }
                compose.onNodeWithText("오늘 후보 요약 0").performScrollTo().performTouchInput { longClick() }
                await(app.getString(R.string.request_card_selected, 1))
                compose.onNodeWithText("오늘 후보 요약 1").performScrollTo().performTouchInput { longClick() }
                compose.onNodeWithText("오늘 후보 요약 3").performScrollTo().performTouchInput { longClick() }
                await(app.getString(R.string.request_card_selected_max))
                capture("selection")
                click(app.getString(R.string.candidates_clear))
                compose.onNodeWithText("오늘 후보 요약 0").performScrollTo().performTouchInput { longClick() }
                await(app.getString(R.string.request_card_selected, 1))
                click(app.getString(R.string.request_card_create))
                await(app.getString(R.string.request_card_evidence))
                capture("quote")
                click(app.getString(R.string.request_card_research))
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("검색 결과가 길면 최대 약", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                await(app.getString(R.string.request_card_evidence))
                capture("quote-research")
                click(app.getString(R.string.request_card_research))
                await(app.getString(R.string.request_card_evidence))
                compose.onAllNodesWithText(app.getString(R.string.request_card_create)).onLast().performClick()
                await(app.getString(R.string.request_card_done))
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                click(app.getString(R.string.archive))
                compose.onNodeWithText(card.title).performScrollTo().assertIsDisplayed()
                capture("requested-card")
                click(app.getString(R.string.today))
                click(app.getString(R.string.candidates_count, 4))
                runBlocking {
                    val runtime = app.runtime()
                    val day = java.time.LocalDate.parse(date)
                    val keys = listOf(signals.first().item.key)
                    val plain = checkNotNull(runtime.estimateRequestedCards(day, keys, false).estimatedUsd)
                    val research = checkNotNull(runtime.estimateRequestedCards(day, keys, true).estimatedUsd)
                    val dailyLimit = String.format(java.util.Locale.ROOT, "%.2f", (plain + research) / 2 + 0.01)
                    runtime.setBudget(listOf("requestDailyUsd=$dailyLimit"))
                }
                compose.onNodeWithText("오늘 후보 요약 0").performScrollTo().performTouchInput { longClick() }
                await(app.getString(R.string.request_card_selected, 1))
                click(app.getString(R.string.request_card_create))
                await(app.getString(R.string.request_card_evidence))
                click(app.getString(R.string.request_card_research))
                val researchOption = compose.onNode(hasText(app.getString(R.string.request_card_research)) and hasClickAction())
                // 추가 조사만 하루 남은 예산을 넘는 경우: "다 썼다"가 아니라 "끄면 만들 수 있다"를 안내한다.
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("추가 조사를 끄면 만들 수 있습니다", substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                researchOption.assertIsEnabled().assertIsOn()
                capture("research-only-over-budget")
                click(app.getString(R.string.request_card_research))
                await(app.getString(R.string.request_card_evidence))
                researchOption.assertIsOff()
                compose.onAllNodesWithText(app.getString(R.string.request_card_create)).onLast().assertIsEnabled()
                capture("research-off-quote")
                click(app.getString(R.string.cancel))

                checkNotNull(configFile.parentFile).mkdirs()
                configFile.writeText(contractJson.encodeToString(PipelineConfig.defaults.copy(
                    models = PipelineConfig.defaults.models.copy(classify = StageModel(ProviderId.OPENAI, "gpt-5.4-mini")))))
                click(app.getString(R.string.request_card_create))
                await(app.getString(R.string.request_card_research_unsupported))
                researchOption.assertIsNotEnabled().assertIsOff()
                capture("research-unsupported")
                click(app.getString(R.string.cancel))
                configFile.delete()
                click(app.getString(R.string.candidates_clear))

                runBlocking { app.runtime().setBudget(listOf("requestDailyUsd=1.25")) }
                ledger.writeText("""{"c1-fixture":{"reservedUsd":1.25,"actualUsd":1.25}}""")
                compose.onNodeWithText("오늘 후보 요약 0").performScrollTo().performTouchInput { longClick() }
                await(app.getString(R.string.request_card_selected, 1))
                click(app.getString(R.string.request_card_create))
                await(app.getString(R.string.request_card_daily_block, 1.25))
                compose.onNodeWithText("이번 요청 최대 $0.50", substring = true).assertExists()
                compose.onNode(hasText(app.getString(R.string.request_card_research)) and hasClickAction()).assertIsNotEnabled()
                capture("request-daily-block")
                click(app.getString(R.string.cancel))
                marker.writeText("{}")
                click(app.getString(R.string.request_card_create))
                await(app.getString(R.string.request_card_settlement_block))
                capture("request-monthly-block")
                click(app.getString(R.string.cancel))
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                click(app.getString(R.string.settings))
                click(app.getString(R.string.operate))
                val notice = app.getString(R.string.request_card_settlement_block_here)
                await(notice)
                val release = app.getString(R.string.request_card_release)
                compose.onNodeWithText(app.getString(R.string.request_card_daily_limit)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(notice).assertIsDisplayed()
                compose.onNodeWithText(release).assertIsDisplayed()
                capture("budget", scenario)
                click(release)
                compose.waitUntil(10_000) { !marker.exists() }
                click(app.getString(R.string.today))
                file.writeText(contractJson.encodeToString(run.copy(items = emptyList(), classified = emptyList(), dropped = emptyList(),
                    report = report.copy(stages = listOf(StageResult("select", 0, 0, 0))))))
                runBlocking { app.sync.refresh() }
                click(app.getString(R.string.today))
                click(app.getString(R.string.candidates_count, 0))
                await(app.getString(R.string.candidates_empty))
                capture("empty")
                if (captureEnabled()) {
                    val loaded = java.util.concurrent.CountDownLatch(1)
                    scenario.onActivity { activity ->
                        val rendered = HtmlRenderer(emptyList(), includeCandidates = true).render(run)
                        activity.setContentView(android.webkit.WebView(activity).apply {
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(view: android.webkit.WebView, url: String) { loaded.countDown() }
                            }
                            loadDataWithBaseURL(null, rendered.html, "text/html", "UTF-8", null)
                        })
                    }
                    check(loaded.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    instrumentation.waitForIdleSync()
                    capture("mail", scenario)
                }
            }
        } finally {
            app.runtimeOverride = null
            if (original == null) file.delete() else file.writeBytes(original)
            if (originalLedger == null) ledger.delete() else ledger.writeBytes(originalLedger)
            if (originalMarker == null) marker.delete() else marker.writeBytes(originalMarker)
            if (originalBudget == null) budgetFile.delete() else budgetFile.writeBytes(originalBudget)
            if (originalConfig == null) configFile.delete() else configFile.writeBytes(originalConfig)
            requested.listFiles().orEmpty().filterNot { it in existing }.forEach { it.delete() }
            prefs.edit().putBoolean("finished", finished).commit()
            runBlocking { app.database.dao().saveSettings(settings); app.sync.refresh() }
        }
    }

    private fun await(label: String) = compose.waitUntil(10_000) {
        check(compose.onAllNodesWithText(app.getString(R.string.run_failed)).fetchSemanticsNodes().isEmpty()) {
            "요청 카드 견적 또는 실행 실패"
        }
        compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
    }

    private fun click(label: String) {
        await(label)
        val node = compose.onNodeWithText(label)
        if (!node.isDisplayed()) node.performScrollTo()
        node.performClick()
    }

    private fun captureEnabled() = InstrumentationRegistry.getArguments().getString("c1Capture") == "true"

    private fun capture(name: String, scenario: ActivityScenario<MainActivity>? = null) {
        if (!captureEnabled()) return
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(750, 5_000)
        val theme = if (app.resources.configuration.uiMode and 0x30 == 0x20) "dark" else "light"
        val directory = app.filesDir.resolve("c1-screens/$theme").apply { mkdirs() }
        fun save(bitmap: Bitmap) {
            directory.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        if (scenario == null) save(checkNotNull(instrumentation.uiAutomation.takeScreenshot()))
        else scenario.onActivity {
            val view = it.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            save(bitmap)
        }
    }
}

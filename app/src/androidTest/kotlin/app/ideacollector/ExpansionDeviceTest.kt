package app.ideacollector

import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ideacollector.data.LOCAL_REPO
import app.ideacollector.github.contractJson
import collector.config.PipelineConfig
import collector.model.IdeaCard
import collector.model.RunReport
import collector.pipeline.PipelineResult
import collector.report.HtmlRenderer
import collector.stage.Category
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExpansionDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as CollectorApp

    @Test fun expandedCardAndCriteriaSettings() = expandedScreens(editSettings = true)

    @Test fun expandedScreensInDarkMode() = withAppearance("yes", "1.0") {
        expandedScreens(editSettings = false, expectedDark = true)
    }

    @Test fun expandedScreensAtFont200() = withAppearance("no", "2.0") {
        expandedScreens(editSettings = false, expectedDark = false, expectedFontScale = 2f)
    }

    @Test fun aQueuedLensChangePreservesOtherSettings() = runBlocking {
        val file = app.paths.pipelineConfig
        val original = file.takeIf { it.isFile }?.readBytes()
        val before = """{"expansion":{"seedCount":5,"lenses":{"korea":false}}}"""
        try {
            assertTrue(app.database.dao().localChanges().isEmpty())
            file.parentFile?.mkdirs()
            file.writeText(before)
            app.operationRunning.value = true
            app.patchLocalConfig("pipeline.local.json", buildJsonObject {
                put("expansion", buildJsonObject { put("lenses", buildJsonObject { put("invert", false) }) })
            })
            assertEquals(before, file.readText())
            assertEquals(1, app.database.dao().localChanges().size)
            assertFalse(app.criteriaSettings().second.expansion.lenses.getValue("invert"))
            app.finishOperation()
            assertTrue(app.database.dao().localChanges().isEmpty())
            val defaults = PipelineConfig.defaults.expansion
            val saved = PipelineConfig.load(file).expansion
            assertEquals(defaults.copy(seedCount = 5, lenses = defaults.lenses + mapOf("korea" to false, "invert" to false)), saved)
        } finally {
            app.operationRunning.value = false
            if (original == null) file.delete() else file.writeBytes(original)
            app.sync.refresh()
        }
    }

    private fun expandedScreens(editSettings: Boolean, expectedDark: Boolean? = null, expectedFontScale: Float? = null) {
        compose.waitUntil(30_000) { app.recovery.value == BackupRecovery.READY && !app.operationRunning.value }
        val date = today()
        val preferences = app.getSharedPreferences("first-run", 0)
        val wasFinished = preferences.getBoolean("finished", false)
        val settings = runBlocking { app.settings() }
        val dayFile = app.paths.outDir.apply { mkdirs() }.resolve("$date.json")
        val configFile = app.paths.pipelineConfig
        val legacyDay = LocalDate.parse(date).minusDays(1).toString()
        val legacyFile = app.paths.outDir.resolve("$legacyDay.json")
        val files = if (editSettings) listOf(dayFile, configFile, legacyFile) else listOf(dayFile, configFile)
        val originals = files.associateWith { file -> file.takeIf { it.isFile }?.readBytes() }
        val reference = collector.model.SignalRef("fixture", "https://example.invalid/speech", "음성을 글로 바꾸는 기술")
        val product = collector.model.SignalRef("fixture-product", "https://example.invalid/voice-notes", "음성으로 회의 기록을 남기는 제품")
        val expanded = IdeaCard(id = "x1-expansion-fixture", runDate = date, title = "현장 점검 기록 도우미",
            oneLiner = "작업자가 남긴 음성 점검 기록을 정리합니다.", whyToday = "새 음성 인식 기술을 다른 고객에게 적용했습니다.",
            problem = "현장 메모를 사무실에서 다시 작성합니다.", techSignals = listOf(reference),
            demandSignals = emptyList(), competitors = listOf(product), history = emptyList(), effort = "1인 2주",
            differentiation = "점검표로 바로 정리", biggestRisk = "소음 환경의 인식률", validationPlan = "현장 녹음 10건으로 시험",
            caveats = listOf("사람들의 요청·불편 자료 없음 — AI 추정"), category = "dev-tools", badges = listOf("급상승"),
            tags = listOf("speech"), targetCustomer = "현장 점검 담당자", coreTech = "음성 인식", pain = "점검 메모를 옮기는 일이 번거롭습니다.",
            kind = "expanded", lens = "other-customer", seedId = "x1-seed",
            speculation = listOf("현장 담당자에게도 음성 기록을 정리할 도구가 필요할 수 있습니다."))
        val report = RunReport(date, Instant.now().toString(), emptyList(), emptyList(), emptyMap(), 0, cardCount = 1)
        val run = PipelineResult(emptyList(), emptyList(), report, cards = listOf(expanded))
        val legacy = expanded.copy(id = "x1-legacy-fixture", runDate = legacyDay, title = "이전 형식 장부 도우미",
            problem = "장부를 매번 다시 작성합니다.")
        val legacyJson = JsonObject(contractJson.encodeToJsonElement(legacy).jsonObject -
            setOf("targetCustomer", "coreTech", "pain", "kind", "lens", "seedId", "speculation"))
        try {
            preferences.edit().putBoolean("finished", true).commit()
            configFile.delete()
            dayFile.writeText(contractJson.encodeToString(run))
            if (editSettings) {
                val oldRun = contractJson.encodeToJsonElement(run.copy(report = report.copy(runDate = legacyDay))).jsonObject
                legacyFile.writeText(JsonObject(oldRun + ("cards" to JsonArray(listOf(legacyJson)))).toString())
            }
            runBlocking {
                app.database.dao().saveSettings(settings.copy(repository = LOCAL_REPO))
                app.sync.refresh()
                if (editSettings) {
                    val stored = app.database.dao().artifacts(LOCAL_REPO).single { it.path == "out/$legacyDay.json" }
                    val card = contractJson.parseToJsonElement(stored.json).jsonObject.getValue("cards").jsonArray.single()
                    assertEquals(legacyJson, card)
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    expectedDark?.let { assertEquals(it, dark(activity.resources.configuration)) }
                    expectedFontScale?.let { assertEquals(it, activity.resources.configuration.fontScale, 0.05f) }
                }
                val badge = app.getString(R.string.expansion_badge, app.getString(R.string.expansion_other_customer))
                show(badge)
                show("개발 도구")
                capture("today", scenario)
                show(expanded.title).performClick()
                show("개발 도구")
                show(app.getString(R.string.evidence))
                show(app.getString(R.string.products))
                show(app.getString(R.string.expansion_speculation))
                show(app.getString(R.string.expansion_speculation_help))
                show("• " + expanded.speculation.single())
                show(app.getString(R.string.judgment)).performScrollTo()
                capture("detail")
                if (editSettings) {
                    show(app.getString(R.string.archive)).performClick()
                    show(app.getString(R.string.search))
                    show(legacy.title).performClick()
                    show(legacy.problem)
                    compose.onNodeWithText(app.getString(R.string.expansion_speculation)).assertDoesNotExist()
                }
                show(app.getString(R.string.settings)).performClick()
                show(app.getString(R.string.criteria)).performClick()
                show(app.getString(R.string.expansion_settings))
                show(app.getString(R.string.expansion_enabled)).assertIsOn()
                show(app.getString(R.string.expansion_enabled_help))
                show(app.getString(R.string.expansion_my_fields))
                capture("criteria")
                show(app.getString(R.string.expansion_methods))
                show(app.getString(R.string.expansion_enabled)).assertIsOn()
                show(app.getString(lensLabels.values.last().first)).assertIsOn()
                if (expectedFontScale == null) {
                    capture("criteria-lenses")
                    show(app.getString(R.string.expansion_enabled)).performTouchInput { click() }
                    show(app.getString(R.string.expansion_disabled)).performScrollTo()
                    show(app.getString(lensLabels.values.last().first)).assertIsNotEnabled()
                    capture("criteria-expansion-off")
                    show(app.getString(R.string.expansion_enabled)).performTouchInput { click() }
                }
                for ((label, _) in lensLabels.values) {
                    show(app.getString(label)).assertIsOn().performTouchInput { click() }
                    show(app.getString(label)).assertIsOff()
                }
                show(app.getString(R.string.expansion_no_lenses)).performScrollTo()
                capture(if (expectedFontScale == null) "criteria-lenses-off" else "criteria-lenses")
                if (editSettings) {
                    show(app.getString(R.string.expansion_my_fields)).performTextReplacement("회계\n초등 교육")
                    compose.onNodeWithText("회계\n초등 교육").assertExists()
                    scenario.onActivity { activity ->
                        val input = activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        input.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                        activity.window.decorView.findFocus()?.clearFocus()
                    }
                }
                if (expectedFontScale == null) {
                    show(app.getString(R.string.expansion_my_fields))
                    capture("criteria-fields")
                }
                if (!editSettings) return@use
                show(app.getString(R.string.save)).assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(10_000) { PipelineConfig.load(configFile).expansion.myFields == listOf("회계", "초등 교육") }
                val saved = PipelineConfig.load(configFile).expansion
                assertEquals(listOf("회계", "초등 교육"), saved.myFields)
                assertTrue(saved.lenses.values.none { it })
                if (captureEnabled() && !dark()) captureMail(scenario, run)
            }
        } finally {
            preferences.edit().putBoolean("finished", wasFinished).commit()
            for ((file, contents) in originals) {
                if (contents == null) file.delete() else file.writeBytes(contents)
            }
            runBlocking { app.database.dao().saveSettings(settings); app.sync.refresh() }
        }
    }

    private fun show(label: String): SemanticsNodeInteraction {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onAllNodesWithText(label).onFirst()
        if (!node.isDisplayed()) node.performScrollTo()
        return node.assertIsDisplayed()
    }

    private fun captureEnabled() = InstrumentationRegistry.getArguments().getString("x1Capture") == "true"
    private fun dark(config: android.content.res.Configuration = app.resources.configuration) = config.uiMode and 0x30 == 0x20

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun withAppearance(night: String, font: String, test: () -> Unit) {
        val oldNight = shell("cmd uimode night").substringAfter("Night mode: ")
        val oldFont = shell("settings get system font_scale")
        try {
            shell("cmd uimode night $night")
            shell("settings put system font_scale $font")
            compose.waitUntil(10_000) {
                dark() == (night == "yes") && app.resources.configuration.fontScale == font.toFloat()
            }
            test()
        } finally {
            shell("cmd uimode night $oldNight")
            shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
        }
    }

    private fun capture(name: String, scenario: ActivityScenario<MainActivity>? = null) {
        if (!captureEnabled()) return
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(750, 5_000)
        val theme = when {
            app.resources.configuration.fontScale == 2f -> "font200"
            dark() -> "dark"
            else -> "light"
        }
        val directory = app.filesDir.resolve("x1-screens/$theme").apply { mkdirs() }
        fun save(screen: Bitmap) {
            directory.resolve("$name.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screen.recycle()
        }
        if (scenario == null) save(checkNotNull(instrumentation.uiAutomation.takeScreenshot()))
        else scenario.onActivity { activity ->
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            save(bitmap)
        }
    }

    private fun captureMail(scenario: ActivityScenario<MainActivity>, run: PipelineResult) {
        val ready = CountDownLatch(1)
        val mail = HtmlRenderer(listOf(Category("dev-tools", "개발 도구"))).render(run)
        scenario.onActivity { activity ->
            activity.setContentView(WebView(activity).apply {
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                    val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    insets
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) { ready.countDown() }
                        })
                    }
                }
                loadDataWithBaseURL(null, mail.html, "text/html", "UTF-8", null)
            })
        }
        check(ready.await(10, TimeUnit.SECONDS))
        capture("mail")
    }
}

package app.ideacollector

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

import android.content.Intent
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.withTimeout
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ideacollector.github.contractJson
import app.ideacollector.data.PendingLocalChange
import kotlinx.coroutines.flow.first
import app.ideacollector.work.CollectorRunService
import app.ideacollector.work.DailyOutcome
import app.ideacollector.work.RunNotifications
import app.ideacollector.work.RunStatus
import collector.config.ConfigLoader
import collector.config.PipelineConfig
import collector.config.SourcesFile
import collector.llm.*
import collector.model.IdeaCard
import collector.model.Item
import collector.model.RunReport
import collector.pipeline.PipelineResult
import collector.runtime.CollectorRuntime
import collector.trend.ThemeRow
import collector.trend.TrendReport
import java.io.File
import java.time.Instant
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class R3DeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as CollectorApp
    private val prefs get() = app.getSharedPreferences("first-run", 0)
    private val key = "ANTHROPIC_API_KEY"

    @After fun clearFixtures() {
        app.modelListOverride = null
        app.testMailOverride = null
        app.runtimeOverride = null
        app.operationRunning.value = false
        CollectorRunService.running.value = false
        CollectorRunService.dailyOutcome.value = null
    }

    @Test fun wizardChecksKeySkipsMailAndCriteriaAndShowsPermissions() {
        val finished = prefs.getBoolean("finished", false)
        val secret = runBlocking { app.secrets.get(key) }
        val verified = app.getSharedPreferences("key-confirmation", 0)
        val previousCheck = verified.getString(key, null)
        val previousFailure = verified.getBoolean("failed-$key", false)
        val previousSchedule = runBlocking { app.schedule.options() }
        val testTime = java.time.LocalTime.now().plusHours(2).withSecond(0).withNano(0)
        val date = LocalDate.now()
        val runState = app.getSharedPreferences("phone-run", 0)
        val successKey = "success-$date"
        val failureKey = "failure-$date"
        val paidRetryKey = "paid-retry-block-$date"
        val savedSuccess = runState.all[successKey] as? Boolean
        val savedFailure = runState.getString(failureKey, null)
        val savedPaidRetry = runState.all[paidRetryKey] as? Boolean
        val savedLastRun = runState.getString("last-run", null)
        val dayFile = app.paths.outDir.resolve("$date.json")
        val savedDay = dayFile.takeIf { it.isFile }?.readBytes()
        val ledger = app.paths.outDir.resolve("cost-ledger/$date.json")
        val savedLedger = ledger.takeIf { it.isFile }?.readBytes()
        runBlocking { app.schedule.save(previousSchedule.copy(time = testTime)) }
        val previousMailEnabled = app.mailEnabled
        var requests = 0
        app.modelListOverride = { _, _ -> requests++; listOf(PipelineConfig.defaults.models.classify.model) }
        prefs.edit().putBoolean("finished", false).commit()
        try {
            runState.edit().remove(successKey).remove(failureKey).remove(paidRetryKey).remove("last-run").commit()
            dayFile.delete()
            ledger.delete()
            screen { scenario ->
                await(R.string.r3_step, 1)
                visible(R.string.r3_step, 1)
                scenario.onActivity {
                    assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                }
                if (runBlocking { aiKeys.none { (name, _) -> app.secrets.has(name) } && !app.secrets.has("GITHUB_TOKEN") }) {
                    capture(scenario, "SC-00-1")
                    captureWizardKeyboard(scenario)
                }
                val fixture = UUID.randomUUID().toString()
                compose.onAllNodesWithText(app.getString(R.string.key_anthropic)).onLast()
                    .performScrollTo().performTextInput(fixture)
                compose.onAllNodesWithText(app.getString(R.string.r3_check)).onFirst().performScrollTo().performClick()
                await(R.string.r3_key_ok, fixture.takeLast(4))
                action(R.string.r3_next)
                visible(R.string.r3_step, 2)
                scenario.onActivity {
                    assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                }
                action(R.string.r3_mail_skip)
                visible(R.string.r3_step, 3)
                capture(scenario, "SC-00-3")
                action(R.string.r3_skip)
                visible(R.string.r3_step, 4)
                visible(R.string.r3_exact_reason)
                if (runBlocking { !app.schedule.status().exact }) node(R.string.r3_late).performScrollTo().assertIsDisplayed()
                capture(scenario, "SC-00-4")
                if (android.os.Build.MANUFACTURER.equals("samsung", true)) {
                    node(R.string.r3_samsung).performScrollTo().assertIsDisplayed()
                } else node(R.string.r3_samsung).assertDoesNotExist()
                action(R.string.r3_next)
                await(R.string.r3_step, 5)
                capture(scenario, "SC-00-5")
                if (captureEnabled()) {
                    CollectorRunService.running.value = true
                    RunNotifications.currentStage.value = R.string.run_classifying
                    await(R.string.r3_progress, 2, app.getString(R.string.run_classifying))
                    capture(scenario, "SC-00-5-running")
                    app.schedule.failure(date, paid = true)
                    CollectorRunService.running.value = false
                    await(R.string.r3_first_retry_blocked, testTime.toString())
                    capture(scenario, "SC-00-5-failed")
                    CollectorRunService.running.value = true
                    runState.edit().remove(failureKey).commit()
                    compose.waitForIdle()
                    CollectorRunService.running.value = false
                }
                assertEquals(testTime, runBlocking { app.schedule.options().time })
                action(R.string.r3_run_later)
                await(R.string.today_title)
                assertTrue(app.firstRunFinished())
                assertEquals(1, requests)
            }
        } finally {
            prefs.edit().putBoolean("finished", finished).commit()
            runState.edit().apply {
                if (savedSuccess == null) remove(successKey) else putBoolean(successKey, savedSuccess)
                if (savedPaidRetry == null) remove(paidRetryKey) else putBoolean(paidRetryKey, savedPaidRetry)
                putString(failureKey, savedFailure)
                putString("last-run", savedLastRun)
            }.commit()
            if (savedDay != null) dayFile.writeBytes(savedDay)
            if (savedLedger != null) ledger.writeBytes(savedLedger)
            app.mailEnabled = previousMailEnabled
            verified.edit().putString(key, previousCheck).putBoolean("failed-$key", previousFailure).commit()
            runBlocking {
                if (secret == null) app.secrets.clear(key) else app.secrets.set(key, secret)
                app.schedule.save(previousSchedule)
            }
        }
    }

    @Test fun upgradeWithStoredKeyCompletesMigrationOnce() = runBlocking {
        val finished = prefs.all["finished"] as? Boolean
        val secret = app.secrets.get(key)
        try {
            app.secrets.set(key, UUID.randomUUID().toString())
            prefs.edit().putBoolean("finished", false).commit()
            val today = LocalDate.now()
            assertTrue(!app.schedule.canRunNew(today.plusDays(1)))
            app.schedule.dispatch(today) { error("Unfinished onboarding must not launch a scheduled run") }
            prefs.edit().remove("finished").commit()
            assertTrue(app.schedule.canRunNew(LocalDate.now().plusDays(1)))
            app.secrets.clear(key)
            assertTrue(app.migrateFirstRun())
        } finally {
            val edit = prefs.edit()
            if (finished == null) edit.remove("finished") else edit.putBoolean("finished", finished)
            edit.commit()
            if (secret == null) app.secrets.clear(key) else app.secrets.set(key, secret)
        }
    }

    @Test fun wizardRestoresPublishedFirstRunBeforeShowingKeys() {
        val savedFinished = prefs.all["finished"] as? Boolean
        val savedDate = app.firstRunDate()
        val target = LocalDate.now().minusDays(7)
        val report = app.paths.outDir.resolve("$target.json")
        val previous = report.takeIf { it.isFile }?.readBytes()
        try {
            prefs.edit().putBoolean("finished", false).commit()
            app.rememberFirstRunDate(target)
            checkNotNull(report.parentFile).mkdirs()
            val published = RunReport(target.toString(), Instant.now().toString(), emptyList(), emptyList(), emptyMap(), 1, cardCount = 3)
            report.writeText(contractJson.encodeToString(PipelineResult(emptyList(), emptyList(), published)))
            screen { scenario ->
                await(R.string.r3_step, 5)
                val complete = compose.onNode(hasText(app.getString(R.string.success)) and hasClickAction())
                compose.waitUntil(8_000) { complete.isDisplayed() }
                assertTrue(!app.firstRunFinished())
                capture(scenario, "SC-00-5-completed")
                complete.performClick()
                await(R.string.today_title)
                assertTrue(app.firstRunFinished())
            }
        } finally {
            if (previous == null) report.delete() else report.writeBytes(previous)
            prefs.edit().putString("date", savedDate?.toString()).apply {
                if (savedFinished == null) remove("finished") else putBoolean("finished", savedFinished)
            }.commit()
        }
    }

    @Test fun firstRunServiceBlocksPaidFailure() {
        val date = LocalDate.now()
        val state = app.getSharedPreferences("phone-run", 0)
        val failureKey = "failure-$date"
        val previous = state.getString(failureKey, null)
        try {
            app.runtimeOverride = CollectorRuntime(collector.runtime.CollectorPaths(
                app.cacheDir.resolve(UUID.randomUUID().toString()), app.paths.configDir), { null }, Clock.systemDefaultZone(),
                sourceAdapters = emptyList(), dailyProviders = { emptyList() })
            app.schedule.failure(date, paid = true)
            CollectorRunService.dailyOutcome.value = null
            screen { scenario ->
                scenario.onActivity { assertTrue(CollectorRunService.startDaily(it, firstRun = true)) }
                runBlocking {
                    val result = withTimeout(10_000) { CollectorRunService.dailyOutcome.first { it != null } }
                    assertEquals(DailyOutcome(false, R.string.r3_first_retry_blocked), result)
                }
            }
        } finally {
            state.edit().putString(failureKey, previous).commit()
        }
    }

    @Test fun existingKeyRemainsUsableAfterNetworkFailure() {
        val check = app.getSharedPreferences("key-confirmation", 0)
        val previous = check.getString(key, null)
        val failed = check.getBoolean("failed-$key", false)
        val stored = runBlocking { app.secrets.get(key) }
        val finished = prefs.getBoolean("finished", false)
        check.edit().remove(key).remove("failed-$key").commit()
        prefs.edit().putBoolean("finished", true).commit()
        app.modelListOverride = { _, _ -> throw java.net.SocketTimeoutException() }
        try {
            runBlocking { assertTrue(app.secrets.set(key, UUID.randomUUID().toString())); assertTrue(app.hasVerifiedKey()) }
            screen {
                action(R.string.settings)
                action(R.string.operate)
                action(R.string.r3_key_recheck)
                await(R.string.r3_key_unchecked)
                assertFalse(check.contains(key))
                assertFalse(check.getBoolean("failed-$key", false))
                runBlocking { assertTrue(app.hasVerifiedKey()) }
            }
        } finally {
            prefs.edit().putBoolean("finished", finished).commit()
            check.edit().putString(key, previous).putBoolean("failed-$key", failed).commit()
            runBlocking { if (stored == null) app.secrets.clear(key) else app.secrets.set(key, stored) }
        }
    }

    @Test fun mailTestUsesDraftWithoutSavingIt() = runBlocking {
        val names = listOf("MAIL_TO", "SMTP_USERNAME", "SMTP_PASSWORD")
        val before = names.associateWith { app.secrets.get(it) }
        val enabled = app.mailEnabled
        val address = listOf(UUID.randomUUID().toString(), "example.invalid").joinToString(64.toChar().toString())
        val draft = app.mailDraft(address, address, UUID.randomUUID().toString())
        var called = false
        app.testMailOverride = { supplied ->
            assertTrue(supplied == draft)
            called = true
            collector.mail.MailSendResult.SENT
        }
        assertEquals(collector.mail.MailSendResult.SENT, app.testMail(draft))
        assertTrue(called)
        assertTrue(before == names.associateWith { app.secrets.get(it) })
        assertEquals(enabled, app.mailEnabled)
    }

    @Test fun queuedCriteriaSavesMergeInOrder() = runBlocking {
        val file = app.paths.promptsDir.resolve("criteria.md")
        val revisions = app.paths.promptsDir.resolve("criteria.versions.json")
        val saved = listOf(file, revisions).associateWith { it.takeIf(File::isFile)?.readBytes() }
        try {
            file.parentFile?.mkdirs()
            file.writeText(collector.prompt.PromptCatalog.defaultText("criteria") + "\n직접 작성한 내용\n")
            app.operationRunning.value = true
            app.patchCriteria(mapOf("개발 범위" to "한 달"))
            app.patchCriteria(mapOf("수익화 방식" to "구독"))
            assertEquals(2, app.database.dao().localChanges().count { it.configName == "criteria" })
            app.finishOperation()
            val merged = app.currentCriteria()
            assertEquals("한 달", criteriaFields(merged)[0])
            assertEquals("구독", criteriaFields(merged)[4])
            assertTrue(merged.endsWith("\n직접 작성한 내용\n"))
        } finally {
            app.operationRunning.value = false
            saved.forEach { (path, bytes) -> if (bytes == null) path.delete() else path.writeBytes(bytes) }
            app.sync.refresh()
        }
    }

    @Test fun queuedSettingsPreserveEarlierFields() = runBlocking {
        val budgetFile = app.paths.outDir.resolve("settings/budget.json")
        val saved = budgetFile.takeIf(File::isFile)?.readBytes()
        try {
            app.operationRunning.value = true
            suspend fun save(field: String, amount: Double) {
                app.submit(listOf(collector.data.InboxRequest.SettingsSet(collector.data.DataBranch.VERSION,
                    requestId(), Instant.now().toString(), kotlinx.serialization.json.JsonObject(
                        mapOf(field to kotlinx.serialization.json.JsonPrimitive(amount))), null)))
            }
            save("dailyCardUsd", 0.61)
            save("analysisRequestUsd", 1.23)
            val changes = app.queuedSettings(app.database.dao().localChanges())
            assertEquals(listOf("dailyCardUsd", "analysisRequestUsd"), changes.takeLast(2).map { it.budget!!.keys.single() })
            app.finishOperation()
            val budget = collector.budget.BudgetSettings.load(app.paths.outDir)
            assertEquals(0.61, budget.dailyCardUsd, 0.0)
            assertEquals(1.23, budget.analysisRequestUsd, 0.0)
        } finally {
            app.operationRunning.value = false
            if (saved == null) budgetFile.delete() else budgetFile.writeBytes(saved)
            app.sync.refresh()
        }
    }

    @Test fun sourceSaveRejectsInvalidRoutingAndMissingSource() = runBlocking {
        val file = app.paths.sourcesConfig
        val saved = file.takeIf { it.isFile }?.readBytes()
        app.paths.configDir.mkdirs()
        file.writeText(ConfigLoader.json.encodeToString(SourcesFile.serializer(), ConfigLoader.loadDefault()))
        val source = ConfigLoader.loadDefault().sources.filterIsInstance<collector.config.HnConfig>().single()
        try {
            app.operationRunning.value = true
            val firstEdit = source.copy(profile = listOf("demand-signal"))
            app.saveSource(source, firstEdit)
            assertEquals(firstEdit, app.sourceSettings().sources.first { it.sourceId == source.sourceId })
            app.saveSource(source, source.copy(maxItems = 7))
            app.finishOperation()
            val merged = firstEdit.copy(maxItems = 7)
            assertEquals(merged, ConfigLoader.load(file).sources.first { it.sourceId == source.sourceId })
            assertTrue(runCatching { app.saveSource(merged, merged.copy(profile = emptyList())) }.isFailure)
            val withoutHn = ConfigLoader.load(file).let { it.copy(sources = it.sources.filterNot { s -> s.sourceId == source.sourceId }) }
            file.writeText(ConfigLoader.json.encodeToString(SourcesFile.serializer(), withoutHn))
            assertTrue(runCatching { app.saveSource(source, source.copy(maxItems = 7)) }.isFailure)
        } finally {
            if (saved == null) file.delete() else file.writeBytes(saved)
            app.sync.refresh()
        }
        Unit
    }

    @Test fun todayStatusAndLocalSettingsScreens() {
        val finished = prefs.getBoolean("finished", false)
        prefs.edit().putBoolean("finished", true).commit()
        val date = LocalDate.now().toString()
        val dayFile = app.paths.outDir.resolve("$date.json")
        val savedDay = dayFile.takeIf { it.isFile }?.readBytes()
        val savedPipeline = app.paths.pipelineConfig.takeIf { it.isFile }?.readBytes()
        val criteriaFile = app.paths.promptsDir.resolve("criteria.md")
        val savedCriteria = criteriaFile.takeIf { it.isFile }?.readBytes()
        val savedSources = app.paths.sourcesConfig.takeIf { it.isFile }?.readBytes()
        val ledger = app.paths.outDir.resolve("cost-ledger/$date.json")
        val savedLedger = ledger.takeIf { it.isFile }?.readBytes()
        val mailNames = listOf("MAIL_TO", "SMTP_USERNAME", "SMTP_PASSWORD")
        val savedMail = runBlocking { mailNames.associateWith { app.secrets.get(it) } }
        val previouslyEnabled = app.mailEnabled
        var completions = 0
        val provider = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                completions++
                return LlmResult("""{"result":"시험 결과"}""", id, req.model, 10, 5, 0, 0.00001)
            }
        }
        val runtime = CollectorRuntime(app.paths, { null }, Clock.systemDefaultZone(),
            sourceAdapters = emptyList(), dailyProviders = { listOf(provider) })
        fun restore(file: File, data: ByteArray?) {
            if (data == null) file.delete() else file.writeBytes(data)
        }
        try {
            val now = Instant.now()
            checkNotNull(dayFile.parentFile).mkdirs()
            app.paths.configDir.mkdirs()
            app.paths.sourcesConfig.writeText(ConfigLoader.json.encodeToString(SourcesFile.serializer(), ConfigLoader.loadDefault()))
            val items = (1..5).map { Item("hn", "$it", "시험 자료 $it", "https://example.invalid/$it", 10, publishedAt = now) }
            val card = IdeaCard(id = "r3-fixture-card", runDate = date, title = "문서 변경 요약 도우미",
                oneLiner = "팀 문서에서 바뀐 내용과 다음 할 일을 모아 줍니다.",
                whyToday = "", problem = "", techSignals = emptyList(), demandSignals = emptyList(),
                competitors = emptyList(), history = emptyList(), effort = "", differentiation = "",
                biggestRisk = "", validationPlan = "", caveats = emptyList(), category = "업무 자동화", tags = emptyList())
            val report = RunReport(date, now.toString(), emptyList(), emptyList(), emptyMap(), 1, cardCount = 1)
            if (captureEnabled()) {
                dayFile.delete()
                runBlocking { app.sync.refresh() }
                screen { scenario ->
                    compose.waitUntil(8_000) {
                        compose.onAllNodesWithText("오늘 결과 없음", substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    capture(scenario, "SC-01-no-today")
                }
            }
            dayFile.writeText(contractJson.encodeToString(PipelineResult(items, emptyList(), report, cards = listOf(card))))
            runBlocking { app.sync.refresh() }
            app.runtimeOverride = runtime
            screen { scenario ->
                val fontScale = mutableFloatStateOf(1f)
                if (InstrumentationRegistry.getArguments().getString("r3Capture") == "true") scenario.onActivity { activity ->
                    val screens = ScreensViewModel(app.database.dao())
                    activity.setContent {
                        CollectorTheme {
                            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.floatValue)) {
                                CollectorScreens(app, screens, null) {}
                            }
                        }
                    }
                }
                compose.waitUntil(8_000) { compose.onAllNodesWithText(card.title).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(card.title).assertIsDisplayed()
                capture(scenario, "SC-01-normal")
                if (InstrumentationRegistry.getArguments().getString("r3Capture") == "true") {
                    scenario.onActivity { fontScale.floatValue = 2f }
                    capture(scenario, "SC-01-200")
                    scenario.onActivity { fontScale.floatValue = 1f }
                }
                CollectorRunService.running.value = true
                RunNotifications.currentStage.value = R.string.run_classifying
                await(R.string.r3_progress, 2, app.getString(R.string.run_classifying))
                compose.onNodeWithContentDescription(app.getString(R.string.r3_running)).assertIsNotEnabled()
                capture(scenario, "SC-01-running")
                app.runtimeOverride = null
                CollectorRunService.running.value = false
                scenario.recreate()
                await(R.string.r3_key_warning)

                runBlocking {
                    val address = listOf(UUID.randomUUID().toString(), "example.invalid").joinToString(64.toChar().toString())
                    app.secrets.setAll(app.mailDraft(address, address, UUID.randomUUID().toString()))
                    app.mailEnabled = true
                }
                if (InstrumentationRegistry.getArguments().getString("r3Capture") == "true") {
                    val previousCount = RunNotifications.previousFailures.value
                    try {
                        RunNotifications.previousFailures.value = 1
                        val more = compose.onNodeWithText("외 ", substring = true)
                        compose.waitUntil(8_000) { more.isDisplayed() }
                        more.performClick()
                        await(R.string.r3_warning_title)
                        capture(scenario, "SC-01-warning-more", display = true)
                        instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
                        compose.waitUntil(8_000) { !node(R.string.r3_warning_title).isDisplayed() }
                    } finally {
                        RunNotifications.previousFailures.value = previousCount
                    }
                }
                val mailFixture = app.cacheDir.resolve("r3-mail-status").resolve(UUID.randomUUID().toString())
                app.runtimeOverride = CollectorRuntime(collector.runtime.CollectorPaths(mailFixture, app.paths.configDir),
                    { null }, Clock.systemDefaultZone())
                assertEquals(collector.runtime.ScheduledMailStatus.FAILED,
                    app.runtimeOverride!!.scheduledMailStatus(LocalDate.parse(date)))
                CollectorRunService.running.value = true
                runBlocking {
                    app.operationRunning.value = true
                    app.patchLocalConfig("pipeline.local.json", kotlinx.serialization.json.JsonObject(emptyMap()))
                }
                await(R.string.r3_pending)
                action(R.string.settings)
                action(R.string.operate)
                await(R.string.r3_pending)
                action(R.string.today)
                CollectorRunService.running.value = false
                app.operationRunning.value = false
                val failedMail = app.getString(R.string.r3_mail_fix)
                compose.waitUntil(8_000) { compose.onAllNodesWithText(failedMail, substring = true).fetchSemanticsNodes().isNotEmpty() }
                capture(scenario, "SC-01-mail-failed")
                app.runtimeOverride = runtime
                if (captureEnabled()) runBlocking { mailNames.forEach { app.secrets.clear(it) } }
                action(R.string.settings)
                action(R.string.operate)
                await(R.string.daily_enabled)
                capture(scenario, "SC-11")
                if (captureEnabled()) {
                    val checks = app.getSharedPreferences("key-confirmation", 0)
                    val before = checks.getBoolean("failed-$key", false)
                    checks.edit().putBoolean("failed-$key", true).commit()
                    try {
                        action(R.string.today)
                        action(R.string.settings)
                        action(R.string.operate)
                        val label = app.getString(R.string.r3_key_failed_replace, app.getString(R.string.provider_claude))
                        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                        capture(scenario, "SC-11-key-failed")
                    } finally {
                        checks.edit().putBoolean("failed-$key", before).commit()
                    }
                }
                if (captureEnabled()) {
                    node(R.string.r3_monthly_hint).performScrollTo()
                    capture(scenario, "SC-11-budget")
                }
                captureKeyboard(scenario, R.string.daily_budget, "SC-11-keyboard")
                runBlocking { app.database.dao().enqueueLocalChange(PendingLocalChange(
                    configName = "pipeline.local.json", json = "{}", failed = true)) }
                await(R.string.r3_failed_changes, 1)
                capture(scenario, "SC-11-failed")
                runBlocking {
                    app.database.dao().localChanges().filter { it.configName == "patch:pipeline.local.json" && it.json == "{}" }
                        .forEach { app.database.dao().removeLocalChange(it.id) }
                }
                back()
                action(R.string.sources)
                await(R.string.source_hn)
                capture(scenario, "SC-07")
                action(R.string.source_hn)
                node(R.string.r3_source_test).performScrollTo().assertIsDisplayed()
                capture(scenario, "SC-07-detail")
                back()
                back()
                val initialCount = 4
                val expectedCount = 5
                runBlocking {
                    app.patchLocalConfig("pipeline.local.json", kotlinx.serialization.json.JsonObject(
                        mapOf("ideaCount" to kotlinx.serialization.json.JsonPrimitive(initialCount))))
                }
                awaitCardCount(initialCount)
                action(R.string.criteria)
                app.operationRunning.value = true
                node(R.string.r3_card_count).performScrollTo().performTextReplacement(expectedCount.toString())
                compose.onNodeWithText(expectedCount.toString()).assertExists()
                node(R.string.save).performScrollTo().assertIsEnabled()
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
                awaitCardCount(expectedCount)
                runBlocking {
                    app.patchLocalConfig("pipeline.local.json", kotlinx.serialization.json.JsonObject(
                        mapOf("maxCardsPerCategory" to kotlinx.serialization.json.JsonPrimitive(6))))
                    app.patchCriteria(mapOf("수익화 방식" to "시험용 기준"))
                }
                node(R.string.r3_card_count).performScrollTo().performTextReplacement(initialCount.toString())
                val save = node(R.string.save).performScrollTo()
                compose.waitUntil(8_000) {
                    compose.onAllNodes(hasText(app.getString(R.string.save)) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                }
                save.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
                awaitCardCount(initialCount)
                runBlocking { app.finishOperation() }
                assertEquals(initialCount, PipelineConfig.load(app.paths.pipelineConfig).ideaCount)
                assertEquals(6, PipelineConfig.load(app.paths.pipelineConfig).maxCardsPerCategory)
                assertTrue(app.currentCriteria().contains("시험용 기준"))
                capture(scenario, "SC-09")
                captureKeyboard(scenario, R.string.r3_card_count, "SC-09-keyboard")
                back()
                action(R.string.prompts)
                capture(scenario, "SC-15")
                compose.onNodeWithText("관심 아이디어 추적").performScrollTo().performClick()
                app.runtimeOverride = runtime
                action(R.string.r3_prompt_trial)
                await(R.string.r3_check)
                assertEquals(0, completions)
                node(R.string.r3_check).performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("시험 결과", substring = true).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(1, completions)
                capture(scenario, "SC-16")
                if (captureEnabled()) {
                    action(R.string.edit)
                    captureKeyboard(scenario, R.string.prompt_body, "SC-16-keyboard")
                }
            }
        } finally {
            prefs.edit().putBoolean("finished", finished).commit()
            restore(dayFile, savedDay)
            restore(app.paths.pipelineConfig, savedPipeline)
            restore(criteriaFile, savedCriteria)
            restore(app.paths.sourcesConfig, savedSources)
            restore(ledger, savedLedger)
            app.mailEnabled = previouslyEnabled
            runBlocking {
                savedMail.forEach { (name, value) -> if (value == null) app.secrets.clear(name) else app.secrets.set(name, value) }
                app.database.dao().localChanges().filter { it.configName == "patch:pipeline.local.json" && it.json == "{}" }
                    .forEach { app.database.dao().removeLocalChange(it.id) }
                app.database.dao().observeFailedLocalChanges().first().filter { it.configName == "pipeline.local.json" && it.json == "{}" }
                    .forEach { app.database.dao().removeLocalChange(it.id) }
                app.sync.refresh()
            }
        }
    }

    @Test fun notificationsRefreshWhenActivityResumes() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        val status = RunStatus(null, false, false, true, false, false)
        screen { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    CollectorTheme {
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                RunPermissions(app, status, notifications = true)
                            }
                        }
                    }
                }
            }
            val wasGranted = androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled()
            if (!wasGranted) {
                visible(R.string.r3_permission_off, app.getString(R.string.r3_notification))
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
                instrumentation.uiAutomation.grantRuntimePermission(app.packageName, permission)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            }
            await(R.string.r3_permission_on, app.getString(R.string.r3_notification))
            capture(scenario, "permissions-granted")
        }
    }

    @Test fun analysisShowsOnlyTrendCandidates() {
        val finished = prefs.getBoolean("finished", false)
        val date = LocalDate.now().toString()
        val trend = app.paths.outDir.resolve("trend/$date.json")
        val previous = trend.takeIf { it.isFile }?.readBytes()
        prefs.edit().putBoolean("finished", true).commit()
        try {
            checkNotNull(trend.parentFile).mkdirs()
            if (captureEnabled()) {
                trend.writeText(contractJson.encodeToString(TrendReport(date = date, themes = emptyList())))
                runBlocking { app.sync.refresh() }
                screen { scenario ->
                    action(R.string.settings)
                    action(R.string.analysis_title)
                    await(R.string.r3_analysis_empty)
                    capture(scenario, "analysis-empty")
                }
            }
            trend.writeText(contractJson.encodeToString(TrendReport(date = date,
                themes = listOf(ThemeRow(id = "fixture", name = "시험 후보", candidate = true),
                    ThemeRow(id = "other", name = "일반 테마")))))
            runBlocking { app.sync.refresh() }
            screen { scenario ->
                action(R.string.settings)
                action(R.string.analysis_title)
                visible(R.string.r3_analysis_candidate, "시험 후보")
                compose.onNodeWithText("일반 테마", substring = true).assertDoesNotExist()
                capture(scenario, "analysis")
                if (captureEnabled()) {
                    node(R.string.r3_analysis_candidate, "시험 후보").performClick()
                    compose.waitUntil(8_000) {
                        compose.onAllNodesWithText("분석 실행", substring = true).fetchSemanticsNodes().isNotEmpty()
                    }
                    capture(scenario, "analysis-amount")
                }
            }
        } finally {
            prefs.edit().putBoolean("finished", finished).commit()
            if (previous == null) trend.delete() else trend.writeBytes(previous)
            runBlocking { app.sync.refresh() }
        }
    }

    @Test fun wizardKeyScreenAtTwoHundredPercent() {
        screen { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    CollectorTheme {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                            Surface(Modifier.fillMaxSize()) { FirstRunWizard(app, { }) { } }
                        }
                    }
                }
            }
            visible(R.string.r3_key_intro)
            capture(scenario, "SC-00-1-200")
        }
    }

    @Test fun warningsAreGroupedInPriorityOrderAtTwoHundredPercent() {
        screen { scenario ->
            val next = ZonedDateTime.now().plusDays(1)
            val fontScale = mutableFloatStateOf(1f)
            scenario.onActivity { activity ->
                activity.setContent {
                    CollectorTheme {
                        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.floatValue)) {
                            Surface(Modifier.fillMaxSize()) {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    RunWarnings(RunStatus(next, false, false, true, false, false),
                                        R.string.r3_too_late, true, true, {}, {})
                                }
                            }
                        }
                    }
                }
            }
            visible(R.string.r3_key_warning)
            visible(R.string.r3_warnings_more, 3)
            node(R.string.r3_missed, app.getString(R.string.r3_too_late)).assertDoesNotExist()
            scenario.onActivity { fontScale.floatValue = 2f }
            visible(R.string.r3_key_warning)
            capture(scenario, "SC-01-warning-200")
            node(R.string.r3_warnings_more, 3).performClick()
            await(R.string.r3_exact_warning)
            await(R.string.r3_budget_warning)
        }
    }

    private fun awaitCardCount(count: Int) {
        compose.waitUntil(12_000) {
            runBlocking {
                effectivePipeline(app, app.database.dao().localChanges()).ideaCount == count
            }
        }
    }

    private fun node(id: Int, vararg args: Any) = compose.onNodeWithText(app.getString(id, *args))
    private fun visible(id: Int, vararg args: Any) { node(id, *args).performScrollTo().assertIsDisplayed() }
    private fun await(id: Int, vararg args: Any) {
        compose.waitUntil(8_000) { compose.onAllNodesWithText(app.getString(id, *args)).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun action(id: Int) {
        await(id)
        val target = node(id)
        if (!target.isDisplayed()) target.performScrollTo()
        target.performClick()
    }
    private fun back() = compose.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
    private fun screen(block: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use(block)
    }
    private fun captureEnabled() = InstrumentationRegistry.getArguments().getString("r3Capture") == "true"

    private fun captureKeyboard(scenario: ActivityScenario<MainActivity>, field: Int, name: String) {
        if (!captureEnabled()) return
        action(field)
        var secured = false
        scenario.onActivity {
            secured = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            if (secured) it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                .show(WindowInsetsCompat.Type.ime())
        }
        try {
            compose.waitUntil(8_000) {
                var visible = false
                scenario.onActivity {
                    visible = ViewCompat.getRootWindowInsets(it.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                visible
            }
            capture(scenario, name, display = true)
        } finally {
            scenario.onActivity {
                androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                    .hide(WindowInsetsCompat.Type.ime())
                if (secured) it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    private fun captureWizardKeyboard(scenario: ActivityScenario<MainActivity>) {
        if (InstrumentationRegistry.getArguments().getString("r3Capture") != "true") return
        scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        try {
            compose.onAllNodesWithText(app.getString(R.string.key_anthropic)).onLast()
                .performScrollTo().performClick()
            scenario.onActivity {
                androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                    .show(WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(8_000) {
                var visible = false
                scenario.onActivity {
                    visible = ViewCompat.getRootWindowInsets(it.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                visible
            }
            capture(scenario, "SC-00-1-keyboard", display = true)
            scenario.onActivity { androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                .hide(WindowInsetsCompat.Type.ime()) }
        } finally {
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }

    private fun capture(scenario: ActivityScenario<MainActivity>, name: String, display: Boolean = false) {
        if (InstrumentationRegistry.getArguments().getString("r3Capture") != "true") return
        compose.waitForIdle()
        if (display) {
            saveCapture(name, checkNotNull(instrumentation.uiAutomation.takeScreenshot()))
            return
        }
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            saveCapture(name, bitmap)
        }
    }
    private fun saveCapture(name: String, bitmap: Bitmap) {
        val theme = if (app.resources.configuration.uiMode and 0x30 == 0x20) "dark" else "light"
        val directory = app.filesDir.resolve("r3-r4-screens/$theme").apply { mkdirs() }
        try {
            directory.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}

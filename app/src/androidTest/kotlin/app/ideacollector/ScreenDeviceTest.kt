package app.ideacollector

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import app.ideacollector.data.AppSettings
import app.ideacollector.data.Artifact
import app.ideacollector.data.RepositorySync
import app.ideacollector.github.GitHubClient
import app.ideacollector.github.contractJson

@RunWith(AndroidJUnit4::class)
class ScreenDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as CollectorApp
    private lateinit var original: AppSettings

    @Before fun samples() = runBlocking {
        original = app.settings()
        seedSample(app)
    }

    @After fun restore() = runBlocking {
        app.database.dao().saveSettings(original)
    }

    @Test fun fourTabsNavigate() = screen {
        node(R.string.today_title).assertIsDisplayed()
        node(R.string.track).performClick()
        compose.onNodeWithText("로컬 회의록 도우미").assertIsDisplayed()
        node(R.string.archive).performClick()
        node(R.string.search).assertIsDisplayed()
        node(R.string.settings).performClick()
        node(R.string.connect).assertIsDisplayed()
    }

    @Test fun starEnqueuesTrackAndFeedback() = screen {
        compose.onNodeWithText("개인 문서 찾기").performScrollTo().performClick()
        val before = runBlocking { app.database.dao().pending(SAMPLE_REPO).map { it.requestId }.toSet() }
        compose.onNodeWithContentDescription(app.getString(R.string.track_start)).performClick()
        compose.waitUntil(5_000) {
            runBlocking { app.database.dao().pending(SAMPLE_REPO).count { it.requestId !in before } == 2 }
        }
        val sent = runBlocking { app.database.dao().pending(SAMPLE_REPO).filter { it.requestId !in before } }
        assertTrue(sent.any { it.payload() is collector.data.InboxRequest.TrackStart })
        assertTrue(sent.any { it.payload() is collector.data.InboxRequest.Reaction && it.reaction == "STAR" })
        assertEquals(1L, java.time.Duration.between(java.time.Instant.parse(sent[0].createdAt),
            java.time.Instant.parse(sent[1].createdAt)).toMillis())
        assertTrue(sent[0].path < sent[1].path)
        compose.onAllNodesWithText(app.getString(R.string.pending)).assertCountEquals(2)
    }

    @Test fun notInterestedShowsReasonSheet() = screen("card/sample-card-2") {
        node(R.string.not_interested).performScrollTo().performClick()
        node(R.string.choose_reason).assertIsDisplayed()
        node(R.string.weak_evidence).performClick()
        node(R.string.save).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            runBlocking { app.database.dao().pending(SAMPLE_REPO).any { it.reaction == "NOT_INTERESTED" } }
        }
    }

    @Test fun deepLinksOpenCardAndTrack() {
        screen("card/sample-card-1") { compose.onNodeWithText("로컬 회의록 도우미").assertIsDisplayed() }
        screen("track/sample-track-1") { node(R.string.track_detail).assertIsDisplayed() }
    }

    @Test fun promptBackConfirmsUnsavedDraft() = screen {
        node(R.string.settings).performClick()
        node(R.string.prompts).performScrollTo().performClick()
        compose.onNodeWithText("관심 아이디어 추적").performScrollTo().performClick()
        node(R.string.edit).performScrollTo().performClick()
        node(R.string.prompt_body).performScrollTo().performTextReplacement("수정한 프롬프트")
        compose.onNodeWithContentDescription(app.getString(R.string.back)).performScrollTo().performClick()
        node(R.string.discard_title).assertIsDisplayed()
        node(R.string.discard).performClick()
        node(R.string.prompts).assertIsDisplayed()
    }

    @Test fun largeFontKeepsNavigationAndScrollableActions() {
        val previous = shell("settings get system font_scale").trim()
        try {
            shell("settings put system font_scale 2.0")
            screen("card/sample-card-1") {
                assertEquals(2f, app.resources.configuration.fontScale, 0.05f)
                compose.onNodeWithText("로컬 회의록 도우미").assertIsDisplayed()
                node(R.string.not_interested).performScrollTo().assertIsDisplayed()
                node(R.string.archive).performClick()
                node(R.string.search).assertIsDisplayed()
            }
        } finally {
            shell(if (previous == "null") "settings delete system font_scale" else "settings put system font_scale $previous")
        }
    }

    @Test fun rotationPreservesArchiveSearchAndPromptDraft() {
        ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
            compose.waitUntil(5_000) { compose.onAllNodesWithText(app.getString(R.string.archive)).fetchSemanticsNodes().isNotEmpty() }
            node(R.string.archive).performClick()
            node(R.string.search).performTextInput("복원 검색어")
            scenario.recreate()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("복원 검색어").fetchSemanticsNodes().isNotEmpty() }
            node(R.string.settings).performClick()
            node(R.string.prompts).performScrollTo().performClick()
            compose.onNodeWithText("관심 아이디어 추적").performScrollTo().performClick()
            node(R.string.edit).performScrollTo().performClick()
            node(R.string.prompt_body).performScrollTo().performTextReplacement("복원할 프롬프트 초안")
            scenario.recreate()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("복원할 프롬프트 초안").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun todayDistinguishesUpdateAndAuthenticationErrors() {
        val dao = app.database.dao()
        screen {
            for ((status, label) in listOf("APP_UPDATE_REQUIRED" to R.string.update_required,
                "NEEDS_AUTH" to R.string.sync_auth_action)) {
                runBlocking { dao.saveState(checkNotNull(dao.state(SAMPLE_REPO)).copy(status = status)) }
                val text = app.getString(label)
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            }
        }
    }

    @Test fun settingsShowInvalidFileWarning() {
        runBlocking {
            app.database.dao().saveArtifact(Artifact(SAMPLE_REPO,
                "settings/budget.json", "settings", "", """{"dailyCardUsd":[]}"""))
        }
        screen {
            node(R.string.settings).performClick()
            node(R.string.operate).performScrollTo().performClick()
            node(R.string.settings_read_error).assertIsDisplayed()
        }
    }

    @Test fun promptConflictRestoresDraftAndEnforcesSaveLength() {
        val draft = collector.data.InboxRequest.PromptSet(1, requestId(), java.time.Instant.now().toString(),
            "track", "보낸 초안", "old")
        runBlocking {
            app.sync.enqueue(SAMPLE_REPO, draft)
            app.database.dao().complete(SAMPLE_REPO, draft.requestId, "REJECTED", "Prompt baseHash does not match")
        }
        screen {
            node(R.string.settings).performClick()
            node(R.string.prompts).performScrollTo().performClick()
            compose.onNodeWithText("관심 아이디어 추적").performScrollTo().performClick()
            node(R.string.prompt_rebase).performScrollTo().performClick()
            val field = node(R.string.prompt_body).performScrollTo()
            field.assertTextContains(draft.body)
            field.performTextReplacement("")
            node(R.string.save).assertIsNotEnabled()
            field.performTextReplacement("a".repeat(20_000))
            node(R.string.save).assertIsEnabled()
            field.performTextReplacement("a".repeat(20_001))
            node(R.string.save).assertIsNotEnabled()
            node(R.string.prompt_too_long).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun captureReviewScreens() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("p16Capture") == "true")
        val oldNight = shell("cmd uimode night").substringAfterLast(':').trim()
        val oldFont = shell("settings get system font_scale").trim()
        val dao = app.database.dao()
        val onboarding = app.getSharedPreferences("onboarding", android.content.Context.MODE_PRIVATE)
        val previousShown = onboarding.getBoolean("shown", false)
        val directory = java.io.File(app.cacheDir, "P16-r1").apply { mkdirs() }
        fun saveCapture(name: String, screenshot: android.graphics.Bitmap) {
            java.io.File(directory, "$name.png").outputStream().use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
        }
        fun capture(name: String) {
            compose.waitForIdle()
            saveCapture(name, checkNotNull(instrumentation.uiAutomation.takeScreenshot()))
        }
        fun back() = compose.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
        try {
            for ((mode, night) in listOf("light" to "no", "dark" to "yes")) {
                shell("cmd uimode night $night")
                runBlocking { seedSample(app) }
                screen {
                    capture("SC-01-connected-$mode")
                    compose.onAllNodesWithText("로컬 회의록 도우미").onLast().performScrollTo().performClick()
                    capture("SC-02-$mode")
                    back()
                    node(R.string.archive).performClick()
                    capture("SC-03-$mode")
                    node(R.string.track).performClick()
                    capture("SC-13-$mode")
                    compose.onNodeWithText("로컬 회의록 도우미").performClick()
                    capture("SC-14-$mode")
                    node(R.string.settings).performClick()
                    capture("SC-06-$mode")
                    node(R.string.runs).performScrollTo().performClick()
                    capture("SC-10-$mode")
                    back()
                    node(R.string.prompts).performScrollTo().performClick()
                    capture("SC-15-$mode")
                    compose.onNodeWithText("관심 아이디어 추적").performScrollTo().performClick()
                    capture("SC-16-$mode")
                }
                runBlocking {
                    val at = java.time.Instant.now().toString()
                    val version = collector.data.DataBranch.VERSION
                    val card = "sample-card-1"
                    val track = "sample-track-1"
                    val requests = listOf(
                        collector.data.InboxRequest.TrackStart(version, requestId(), at, card) to "PENDING",
                        collector.data.InboxRequest.TrackKeywords(version, requestId(), at, track, listOf("자동화")) to "SENT",
                        collector.data.InboxRequest.Reaction(version, requestId(), at, card, collector.model.Feedback.Kind.STAR, "test") to "APPLIED",
                        collector.data.InboxRequest.TrackStop(version, requestId(), at, track) to "REJECTED"
                    )
                    app.sync.enqueue(SAMPLE_REPO, requests.map { it.first })
                    requests.forEach { (request, status) ->
                        dao.complete(SAMPLE_REPO, request.requestId, status,
                            app.getString(R.string.reason_invalid).takeIf { status == "REJECTED" })
                    }
                }
                screen("card/sample-card-1") {
                    node(R.string.pending).performScrollTo().assertIsDisplayed()
                    node(R.string.sent).assertIsDisplayed()
                    node(R.string.applied).assertIsDisplayed()
                    compose.onNodeWithText(app.getString(R.string.rejected), substring = true).assertIsDisplayed()
                    capture("request-status-$mode")
                }
                shell("settings put system font_scale 2.0")
                screen { capture("SC-01-font-200-$mode") }
                shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
                runBlocking { dao.saveSettings(AppSettings()) }
                onboarding.edit().putBoolean("shown", true).commit()
                screen { capture("SC-01-empty-$mode") }
                okhttp3.mockwebserver.MockWebServer().use { server ->
                    val prior = app.syncOverride
                    app.syncOverride = RepositorySync(app.database, GitHubClient(base = server.url("/")))
                    try {
                        val sha = "a".repeat(40)
                        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"object":{"type":"commit","sha":"$sha"}}"""))
                        server.enqueue(okhttp3.mockwebserver.MockResponse().setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS).setBody(
                            contractJson.encodeToString(
                                collector.data.DataManifest(updatedAt = java.time.Instant.now().toString(),
                                    latestDaily = null, latestTrend = null, files = emptyList())
                            )
                        ))
                        screen { scenario ->
                            compose.onNodeWithText("저장소를 연결하면 매일 아침 카드가 여기 나옵니다 · 연결하기").performClick()
                            compose.onNodeWithText("1 연결").assertIsDisplayed()
                            // Render the empty test form before entering the fixture token. The window stays secure.
                            scenario.onActivity { activity ->
                                val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
                                assertTrue(activity.window.attributes.flags and flag != 0)
                                val view = activity.window.decorView
                                val screenshot = android.graphics.Bitmap.createBitmap(view.width, view.height,
                                    android.graphics.Bitmap.Config.ARGB_8888)
                                view.draw(android.graphics.Canvas(screenshot))
                                saveCapture("SC-00-step-1-$mode", screenshot)
                            }
                            node(R.string.repo).performTextInput("sample/capture")
                            node(R.string.token).performTextInput("emulator-fixture-token")
                            node(R.string.verify).performClick()
                            node(R.string.repo).assertIsNotEnabled()
                            node(R.string.token).assertIsNotEnabled()
                            compose.waitUntil(5_000) { compose.onAllNodesWithText("2 알림 허용").fetchSemanticsNodes().isNotEmpty() }
                            capture("SC-00-step-2-$mode")
                            compose.onNodeWithText("다음").performClick()
                            capture("SC-00-step-3-$mode")
                        }
                    } finally {
                        runBlocking {
                            val configured = app.settings()
                            dao.saveSettings(AppSettings())
                            if (configured.repository == "sample/capture") app.secrets.clear(configured.tokenName)
                        }
                        app.syncOverride = prior
                    }
                }
            }
        } finally {
            onboarding.edit().putBoolean("shown", previousShown).commit()
            shell("cmd uimode night ${oldNight.takeIf { it in setOf("yes", "no", "auto", "custom") } ?: "auto"}")
            shell(if (oldFont == "null") "settings delete system font_scale" else "settings put system font_scale $oldFont")
        }
    }

    private fun node(id: Int) = compose.onNodeWithText(app.getString(id))

    private fun screen(deepLink: String? = null, verify: (ActivityScenario<MainActivity>) -> Unit) {
        val intent = Intent(app, MainActivity::class.java).apply {
            if (deepLink != null) data = Uri.parse("ideacollector://$deepLink")
        }
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(app.getString(R.string.archive)).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText(app.getString(R.string.loading)).fetchSemanticsNodes().isEmpty()
            }
            verify(it)
        }
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }
}

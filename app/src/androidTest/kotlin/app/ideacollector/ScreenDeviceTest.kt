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
        node(R.string.operate).assertIsDisplayed()
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
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(app.getString(R.string.pending)).fetchSemanticsNodes().size == 2
        }
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
        compose.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
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
            app.sync.enqueueSample(SAMPLE_REPO, listOf(draft))
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

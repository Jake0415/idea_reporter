package app.ideacollector

import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** SC-11 Reddit 키 입력: 둘 다 있어야 저장, Keystore 저장·삭제, 입력 중 화면 캡처 차단. */
@RunWith(AndroidJUnit4::class)
class RedditKeysDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CollectorApp
    private val names = listOf("REDDIT_CLIENT_ID", "REDDIT_CLIENT_SECRET")

    @Test fun savesBothKeysAndDeletes() {
        val previous = runBlocking { app.secrets.getAll(names) }
        runBlocking { names.forEach { app.secrets.clear(it) } }
        val id = "test-" + UUID.randomUUID()
        val secret = "test-" + UUID.randomUUID()
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        CollectorTheme {
                            val scope = rememberCoroutineScope()
                            Column { RedditKeys(app) { block -> scope.launch { block() } } }
                        }
                    }
                }
                text(R.string.r3_key_missing).assertIsDisplayed()
                text(R.string.r3_key_replace).performClick()
                field(R.string.reddit_client_id).performTextInput(id)
                compose.waitForIdle()
                scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
                text(R.string.save).assertIsNotEnabled()
                field(R.string.reddit_client_secret).performTextInput(secret)
                text(R.string.save).assertIsEnabled().performClick()
                compose.waitUntil(8_000) { compose.onAllNodesWithText(app.getString(R.string.r3_key_saved)).fetchSemanticsNodes().isNotEmpty() }
                runBlocking {
                    assertEquals(id, app.secrets.get("REDDIT_CLIENT_ID"))
                    assertEquals(secret, app.secrets.get("REDDIT_CLIENT_SECRET"))
                    assertTrue(app.redditKeysStored())
                }
                compose.onAllNodesWithText(id, substring = true).assertCountEquals(0)
                text(R.string.r3_key_delete).performClick()
                compose.waitUntil(8_000) { compose.onAllNodesWithText(app.getString(R.string.r3_key_missing)).fetchSemanticsNodes().isNotEmpty() }
                runBlocking { assertFalse(app.redditKeysStored()) }
            }
        } finally {
            runBlocking {
                names.forEach { app.secrets.clear(it) }
                previous.forEach { (name, value) -> if (value != null) app.secrets.set(name, value) }
            }
        }
    }

    private fun text(id: Int) = compose.onNodeWithText(app.getString(id))
    private fun field(id: Int) = compose.onAllNodes(hasSetTextAction() and hasText(app.getString(id))).onFirst()
}

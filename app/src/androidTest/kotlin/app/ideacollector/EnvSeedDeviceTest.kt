package app.ideacollector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 개인 빌드 키 옮기기: 허용된 키만, 한 번만, 새 자산이면 다시. 가짜 값만 쓴다. */
@RunWith(AndroidJUnit4::class)
class EnvSeedDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CollectorApp
    private val names = listOf("REDDIT_CLIENT_ID", "REDDIT_CLIENT_SECRET", "UNLISTED_KEY")

    @Test fun importsAllowedKeysOnceAndAgainForNewSeed() = runBlocking {
        val previous = app.secrets.getAll(names)
        val prefs = app.getSharedPreferences("env-seed", 0)
        val previousHash = prefs.getString("imported", null)
        try {
            prefs.edit().remove("imported").commit()
            val id = "test-" + UUID.randomUUID()
            val seed = "REDDIT_CLIENT_ID=$id\nREDDIT_CLIENT_SECRET=test-secret\nUNLISTED_KEY=nope\n".toByteArray()
            assertEquals(2, EnvSeed.import(app, seed))
            assertEquals(id, app.secrets.get("REDDIT_CLIENT_ID"))
            assertTrue(app.secrets.get("UNLISTED_KEY") == null)
            assertEquals(0, EnvSeed.import(app, seed))
            val changed = "REDDIT_CLIENT_ID=test-changed\nREDDIT_CLIENT_SECRET=test-secret\n".toByteArray()
            assertEquals(2, EnvSeed.import(app, changed))
            assertEquals("test-changed", app.secrets.get("REDDIT_CLIENT_ID"))
        } finally {
            names.forEach { app.secrets.clear(it) }
            previous.forEach { (name, value) -> if (value != null) app.secrets.set(name, value) }
            prefs.edit().apply { if (previousHash == null) remove("imported") else putString("imported", previousHash) }.commit()
        }
    }
}

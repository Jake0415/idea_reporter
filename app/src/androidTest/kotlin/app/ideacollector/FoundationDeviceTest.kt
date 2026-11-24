package app.ideacollector

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.ideacollector.data.AppDatabase
import app.ideacollector.data.AppSettings
import app.ideacollector.secret.KeystoreSecretProvider
import app.ideacollector.secret.GITHUB_TOKEN
import app.ideacollector.secret.SecretStatus
import app.ideacollector.secret.SecretStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class FoundationDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun keystoreRoundTripAndLostKey() = runBlocking {
        val secrets = KeystoreSecretProvider(context)
        val name = "TEST_KEY"
        try {
            secrets.set(name, "dummy-value-1234")
            assertTrue(secrets.has(name))
            assertEquals("1234", secrets.last4(name))
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry("app.ideacollector.secret.$name")
            assertEquals(SecretStatus.NEEDS_REGISTRATION, secrets.status(name))
            assertNull(secrets.last4(name))
            secrets.set(name, "dummy-value-5678")
            assertEquals("5678", secrets.last4(name))
        } finally {
            secrets.clear(name)
        }
        assertEquals(SecretStatus.MISSING, secrets.status(name))
    }

    @Test fun registrationRecreatesCorruptedDataStore() = runBlocking {
        val directory = java.io.File(context.cacheDir, java.util.UUID.randomUUID().toString())
        check(directory.mkdirs())
        val file = java.io.File(directory, "test.preferences_pb")
        file.writeBytes(byteArrayOf(0))
        val secrets = KeystoreSecretProvider(SecretStorage(file))
        val name = "CORRUPTION_TEST_KEY"
        try {
            assertEquals(SecretStatus.NEEDS_REGISTRATION, secrets.status(name))
            assertTrue(file.exists())
            assertTrue(secrets.set(name, "dummy-value-5678"))
            assertEquals("5678", secrets.last4(name))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry("app.ideacollector.secret.$name")
            directory.deleteRecursively()
        }
    }

    @Test fun roomPersistsSettingsAcrossOpen() = runBlocking {
        val name = "room-device-test.db"
        try {
            val initial = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                initial.dao().saveSettings(AppSettings(repository = "sample/test", syncHours = 12))
            } finally {
                initial.close()
            }
            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                assertEquals(12L, reopened.dao().settings()?.syncHours)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

}

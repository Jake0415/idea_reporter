package app.ideacollector

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import app.ideacollector.data.AppDatabase
import app.ideacollector.data.phoneRunMigration
import app.ideacollector.data.localChangeFailureMigration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhoneMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun addsFailureStateWithoutLosingQueuedRows() {
        val name = "queue-migration-test"
        helper.createDatabase(name, 7).use { db ->
            db.execSQL("INSERT INTO pending_local_changes (configName, json) VALUES ('sources.local.json', '{}')")
        }
        helper.runMigrationsAndValidate(name, 8, true, localChangeFailureMigration).use { db ->
            db.query("SELECT configName, failed FROM pending_local_changes").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("sources.local.json", rows.getString(0))
                assertEquals(0, rows.getInt(1))
            }
        }
    }

    @Test fun migratesUnsentFeedbackAndDeletesLegacyCache() {
        val name = "phone-migration-test"
        helper.createDatabase(name, 6).use { db ->
            db.execSQL("INSERT INTO artifacts VALUES ('owner/repo','out/test.json','daily','sha','{}')")
            db.execSQL("""
                INSERT INTO requests (repository, requestId, path, json, createdAt, status, uploaded, reaction)
                VALUES ('owner/repo','pending','inbox/pending.json','{"fixture":true}','now','PENDING',0,'STAR'),
                       ('owner/repo','sent','inbox/sent.json','{}','now','SENT',1,'STAR')
            """.trimIndent())
        }
        helper.runMigrationsAndValidate(name, 7, true, phoneRunMigration).use { db ->
            db.query("SELECT configName, json FROM pending_local_changes").use { rows ->
                assertTrue(rows.moveToFirst())
                assertTrue(rows.isNull(0))
                assertEquals("""[{"fixture":true}]""", rows.getString(1))
                assertFalse(rows.moveToNext())
            }
            for (table in listOf("artifacts", "requests")) {
                db.query("SELECT * FROM $table").use { assertEquals(0, it.count) }
            }
        }
    }
}

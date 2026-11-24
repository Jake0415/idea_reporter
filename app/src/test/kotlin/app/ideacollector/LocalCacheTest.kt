package app.ideacollector

import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import kotlinx.serialization.encodeToString
import app.ideacollector.data.AppDatabase
import app.ideacollector.data.LOCAL_REPO
import app.ideacollector.data.RepositorySync
import app.ideacollector.github.contractJson
import collector.model.IdeaCard
import collector.model.RunReport
import collector.pipeline.PipelineResult
import collector.runtime.CollectorPaths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LocalCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun refreshIndexesDailyAndPreservesLastValidFile() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        try {
            val paths = CollectorPaths(folder.newFolder("out"), folder.newFolder("config"))
            val importer = RepositorySync(db, paths)
            val day = "2026-11-24"
            val file = paths.outDir.resolve("$day.json")
            val card = IdeaCard(id = "card-1", runDate = day, title = "Automation", oneLiner = "workflow",
                whyToday = "", problem = "", techSignals = emptyList(), demandSignals = emptyList(), competitors = emptyList(),
                history = emptyList(), effort = "", differentiation = "", biggestRisk = "", validationPlan = "",
                caveats = emptyList(), category = "test", tags = emptyList())
            val result = PipelineResult(emptyList(), emptyList(),
                RunReport(day, "${day}T00:00:00Z", emptyList(), emptyList(), emptyMap(), 0), cards = listOf(card))
            file.writeText(contractJson.encodeToString(result))
            importer.refresh()
            assertEquals("card-1", db.dao().search(LOCAL_REPO, "Automation").single().id)
            file.writeText("{")
            importer.refresh()
            assertEquals("PARTIAL", db.dao().state(LOCAL_REPO)?.status)
            assertEquals("card-1", db.dao().search(LOCAL_REPO, "Automation").single().id)
            assertTrue(file.delete())
            importer.refresh()
            assertTrue(db.dao().search(LOCAL_REPO, "Automation").isEmpty())
        } finally {
            db.close()
        }
    }
}

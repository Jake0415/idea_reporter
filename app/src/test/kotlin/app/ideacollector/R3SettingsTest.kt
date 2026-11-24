package app.ideacollector

import app.ideacollector.github.contractJson
import collector.budget.BudgetSettings
import collector.data.DataBranch.VERSION
import collector.data.InboxRequest
import collector.data.ScheduleSettings
import collector.runtime.CollectorPaths
import collector.runtime.CollectorRuntime
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class R3SettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun sourceEditsPreserveQueuedAndUntouchedFields() {
        val original = collector.config.HnConfig()
        val queued = mergeSourcePatch(original, sourcePatch(original, original.copy(maxItems = 7)))
        val latest = mergeSourcePatch(queued, sourcePatch(original, original.copy(windowHours = 48)))
        assertEquals(original.copy(maxItems = 7, windowHours = 48), latest)
    }

    @Test fun criteriaRoundTripPreservesFreeText() {
        val original = collector.prompt.PromptCatalog.defaultText("criteria")
        assertEquals(5, criteriaFields(original).count { it.isNotBlank() })
        assertEquals(original, mergeCriteria(original, emptyMap()))
        val notes = original + "\n직접 쓴 메모\n"
        val first = mergeCriteria(notes, mapOf("개발 범위" to "한 달"))
        val second = mergeCriteria(first, mapOf("수익화 방식" to "구독"))
        assertEquals("한 달", criteriaFields(second)[0])
        assertEquals("구독", criteriaFields(second)[4])
        assertTrue(second.endsWith("\n직접 쓴 메모\n"))
        assertTrue(second.contains("- **선호:**"))
    }

    @Test fun pipelinePatchesPreserveOtherModelSettings() {
        val classify = buildJsonObject { put("models", buildJsonObject { put("classify", "first") }) }
        val fallback = buildJsonObject { put("models", buildJsonObject { put("fallback", false) }) }
        val merged = mergePipelinePatch(classify, fallback)
        assertEquals(buildJsonObject { put("models", buildJsonObject {
            put("classify", "first")
            put("fallback", false)
        }) }, merged)
    }

    @Test fun budgetAcceptsCorePrecisionAndRejectsInvalidAmounts() {
        assertEquals("0.500", budgetAmount("0.500").toString())
        assertNull(budgetAmount("0.001"))
        assertNull(budgetAmount("-1"))
        assertNull(budgetAmount("1e500"))
    }

    @Test fun operatingSettingsWriteCoreBudgetAndSchedule() = runBlocking {
        val paths = CollectorPaths(folder.newFolder("out"), folder.newFolder("config"))
        val runtime = CollectorRuntime(paths, { null }, Clock.systemUTC())
        val budget = buildJsonObject {
            put("dailyCardUsd", 0.75)
            put("monthlyTotalUsd", 15.0)
            put("analysisRequestUsd", 2.0)
        }
        runtime.applyLocalChanges(listOf(InboxRequest.SettingsSet(VERSION, requestId(),
            Instant.now().toString(), budget, ScheduleSettings(false))))
        val saved = BudgetSettings.load(paths.outDir)
        assertEquals(0.75, saved.dailyCardUsd, 0.0)
        assertEquals(15.0, checkNotNull(saved.monthlyTotalUsd), 0.0)
        assertEquals(2.0, saved.analysisRequestUsd, 0.0)
        val schedule = paths.outDir.resolve("settings/schedule.json")
        assertFalse(contractJson.decodeFromString<ScheduleSettings>(schedule.readText()).enabled)
    }
}

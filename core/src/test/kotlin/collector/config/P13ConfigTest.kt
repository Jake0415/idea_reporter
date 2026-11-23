package collector.config

import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class P13ConfigTest {
    @TempDir lateinit var directory: File
    @Test fun mergesPartialLocalConfig() {
        assertEquals("config/pipeline.local.json", PipelineConfig.LOCAL_PATH)
        val local = directory.resolve("pipeline.local.json")
        local.writeText("""{"batch":{"pollSeconds":5},"models":{"fallback":false}}""")
        val defaults = PipelineConfig.defaults
        assertEquals(defaults.copy(batch = defaults.batch.copy(pollSeconds = 5),
            models = defaults.models.copy(fallback = false)), PipelineConfig.load(local))
    }

    @Test fun defaultsEnableClassificationBatchAndSupplementSearch() {
        val raw = checkNotNull(javaClass.getResource("/pipeline.default.json")).readText()
        val config = Json.parseToJsonElement(raw).jsonObject
        assertEquals(Json.parseToJsonElement("""{"classify":true,"backfill":true,"generate":false,"pollSeconds":30,"maxWaitMinutes":90}"""), config["batch"])
        assertEquals(JsonPrimitive(true), config["supplementSearch"]!!.jsonObject["enabled"])
        assertEquals(130, PipelineConfig.defaults.deadlineMinutes)
        val legacyConfig = directory.resolve("pipeline.json")
        legacyConfig.writeText(JsonObject(config - "batch" + ("supplementSearch" to
            buildJsonObject { put("enabled", false); put("maxUses", 3) })).toString())
        assertEquals(PipelineConfig.defaults.copy(supplementSearch = PipelineConfig.Search(false, 3)),
            PipelineConfig.load(legacyConfig))
    }
}

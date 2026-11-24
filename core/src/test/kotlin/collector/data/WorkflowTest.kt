package collector.data

import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowTest {
    @Test fun workflowsUsePinnedActionsAndScopedPermissions() {
        for (name in listOf("daily", "analyze")) {
            val file = Path.of("../.github/workflows/$name.yml")
            val workflow = Yaml().load<Map<*, *>>(Files.readString(file))
            assertEquals(emptyMap<Any, Any>(), workflow["permissions"])
            val job = (workflow["jobs"] as Map<*, *>)["collector"] as Map<*, *>
            val permissions = if (name == "daily") mapOf("contents" to "write", "issues" to "write")
                else mapOf("contents" to "write")
            assertEquals(permissions, job["permissions"])
            assertEquals(mapOf("group" to "collector-data", "queue" to "max"), workflow["concurrency"])
            val steps = (job["steps"] as List<*>).map { it as Map<*, *> }
            val actions = steps.mapNotNull { it["uses"] as? String }
            assertEquals(4, actions.size)
            assertTrue(actions.all { Regex(".+@[a-f0-9]{40}").matches(it) })
            for (run in steps.mapNotNull { it["run"] as? String }) {
                assertFalse(run.contains("secrets."))
                assertFalse(Regex("""\$\{\{\s*(inputs|github\.event)\.""").containsMatchIn(run))
            }
            val triggers = (workflow["on"] ?: workflow[true]) as Map<*, *>
            assertTrue(triggers.keys.none { it in setOf("pull_request", "pull_request_target") })
            if (name == "daily") assertEquals(setOf("workflow_dispatch"), triggers.keys)
            if (name == "analyze") {
                val dispatch = triggers["workflow_dispatch"] as Map<*, *>
                assertEquals(setOf("requestId"), (dispatch["inputs"] as Map<*, *>).keys)
            }
        }
    }

    @Test fun workflowsGatePaidAndMailStagesOnPublication() {
        fun steps(name: String): List<Map<*, *>> {
            val workflow = Yaml().load<Map<*, *>>(Files.readString(Path.of("../.github/workflows/$name.yml")))
            val job = (workflow["jobs"] as Map<*, *>)["collector"] as Map<*, *>
            return (job["steps"] as List<*>).map { it as Map<*, *> }
        }
        val daily = steps("daily").associateBy { it["id"] }
        assertEquals("steps.pull.outcome == 'success'", daily.getValue("collect")["if"])
        assertEquals("always() && steps.reports.outcome == 'success'", daily.getValue("mail")["if"])
        val analysis = steps("analyze")
        assertEquals(true, analysis.single { it["name"] == "Apply inbox" }["continue-on-error"])
        val mail = analysis.single { it["name"] == "Send analysis mail" }
        assertTrue(analysis.indexOfFirst { it["id"] == "results" } < analysis.indexOf(mail))
        assertEquals("steps.analysis.outcome == 'success' && steps.results.outcome == 'success'", mail["if"])
        for (workflow in listOf(steps("daily"), analysis)) {
            val artifact = workflow.single { it["name"] == "Preserve unpublished mail journal" }
            assertEquals("out/mail/public-journal.json", (artifact["with"] as Map<*, *>)["path"])
        }
    }
}

package app.ideacollector

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExpansionSettingsTest {
    @Test fun validatesAtMostFiveFieldsOfThirtyCharacters() {
        assertEquals(listOf("회계", "초등 교육"), parseMyFields(" 회계 \n\n초등 교육 "))
        assertNotNull(parseMyFields(List(5) { "가".repeat(30) }.joinToString("\n")))
        assertNull(parseMyFields(List(6) { "회계" }.joinToString("\n")))
        assertNull(parseMyFields("가".repeat(31)))
    }

    @Test fun patchKeepsOtherExpansionAndLensValues() {
        val original = Json.parseToJsonElement("""{"expansion":{"enabled":true,"myFields":["회계"],"lenses":{"invert":true,"korea":false}}}""").jsonObject
        val patch = Json.parseToJsonElement("""{"expansion":{"lenses":{"invert":false}}}""").jsonObject
        assertEquals(Json.parseToJsonElement("""{"expansion":{"enabled":true,"myFields":["회계"],"lenses":{"invert":false,"korea":false}}}"""),
            mergePipelinePatch(original, patch))
    }
}

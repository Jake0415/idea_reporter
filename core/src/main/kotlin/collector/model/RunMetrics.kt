package collector.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class RunMetrics(
    val snapshots: List<MetricSnapshot> = emptyList(),
    val profiles: Map<String, ProfileMetrics> = emptyMap(),
    val scoreClampWarnings: Int = 0
)

@Serializable
data class ProfileMetrics(val classified: Int, val thresholdPassed: Int, val selected: Int)

internal object RunMetricsSerializer : JsonTransformingSerializer<RunMetrics>(RunMetrics.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) JsonObject(mapOf("snapshots" to element)) else element
}

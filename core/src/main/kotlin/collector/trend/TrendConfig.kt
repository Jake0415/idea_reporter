package collector.trend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TrendConfig(
    val days: Int,
    val weekDays: Int,
    val minimumDataDays: Int,
    val topicSimilarity: Double,
    val sharedTags: Int,
    val spamClusterSize: Int,
    val marketGrowth: Double,
    val marketCount: Int,
    val candidateGrowth: Double,
    val candidateDemand: Int,
    val candidateSources: Int,
    val risingLimit: Int,
    val fallingLimit: Int,
    val repositoryLimit: Int,
    val risingCount: Int,
    val fallingAverage: Double,
    val fallingGrowth: Double,
    val evidenceLimit: Int,
    val allowedLicenses: List<String>,
    val maxThemeTopics: Int = 8,
    val synonyms: Map<String, String> = emptyMap(),
    val excludeThemes: List<String> = emptyList(),
    val suspectedForkRatio: Double = 0.005,
    val suspectedMinStars: Int = 200
) {
    companion object {
        val defaults: TrendConfig by lazy {
            Json.decodeFromString(checkNotNull(TrendConfig::class.java.getResource("/trend.default.json")).readText())
        }
    }
}

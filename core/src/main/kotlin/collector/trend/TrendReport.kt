package collector.trend

import kotlinx.serialization.Serializable

@Serializable
data class TrendReport(
    val date: String = "",
    val startDate: String = "",
    val insufficientData: Boolean = true,
    val dataDays: Int = 0,
    val themes: List<ThemeRow> = emptyList(),
    val rising: List<ThemeRow> = emptyList(),
    val falling: List<ThemeRow> = emptyList(),
    val repositories: List<RepositoryTrend> = emptyList(),
    val languages: Map<String, Int> = emptyMap(),
    val licenses: Map<String, Int> = emptyMap(),
    val spam: List<SpamCluster> = emptyList(),
    val summary: List<String> = emptyList(),
    val unmappedRepeatCount: Int = 0,
    val failedSources: List<String> = emptyList(),
    val broadTags: List<String> = emptyList(),
    val backfillDays: Int = 0,
    val sparseTagCount: Int = 0
)

@Serializable
data class ThemeRow(
    val id: String = "",
    val name: String = "",
    val weeklyCounts: List<Int> = emptyList(),
    val newCount: Int = 0,
    val growth: Double? = null,
    val isNew: Boolean = false,
    val sources: List<String> = emptyList(),
    val demandCount: Int = 0,
    val marketCount: Int = 0,
    val medianStarsPerDay: Double? = null,
    val medianForkRatio: Double? = null,
    val languages: Map<String, Int> = emptyMap(),
    val licenses: Map<String, Int> = emptyMap(),
    val stars: Int = 0,
    val notInterested: Int = 0,
    val evidence: Map<String, List<TrendEvidence>> = emptyMap(),
    val overheated: Boolean = false,
    val candidate: Boolean = false,
    val examples: List<String> = emptyList(),
    val matchedSources: Boolean = false,
    val comparableCount: Int? = null
)

@Serializable
data class TrendEvidence(val itemKey: String = "", val title: String = "", val url: String = "",
                         val source: String = "", val score: Double = 0.0)

@Serializable
data class RepositoryTrend(
    val itemKey: String = "",
    val name: String = "",
    val description: String = "",
    val language: String? = null,
    val license: String? = null,
    val allowedLicense: Boolean = false,
    val forkRatio: Double? = null,
    val themeId: String? = null,
    val themeName: String? = null,
    val starsPerDay: Double = 0.0,
    val suspected: Boolean = false
)

@Serializable
data class SpamCluster(val name: String = "", val count: Int = 0, val dates: List<String> = emptyList())

package collector.config

import collector.llm.StageModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PipelineConfig(
    val models: Models,
    val classifyBatchSize: Int,
    val profiles: Map<String, Selection>,
    val ideaCount: Int,
    val maxCardsPerCategory: Int,
    val maxCandidatesPerTag: Int,
    val recentTopicDays: Int,
    val recentCardDays: Int,
    val badges: Badges,
    val tracking: Tracking,
    val commentsPerItem: Int,
    val feedbackExamples: Int,
    val historyDays: Int,
    val supplementSearch: Search,
    val dailyBudgetUsd: Double,
    val minDemandCandidates: Int = 2,
    val classifyConcurrency: Int = 3
) {
    init {
        require(classifyConcurrency >= 1) { "classifyConcurrency must be at least 1" }
    }

    @Serializable data class Models(val classify: StageModel, val generate: StageModel, val fallback: Boolean)
    @Serializable data class Selection(val threshold: Double, val maxCandidates: Int)
    @Serializable data class Badges(val hnPointsPerHour: Int, val githubStarsPerDay: Int, val streakDays: Int, val streakMin: Int)
    @Serializable data class Search(val enabled: Boolean, val maxUses: Int)
    @Serializable data class Tracking(
        val maxTracked: Int, val quietDays: Int, val queriesPerSource: Int, val minRawScoreFactor: Double,
        val strengthWeights: Map<String, Double>, val weeklyDeepDive: Search
    )

    companion object {
        val defaults: PipelineConfig by lazy {
            Json.decodeFromString(checkNotNull(PipelineConfig::class.java.getResource("/pipeline.default.json")).readText())
        }
    }
}

package collector.config

import collector.runtime.CollectorPaths
import collector.analysis.AnalysisConfig
import collector.llm.StageModel
import collector.stage.expansionLenses
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

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
    val classifyConcurrency: Int = 3,
    val backfillBudgetUsd: Double = 0.5,
    val analysis: AnalysisConfig = AnalysisConfig(),
    val batch: Batch = Batch(),
    val deadlineMinutes: Int = 130,
    val expansion: Expansion = Expansion()
) {
    init {
        require(deadlineMinutes > 0)
        require(classifyConcurrency >= 1) { "classifyConcurrency must be at least 1" }
    }

    @Serializable data class Expansion(
        val enabled: Boolean = true, val cardsPerDay: Int = 1, val seedCount: Int = 3,
        val seedHistoryDays: Int = 30, val lenses: Map<String, Boolean> = expansionLenses.associate { it.id to true },
        val myFields: List<String> = emptyList()
    ) {
        internal fun boundedMyFields(): List<String> = myFields.take(5).map {
            it.substring(0, it.offsetByCodePoints(0, it.codePointCount(0, it.length).coerceAtMost(30)))
        }

        init {
            require(cardsPerDay in 0..2) { "expansion.cardsPerDay must be in 0..2" }
            require(seedCount in 0..6) { "expansion.seedCount must be in 0..6" }
            require(seedHistoryDays >= 0) { "expansion.seedHistoryDays must be non-negative" }
        }
    }

    @Serializable data class Models(val classify: StageModel, val generate: StageModel, val fallback: Boolean)
    @Serializable data class Selection(val threshold: Double, val maxCandidates: Int)
    @Serializable data class Badges(val hnPointsPerHour: Int, val githubStarsPerDay: Int, val streakDays: Int, val streakMin: Int)
    @Serializable data class Search(
        val enabled: Boolean, val maxUses: Int,
        val allowedDomains: List<String> = emptyList(), val blockedDomains: List<String> = emptyList()
    )
    @Serializable data class Batch(
        val classify: Boolean = true, val backfill: Boolean = true, val generate: Boolean = false,
        val pollSeconds: Int = 30, val maxWaitMinutes: Int = 90, val recoveryMinutes: Int = 10
    ) {
        init {
            require(pollSeconds > 0 && maxWaitMinutes >= 0 && recoveryMinutes > 0)
        }
    }
    @Serializable data class Tracking(
        val maxTracked: Int, val quietDays: Int, val queriesPerSource: Int, val minRawScoreFactor: Double,
        val strengthWeights: Map<String, Double>, val weeklyDeepDive: Search
    )

    companion object {
        internal val LOCAL_PATH: String get() = CollectorPaths.workingDirectory().pipelineConfig.invariantSeparatorsPath

        fun load(file: File): PipelineConfig =
            if (file.isFile) parse(file.readText())
            else defaults

        internal fun parse(text: String, format: Json = Json): PipelineConfig {
            val config = format.decodeFromString<PipelineConfig>(
                merge(defaultJson, Json.parseToJsonElement(text).jsonObject).toString()
            )
            return config.copy(expansion = config.expansion.copy(myFields = config.expansion.boundedMyFields()))
        }

        private fun merge(base: JsonObject, local: JsonObject): JsonObject = JsonObject(base + local.mapValues { (key, value) ->
            val original = base[key]
            if (original is JsonObject && value is JsonObject) merge(original, value) else value
        })

        private val defaultJson by lazy {
            Json.parseToJsonElement(checkNotNull(PipelineConfig::class.java.getResource("/pipeline.default.json")).readText()).jsonObject
        }

        val defaults: PipelineConfig by lazy { Json.decodeFromString(defaultJson.toString()) }
    }
}

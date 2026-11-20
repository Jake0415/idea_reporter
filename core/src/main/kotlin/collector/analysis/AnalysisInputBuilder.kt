package collector.analysis

import collector.dev.outputJson
import collector.model.Comment
import collector.model.DropReason
import collector.model.Feedback
import collector.model.Signal
import collector.trend.TagNormalizer
import collector.trend.ThemeRow
import collector.trend.TrendConfig
import collector.trend.TrendInput
import collector.trend.TrendReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.ZoneId

@Serializable
internal data class AnalysisEvidence(
    val itemId: String, val source: String, val title: String, val url: String, val profile: String,
    val score: Double, val summary: String, val body: String, val comments: List<Comment>,
    val stars: Int? = null, val license: String? = null, val language: String? = null,
    val detailsMissing: Boolean = false
)

internal data class AnalysisInput(
    val json: JsonObject, val evidence: Map<String, List<AnalysisEvidence>>, val dataDays: Int,
    val themeNames: Map<String, String>, val reportUrlMismatches: Int = 0
)

internal class AnalysisInputLimitException : IllegalArgumentException("테마 지표와 분석 기준이 입력 문자 상한을 초과했습니다.")

internal class AnalysisInputBuilder(
    private val config: AnalysisConfig, private val trendConfig: TrendConfig = TrendConfig.defaults
) {
    @Serializable private data class Theme(val metrics: ThemeRow, val evidence: List<AnalysisEvidence>)
    @Serializable private data class Card(val id: String, val title: String, val category: String)
    @Serializable private data class DataStatus(
        val startDate: String, val endDate: String, val dataDays: Int,
        val insufficientData: Boolean, val backfillDays: Int
    )
    @Serializable private data class Payload(
        val themes: List<Theme>, val criteria: String, val recentCards: List<Card>,
        val feedback: List<Feedback>, val dataStatus: DataStatus
    )

    fun build(report: TrendReport, requested: List<String>, input: TrendInput, criteria: String,
              evidencePerTheme: Int = 15): AnalysisInput {
        require(requested.size in 1..3 && requested.distinct().size == requested.size) {
            "서로 다른 테마 ID를 1~3개 지정하세요."
        }
        val rows = requested.map { id ->
            requireNotNull(report.themes.find { it.id == id }) { "트렌드 리포트에 없는 테마입니다 · $id" }
        }
        val date = LocalDate.parse(report.date)
        val runs = input.runs.filterKeys { it in date.minusDays(4L * trendConfig.weekDays - 1)..date }.toSortedMap()
        val originals = input.earlierClassifications.associateBy { it.item.key }.toMutableMap()
        val observed = buildList {
            for (run in runs.values) {
                for (signal in run.classified.filter { it.item.key !in run.trackingOnlyKeys }) {
                    originals.putIfAbsent(signal.item.key, signal)
                    add(signal)
                }
                for (drop in run.dropped) {
                    if (drop.reason != DropReason.ALREADY_SEEN || drop.item.key in run.trackingOnlyKeys) continue
                    originals[drop.item.key]?.let { add(it.copy(item = drop.item)) }
                }
            }
        }
        val byId = (observed.asReversed() + originals.values).associateByFirst { it.item.key }
        val normalizer = TagNormalizer(trendConfig, input.themeMap.overrides)
        var reportUrlMismatches = 0
        val evidence = rows.associate { row ->
            val reportRefs = row.evidence.flatMap { (profile, refs) -> refs.map { profile to it } }
            val reportIds = reportRefs.map { (_, ref) -> ref.itemKey }.toSet()
            val reportEvidence = reportRefs.map { (profile, ref) ->
                val signal = byId[ref.itemKey]
                if (signal != null && signal.item.url != ref.url) reportUrlMismatches++
                val detailed = signal?.let {
                    evidence(it.copy(item = it.item.copy(url = ref.url),
                        classification = it.classification.copy(profile = profile, score = ref.score)))
                }
                detailed ?: AnalysisEvidence(ref.itemKey, ref.source, ref.title, ref.url, profile, ref.score,
                    "", "", emptyList(), detailsMissing = true)
            }
            val matching = observed.asReversed().filter {
                it.item.key !in reportIds && row.id in normalizer.themes(it.classification.tags, it.classification.topicKey)
            }.mapNotNull(::evidence)
            val counts = mutableMapOf<String, Int>()
            val selected = (reportEvidence + matching)
                .sortedWith(compareByDescending<AnalysisEvidence> { it.score }.thenBy { it.itemId })
                .distinctBy { it.itemId }.filter {
                    val count = counts.getOrDefault(it.profile, 0)
                    if (count >= 5) false else {
                        counts[it.profile] = count + 1
                        true
                    }
                }.take(evidencePerTheme).toMutableList()
            row.id to selected
        }
        val cards = runs.values.flatMap { it.cards }.distinctBy { it.id }.map { Card(it.id, it.title, it.category) }
        val cardIds = cards.map { it.id }.toSet()
        val feedback = input.feedback.filter {
            it.cardId in cardIds && (it.at == null || it.at.atZone(ZoneId.systemDefault()).toLocalDate() <= date)
        }
        val status = DataStatus(report.startDate, report.date, report.dataDays, report.insufficientData, report.backfillDays)
        val metrics = rows.map { it.copy(evidence = emptyMap()) }
        fun payload() = Payload(metrics.map { Theme(it, evidence.getValue(it.id)) }, criteria, cards, feedback, status)

        fun json() = outputJson.encodeToJsonElement(payload()).jsonObject
        var body = json()
        while (body.toString().length > config.maxInputChars) {
            val lowest = evidence.values.filter { it.isNotEmpty() }.minByOrNull { it.last().score }
                ?: throw AnalysisInputLimitException()
            lowest.removeAt(lowest.lastIndex)
            body = json()
        }
        return AnalysisInput(body, evidence, report.dataDays, rows.associate { it.id to it.name }, reportUrlMismatches)
    }

    private fun evidence(signal: Signal): AnalysisEvidence? {
        val (item, classification, body, hnContext, comments) = signal
        val score = classification.score ?: return null
        if (classification.profile == "none" || item.url.isBlank()) return null
        val github = item.source == "github"
        return AnalysisEvidence(item.key, item.source, item.title, item.url, classification.profile, score,
            classification.summary, body.ifBlank { signal.hnContext?.storyText.orEmpty().ifBlank { item.snippet } }.take(600),
            (comments + hnContext?.topComments.orEmpty().map { Comment("", it) } + item.comments)
                .take(3).map { it.copy(text = it.text.take(300)) },
            item.rawScore.takeIf { github }, item.extra["license"].takeIf { github }, item.extra["language"].takeIf { github })
    }

    private fun <T, K> Iterable<T>.associateByFirst(key: (T) -> K): Map<K, T> = buildMap {
        for (value in this@associateByFirst) putIfAbsent(key(value), value)
    }
}

package collector.analysis

import collector.stage.array
import collector.stage.schema
import collector.stage.string
import collector.stage.strings
import collector.stage.type
import collector.trend.TrendConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

@Serializable
internal data class OpportunityRef(val itemId: String, val url: String, val note: String)

@Serializable
internal data class OpportunityScore(
    val score: Double? = null, val reason: String = "", val refs: List<OpportunityRef> = emptyList()
)

@Serializable
internal data class Opportunity(
    val themeId: String, val name: String, val targetCustomer: String, val problem: String,
    val whyNow: String, val payer: String, val materials: List<OpportunityRef>,
    val demandRefs: List<OpportunityRef>, val techRefs: List<OpportunityRef>, val marketRefs: List<OpportunityRef>,
    val scores: Map<String, OpportunityScore> = emptyMap(), val excludedByCriteria: Boolean,
    val excludedReason: String, val whatWouldChange: String, val risks: List<String>
) {
    fun refs() = demandRefs + techRefs + marketRefs + materials + scores.values.flatMap { it.refs }
}

@Serializable internal data class OpportunityOutput(val opportunities: List<Opportunity>)

internal fun opportunitySchema(themeIds: List<String>): JsonObject {
    val refs = array(schema("itemId" to string, "url" to string, "note" to string))
    val score = schema("score" to type("integer"), "reason" to string, "refs" to refs)
    val themeId = buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(themeIds.map(::JsonPrimitive)))
    }
    return schema("opportunities" to array(schema(
        "themeId" to themeId, "name" to string, "targetCustomer" to string, "problem" to string,
        "whyNow" to string, "payer" to string, "materials" to refs, "demandRefs" to refs,
        "techRefs" to refs, "marketRefs" to refs,
        "scores" to schema(*scoreLabels.keys.map { it to score }.toTypedArray()),
        "excludedByCriteria" to type("boolean"), "excludedReason" to string,
        "whatWouldChange" to string, "risks" to strings
    )))
}

@Serializable internal enum class OpportunityDecision { REQUEST_CANDIDATE, NOT_VIABLE, INVALID }

@Serializable internal data class JudgedOpportunity(
    val id: String, val original: Opportunity, val validated: Opportunity, val score: Double?,
    val gates: List<String>, val gateFailures: List<String>, val provisional: Boolean,
    val decision: OpportunityDecision, val reason: String,
    val invalidCitations: Map<String, Map<String, Int>>, val warnings: List<String>
)

internal class OpportunityJudge(
    private val config: AnalysisConfig, private val trendConfig: TrendConfig = TrendConfig.defaults
) {
    fun judge(opportunities: List<Opportunity>, input: AnalysisInput): List<JudgedOpportunity> =
        opportunities.mapIndexed { index, opportunity -> judge("opp-${index + 1}", opportunity, input) }
            .sortedByDescending { it.score }

    private fun judge(id: String, original: Opportunity, input: AnalysisInput): JudgedOpportunity {
        val correctedThemeId = original.themeId.takeIf { it.isNotBlank() && it !in input.evidence }?.let { name ->
            input.themeNames.entries.singleOrNull { it.value == name }?.key
        }
        val themeId = correctedThemeId ?: original.themeId
        val evidence = input.evidence[themeId].orEmpty().associateBy { it.itemId to it.url }
        val invalid = linkedMapOf<String, Map<String, Int>>()
        val warnings = mutableListOf<String>()
        if (correctedThemeId != null) warnings += "테마 ID 보정 · ${original.themeId} → $themeId"
        fun List<OpportunityRef>.validate(block: String, profile: String? = null): List<OpportunityRef> {
            val counts = mutableMapOf<String, Int>()
            val accepted = filter { ref ->
                val item = evidence[ref.itemId to ref.url]
                val reason = when {
                    item == null -> "항목 ID–URL 불일치"
                    ref.note.isBlank() -> "인용 이유 비어 있음"
                    profile != null && item.profile != profile -> "블록-프로필 불일치"
                    else -> return@filter true
                }
                counts[reason] = counts.getOrDefault(reason, 0) + 1
                false
            }
            if (counts.isNotEmpty()) invalid[block] = counts
            return accepted
        }

        val scores = scoreLabels.keys.mapNotNull { key ->
            val entry = original.scores[key] ?: return@mapNotNull null
            val corrected = entry.score?.takeIf(Double::isFinite)?.coerceIn(1.0, 5.0)?.roundToInt()?.toDouble()
            if (corrected != null && corrected != entry.score) {
                warnings += "${scoreLabels.getValue(key)} 점수 보정 · ${entry.score} → ${corrected.toInt()}"
            }
            key to entry.copy(score = corrected, refs = entry.refs.validate("scores.$key"))
        }.toMap()
        val valid = original.copy(
            themeId = themeId,
            materials = original.materials.validate("materials", "tech-signal"),
            demandRefs = original.demandRefs.validate("demandRefs", "demand-signal"),
            techRefs = original.techRefs.validate("techRefs", "tech-signal"),
            marketRefs = original.marketRefs.validate("marketRefs", "market-signal"),
            scores = scores
        )
        val gates = mutableListOf<String>()
        val failures = mutableListOf<String>()
        fun gate(passed: Boolean, detail: String) {
            gates += "${if (passed) "통과" else "탈락"} · $detail"
            if (!passed) failures += detail
        }

        val demand = valid.demandRefs.distinctBy { it.itemId }.size
        gate(demand >= config.minDemandRefs, "수요 근거 ${demand}건 (기준 ${config.minDemandRefs}건)")
        val sources = valid.refs().map { evidence.getValue(it.itemId to it.url).source }.distinct().size
        gate(sources >= config.minSources, "인용 출처 ${sources}종 (기준 ${config.minSources}종)")
        val provisional = input.dataDays < config.minDataDays
        gates += "${if (provisional) "잠정" else "통과"} · 데이터 ${input.dataDays}일 (기준 ${config.minDataDays}일)"
        val repositories = valid.materials.map { evidence.getValue(it.itemId to it.url) }.filter { it.source == "github" }
        if (repositories.isNotEmpty()) {
            gate(repositories.any { repo -> trendConfig.allowedLicenses.any { it.equals(repo.license, ignoreCase = true) } },
                "GitHub 재료의 허용 라이선스")
        }
        val criteria = if (valid.excludedByCriteria) "평가 기준 제외 · ${valid.excludedReason}" else "평가 기준"
        gate(!valid.excludedByCriteria, criteria)

        val missing = scoreLabels.filterKeys { scores[it]?.score == null }.values
        val invalidReason = when {
            themeId !in input.evidence -> "요청에 없는 테마 · ${original.themeId}"
            missing.isNotEmpty() -> "점수 누락 · ${missing.joinToString()}"
            else -> null
        }
        val score = if (invalidReason == null) {
            (scores.entries.sumOf { (key, value) -> checkNotNull(value.score) * config.weights.getValue(key) } * 10)
                .roundToInt() / 10.0
        } else null
        val decision = when {
            score == null -> OpportunityDecision.INVALID
            failures.isEmpty() && score >= config.threshold -> OpportunityDecision.REQUEST_CANDIDATE
            else -> OpportunityDecision.NOT_VIABLE
        }
        val reason = when {
            invalidReason != null -> invalidReason
            decision == OpportunityDecision.REQUEST_CANDIDATE -> "의뢰 후보"
            failures.isNotEmpty() -> "관문 탈락 · ${failures.joinToString(" / ")}"
            else -> {
                val weakest = scores.minBy { checkNotNull(it.value.score) }
                "가장 약함 · ${scoreLabels.getValue(weakest.key)}(${weakest.value.score?.toInt()})"
            }
        }
        return JudgedOpportunity(id, original, valid, score, gates, failures, provisional, decision, reason, invalid, warnings)
    }

    fun summary(opportunities: List<JudgedOpportunity>): String {
        val candidates = opportunities.count { it.decision == OpportunityDecision.REQUEST_CANDIDATE }
        if (candidates > 0) return "의뢰 후보 ${candidates}건(반대 검토·의뢰서는 다음 단계)"
        val closest = opportunities.firstOrNull()
        return if (closest == null) "사업성 미달 · 평가 가능한 기회 분야 없음"
        else "사업성 미달 · ${closest.validated.name} · ${closest.reason}"
    }
}

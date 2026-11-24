package collector.analysis

import collector.llm.Effort
import collector.llm.ProviderId
import collector.llm.StageModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File

internal val scoreLabels = linkedMapOf(
    "demand" to "수요 강도",
    "willingnessToPay" to "지불 의사",
    "timing" to "시기",
    "competitionGap" to "경쟁 공백",
    "soloFeasibility" to "1인 실현성",
    "defensibility" to "방어력"
)

@Serializable
data class AnalysisConfig(
    val model: StageModel = StageModel(ProviderId.OPENAI, "gpt-6.2-sol"),
    val effort: Effort = Effort.HIGH,
    val maxOutputTokens: Int = 32_000,
    val timeoutMillis: Long = 600_000,
    val requestBudgetUsd: Double = 1.0,
    val monthlyBudgetUsd: Double = 5.0,
    val threshold: Double = 3.5,
    val weights: Map<String, Double> = mapOf(
        "demand" to 0.25, "willingnessToPay" to 0.20, "timing" to 0.15,
        "competitionGap" to 0.15, "soloFeasibility" to 0.15, "defensibility" to 0.10
    ),
    val minDemandRefs: Int = 2,
    val minSources: Int = 2,
    val minDataDays: Int = 14,
    val maxInputChars: Int = 100_000,
    val minFitOutputTokens: Int = 16_000,
    val mail: Mail = Mail()
) {
    @Serializable data class Mail(val analysis: Boolean = true)

    init {
        require(model.provider == ProviderId.OPENAI)
        require(maxOutputTokens in 1..128_000 && timeoutMillis > 0 && maxInputChars > 0)
        require(minFitOutputTokens in 1..128_000)
        require(listOf(requestBudgetUsd, monthlyBudgetUsd).all { it.isFinite() && it >= 0 })
        require(threshold in 1.0..5.0 && minDemandRefs > 0 && minSources > 0 && minDataDays > 0)
        require(weights.keys == scoreLabels.keys && weights.values.all { it in 0.0..1.0 } &&
            kotlin.math.abs(weights.values.sum() - 1.0) < 1e-9)
    }

    companion object {
        internal fun load(file: File, defaults: AnalysisConfig, report: (String) -> Unit = ::println): AnalysisConfig {
            if (!file.isFile) return defaults
            return parse(file.readText(), report)
        }

        internal fun parse(text: String, report: (String) -> Unit = {}, format: Json = Json): AnalysisConfig {
            val fields = Json.decodeFromString<JsonObject>(text)
            val configFields = fields - setOf("requestBudgetUsd", "monthlyBudgetUsd")
            if (configFields.size != fields.size) {
                report("분석 예산 설정 경고 · config/analysis.local.json의 예산 항목 무시 · budget.json 사용")
            }
            return format.decodeFromJsonElement(JsonObject(configFields))
        }
    }
}

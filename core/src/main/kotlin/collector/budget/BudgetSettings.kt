package collector.budget

import collector.dev.outputJson
import collector.dev.writeAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException

@Serializable
enum class OverBudget { ASK, AUTO_FIT, STOP }

@Serializable
data class BudgetSettings(
    val dailyCardUsd: Double = 0.50,
    val analysisRequestUsd: Double = 1.00,
    val analysisMonthlyUsd: Double = 5.00,
    val backfillRunUsd: Double = 0.50,
    val monthlyTotalUsd: Double? = null,
    val warnAtPercent: Int = 80,
    val overBudget: OverBudget = OverBudget.ASK,
    val requestCardUsd: Double = 0.50,
    val requestDailyUsd: Double = 1.00
) {
    companion object {
        private fun file(out: File) = out.resolve("settings/budget.json")

        fun load(out: File, report: (String) -> Unit = ::println): BudgetSettings {
            val file = file(out)
            if (!file.exists()) return BudgetSettings()
            val fields = try {
                Json.parseToJsonElement(file.readText()).jsonObject
            } catch (_: IOException) {
                report("예산 설정 읽기 오류 · budget.json · 기본값 사용")
                return BudgetSettings()
            } catch (_: IllegalArgumentException) {
                report("예산 설정 오류 · budget.json · 기본값 사용")
                return BudgetSettings()
            }
            var settings = BudgetSettings()
            for ((key, value) in fields) {
                if (key !in keys) continue
                settings = try {
                    settings.withValue(key, value)
                } catch (_: IllegalArgumentException) {
                    report("예산 설정 오류 · $key · 기본값 사용")
                    settings
                }
            }
            return settings
        }

        internal fun update(out: File, assignments: List<String>, report: (String) -> Unit = ::println): Map<String, JsonElement> {
            require(assignments.isNotEmpty()) { "--set-budget key=value [key=value…]" }
            var settings = load(out, report)
            val updated = linkedSetOf<String>()
            for (assignment in assignments) {
                val parts = assignment.split('=', limit = 2)
                require(parts.size == 2 && parts[0] in keys) { "지원하는 예산 설정 키와 값을 지정하세요." }
                val (key, value) = parts
                val element = try {
                    if (key == "overBudget") JsonPrimitive(value) else Json.parseToJsonElement(value)
                } catch (_: SerializationException) {
                    throw IllegalArgumentException("예산 설정 오류 · $key")
                }
                settings = settings.withValue(key, element)
                updated += key
            }
            val json = outputJson.encodeToJsonElement(settings).jsonObject
            file(out).writeAtomically(json.toString())
            return json.filterKeys { it in updated }
        }

        private val keys = setOf("dailyCardUsd", "analysisRequestUsd", "analysisMonthlyUsd", "backfillRunUsd",
            "monthlyTotalUsd", "warnAtPercent", "overBudget", "requestCardUsd", "requestDailyUsd")

        internal fun update(out: File, values: JsonObject) {
            require(values.isNotEmpty() && values.keys.all { it in keys })
            val settings = values.entries.fold(load(out) {}) { current, (key, value) ->
                current.withValue(key, value)
            }
            file(out).writeAtomically(outputJson.encodeToJsonElement(settings).toString())
        }
    }

    private fun withValue(key: String, value: JsonElement): BudgetSettings {
        require(value is JsonPrimitive) { "예산 설정 오류 · $key" }
        fun amount(): Double {
            val decimal = value.content.toBigDecimalOrNull()
            require(!value.isString && decimal != null && decimal.signum() >= 0 &&
                decimal.stripTrailingZeros().scale() <= 2 && decimal.toDouble().isFinite()) {
                "예산 설정 오류 · $key · 0 이상의 금액(소수 최대 2자리)을 지정하세요."
            }
            return decimal.toDouble()
        }
        return when (key) {
            "dailyCardUsd" -> copy(dailyCardUsd = amount())
            "requestCardUsd" -> copy(requestCardUsd = amount())
            "requestDailyUsd" -> copy(requestDailyUsd = amount())
            "analysisRequestUsd" -> copy(analysisRequestUsd = amount())
            "analysisMonthlyUsd" -> copy(analysisMonthlyUsd = amount())
            "backfillRunUsd" -> copy(backfillRunUsd = amount())
            "monthlyTotalUsd" -> copy(monthlyTotalUsd = if (value == JsonNull) null else amount())
            "warnAtPercent" -> {
                val percent = value.intOrNull
                require(!value.isString && percent != null && percent in 0..100) { "예산 설정 오류 · $key · 0~100 정수" }
                copy(warnAtPercent = percent)
            }
            "overBudget" -> copy(overBudget = requireNotNull(OverBudget.entries.find { value.isString && it.name == value.content }) {
                "예산 설정 오류 · $key · ASK, AUTO_FIT, STOP"
            })
            else -> error("알 수 없는 예산 설정 키")
        }
    }
}

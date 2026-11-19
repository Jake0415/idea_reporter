package collector.dev

import collector.llm.CostLedger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.LocalDate

internal class FileCostLedger(
    private val directory: File,
    private val historicalSpend: (LocalDate) -> Double = { 0.0 }
) : CostLedger {
    @Serializable
    private data class Entry(val reservedUsd: Double, val actualUsd: Double? = null)

    private fun file(day: LocalDate) = directory.resolve("$day.json")

    private fun entries(day: LocalDate): Map<String, Entry> {
        val file = file(day)
        return if (file.exists()) outputJson.decodeFromString(file.readText())
        else historicalSpend(day).takeIf { it > 0.0 }
            ?.let { mapOf("prior-runs" to Entry(0.0, it)) }.orEmpty()
    }

    override fun total(day: LocalDate): Double = entries(day).values.sumOf { it.actualUsd ?: it.reservedUsd }

    override fun reserve(day: LocalDate, id: String, amount: Double) =
        file(day).writeAtomically(outputJson.encodeToString(entries(day) + (id to Entry(amount))))

    override fun settle(day: LocalDate, id: String, amount: Double) {
        val entries = entries(day)
        val entry = entries.getValue(id)
        file(day).writeAtomically(outputJson.encodeToString(entries + (id to entry.copy(actualUsd = amount))))
    }
}

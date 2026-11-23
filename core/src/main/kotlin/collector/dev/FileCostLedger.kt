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
    private data class Entry(val reservedUsd: Double, val actualUsd: Double? = null, val batch: Boolean = false)

    private fun file(day: LocalDate) = directory.resolve("$day.json")

    private fun entries(day: LocalDate): Map<String, Entry> {
        val file = file(day)
        return if (file.exists()) outputJson.decodeFromString(file.readText())
        else historicalSpend(day).takeIf { it > 0.0 }
            ?.let { mapOf("prior-runs" to Entry(0.0, it)) }.orEmpty()
    }

    override fun total(day: LocalDate): Double = entries(day).values.sumOf { it.actualUsd ?: it.reservedUsd }

    override fun hasReservation(day: LocalDate, id: String) = id in entries(day)

    override fun batchReservations(owner: String): Map<LocalDate, Map<String, Double>> =
        directory.listFiles().orEmpty().mapNotNull { file ->
            val day = runCatching { LocalDate.parse(file.nameWithoutExtension) }.getOrNull() ?: return@mapNotNull null
            if (file.extension != "json") return@mapNotNull null
            day to entries(day).filter { (id, entry) -> id.startsWith("$owner/") && entry.actualUsd == null }
                .mapValues { it.value.reservedUsd }
        }.toMap()

    override fun reserve(day: LocalDate, id: String, amount: Double) =
        file(day).writeAtomically(outputJson.encodeToString(entries(day) + (id to Entry(amount))))

    override fun settle(day: LocalDate, id: String, amount: Double) = settle(day, id, amount, batch = false)

    override fun settleBatch(day: LocalDate, id: String, amount: Double) = settle(day, id, amount, batch = true)

    override fun estimatePendingBatch(day: LocalDate, id: String, amount: Double) =
        settle(day, id, amount, batch = true, onlyIfUnsettled = true)

    private fun settle(day: LocalDate, id: String, amount: Double, batch: Boolean, onlyIfUnsettled: Boolean = false) {
        val entries = entries(day)
        val entry = entries[id] ?: return
        if (onlyIfUnsettled && entry.actualUsd != null) return
        file(day).writeAtomically(outputJson.encodeToString(entries + (id to entry.copy(actualUsd = amount, batch = batch))))
    }
}

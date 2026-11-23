package collector.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

@Serializable
data class LlmCall(
    val stage: String,
    val provider: ProviderId,
    val model: String,
    val promptId: String,
    val promptVersion: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val costUsd: Double = 0.0,
    val fallbackUsed: Boolean = false,
    val error: String? = null,
    val cacheWriteTokens: Int = 0,
    val priceFallback: Boolean = false,
    val priceUnverified: Boolean = false,
    val promptVersions: Map<String, String> = emptyMap(),
    val reservedUsd: Double = 0.0,
    val batch: Boolean = false,
    val webSearchRequests: Int = 0
)

class BudgetExceededException : RuntimeException("일일 LLM 예산을 초과했습니다.")

interface CostLedger {
    fun total(day: LocalDate): Double
    fun reserve(day: LocalDate, id: String, amount: Double)
    fun settle(day: LocalDate, id: String, amount: Double)
    fun hasReservation(day: LocalDate, id: String): Boolean = true
    fun batchReservations(owner: String): Map<LocalDate, Map<String, Double>> = emptyMap()
    fun settleBatch(day: LocalDate, id: String, amount: Double) = settle(day, id, amount)
    fun estimatePendingBatch(day: LocalDate, id: String, amount: Double) = settleBatch(day, id, amount)
}

class CostGuard(
    val dailyBudgetUsd: Double,
    private val clock: Clock = Clock.systemDefaultZone(),
    initialSpentUsd: Double = 0.0,
    private val ledger: CostLedger? = null,
    private val budgetPerRun: Boolean = false,
    private val allowsReservation: (LocalDate, Double) -> Boolean = { _, _ -> true }
) {
    @Serializable
    internal data class Reservation(val date: String, val id: String, val amount: Double) {
        val day: LocalDate get() = LocalDate.parse(date)
    }

    private val mutex = Mutex()
    private val history = mutableListOf<LlmCall>()
    private val pendingBatchReservations = mutableSetOf<String>()
    private var date = LocalDate.now(clock)
    private var spent = initialSpentUsd
    private var ledgerLoaded = budgetPerRun || ledger == null

    init {
        require(dailyBudgetUsd.isFinite() && dailyBudgetUsd >= 0) { "일일 예산은 0 이상의 유한한 값이어야 합니다." }
    }

    val calls: List<LlmCall> get() = synchronized(history) { history.toList() }

    fun drainCalls(): List<LlmCall> = synchronized(history) {
        history.toList().also { history.clear() }
    }

    internal fun prepareBatchReservation(owner: String, projectedUsd: Double) =
        Reservation(LocalDate.now(clock).toString(), "$owner/${UUID.randomUUID()}", projectedUsd)

    internal suspend fun reserve(projectedUsd: Double, batch: Boolean = false, headroomUsd: Double = 0.0,
                                 prepared: Reservation? = null): Reservation = mutex.withLock {
        resetIfNewDay()
        val required = projectedUsd + headroomUsd
        if (spent >= dailyBudgetUsd || spent + required > dailyBudgetUsd ||
            !allowsReservation(date, required)) throw BudgetExceededException()
        val reservation = prepared ?: Reservation(date.toString(), UUID.randomUUID().toString(), projectedUsd)
        ledger?.reserve(reservation.day, reservation.id, projectedUsd)
        if (batch) pendingBatchReservations.add(reservation.id)
        spent += projectedUsd
        reservation
    }

    internal suspend fun restoreBatchReservations(reservations: List<Reservation>) = mutex.withLock {
        resetIfNewDay()
        for (reservation in reservations) {
            if (ledger?.hasReservation(reservation.day, reservation.id) == false) continue
            if (pendingBatchReservations.add(reservation.id) && (budgetPerRun || ledger == null && reservation.day == date)) {
                spent += reservation.amount
            }
        }
    }

    internal fun batchReservations(owner: String): List<Reservation> = ledger?.batchReservations(owner).orEmpty()
        .flatMap { (day, entries) -> entries.map { (id, amount) -> Reservation(day.toString(), id, amount) } }

    internal suspend fun estimatePendingBatch(reservations: Collection<Reservation>) = withContext(NonCancellable) {
        mutex.withLock {
            for (reservation in reservations) ledger?.estimatePendingBatch(reservation.day, reservation.id, reservation.amount)
        }
    }

    internal suspend fun record(call: LlmCall, reservation: Reservation? = null, settled: Boolean = true) = withContext(NonCancellable) {
        mutex.withLock {
            if (call.batch && reservation != null &&
                (ledger?.hasReservation(reservation.day, reservation.id) == false ||
                    ledger == null && reservation.id !in pendingBatchReservations)) return@withLock
            val reservedUsd = if (settled) 0.0 else reservation?.amount ?: 0.0
            if (reservation != null && settled) {
                if (call.batch) ledger?.settleBatch(reservation.day, reservation.id, call.costUsd)
                else ledger?.settle(reservation.day, reservation.id, call.costUsd)
            }
            resetIfNewDay()
            val batchReservation = reservation != null && pendingBatchReservations.remove(reservation.id)
            if (batchReservation && !budgetPerRun && ledger != null) {
                spent = ledger.total(date)
            } else if (budgetPerRun || reservation == null || reservation.day == date) {
                spent += maxOf(call.costUsd, reservedUsd) - (reservation?.amount ?: 0.0)
            }
            synchronized(history) { history.add(if (reservedUsd > call.costUsd) call.copy(reservedUsd = reservedUsd) else call) }
        }
    }

    private fun resetIfNewDay() {
        val today = LocalDate.now(clock)
        if (date != today || !ledgerLoaded) {
            if (!budgetPerRun) spent = ledger?.total(today) ?: 0.0
            date = today
            ledgerLoaded = true
        }
    }
}

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
    val reservedUsd: Double = 0.0
)

class BudgetExceededException : RuntimeException("일일 LLM 예산을 초과했습니다.")

interface CostLedger {
    fun total(day: LocalDate): Double
    fun reserve(day: LocalDate, id: String, amount: Double)
    fun settle(day: LocalDate, id: String, amount: Double)
}

class CostGuard(
    val dailyBudgetUsd: Double,
    private val clock: Clock = Clock.systemDefaultZone(),
    initialSpentUsd: Double = 0.0,
    private val ledger: CostLedger? = null
) {
    internal data class Reservation(val day: LocalDate, val id: String, val amount: Double)

    private val mutex = Mutex()
    private val history = mutableListOf<LlmCall>()
    private var date = LocalDate.now(clock)
    private var spent = ledger?.total(date) ?: initialSpentUsd

    init {
        require(dailyBudgetUsd.isFinite() && dailyBudgetUsd >= 0) { "일일 예산은 0 이상의 유한한 값이어야 합니다." }
    }

    val calls: List<LlmCall> get() = synchronized(history) { history.toList() }

    fun drainCalls(): List<LlmCall> = synchronized(history) {
        history.toList().also { history.clear() }
    }

    internal suspend fun reserve(projectedUsd: Double): Reservation = mutex.withLock {
        resetIfNewDay()
        if (spent >= dailyBudgetUsd || spent + projectedUsd > dailyBudgetUsd) throw BudgetExceededException()
        val reservation = Reservation(date, UUID.randomUUID().toString(), projectedUsd)
        ledger?.reserve(date, reservation.id, projectedUsd)
        spent += projectedUsd
        reservation
    }

    internal suspend fun record(call: LlmCall, reservation: Reservation? = null, settled: Boolean = true) = withContext(NonCancellable) {
        mutex.withLock {
            val reservedUsd = if (settled) 0.0 else reservation?.amount ?: 0.0
            if (reservation != null && settled) ledger?.settle(reservation.day, reservation.id, call.costUsd)
            resetIfNewDay()
            if (reservation == null || reservation.day == date) {
                spent += maxOf(call.costUsd, reservedUsd) - (reservation?.amount ?: 0.0)
            }
            synchronized(history) { history.add(if (reservedUsd > call.costUsd) call.copy(reservedUsd = reservedUsd) else call) }
        }
    }

    private fun resetIfNewDay() {
        val today = LocalDate.now(clock)
        if (date != today) {
            date = today
            spent = ledger?.total(today) ?: 0.0
        }
    }
}

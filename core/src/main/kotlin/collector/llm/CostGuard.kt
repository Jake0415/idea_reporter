package collector.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.LocalDate

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
    val priceUnverified: Boolean = false
)

class BudgetExceededException : RuntimeException("일일 LLM 예산을 초과했습니다.")

class CostGuard(val dailyBudgetUsd: Double, private val clock: Clock = Clock.systemDefaultZone()) {
    private val mutex = Mutex()
    private val history = mutableListOf<LlmCall>()
    private var date = LocalDate.now(clock)
    private var spent = 0.0

    init {
        require(dailyBudgetUsd.isFinite() && dailyBudgetUsd >= 0) { "일일 예산은 0 이상의 유한한 값이어야 합니다." }
    }

    val calls: List<LlmCall> get() = synchronized(history) { history.toList() }

    fun drainCalls(): List<LlmCall> = synchronized(history) {
        history.toList().also { history.clear() }
    }

    internal suspend fun checkBudget() = mutex.withLock {
        resetIfNewDay()
        if (spent >= dailyBudgetUsd) throw BudgetExceededException()
    }

    internal suspend fun record(call: LlmCall) = withContext(NonCancellable) {
        mutex.withLock {
            resetIfNewDay()
            spent += call.costUsd
            synchronized(history) { history.add(call) }
        }
    }

    private fun resetIfNewDay() {
        val today = LocalDate.now(clock)
        if (date != today) {
            date = today
            spent = 0.0
        }
    }
}

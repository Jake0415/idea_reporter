package collector.stage

import collector.config.PipelineConfig
import collector.model.Signal
import collector.prompt.PromptCatalog
import collector.prompt.parseCriteriaFields
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.UUID

@Serializable data class ExpansionLens(val id: String, val name: String)

@Serializable
private data class ExpansionCatalog(val lenses: List<ExpansionLens>, val customers: List<String>)

private val catalog by lazy {
    Json.decodeFromString<ExpansionCatalog>(
        checkNotNull(ExpansionLens::class.java.getResourceAsStream("/lenses.default.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    )
}

val expansionLenses: List<ExpansionLens> get() = catalog.lenses

@Serializable
data class ExpansionSeed(
    val id: String, val lens: String, val lensName: String, val lensDescription: String,
    val targetCustomer: String, val itemIds: List<String>, val techTopic: String
) {
    internal fun usedOn(day: LocalDate) = UsedExpansionSeed(day.toString(), techTopic, targetCustomer, lens)
}

@Serializable
data class UsedExpansionSeed(val date: String, val techTopic: String, val targetCustomer: String, val lens: String) {
    internal val key get() = Triple(techTopic, cardKey(targetCustomer), lens)
    internal val parsedDate get() = runCatching { LocalDate.parse(date) }.getOrNull()
}

private val lensDescription = Regex("(?m)^- `([a-z0-9-]+)`: (.+)$")

internal fun makeExpansionSeeds(
    day: LocalDate, config: PipelineConfig.Expansion, signals: List<Signal>, criteria: String,
    history: List<UsedExpansionSeed>, weights: Map<String, Int>,
    ideasPrompt: String = PromptCatalog.defaultText("ideas"), recentCards: List<RecentCard> = emptyList()
): List<ExpansionSeed> {
    if (!config.enabled || config.cardsPerDay <= 0 || config.seedCount <= 0) return emptyList()
    val historyRange = day.minusDays(config.seedHistoryDays.toLong())..day
    val recent = history.mapNotNull { seed ->
        seed.parsedDate?.takeIf { it in historyRange }?.let { seed to it }
    }
    val used = recent.mapTo(hashSetOf()) { (seed, _) -> seed.key }
    val latest = recent.groupBy { (seed, _) -> seed.lens }.mapValues { (_, value) -> value.maxOf { it.second } }
    val enabled = expansionLenses.filter { config.lenses[it.id] == true }
    if (enabled.isEmpty()) return emptyList()
    val start = Math.floorMod(day.toEpochDay(), enabled.size.toLong()).toInt()
    val lenses = (enabled.drop(start) + enabled.take(start)).sortedByDescending { lens ->
        val lastUsed = latest[lens.id]
        val age = lastUsed?.until(day, java.time.temporal.ChronoUnit.DAYS) ?: (config.seedHistoryDays.toLong() + 1)
        age + (weights[lens.id] ?: 0).coerceIn(-1, 3) * 7L
    }
    val criteriaCustomers = parseCriteriaFields(criteria)["대상 고객"].orEmpty().trim()
        .takeUnless { it.startsWith("제한 없음") }.orEmpty().split(" · ", ",").map(String::trim).filter(String::isNotBlank)
    val customers = config.boundedMyFields().filter(String::isNotBlank)
        .ifEmpty { criteriaCustomers }.ifEmpty { catalog.customers }.filter { cardKey(it).isNotEmpty() }.distinctBy(::cardKey)
    if (customers.isEmpty()) return emptyList()
    val recentPairs = recentCards.flatMap { recentCard ->
        recentCard.techTopics.map { it to cardKey(recentCard.card.targetCustomer) }
    }.toSet()
    val descriptions = (lensDescription.findAll(PromptCatalog.defaultText("ideas")).toList() +
        lensDescription.findAll(ideasPrompt).toList()).associate { it.groupValues[1] to it.groupValues[2] }
    val technology = signals.filter { it.classification.profile == "tech-signal" && it.classification.topicKey.isNotBlank() }
        .distinctBy { it.classification.topicKey }
    val demand = signals.filter { it.classification.profile in setOf("demand-signal", "market-signal") }
    val chosenLenses = mutableSetOf<String>()
    return sequence {
        for (offset in customers.indices) for ((index, tech) in technology.withIndex()) {
            val topic = tech.classification.topicKey
            val companion = demand.firstOrNull { it.classification.topicKey == topic }
                ?: demand.getOrNull(index % demand.size.coerceAtLeast(1))
            val ids = listOfNotNull(tech.item.key, companion?.item?.key)
            val customer = customers[(index + offset) % customers.size]
            val customerKey = cardKey(customer)
            if ((topic to customerKey) in recentPairs) continue
            val available = lenses.filter { Triple(topic, customerKey, it.id) !in used }
            val lens = available.firstOrNull { it.id !in chosenLenses } ?: available.firstOrNull() ?: continue
            chosenLenses += lens.id
            val id = UUID.nameUUIDFromBytes(listOf(day, topic, customerKey, lens.id).joinToString("|").toByteArray()).toString()
            yield(ExpansionSeed(id, lens.id, lens.name, descriptions.getValue(lens.id), customer, ids, topic))
        }
    }.take(config.seedCount).toList()
}

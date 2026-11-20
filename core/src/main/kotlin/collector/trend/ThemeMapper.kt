package collector.trend

import collector.model.Signal
import collector.refine.Deduplicator
import kotlinx.serialization.Serializable

@Serializable
data class ThemeMap(
    val overrides: Map<String, String> = emptyMap(),
    val version: Int = 0,
    val broadTags: List<String> = emptyList()
)

object ThemeMapper {
    internal const val VERSION = 3

    private val genericTags = setOf(
        "ai", "artificial-intelligence", "machine-learning", "deep-learning", "nlp", "llm", "github",
        "transformers", "safetensors", "gguf", "diffusers", "gradio", "pytorch", "text-generation",
        "endpoints-compatible", "mcp", "agent", "agents", "ai-agent", "ai-agents", "open-source",
        "python", "typescript", "generative-ai", "chatgpt", "claude", "openai", "gemini"
    )
    private val tagSeparator = Regex("[_\\s]+")

    fun groupTopics(signals: List<Signal>, config: TrendConfig): Map<String, String> {
        val groups = signals.filter { it.classification.topicKey.isNotBlank() }.groupBy { it.classification.topicKey }
        val keys = groups.keys.sortedWith(compareByDescending<String> { groups.getValue(it).size }.thenBy { it })
        val words = groups.mapValues { (_, group) ->
            group.flatMap { Deduplicator.titleTokens(it.classification.topicLabel) }.filter { it.length > 2 }.toSet()
        }
        val tags = groups.mapValues { (_, group) ->
            group.flatMap { it.item.tags + it.classification.tags }
                .map { it.trim().lowercase().replace(tagSeparator, "-") }
                .filter { it.length > 2 && it !in genericTags && ':' !in it }.toSet()
        }
        val frequent = tags.values.flatten().groupingBy { it }.eachCount()
            .filterValues { it >= 3 && it.toLong() * 20 >= groups.size }.keys
        val distinctTags = tags.mapValues { (_, topicTags) -> topicTags - frequent }

        val aliases = mutableMapOf<String, String>()
        val sizes = sortedMapOf<String, Int>()
        for (topic in keys) {
            val match = sizes.keys.firstOrNull { representative ->
                if (sizes.getValue(representative) >= config.maxThemeTopics) return@firstOrNull false
                val similarity = Deduplicator.jaccard(words.getValue(topic), words.getValue(representative))
                similarity >= config.topicSimilarity || (similarity >= 0.2 &&
                    distinctTags.getValue(topic).intersect(distinctTags.getValue(representative)).size >= config.sharedTags)
            }
            val representative = match ?: topic
            aliases[topic] = representative
            sizes[representative] = (sizes[representative] ?: 0) + 1
        }
        return aliases
    }
}

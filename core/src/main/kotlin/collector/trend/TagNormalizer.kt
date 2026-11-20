package collector.trend

internal class TagNormalizer(config: TrendConfig, overrides: Map<String, String>) {
    private val synonyms = config.synonyms.mapKeys { canonical(it.key) }.mapValues { canonical(it.value) }
    private val overrides = overrides.mapKeys { synonym(canonical(it.key)) }.mapValues { canonical(it.value) }
    private val excluded = config.excludeThemes.map { synonym(canonical(it)) }.toSet()

    fun themes(tags: List<String>): Set<String> = tags.mapNotNull {
        val tag = synonym(canonical(it))
        val theme = overrides[tag] ?: tag
        theme.takeIf { it.isNotEmpty() && tag !in excluded && it !in excluded }
    }.toSet()

    private fun synonym(tag: String) = synonyms[tag] ?: tag

    private companion object {
        val separator = Regex("[_\\s]+")

        val singularExceptions = setOf("news", "ios", "devops", "mlops", "kubernetes", "aws", "gas", "canvas",
            "analytics", "physics", "windows", "postgres", "redis", "nodejs", "js")
        val protectedEndings = listOf("ss", "us", "is", "sis", "ics", "ews", "ios", "ops", "as")

        fun canonical(value: String): String {
            val tag = value.lowercase().replace(separator, "-").trim('-')
            val lastPart = tag.substringAfterLast('-')
            return if (lastPart in singularExceptions || protectedEndings.any { lastPart.endsWith(it) }) tag
            else tag.removeSuffix("s").trimEnd('-')
        }
    }
}

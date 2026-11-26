package collector.prompt

val criteriaNames = listOf("개발 범위", "대상 고객", "관심 분야", "제외 분야", "수익화 방식")
private val criteriaLine = Regex("""^(- (?:\*\*)?(개발 범위|대상 고객|관심 분야|제외 분야|수익화(?: 방식)?):(?:\*\*)?[ \t]*)([^\r\n]*)""", RegexOption.MULTILINE)

private fun criteriaName(name: String) = if (name == "수익화") "수익화 방식" else name

fun parseCriteriaFields(body: String): Map<String, String> =
    buildMap {
        criteriaLine.findAll(body).forEach { putIfAbsent(criteriaName(it.groupValues[2]), it.groupValues[3]) }
    }

fun mergeCriteria(body: String, changes: Map<String, String>): String {
    val remaining = changes.toMutableMap()
    val merged = criteriaLine.replace(body) { line ->
        val name = criteriaName(line.groupValues[2])
        val value = remaining.remove(name) ?: return@replace line.value
        line.groupValues[1] + value
    }
    if (remaining.isEmpty()) return merged
    val separator = if ("\r\n" in body) "\r\n" else "\n"
    val prefix = if (merged.isEmpty() || merged.endsWith("\n")) merged else merged + separator
    return prefix + remaining.entries.joinToString(separator, postfix = separator) { (name, value) -> "- **$name:** $value" }
}

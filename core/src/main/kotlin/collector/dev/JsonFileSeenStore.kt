package collector.dev

import collector.model.Item
import collector.port.SeenStore
import collector.refine.UrlNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** PC 실행 전용: 이미 본 글을 JSON 파일에 기억한다. 앱에서는 Room 구현을 쓴다. */
class JsonFileSeenStore(private val file: File) : SeenStore {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    override suspend fun filterUnseen(items: List<Item>): List<Item> =
        filterUnseen(items, LocalDate.now())

    override suspend fun filterUnseen(items: List<Item>, today: LocalDate): List<Item> {
        val state = load()
        return items.filter {
            val url = UrlNormalizer.normalize(it.url)
            it.key !in state.keys && url !in state.urls &&
                state.keyDates[it.key]?.let { date -> date >= today.toString() } != false &&
                state.urlDates[url]?.let { date -> date >= today.toString() } != false
        }
    }

    override suspend fun markSeen(items: List<Item>) = markSeen(items, LocalDate.now())

    override suspend fun markSeen(items: List<Item>, today: LocalDate) {
        file.writeAtomically(markedContent(items, today))
    }

    internal fun markedContent(items: List<Item>, today: LocalDate): String {
        val state = load()
        val date = today.toString()
        fun remember(existing: Map<String, String>, values: List<String>): Map<String, String> =
            existing.toMutableMap().apply { values.forEach { merge(it, date, ::minOf) } }
        val updated = state.copy(
            keyDates = remember(state.keyDates, items.map { it.key }),
            urlDates = remember(state.urlDates, items.map { UrlNormalizer.normalize(it.url) })
        )
        return json.encodeToString(State.serializer(), updated)
    }

    private fun load(): State =
        if (file.isFile) json.decodeFromString(State.serializer(), file.readText(Charsets.UTF_8)) else State()

    @Serializable
    private data class State(val keys: Set<String> = emptySet(), val urls: Set<String> = emptySet(),
        val keyDates: Map<String, String> = emptyMap(), val urlDates: Map<String, String> = emptyMap())
}

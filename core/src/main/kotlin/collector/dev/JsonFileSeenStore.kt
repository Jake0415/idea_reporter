package collector.dev

import collector.model.Item
import collector.port.SeenStore
import collector.refine.UrlNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** PC 실행 전용: 이미 본 글을 JSON 파일에 기억한다. 앱에서는 Room 구현을 쓴다. */
class JsonFileSeenStore(private val file: File) : SeenStore {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    private var state: State = load()

    override suspend fun filterUnseen(items: List<Item>): List<Item> =
        items.filter { it.key !in state.keys && UrlNormalizer.normalize(it.url) !in state.urls }

    override suspend fun markSeen(items: List<Item>) {
        state = State(
            keys = state.keys + items.map { it.key },
            urls = state.urls + items.map { UrlNormalizer.normalize(it.url) }
        )
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(State.serializer(), state), Charsets.UTF_8)
    }

    private fun load(): State =
        if (file.isFile) json.decodeFromString(State.serializer(), file.readText(Charsets.UTF_8)) else State()

    @Serializable
    private data class State(val keys: Set<String> = emptySet(), val urls: Set<String> = emptySet())
}

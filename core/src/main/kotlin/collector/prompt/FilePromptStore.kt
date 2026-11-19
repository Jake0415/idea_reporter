package collector.prompt

import collector.model.InstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.DateTimeException
import java.time.Instant

class FilePromptStore internal constructor(
    private val directory: Path,
    private val replace: (Path, Path) -> Unit
) : PromptStore {
    constructor(directory: Path = Path.of("config/prompts")) : this(directory, { source, target ->
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    })

    @Serializable
    private data class Revision(
        val text: String,
        val version: Int,
        val baseHash: String,
        @Serializable(with = InstantSerializer::class) val savedAt: Instant? = null
    )

    @Synchronized
    override fun get(id: String): PromptOverride? {
        val path = promptPath(id)
        if (!Files.exists(path)) return null
        val text = Files.readString(path)
        // Equal bodies have the same hash, including comments and whitespace.
        val match = history(id).firstOrNull { it.text == text }
        return PromptOverride(id, text, match?.version?.toString() ?: "unknown", match?.baseHash.orEmpty(), match?.savedAt)
    }

    @Synchronized
    override fun save(id: String, text: String): PromptOverride {
        PromptResolver.validate(text)
        val path = promptPath(id)
        val history = history(id)
        val next = Revision(text, (history.firstOrNull()?.version ?: 0) + 1,
            PromptResolver.hash(PromptCatalog.defaultText(id)), Instant.now())
        Files.createDirectories(directory)
        write(path, text)
        write(historyPath(id), Json.encodeToString((listOf(next) + history).take(10)))
        return PromptOverride(id, text, next.version.toString(), next.baseHash, next.savedAt)
    }

    @Synchronized
    override fun reset(id: String) {
        history(id)
        Files.deleteIfExists(promptPath(id))
    }

    @Synchronized
    override fun versions(id: String): List<PromptVersion> =
        history(id).map { PromptVersion(id, it.version, it.text, it.savedAt) }

    private fun history(id: String): List<Revision> {
        val path = historyPath(id)
        if (!Files.exists(path)) return emptyList()
        try {
            return Json.decodeFromString(Files.readString(path))
        } catch (_: SerializationException) {
            // Malformed JSON or an invalid revision.
        } catch (_: DateTimeException) {
            // Invalid revision timestamp.
        } catch (_: CharacterCodingException) {
            // Invalid UTF-8.
        }
        Files.move(path, path.resolveSibling("${path.fileName}.bak"), REPLACE_EXISTING)
        return emptyList()
    }

    private fun promptPath(id: String): Path {
        PromptCatalog.requireEntry(id)
        return directory.resolve("$id.md")
    }

    private fun historyPath(id: String): Path {
        PromptCatalog.requireEntry(id)
        return directory.resolve("$id.versions.json")
    }

    private fun write(path: Path, text: String) {
        val temp = Files.createTempFile(directory, "prompt-", ".tmp")
        try {
            Files.writeString(temp, text)
            replace(temp, path)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

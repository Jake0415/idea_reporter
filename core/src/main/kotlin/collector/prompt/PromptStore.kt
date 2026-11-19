package collector.prompt

import collector.model.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class PromptOverride(
    val id: String,
    val text: String,
    val version: String,
    val baseHash: String,
    @Serializable(with = InstantSerializer::class) val editedAt: Instant?
)

@Serializable
data class PromptVersion(
    val id: String,
    val version: Int,
    val text: String,
    @Serializable(with = InstantSerializer::class) val savedAt: Instant?
)

interface PromptStore {
    fun get(id: String): PromptOverride?
    fun save(id: String, text: String): PromptOverride
    fun reset(id: String)
    fun versions(id: String): List<PromptVersion>
}

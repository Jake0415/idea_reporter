package collector.prompt

import java.security.MessageDigest

data class ResolvedPrompt(
    val text: String, val promptId: String, val version: String, val baseChanged: Boolean, val warning: String? = null
)

class PromptResolver(
    private val store: PromptStore,
    private val defaultText: (String) -> String = PromptCatalog::defaultText
) {
    fun resolve(id: String): ResolvedPrompt {
        PromptCatalog.requireEntry(id)
        val base = defaultText(id)
        val override = store.get(id) ?: return ResolvedPrompt(validate(base), id, "default", false)
        val text = try {
            validate(override.text)
        } catch (e: IllegalArgumentException) {
            return ResolvedPrompt(validate(base), id, "default", false, e.message)
        }
        return ResolvedPrompt(
            text, id, override.version,
            override.baseHash.isNotEmpty() && override.baseHash != hash(base) && override.baseHash != rawHash(base)
        )
    }

    companion object {
        private val leadingComments = Regex("""^(?:\s*<!--.*?-->)+""", RegexOption.DOT_MATCHES_ALL)

        internal fun validate(text: String): String {
            require(text.length <= 20_000) { "프롬프트는 2만 자 이하여야 합니다." }
            val body = body(text)
            require(body.isNotEmpty()) { "프롬프트 내용이 비어 있습니다." }
            return body
        }

        private fun body(text: String): String = text.replaceFirst(leadingComments, "").trim()

        internal fun hash(text: String): String = rawHash(body(text))

        // Older history entries hashed the complete default file.
        private fun rawHash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

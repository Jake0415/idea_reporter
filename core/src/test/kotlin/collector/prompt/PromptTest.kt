package collector.prompt

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.AccessDeniedException
import java.nio.file.StandardCopyOption.*
import kotlin.test.*

class PromptTest {
    @TempDir lateinit var directory: Path

    @Test fun commonPromptInfoIsAvailableWhenProfileNamesCannotBeRead() {
        val entries = PromptCatalog.buildEntries { path ->
            when (path) {
                "profiles/tech-signal/profile.json" -> throw java.io.IOException("읽기 실패")
                "profiles/demand-signal/profile.json" -> "{"
                else -> null
            }
        }
        assertEquals("prompts/ideas.md", entries.getValue("ideas").resourcePath)
        for (profile in listOf("tech-signal", "demand-signal", "market-signal")) {
            assertEquals("$profile 포함 기준", entries.getValue("profile.$profile.match").name)
        }
    }

    @Test fun resolvesOverridesAndTracksRevisions() {
        val store = FilePromptStore(directory)
        val resolver = PromptResolver(store)
        assertEquals("default", resolver.resolve("classify").version)
        assertFalse(resolver.resolve("classify").text.startsWith("<!--"))
        repeat(12) { store.save("classify", "<!-- 설명 -->\n수정 ${it + 1}") }
        assertEquals("수정 12", resolver.resolve("classify").text)
        assertEquals("12", resolver.resolve("classify").version)
        assertEquals((12 downTo 3).toList(), store.versions("classify").map { it.version })
        assertTrue(PromptResolver(store) { "새 기본값" }.resolve("classify").baseChanged)
        store.reset("classify")
        assertEquals("default", resolver.resolve("classify").version)
        assertEquals("13", store.save("classify", "다시 수정").version)
    }

    @Test fun rejectsInvalidOverrides() {
        val store = FilePromptStore(directory)
        for (text in listOf("", "<!-- 설명 -->", "가".repeat(20_001))) {
            assertFailsWith<IllegalArgumentException> { store.save("classify", text) }
        }
        assertNull(store.get("classify"))
        assertEquals(20_000, store.save("classify", "가".repeat(20_000)).text.length)
    }

    @Test fun usesDefaultForBrokenOverridesAndIgnoresCommentChanges() {
        val store = FilePromptStore(directory)
        val saved = store.save("classify", "수정")
        assertEquals(saved, store.get("classify"))
        assertEquals(saved.editedAt, store.versions("classify").single().savedAt)
        val base = PromptCatalog.defaultText("classify")
        val resolver = PromptResolver(store) { "<!-- 새 설명 -->\n$base" }
        assertFalse(resolver.resolve("classify").baseChanged)
        for (text in listOf("", "가".repeat(20_001))) {
            Files.writeString(directory.resolve("classify.md"), text)
            val resolved = resolver.resolve("classify")
            assertEquals("default", resolved.version)
            assertEquals(PromptResolver.validate(base), resolved.text)
            assertNotNull(resolved.warning)
        }
    }

    @Test fun readsBodyWithUnknownVersionAfterSecondReplacementFails() {
        val store = FilePromptStore(directory) { source, target ->
            if (target.fileName.toString().endsWith(".json")) throw AccessDeniedException(target.toString())
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val reader = FilePromptStore(directory)
        val previous = reader.save("classify", "이전 본문")
        assertFailsWith<AccessDeniedException> { store.save("classify", "새 본문") }
        val current = assertNotNull(reader.get("classify"))
        assertEquals("새 본문", current.text)
        assertEquals("unknown", current.version)
        assertEquals("", current.baseHash)
        assertEquals(previous.text, reader.versions("classify").single().text)
    }

    @Test fun recoversCorruptHistoryThroughEveryOperation() {
        val store = FilePromptStore(directory)
        val history = directory.resolve("classify.versions.json")
        val backup = directory.resolve("classify.versions.json.bak")
        for (corrupt in listOf(byteArrayOf(0xc3.toByte(), 0x28), "{".toByteArray())) {
            store.save("classify", "original")
            Files.write(history, corrupt)
            assertEquals("unknown", assertNotNull(store.get("classify")).version)
            assertContentEquals(corrupt, Files.readAllBytes(backup))

            Files.write(history, corrupt)
            assertTrue(store.versions("classify").isEmpty())
            assertFalse(Files.exists(history))

            Files.write(history, corrupt)
            store.reset("classify")
            assertNull(store.get("classify"))
            assertFalse(Files.exists(history))

            Files.write(history, corrupt)
            assertEquals("1", store.save("classify", "replacement").version)
            assertEquals("replacement", store.versions("classify").single().text)
            assertContentEquals(corrupt, Files.readAllBytes(backup))
        }
    }

    @Test fun validatesBodyWhenHistoryCannotBeDecoded() {
        val store = FilePromptStore(directory)
        store.save("classify", "수정")
        val history = directory.resolve("classify.versions.json")
        val resolver = PromptResolver(store)
        for (corrupt in listOf("{", """[{"text":"수정","version":1,"baseHash":"","savedAt":"bad"}]""")) {
            Files.writeString(history, corrupt)
            assertEquals("unknown", resolver.resolve("classify").version)
        }
        Files.writeString(directory.resolve("classify.md"), "<!-- 설명 -->")
        val resolved = resolver.resolve("classify")
        assertEquals("default", resolved.version)
        assertEquals(PromptResolver.validate(PromptCatalog.defaultText("classify")), resolved.text)
        assertNotNull(resolved.warning)
    }
}

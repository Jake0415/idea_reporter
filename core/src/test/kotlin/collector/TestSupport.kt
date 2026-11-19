package collector

import collector.model.Item
import java.time.Instant

fun fixture(name: String): String =
    object {}.javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

val NOW: Instant = Instant.parse("2026-11-19T00:00:00Z")

fun item(
    source: String = "hn",
    id: String = "1",
    title: String = "Some title",
    url: String = "https://example.com/$id",
    rawScore: Int = 100,
    publishedAt: Instant = NOW.minusSeconds(3600),
    snippet: String = ""
) = Item(source = source, externalId = id, title = title, url = url, rawScore = rawScore, publishedAt = publishedAt, snippet = snippet)

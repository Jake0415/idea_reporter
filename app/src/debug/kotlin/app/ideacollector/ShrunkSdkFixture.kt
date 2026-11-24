package app.ideacollector

import android.content.Context
import collector.llm.AnthropicProvider
import collector.llm.Effort
import collector.llm.LlmRequest
import collector.llm.OpenAiProvider
import collector.runtime.CollectorPaths
import collector.runtime.CollectorRuntime
import java.time.Clock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

// A stable entry point for the separate instrumentation APK; SDK code is optimized normally.
object ShrunkSdkFixture {
    @JvmStatic fun complete(baseUrl: String, anthropic: Boolean): String? = runBlocking {
        val schema = Json.parseToJsonElement(
            """{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}"""
        ).jsonObject
        val request = LlmRequest(if (anthropic) "claude-haiku-5-5" else "gpt-5.4-mini",
            "system", "test", schema, "test", 128, Effort.LOW)
        if (anthropic) AnthropicProvider("mock", baseUrl).use { it.complete(request).json }
        else OpenAiProvider("mock", "$baseUrl/v1").use { it.complete(request).json }
    }

    @JvmStatic fun initializeSmtp(context: Context, port: Int) {
        runBlocking {
            val fixture = context.cacheDir.resolve("shrunk-smtp")
            val paths = CollectorPaths(fixture.resolve("out"), fixture.resolve("config"))
            val secrets = mapOf("SMTP_HOST" to "127.0.0.1", "SMTP_PORT" to port.toString(),
                "SMTP_USERNAME" to "sender@example.invalid", "SMTP_PASSWORD" to "fixture",
                "MAIL_TO" to "recipient@example.invalid")
            CollectorRuntime(paths, { secrets[it] }, Clock.systemUTC()).mailTest()
        }
    }
}

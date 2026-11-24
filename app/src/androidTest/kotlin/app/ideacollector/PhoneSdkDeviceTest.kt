package app.ideacollector

import collector.llm.AnthropicProvider
import collector.llm.Effort
import collector.llm.LlmRequest
import collector.llm.OpenAiProvider
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Properties
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PhoneSdkDeviceTest {
    private val schema = Json.parseToJsonElement(
        """{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}"""
    ).jsonObject
    private val request = LlmRequest("", "system", "test", schema, "test", 128, Effort.LOW)
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun anthropic() = runBlocking {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            server.enqueue(json("""{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
                "content":[{"type":"text","text":"{\"ok\":true}"}],"stop_reason":"end_turn","stop_sequence":null,
                "usage":{"input_tokens":10,"output_tokens":4}}"""))
            AnthropicProvider("mock", server.url("/").toString()).use {
                assertEquals("""{"ok":true}""", it.complete(request.copy(model = "claude-haiku-5-5")).json)
            }
            assertEquals("/v1/messages", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        }
    }

    @Test fun openAi() = runBlocking {
        MockWebServer().use { server ->
            server.start(loopback, 0)
            server.enqueue(json("""{"id":"chat_1","object":"chat.completion","created":1,"model":"gpt-5.4-mini",
                "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"{\"ok\":true}"}}],
                "usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}"""))
            OpenAiProvider("mock", server.url("/v1").toString()).use {
                assertEquals("""{"ok":true}""", it.complete(request.copy(model = "gpt-5.4-mini")).json)
            }
            assertEquals("/v1/chat/completions", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        }
    }

    @Test fun angusSendsOneMessageOverLoopback() {
        ServerSocket(0, 1, loopback).use { listener ->
            listener.soTimeout = 5_000
            val received = FutureTask {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream().bufferedWriter()
                    fun reply(text: String) { output.write("$text\r\n"); output.flush() }
                    reply("220 fake SMTP")
                    var data = false
                    val message = mutableListOf<String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        when {
                            data && line == "." -> { data = false; reply("250 stored") }
                            data -> message += line
                            line == "DATA" -> { data = true; reply("354 send data") }
                            line == "QUIT" -> { reply("221 done"); break }
                            else -> reply("250 ok")
                        }
                    }
                    message
                }
            }
            thread(isDaemon = true) { received.run() }
            val session = Session.getInstance(Properties().apply {
                setProperty("mail.smtp.starttls.enable", "false")
                setProperty("mail.smtp.connectiontimeout", "5000")
                setProperty("mail.smtp.timeout", "5000")
                setProperty("mail.smtp.localhost", "localhost")
            })
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress("sender@example.invalid"))
                setRecipients(Message.RecipientType.TO, "recipient@example.invalid")
                subject = "local delivery"
                setText("fixture")
                saveChanges()
            }
            session.getTransport("smtp").use {
                it.connect(loopback.hostAddress, listener.localPort, null, null)
                it.sendMessage(message, message.allRecipients)
            }
            assertTrue(received.get(5, TimeUnit.SECONDS).contains("fixture"))
        }
    }

    @Test fun smtpInitializesLibraryLogFiltersWithTlsRequired() = runBlocking {
        ServerSocket(0, 1, loopback).use { listener ->
            listener.soTimeout = 5_000
            val commands = FutureTask {
                listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    val output = socket.getOutputStream().bufferedWriter()
                    fun reply(text: String) { output.write("$text\r\n"); output.flush() }
                    reply("220 fake SMTP")
                    val seen = mutableListOf<String>()
                    while (true) {
                        val command = input.readLine() ?: break
                        seen += command
                        if (command == "QUIT") { reply("221 done"); break }
                        reply("250 local")
                    }
                    seen
                }
            }
            thread(isDaemon = true) { commands.run() }
            val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            val paths = collector.runtime.CollectorPaths(app.cacheDir.resolve("smtp-fixture/out"),
                app.cacheDir.resolve("smtp-fixture/config"))
            val values = mapOf("SMTP_HOST" to loopback.hostAddress, "SMTP_PORT" to listener.localPort.toString(),
                "SMTP_USERNAME" to "sender@example.invalid", "SMTP_PASSWORD" to "fixture",
                "MAIL_TO" to "recipient@example.invalid")
            collector.runtime.CollectorRuntime(paths, { values[it] }, java.time.Clock.systemUTC()).mailTest()
            assertFalse(commands.get(5, TimeUnit.SECONDS).any { it.startsWith("AUTH") || it == "DATA" })
            assertEquals(java.util.logging.Level.OFF, java.util.logging.Logger.getLogger("jakarta.mail").level)
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}

package collector.mail

import collector.config.GitHubConfig
import collector.config.SourceConfig
import collector.config.SourcesFile
import collector.dev.runMain
import collector.dev.runScheduled
import collector.model.Item
import collector.llm.LlmProvider
import collector.llm.LlmRequest
import collector.llm.LlmResult
import collector.llm.ModelInfo
import collector.llm.ProviderId
import collector.port.SecretProvider
import collector.source.SourceAdapter
import kotlinx.coroutines.test.runTest
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.KeyStore
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Properties
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SmtpMailerTest {
    @TempDir lateinit var temporary: File
    private val message = MailMessage("렌더러의 제목", "<h1>안녕하세요</h1>", "안녕하세요")

    @Test fun `SMTP sends UTF-8 alternative bodies and both recipients over STARTTLS`() = deliver()

    @Test fun `accepted SMTP message succeeds even when QUIT times out`() = deliver(closeFails = true)

    @Test fun `disconnect after SMTP body is unknown and next schedule makes no SMTP call`() =
        scheduledSmtpFailure(rejectAuth = false)

    @Test fun `SMTP authentication rejection is failed and next schedule retries`() =
        scheduledSmtpFailure(rejectAuth = true)

    @Test fun `MAIL FROM rejection remains definite when RSET times out`() {
        val (serverTls, clientTls) = tlsContexts()
        val previous = SSLContext.getDefault()
        SSLContext.setDefault(clientTls)
        try {
            loopback().use { server ->
                val received = background {
                    server.accept().use { socket ->
                        smtpConversation(socket, serverTls, server.localPort) { smtp ->
                            smtp.reply("235 Authentication successful")
                            smtp.expect("MAIL FROM:")
                            smtp.reply("550 Sender rejected")
                            smtp.expect("RSET")
                            if (smtp.line() == "QUIT") smtp.reply("221 Bye")
                        }
                    }
                }
                val error = assertFailsWith<MailSendException> { mailer(server.localPort, 1_000).send(message) }
                received.get(10, TimeUnit.SECONDS)
                assertFalse(error.uncertain)
            }
        } finally {
            SSLContext.setDefault(previous)
        }
    }

    @Test fun `first recipient rejection and second acceptance fail without sending DATA`() {
        val (serverTls, clientTls) = tlsContexts()
        val previous = SSLContext.getDefault()
        SSLContext.setDefault(clientTls)
        try {
            loopback().use { server ->
                val received = background {
                    server.accept().use { socket ->
                        smtpConversation(socket, serverTls, server.localPort) { smtp ->
                            smtp.reply("235 Authentication successful")
                            smtp.expect("MAIL FROM:")
                            smtp.reply("250 OK")
                            repeat(2) { index ->
                                smtp.expect("RCPT TO:")
                                smtp.reply(if (index == 0) "550 Recipient rejected" else "250 OK")
                            }
                            smtp.expect("RSET")
                            smtp.reply("250 OK")
                            smtp.expect("QUIT")
                            smtp.reply("221 Bye")
                        }
                    }
                }
                val error = assertFailsWith<MailSendException> { mailer(server.localPort, 3_000).send(message) }
                received.get(10, TimeUnit.SECONDS)
                assertFalse(error.uncertain)
            }
        } finally {
            SSLContext.setDefault(previous)
        }
    }

    private fun scheduledSmtpFailure(rejectAuth: Boolean) = runTest {
        val (serverTls, clientTls) = tlsContexts()
        val previous = SSLContext.getDefault()
        SSLContext.setDefault(clientTls)
        try {
            val out = temporary.resolve("out").apply { mkdirs() }
            val key = "2026-11-20"
            out.resolve("$key.txt").writeText("Subject\nBody")
            out.resolve("$key.html").writeText("<p>Body</p>")
            val clock = Clock.fixed(Instant.parse("${key}T12:00:00Z"), ZoneId.of("Asia/Seoul"))
            val secrets = SecretProvider { null }
            loopback().use { server ->
                val received = background {
                    server.accept().use { socket ->
                        smtpConversation(socket, serverTls, server.localPort) { smtp ->
                            if (rejectAuth) {
                                smtp.reply("535 Authentication rejected")
                                assertFalse(smtp.line().orEmpty().startsWith("MAIL FROM"))
                            } else {
                                smtp.reply("235 Authentication successful")
                                assertTrue(receiveBody(smtp).contains("Body"))
                            }
                        }
                    }
                }
                assertEquals(0, runScheduled(out, secrets, clock, daily = { _, _ -> true },
                    mailer = mailer(server.localPort, 3_000)))
                received.get(10, TimeUnit.SECONDS)
            }
            val journal = out.resolve("mail/sent.json")
            assertTrue(journal.readText().contains(if (rejectAuth) "\"failed\"" else "\"unknown\""))
            if (!rejectAuth) {
                assertTrue(out.resolve("logs/$key.log").readText()
                    .contains("발송 여부 확인 필요 — 받은편지함 확인 후 필요하면 --resend"))
            }
            var calls = 0
            val next = Clock.offset(clock, java.time.Duration.ofDays(1))
            assertEquals(0, runScheduled(out, secrets, next, daily = { _, _ -> false }, trend = {},
                mailer = Mailer { calls++ }))
            assertEquals(if (rejectAuth) 1 else 0, calls)
        } finally {
            SSLContext.setDefault(previous)
        }
    }

    @Test fun `scheduled entry point uses a fake provider and local SMTP`() = runTest {
        val out = temporary.resolve("out")
        val requests = mutableListOf<String>()
        val adapter = object : SourceAdapter {
            override val id = "github"
            override suspend fun fetch(config: SourceConfig, now: Instant) =
                listOf(Item(id, "1", "Local AI tool", "https://example.test/tool", 250, publishedAt = now))
        }
        val provider = object : LlmProvider {
            override val id = ProviderId.ANTHROPIC
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun complete(req: LlmRequest): LlmResult {
                requests += req.schemaName
                val json = """{"items":[{"itemId":"github:1","profile":"none","score":null,"reason":"unrelated","summary":"","tags":[],"topicKey":"","topicLabel":""}]}"""
                return LlmResult(json, id, req.model, 100, 10, 0, 0.0001)
            }
        }
        val clock = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneId.systemDefault())
        val key = java.time.LocalDate.now(clock).toString()
        deliver { smtp ->
            runMain(arrayOf("--scheduled"), out,
                SourcesFile(listOf(GitHubConfig(profile = listOf("tech-signal")))) to listOf(adapter),
                scheduledClock = clock, scheduledMailer = { smtp }) { listOf(provider) }
            MailMessage(out.resolve("$key.txt").readLines().first(),
                out.resolve("$key.html").readText(), out.resolve("$key.txt").readText())
        }
        assertTrue(requests.contains("classify"))
        val summary = out.resolve("logs/$key.log").readLines().single { it.contains("일일 요약") }
        assertTrue(summary.contains("카드 0개") && summary.contains("추정 USD 0.000100") && summary.contains("실패 출처 0개"))
    }

    private fun deliver(closeFails: Boolean = false) = kotlinx.coroutines.runBlocking {
        deliver(closeFails) { smtp -> smtp.send(message); message }
        Unit
    }

    private suspend fun deliver(
        closeFails: Boolean = false,
        acceptedReply: String = "250 OK",
        send: suspend (SmtpMailer) -> MailMessage
    ): MimeMessage {
        val (serverTls, clientTls) = tlsContexts()
        val previous = SSLContext.getDefault()
        SSLContext.setDefault(clientTls)
        try {
            loopback().use { server ->
                val received = background {
                    server.accept().use { socket ->
                        smtpConversation(socket, serverTls, server.localPort) { smtp ->
                            smtp.reply("235 Authentication successful")
                            val raw = receiveBody(smtp)
                            smtp.reply(acceptedReply)
                            smtp.expect("QUIT")
                            if (closeFails) assertNull(smtp.line()) else smtp.reply("221 Bye")
                            raw
                        }
                    }
                }
                val expected = send(mailer(server.localPort, if (closeFails) 500 else 30_000))
                val mime = MimeMessage(Session.getInstance(Properties()),
                    received.get(10, TimeUnit.SECONDS).byteInputStream(Charsets.US_ASCII))
                assertEquals(expected.subject, mime.subject)
                assertEquals(listOf("first@example.invalid", "second@example.invalid"), mime.allRecipients.map { it.toString() })
                assertTrue(mime.isMimeType("multipart/alternative"))
                val parts = mime.content as MimeMultipart
                assertEquals(2, parts.count)
                assertTrue(parts.getBodyPart(0).isMimeType("text/plain"))
                assertTrue(parts.getBodyPart(1).isMimeType("text/html"))
                assertEquals(expected.text.trimEnd(), parts.getBodyPart(0).content.toString().replace("\r\n", "\n").trimEnd())
                assertEquals(expected.html.trimEnd(), parts.getBodyPart(1).content.toString().replace("\r\n", "\n").trimEnd())
                return mime
            }
        } finally {
            SSLContext.setDefault(previous)
        }
    }

    private fun <T> smtpConversation(socket: Socket, serverTls: SSLContext, port: Int, run: (Conversation) -> T): T {
        val plain = Conversation(socket)
        plain.reply("220 localhost")
        plain.expect("EHLO ")
        plain.reply("250-localhost", "250 STARTTLS")
        plain.expect("STARTTLS")
        plain.reply("220 Ready")
        val tls = serverTls.socketFactory.createSocket(socket, "localhost", port, true) as SSLSocket
        return tls.use {
            it.useClientMode = false
            val smtp = Conversation(it)
            smtp.expect("EHLO ")
            smtp.reply("250-localhost", "250 AUTH PLAIN")
            smtp.expect("AUTH PLAIN ")
            run(smtp)
        }
    }

    private fun receiveBody(smtp: Conversation): String {
        smtp.expect("MAIL FROM:<sender@example.invalid>")
        smtp.reply("250 OK")
        for (recipient in listOf("first", "second")) {
            smtp.expect("RCPT TO:<$recipient@example.invalid>")
            smtp.reply("250 OK")
        }
        smtp.expect("DATA")
        smtp.reply("354 Continue")
        return generateSequence { checkNotNull(smtp.line()) }.takeWhile { it != "." }.joinToString("\r\n")
    }

    @Test fun `SMTP refuses plaintext and discards potentially sensitive server responses`() {
        loopback().use { server ->
            val nextLine = background {
                server.accept().use { socket ->
                    val smtp = Conversation(socket)
                    smtp.reply("220 localhost")
                    smtp.expect("EHLO ")
                    smtp.reply("250 other@example.invalid fake-secret")
                    smtp.line()
                }
            }
            val error = assertFailsWith<MailSendException> { mailer(server.localPort).send(message) }
            assertEquals("SMTP 발송 실패", error.message)
            assertNull(error.cause)
            assertFalse(nextLine.get(10, TimeUnit.SECONDS).orEmpty().startsWith("AUTH"))
        }
    }

    @Test fun `SMTP rejects an untrusted certificate`() = rejectTls(trusted = false)

    @Test fun `SMTP rejects a trusted certificate with a mismatched hostname`() = rejectTls(trusted = true)

    private fun rejectTls(trusted: Boolean) {
        val (serverTls, clientTls) = tlsContexts(if (trusted) "dns:localhost" else "ip:127.0.0.1")
        val previous = SSLContext.getDefault()
        SSLContext.setDefault(if (trusted) clientTls else SSLContext.getInstance("TLS").apply { init(null, null, null) })
        try {
            loopback().use { server ->
                val command = background {
                    server.accept().use { socket ->
                        val plain = Conversation(socket)
                        plain.reply("220 localhost")
                        plain.expect("EHLO ")
                        plain.reply("250-localhost", "250 STARTTLS")
                        plain.expect("STARTTLS")
                        plain.reply("220 Ready")
                        val tls = serverTls.socketFactory.createSocket(socket, "localhost", server.localPort, true) as SSLSocket
                        tls.use {
                            it.useClientMode = false
                            try {
                                Conversation(it).line()
                            } catch (_: javax.net.ssl.SSLException) {
                                null
                            } catch (_: SocketException) {
                                null
                            }
                        }
                    }
                }
                assertFailsWith<MailSendException> { mailer(server.localPort, 3_000).send(message) }
                assertNull(command.get(10, TimeUnit.SECONDS))
            }
        } finally {
            SSLContext.setDefault(previous)
        }
    }

    @Test fun `SMTP greeting read timeout closes connection`() {
        loopback().use { server ->
            val command = background { server.accept().use { Conversation(it).line() } }
            assertFailsWith<MailSendException> { mailer(server.localPort, 250).send(message) }
            assertNull(command.get(5, TimeUnit.SECONDS))
        }
    }

    @Test @Order(1)
    fun `first SMTP send keeps JUL configuration and silences Angus`() = kotlinx.coroutines.runBlocking {
        val manager = LogManager.getLogManager()
        val smtpLogger = "org.eclipse.angus.mail.smtp"
        val reply = "250 Synthetic mail server response"
        val captured = ByteArrayOutputStream()
        val stdout = System.out
        val stderr = System.err
        val config = """
            handlers=java.util.logging.ConsoleHandler
            java.util.logging.ConsoleHandler.level=FINEST
            java.util.logging.FileHandler.level=FINEST
            java.util.logging.FileHandler.pattern=${temporary.resolve("smtp-%u.log").invariantSeparatorsPath}
            $smtpLogger.level=FINEST
            $smtpLogger.handlers=java.util.logging.FileHandler
            $smtpLogger.protocol.level=FINEST
            $smtpLogger.protocol.handlers=java.util.logging.FileHandler
        """.trimIndent()
        PrintStream(captured, true, Charsets.UTF_8).use { output ->
            System.setOut(output)
            System.setErr(output)
            try {
                manager.readConfiguration(config.byteInputStream())
                val rootHandlers = manager.getLogger("").handlers.toList()
                deliver(acceptedReply = reply) { smtp -> smtp.send(message); message }
                val logs = captured.toString(Charsets.UTF_8) +
                    temporary.listFiles().orEmpty().filter { it.extension == "log" }.joinToString { it.readText() }
                assertFalse(logs.contains("first@example.invalid"))
                assertFalse(logs.contains("second@example.invalid"))
                assertFalse(logs.contains(reply))
                assertEquals("java.util.logging.FileHandler", manager.getProperty("$smtpLogger.handlers"))
                assertEquals(rootHandlers, manager.getLogger("").handlers.toList())
            } finally {
                System.setOut(stdout)
                System.setErr(stderr)
                manager.readConfiguration()
            }
        }
    }

    @Test fun `JUL FINE handlers receive no SMTP address or raw reply`() {
        val records = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records += record.message }
            override fun flush() = Unit
            override fun close() = Unit
        }.apply { level = Level.ALL }
        val loggers = listOf("jakarta.mail", "org.eclipse.angus.mail.smtp", "org.eclipse.angus.mail.smtp.protocol")
            .map(Logger::getLogger)
        val previous = loggers.map { Triple(it.level, it.useParentHandlers, it.filter) }
        loggers.forEach { it.level = Level.FINEST; it.addHandler(handler) }
        try {
            `SMTP refuses plaintext and discards potentially sensitive server responses`()
            assertTrue(loggers.all { it.level == Level.OFF && !it.useParentHandlers })
            assertTrue(records.isEmpty())
        } finally {
            loggers.zip(previous).forEach { (logger, state) ->
                logger.removeHandler(handler)
                logger.level = state.first
                logger.useParentHandlers = state.second
                logger.filter = state.third
            }
        }
    }

    private fun mailer(port: Int, readTimeoutMillis: Int = 30_000): SmtpMailer {
        val settings = mapOf(
            "SMTP_HOST" to "127.0.0.1", "SMTP_PORT" to port.toString(),
            "SMTP_USERNAME" to "sender@example.invalid", "SMTP_PASSWORD" to "fake-secret",
            "MAIL_TO" to "first@example.invalid, second@example.invalid"
        )
        return checkNotNull(SmtpMailer.from(SecretProvider(settings::get), readTimeoutMillis))
    }

    private fun loopback() = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }

    private fun <T> background(block: () -> T): FutureTask<T> = FutureTask(block).also {
        Thread(it, "local-smtp-test").apply { isDaemon = true }.start()
    }

    private class Conversation(socket: Socket) {
        init { socket.soTimeout = 10_000 }
        private val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        private val output = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
        fun line(): String? = input.readLine()
        fun expect(prefix: String) { assertTrue(line().orEmpty().startsWith(prefix), "Expected SMTP $prefix") }
        fun reply(vararg lines: String) {
            lines.forEach { output.write("$it\r\n") }
            output.flush()
        }
    }

    private fun tlsContexts(san: String = "ip:127.0.0.1"): Pair<SSLContext, SSLContext> {
        val store = temporary.resolve("smtp.p12")
        val password = "test-only".toCharArray()
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(keytool, "-genkeypair", "-alias", "localhost", "-keyalg", "RSA",
            "-dname", "CN=localhost", "-ext", "SAN=$san", "-validity", "2",
            "-storetype", "PKCS12", "-keystore", store.path, "-storepass", String(password))
            .redirectErrorStream(true).start()
        assertTrue(process.waitFor(15, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
        val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, password) } }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        return SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) } to
            SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
    }
}

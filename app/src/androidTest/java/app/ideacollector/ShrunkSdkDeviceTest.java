package app.ideacollector;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class ShrunkSdkDeviceTest {
    private final Context context;

    public ShrunkSdkDeviceTest() {
        this(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    ShrunkSdkDeviceTest(Context context) {
        this.context = context;
    }

    @Test public void anthropicUsesShrunkSdk() throws Exception {
        checkHttp(true, "/v1/messages",
            "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"ok\\\":true}\"}],\"stop_reason\":\"end_turn\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":4}}");
    }

    @Test public void openAiUsesShrunkSdk() throws Exception {
        checkHttp(false, "/v1/chat/completions",
            "{\"id\":\"chat_1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-5.4-mini\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"ok\\\":true}\"}}],"
            + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":5,\"total_tokens\":105}}");
    }

    @Test public void smtpInitializesShrunkTransportWithRequiredTls() throws Exception {
        try (ServerSocket listener = listener()) {
            FutureTask<Boolean> task = new FutureTask<>(() -> {
                try (Socket socket = listener.accept()) {
                    BufferedReader input = reader(socket);
                    reply(socket, "220 fixture\r\n");
                    String greeting = input.readLine();
                    if (greeting == null || !greeting.startsWith("EHLO ")) return false;
                    reply(socket, "250 fixture\r\n");
                    String next = input.readLine();
                    if ("QUIT".equals(next)) reply(socket, "221 done\r\n");
                    return next == null || "QUIT".equals(next);
                }
            });
            start(task);
            ShrunkSdkFixture.initializeSmtp(context, listener.getLocalPort());
            assertTrue(task.get(10, TimeUnit.SECONDS));
            assertEquals(Level.OFF, Logger.getLogger("jakarta.mail").getLevel());
        }
    }

    private void checkHttp(boolean anthropic, String path, String json) throws Exception {
        try (ServerSocket listener = listener()) {
            FutureTask<String> task = new FutureTask<>(() -> {
                try (Socket socket = listener.accept()) {
                    BufferedReader input = reader(socket);
                    String request = input.readLine();
                    int length = 0;
                    for (String line; (line = input.readLine()) != null && !line.isEmpty();) {
                        if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                            length = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    for (int i = 0; i < length && input.read() != -1; i++) {}
                    reply(socket, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n"
                        + "Content-Length: " + json.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + json);
                    return request;
                }
            });
            start(task);
            assertEquals("{\"ok\":true}", ShrunkSdkFixture.complete("http://127.0.0.1:" + listener.getLocalPort(), anthropic));
            assertEquals("POST " + path + " HTTP/1.1", task.get(10, TimeUnit.SECONDS));
        }
    }

    private static ServerSocket listener() throws Exception {
        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        listener.setSoTimeout(10_000);
        return listener;
    }

    private static BufferedReader reader(Socket socket) throws Exception {
        socket.setSoTimeout(10_000);
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static void reply(Socket socket, String text) throws Exception {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static void start(FutureTask<?> task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }
}

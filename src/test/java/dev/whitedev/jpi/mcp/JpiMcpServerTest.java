package dev.whitedev.jpi.mcp;

import java.util.Collections;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class JpiMcpServerTest {
    @TempDir Path directory;

    @Test void discoversOnlyAnalysisToolsAndHandlesMissingSessionsWithoutLosingTransport() throws Exception {
        try (Client client = new Client(directory)) {
            client.initialize();
            JsonObject tools = client.request(2, "tools/list", Map.of()).getAsJsonObject("result");
            assertEquals(14, tools.getAsJsonArray("tools").size());
            for (var tool : tools.getAsJsonArray("tools")) {
                var definition = tool.getAsJsonObject();
                assertTrue(definition.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
                assertFalse(definition.get("name").getAsString().matches(".*(patch|execute|attach|breakpoint|memory|bind).*"));
            }
            JsonObject resource = client.request(3, "resources/read", Map.of("uri", "jpi://session"));
            String text = resource.getAsJsonObject("result").getAsJsonArray("contents").get(0).getAsJsonObject().get("text").getAsString();
            assertFalse(McpJson.GSON.fromJson(text, JsonObject.class).get("connected").getAsBoolean());
            var missing = client.call(4, "jpi_search_classes", Map.of("query", "java"));
            assertEquals("NO_ACTIVE_SESSION", missing.getAsJsonObject("structuredContent").get("code").getAsString());
            assertTrue(missing.get("isError").getAsBoolean());
            var unknown = client.call(5, "jpi_execute", Map.of("source", "super-secret-payload"));
            assertEquals("INVALID_ARGUMENT", unknown.getAsJsonObject("structuredContent").get("code").getAsString());
            var session = client.call(6, "jpi_get_session", Map.of());
            assertFalse(session.getAsJsonObject("structuredContent").get("connected").getAsBoolean());
            client.write("{broken\n");
            assertEquals(-32700, client.next().getAsJsonObject("error").get("code").getAsInt());
            client.write("[]\n");
            assertEquals(-32600, client.next().getAsJsonObject("error").get("code").getAsInt());
            client.write("{\"jsonrpc\":\"1.0\",\"id\":9,\"method\":\"ping\"}\n");
            assertEquals(-32600, client.next().getAsJsonObject("error").get("code").getAsInt());
            assertTrue(client.request(7, "ping", Map.of()).has("result"));
            assertEquals(4, client.audit.recent().size());
            assertTrue(Files.readString(client.audit.path()).contains("test-client"));
            assertFalse(Files.readString(client.audit.path()).contains("super-secret-payload"));
        }
    }

    @Test void rejectsInvalidInitializationAndSupportsVersionNegotiation() throws Exception {
        try (Client client = new Client(directory)) {
            assertEquals(-32600, client.request(1, "tools/list", Map.of()).getAsJsonObject("error").get("code").getAsInt());
            var init = client.request(2, "initialize", Map.of("protocolVersion", "unknown", "clientInfo", Map.of("name", "test-client", "version", "1"), "capabilities", Map.of()));
            assertEquals(JpiMcpServer.VERSION, init.getAsJsonObject("result").get("protocolVersion").getAsString());
            client.write(McpJson.GSON.toJson(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")) + "\n");
            assertTrue(client.request(3, "tools/list", Map.of()).has("result"));
            var result = client.call(4, "jpi_get_session", Map.of("unexpected", true));
            assertEquals("INVALID_ARGUMENT", result.getAsJsonObject("structuredContent").get("code").getAsString());
        }
    }

    @Test void limitsUnicodeOutputAndTruncatesOversizedCollections() {
        assertEquals("😀", McpJson.bounded("😀test", 4));
        assertEquals("", McpJson.bounded("😀test", 3));
        String value = "x".repeat(3000);
        Map<String, Object> result = McpJson.fit(Map.of("matches", Collections.nCopies(2000, Map.of("value", value)), "truncated", false));
        assertEquals(Boolean.TRUE, result.get("truncated"));
        assertTrue(((List<?>) result.get("matches")).size() < 2000);
        McpJson.checkSize(result);
    }

    private static final class Client implements AutoCloseable {
        final PipedInputStream input = new PipedInputStream(1024 * 1024);
        final PipedOutputStream requests = new PipedOutputStream(input);
        final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>();
        final McpAuditLog audit;
        final JpiMcpServer server;
        final Thread thread;

        Client(Path directory) throws Exception {
            audit = new McpAuditLog(directory);
            OutputStream output = new OutputStream() {
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                @Override public synchronized void write(int value) {
                    if (value == '\n') { responses.add(buffer.toString(StandardCharsets.UTF_8)); buffer.reset(); }
                    else buffer.write(value);
                }
            };
            server = new JpiMcpServer(input, output, new McpSessionState(), audit);
            thread = Thread.ofVirtual().start(server::run);
        }
        void initialize() throws Exception {
            assertTrue(request(1, "initialize", Map.of("protocolVersion", JpiMcpServer.VERSION,
                    "clientInfo", Map.of("name", "test-client", "version", "1"), "capabilities", Map.of())).has("result"));
            write(McpJson.GSON.toJson(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")) + "\n");
        }
        JsonObject call(int id, String tool, Map<String, Object> arguments) throws Exception {
            return request(id, "tools/call", Map.of("name", tool, "arguments", arguments)).getAsJsonObject("result");
        }
        JsonObject request(int id, String method, Map<String, Object> params) throws Exception {
            write(McpJson.GSON.toJson(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params)) + "\n");
            return next();
        }
        void write(String value) throws Exception { requests.write(value.getBytes(StandardCharsets.UTF_8)); requests.flush(); }
        JsonObject next() throws Exception {
            String value = responses.poll(5, TimeUnit.SECONDS);
            assertNotNull(value, "MCP response timed out");
            return McpJson.GSON.fromJson(value, JsonObject.class);
        }
        @Override public void close() throws Exception {
            requests.close();
            thread.join(2000);
            server.close();
            assertFalse(thread.isAlive());
        }
    }
}

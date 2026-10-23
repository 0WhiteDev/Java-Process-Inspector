package dev.whitedev.jpi.attach;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import dev.whitedev.jpi.mcp.JpiMcpFacade;
import dev.whitedev.jpi.mcp.McpErrorCode;
import dev.whitedev.jpi.mcp.McpException;
import dev.whitedev.jpi.mcp.McpJson;
import dev.whitedev.jpi.mcp.McpSessionState;
import dev.whitedev.jpi.mcp.McpAuditLog;
import dev.whitedev.jpi.mcp.JpiMcpServer;
import dev.whitedev.jpi.mcp.tools.McpTools;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.protocol.WireProtocol;
import org.junit.jupiter.api.Test;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class McpSessionIsolationTest {
    @TempDir Path directory;

    @Test void clientCancellationReturnsAnErrorAndLeavesTheTargetSessionUsable() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(operation -> {
            if (operation == Operation.PING) return "pong";
            assertEquals(Operation.SESSION_INFO, operation);
            requested.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "runtime=Instrumentation\ncommunication=java-agent\n";
        })) {
            McpSessionState state = new McpSessionState();
            state.setSession(fixture.session);
            var incoming = new PipedInputStream();
            var client = new PipedOutputStream(incoming);
            var replies = new LinkedBlockingQueue<JsonObject>();
            var output = new OutputStream() {
                final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                @Override public synchronized void write(int value) {
                    if (value == '\n') {
                        replies.add(McpJson.GSON.fromJson(bytes.toString(StandardCharsets.UTF_8), JsonObject.class));
                        bytes.reset();
                    } else bytes.write(value);
                }
            };
            JpiMcpServer server = new JpiMcpServer(incoming, output, state, new McpAuditLog(directory));
            Thread transport = Thread.ofVirtual().start(server::run);
            try {
                send(client, Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params",
                        Map.of("protocolVersion", "2025-11-25", "clientInfo", Map.of("name", "cancel-test", "version", "1"))));
                assertNotNull(replies.poll(3, TimeUnit.SECONDS));
                send(client, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
                send(client, Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call", "params", Map.of("name", "jpi_get_session")));
                assertTrue(requested.await(3, TimeUnit.SECONDS));
                send(client, Map.of("jsonrpc", "2.0", "method", "notifications/cancelled", "params", Map.of("requestId", 2)));
                JsonObject cancelled = replies.poll(3, TimeUnit.SECONDS);
                assertNotNull(cancelled);
                assertEquals("OPERATION_TIMEOUT", cancelled.getAsJsonObject("result").getAsJsonObject("structuredContent").get("code").getAsString());
                release.countDown();
                assertEquals("pong", fixture.session.requestText(Operation.PING, ""));
                send(client, Map.of("jsonrpc", "2.0", "id", 3, "method", "ping"));
                assertNotNull(replies.poll(3, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                client.close();
                transport.join(2000);
                server.close();
            }
        }
    }

    private static void send(OutputStream output, Map<String, Object> message) throws Exception {
        output.write((McpJson.GSON.toJson(message) + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    @Test void discardsResultsWhenTheSessionChangesDuringARead() throws Exception {
        McpSessionState state = new McpSessionState();
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Fixture fixture = new Fixture(operation -> {
            assertEquals(Operation.SESSION_INFO, operation);
            requested.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            return "communication=java-agent\nruntime=Instrumentation\n";
        })) {
            state.setSession(fixture.session);
            var result = CompletableFuture.supplyAsync(() -> {
                try { return new JpiMcpFacade(state).session(); }
                catch (Exception error) { throw new CompletionException(error); }
            });
            assertTrue(requested.await(3, TimeUnit.SECONDS));
            state.setSession(null);
            release.countDown();
            var failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertInstanceOf(McpException.class, failure.getCause());
            assertEquals(McpErrorCode.SESSION_CHANGED, ((McpException) failure.getCause()).code());
        }
    }

    @Test void pinsQueuedCallsAndValidatesLimitsBeforeReadingTheTarget() throws Exception {
        McpSessionState state = new McpSessionState();
        try (Fixture fixture = new Fixture(operation -> { fail("Invalid calls must not query the target"); return ""; })) {
            state.setSession(fixture.session);
            var pinned = state.snapshot();
            McpTools tools = new McpTools(new JpiMcpFacade(state));
            for (Object invalid : List.of(0, 501, 1.5, "50")) {
                var error = assertThrows(McpException.class, () -> tools.call("jpi_search_classes", McpJson.object(Map.of("query", "java", "limit", invalid))));
                assertEquals(McpErrorCode.INVALID_ARGUMENT, error.code());
            }
            for (String invalid : List.of("(I)Vjunk", "(V)V", "([V)V", "invalid")) {
                var error = assertThrows(McpException.class, () -> tools.call("jpi_get_method", McpJson.object(
                        Map.of("classId", "c:1", "methodName", "test", "descriptor", invalid))));
                assertEquals(McpErrorCode.INVALID_ARGUMENT, error.code());
            }
            state.setSession(fixture.session);
            var error = assertThrows(McpException.class, () -> tools.call("jpi_get_session", new JsonObject(), pinned));
            assertEquals(McpErrorCode.SESSION_CHANGED, error.code());
        }
    }

    @Test void preservesSeparateDefinitionsWithTheSameBinaryName() throws Exception {
        try (Fixture fixture = new Fixture(operation -> switch (operation) {
            case TARGET_FINGERPRINT -> "H\t1\t1\t\tMjE=\t2\n";
            case CLASSES -> "c:1\tdemo.Auth\tdemo.Loader@1\t<unnamed>\ttrue\ttrue\tclass\t20\t1\n"
                    + "c:2\tdemo.Auth\tdemo.Loader@2\t<unnamed>\ttrue\ttrue\tclass\t20\t1\n";
            case CLASSLOADER_SNAPSHOT -> "S\t1\nC\tYzox\tZGVtby5BdXRo\tbDox\t\tdefinition\n"
                    + "C\tYzoy\tZGVtby5BdXRo\tbDoy\t\tdefinition\n";
            default -> throw new AssertionError("Unexpected operation " + operation);
        })) {
            McpSessionState state = new McpSessionState();
            state.setSession(fixture.session);
            McpTools tools = new McpTools(new JpiMcpFacade(state));
            var result = tools.call("jpi_search_classes", McpJson.object(Map.of("query", "Auth")));
            List<?> matches = (List<?>) result.get("matches");
            assertEquals(2, matches.size());
            assertNotEquals(((Map<?, ?>) matches.get(0)).get("classId"), ((Map<?, ?>) matches.get(1)).get("classId"));
            assertNotEquals(((Map<?, ?>) matches.get(0)).get("loaderId"), ((Map<?, ?>) matches.get(1)).get("loaderId"));
            assertEquals(Boolean.TRUE, tools.call("jpi_search_classes", McpJson.object(Map.of("query", "Auth", "limit", 1))).get("truncated"));
        }
    }

    @Test void investigationReadsHistoryInsteadOfDrainingLiveTracerEvents() throws Exception {
        var seen = Collections.synchronizedList(new ArrayList<Operation>());
        try (Fixture fixture = new Fixture(operation -> {
            seen.add(operation);
            return switch (operation) {
                case TARGET_FINGERPRINT -> "H\t1\t1\t\tMjE=\t0\n";
                case CONSTANT_SEARCH, XREF_SEARCH, TRACE_HISTORY -> "";
                default -> throw new AssertionError("Unexpected operation " + operation);
            };
        })) {
            McpSessionState state = new McpSessionState();
            state.setSession(fixture.session);
            new McpTools(new JpiMcpFacade(state)).call("jpi_investigate", McpJson.object(Map.of("query", "auth")));
            assertTrue(seen.contains(Operation.TRACE_HISTORY));
            assertFalse(seen.contains(Operation.TRACE_EVENTS));
        }
    }

    @FunctionalInterface private interface Answer { String respond(Operation operation) throws Exception; }
    private static final class Fixture implements AutoCloseable {
        final ServerSocket listener;
        final Socket peer;
        final InspectorSession session;
        final Thread server;
        final CompletableFuture<Void> finished = new CompletableFuture<>();

        Fixture(Answer answer) throws Exception {
            listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            peer = listener.accept();
            session = new InspectorSession(new JvmDescriptor("123", "fixture"), socket);
            server = Thread.ofVirtual().start(() -> {
                try {
                    DataInputStream input = new DataInputStream(peer.getInputStream());
                    DataOutputStream output = new DataOutputStream(peer.getOutputStream());
                    while (true) {
                        var request = WireProtocol.readRequest(input);
                        if (request.operation() == Operation.DISCONNECT) break;
                        WireProtocol.writeResponse(output, true, WireProtocol.utf8(answer.respond(request.operation())));
                    }
                    finished.complete(null);
                } catch (Throwable error) { finished.completeExceptionally(error); }
            });
        }
        @Override public void close() throws Exception {
            session.close();
            finished.get(3, TimeUnit.SECONDS);
            peer.close();
            listener.close();
            server.join(1000);
        }
    }
}

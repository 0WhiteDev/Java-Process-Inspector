package dev.whitedev.jpi.integration;

import com.google.gson.JsonObject;
import dev.whitedev.jpi.attach.AttachService;
import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.attach.JvmDescriptor;
import dev.whitedev.jpi.mcp.JpiMcpFacade;
import dev.whitedev.jpi.mcp.McpJson;
import dev.whitedev.jpi.mcp.McpSessionState;
import dev.whitedev.jpi.mcp.tools.McpTools;
import dev.whitedev.jpi.protocol.Operation;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class McpIntegrationIT {
    @Test void packagedStdioServerKeepsStdoutCleanAndExitsOnClientDisconnect() throws Exception {
        String javaExe = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Path home = Files.createTempDirectory("jpi-mcp-home-");
        Process server = new ProcessBuilder(javaExe, "-Djava.awt.headless=true", "-Duser.home=" + home,
                "-jar", System.getProperty("jpi.agent.jar", "target/jpi.jar"), "--mcp").start();
        try {
            var input = server.inputReader(StandardCharsets.UTF_8);
            var output = server.outputWriter(StandardCharsets.UTF_8);
            output.write(McpJson.GSON.toJson(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                    "params", Map.of("protocolVersion", "2025-11-25", "clientInfo", Map.of("name", "integration-test", "version", "1"), "capabilities", Map.of()))) + "\n");
            output.flush();
            assertTrue(McpJson.GSON.fromJson(input.readLine(), JsonObject.class).has("result"));
            output.write(McpJson.GSON.toJson(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")) + "\n");
            output.write(McpJson.GSON.toJson(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list")) + "\n");
            output.flush();
            var listed = McpJson.GSON.fromJson(input.readLine(), JsonObject.class);
            assertEquals(14, listed.getAsJsonObject("result").getAsJsonArray("tools").size());
            output.close();
            assertTrue(server.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, server.exitValue(), new String(server.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            assertNull(input.readLine());
        } finally { server.destroyForcibly(); }
    }

    @Test void analyzesARealAttachedJvmWithEveryMvpTool() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process target = new ProcessBuilder(java, "-cp", System.getProperty("jpi.test.classes", "target/test-classes"), AttachTarget.class.getName())
                .redirectErrorStream(true).start();
        try {
            String pid = target.inputReader().readLine();
            assertEquals(Long.toString(target.pid()), pid);
            try (InspectorSession session = new AttachService(new File(System.getProperty("jpi.agent.jar", "target/jpi.jar")))
                    .attach(new JvmDescriptor(pid, "MCP test fixture"))) {
                McpSessionState state = new McpSessionState();
                state.setSession(session);
                McpTools tools = new McpTools(new JpiMcpFacade(state));
                var info = call(tools, "jpi_get_session", Map.of());
                assertEquals(Boolean.TRUE, info.get("connected"));
                var search = call(tools, "jpi_search_classes", Map.of("query", AttachTarget.class.getName()));
                var matches = (List<?>) search.get("matches");
                assertFalse(matches.isEmpty());
                String id = ((Map<?, ?>) matches.getFirst()).get("classId").toString();
                assertTrue(id.startsWith("c:"));
                var context = (Map<?, ?>) search.get("context");
                assertTrue(context.get("targetFingerprint").toString().startsWith("JPI-"));
                assertEquals(AttachTarget.class.getName(), call(tools, "jpi_get_class", Map.of("classId", id)).get("name"));
                Map<String, Object> method = Map.of("classId", id, "methodName", "branchValue", "descriptor", "(I)I");
                var details = call(tools, "jpi_get_method", method);
                assertTrue(((Number) details.get("bytecodeSize")).intValue() > 0);
                var constants = (List<?>) call(tools, "jpi_search_constants", Map.of("query", "jpi-smoke-target")).get("matches");
                assertFalse(constants.isEmpty());
                assertTrue(constants.stream().map(value -> (Map<?, ?>) value).anyMatch(value -> value.containsKey("methodName")));
                for (String engine : List.of("cfr", "vineflower", "procyon")) {
                    var source = call(tools, "jpi_decompile_class", Map.of("classId", id, "engine", engine));
                    assertTrue(source.get("source").toString().contains("branchValue"), engine);
                }
                var source = call(tools, "jpi_decompile_method", method);
                assertTrue(source.get("source").toString().contains("branchValue"));
                assertFalse(source.get("source").toString().contains("fileRoundTrip"));
                assertFalse(((List<?>) call(tools, "jpi_find_xrefs", Map.of("classId", id, "methodName", "digest", "descriptor", "([B)[B")).get("references")).isEmpty());
                assertFalse(((List<?>) call(tools, "jpi_build_cfg", method).get("blocks")).isEmpty());
                var graph = call(tools, "jpi_build_call_graph", Map.of("classId", id, "methodName", "digest", "descriptor", "([B)[B", "maxDepth", 1, "maxNodes", 10));
                assertFalse(((List<?>) graph.get("nodes")).isEmpty());
                assertFalse(((List<?>) call(tools, "jpi_get_threads", Map.of()).get("threads")).isEmpty());
                assertEquals("idle", call(tools, "jpi_get_exceptions", Map.of()).get("state"));
                assertTrue(call(tools, "jpi_get_target_fingerprint", Map.of()).get("fingerprint").toString().startsWith("JPI-"));
                var investigation = call(tools, "jpi_investigate", Map.of("query", "jpi-smoke-target", "maxResults", 3));
                assertFalse(((List<?>) investigation.get("results")).isEmpty());
                assertEquals("pong", session.requestText(Operation.PING, ""));
                assertTrue(target.isAlive());
            }
        } finally {
            target.destroy();
            if (!target.waitFor(3, TimeUnit.SECONDS)) target.destroyForcibly();
        }
    }

    private Map<String, Object> call(McpTools tools, String name, Map<String, Object> args) throws Exception {
        Map<String, Object> result = tools.call(name, McpJson.object(args));
        McpJson.checkSize(result);
        return result;
    }
}

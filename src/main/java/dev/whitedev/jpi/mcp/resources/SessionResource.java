package dev.whitedev.jpi.mcp.resources;

import com.google.gson.JsonObject;

import dev.whitedev.jpi.mcp.JpiMcpFacade;
import dev.whitedev.jpi.mcp.McpJson;
import dev.whitedev.jpi.mcp.McpSessionState;
import java.util.List;
import java.util.Map;

public final class SessionResource {
    public static final String URI = "jpi://session";
    private final JpiMcpFacade facade;
    public SessionResource(JpiMcpFacade facade) { this.facade = facade; }

    public Map<String, Object> definition() {
        return Map.of("uri", URI, "name", "JPI session", "description", "Current session selected by the user in JPI.", "mimeType", "application/json");
    }

    public Map<String, Object> read() throws Exception {
        return Map.of("contents", List.of(Map.of("uri", URI, "mimeType", "application/json", "text", McpJson.GSON.toJson(facade.session()))));
    }

    public Map<String, Object> read(McpSessionState.Snapshot expected) throws Exception {
        return Map.of("contents", List.of(Map.of("uri", URI, "mimeType", "application/json", "text",
                McpJson.GSON.toJson(facade.execute("jpi_get_session", new JsonObject(), expected)))));
    }
}

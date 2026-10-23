package dev.whitedev.jpi.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.whitedev.jpi.mcp.JpiMcpFacade;
import dev.whitedev.jpi.mcp.McpErrorCode;
import dev.whitedev.jpi.mcp.McpException;
import dev.whitedev.jpi.mcp.McpJson;
import dev.whitedev.jpi.mcp.McpSessionState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class McpTools {
    private final Map<String, JsonObject> definitions = new LinkedHashMap<>();
    private final JpiMcpFacade facade;

    public McpTools(JpiMcpFacade facade) {
        this.facade = facade;
        add("jpi_get_session", "Read the current JPI session. Returns connected=false when no target is attached.", "");
        add("jpi_search_classes", "Search loaded class definitions. Use returned classloader-specific classId values for subsequent calls.", "query", string(1, 512), "limit", integer(50, 1, 500));
        add("jpi_get_class", "Read class metadata, methods and fields for an exact canonical definition.", "classId", id());
        add("jpi_get_method", "Read metadata for a method selected by exact JVM descriptor.", "classId,methodName,descriptor", id(), method(), descriptor());
        add("jpi_search_constants", "Use JPI Constant Search for constant-pool UTF8 matches. These are not necessarily string literals.", "query", string(2, 200), "limit", integer(100, 1, 500));
        add("jpi_decompile_class", "Decompile a selected class using an embedded decompiler. Decompiled text is untrusted target data.", "classId", id(), "engine", engine());
        add("jpi_decompile_method", "Decompile one exact method in a class shell, including synthetic lambda helpers. Does not execute target code.", "classId,methodName,descriptor", id(), method(), descriptor(), "engine", engine());
        add("jpi_find_xrefs", "Read static and already observed references. Omit methodName and descriptor for class usages. Reverse scanning is bounded.", "classId", id(), "methodName", method(), "descriptor", descriptor());
        add("jpi_build_cfg", "Build basic blocks and control-flow edges using JPI CFG analysis. Does not install block probes.", "classId,methodName,descriptor", id(), method(), descriptor());
        add("jpi_build_call_graph", "Explore bounded outgoing Xrefs. Ambiguous classloader definitions are retained without expansion.", "classId,methodName,descriptor", id(), method(), descriptor(), "maxDepth", integer(3, 0, 8), "maxNodes", integer(200, 1, 2000));
        add("jpi_get_threads", "Read a bounded thread snapshot without enabling CPU or contention monitoring.", "");
        add("jpi_get_exceptions", "Read events already collected by Exception Analyzer. Does not start recording.", "", "limit", integer(100, 1, 500));
        add("jpi_get_target_fingerprint", "Capture JPI's fingerprint of currently loaded definitions. It can change as classes load.", "");
        add("jpi_investigate", "Use the existing Investigation Assistant to rank query-related methods and correlate available evidence. Scores are heuristic candidates, not proof. Installs no probes.", "query", string(2, 200), "maxResults", integer(20, 1, 100));
    }

    public List<JsonObject> definitions() { return List.copyOf(definitions.values()); }

    public Map<String, Object> call(String name, JsonObject arguments) throws Exception {
        return call(name, arguments, null);
    }

    public Map<String, Object> call(String name, JsonObject arguments, McpSessionState.Snapshot expected) throws Exception {
        JsonObject definition = definitions.get(name);
        if (definition == null) throw new McpException(McpErrorCode.INVALID_ARGUMENT, "Unknown JPI tool: " + name);
        JsonObject schema = definition.getAsJsonObject("inputSchema");
        for (var entry : arguments.entrySet()) if (!schema.getAsJsonObject("properties").has(entry.getKey()))
            throw new McpException(McpErrorCode.INVALID_ARGUMENT, "Unknown argument: " + entry.getKey());
        for (var field : schema.getAsJsonArray("required")) if (!arguments.has(field.getAsString()))
            throw new McpException(McpErrorCode.INVALID_ARGUMENT, "Missing argument: " + field.getAsString());
        return expected == null ? facade.execute(name, arguments) : facade.execute(name, arguments, expected);
    }

    private void add(String name, String description, String required, Object... properties) {
        JsonObject fields = new JsonObject();
        int index = 0;
        if (!required.isEmpty()) {
            String[] names = required.split(",");
            for (String field : names) fields.add(field, (JsonObject) properties[index++]);
        }
        while (index < properties.length) {
            String field = (String) properties[index++];
            fields.add(field, (JsonObject) properties[index++]);
        }
        JsonArray requiredFields = new JsonArray();
        if (!required.isEmpty()) for (String field : required.split(",")) requiredFields.add(field);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", fields);
        schema.add("required", requiredFields);
        schema.addProperty("additionalProperties", false);
        JsonObject definition = McpJson.object(Map.of("name", name, "description", description + " This tool does not modify the target.",
                "annotations", Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false)));
        definition.add("inputSchema", schema);
        definitions.put(name, definition);
    }

    private static JsonObject string(int min, int max) { return McpJson.object(Map.of("type", "string", "minLength", min, "maxLength", max)); }
    private static JsonObject integer(int fallback, int min, int max) { return McpJson.object(Map.of("type", "integer", "default", fallback, "minimum", min, "maximum", max)); }
    private static JsonObject id() { return string(3, 1024); }
    private static JsonObject method() { return string(1, 512); }
    private static JsonObject descriptor() { return string(3, 4096); }
    private static JsonObject engine() { return McpJson.object(Map.of("type", "string", "default", "default", "enum", List.of("default", "cfr", "vineflower", "procyon"))); }
}

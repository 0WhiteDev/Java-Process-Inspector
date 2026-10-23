package dev.whitedev.jpi.mcp;

import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.Map;
import java.nio.charset.StandardCharsets;

public final class McpJson {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    public static final int MAX_RESULT_BYTES = 4 * 1024 * 1024;
    private McpJson() {}

    public static JsonObject object(Object value) { return GSON.toJsonTree(value).getAsJsonObject(); }

    public static String bounded(String value, int bytes) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= bytes) return value;
        int end = bytes;
        while (end > 0 && (encoded[end] & 0xc0) == 0x80) end--;
        return new String(encoded, 0, end, StandardCharsets.UTF_8);
    }

    public static void checkSize(Object value) {
        if (GSON.toJson(value).getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES)
            throw new McpException(McpErrorCode.RESULT_TOO_LARGE, "The result exceeds 4 MiB. Use a narrower query.");
    }

    public static Map<String, Object> fit(Map<String, Object> value) {
        JsonObject tree = object(value);
        boolean trimmed = false;
        while (GSON.toJson(tree).getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES - 1024) {
            JsonArray array = largestArray(tree, null);
            if (array != null && !array.isEmpty()) {
                int keep = array.size() / 2;
                while (array.size() > keep) array.remove(array.size() - 1);
            } else {
                String largest = null;
                for (var entry : tree.entrySet()) if (entry.getValue().isJsonPrimitive()
                        && entry.getValue().getAsJsonPrimitive().isString()
                        && (largest == null || entry.getValue().getAsString().length() > tree.get(largest).getAsString().length())) largest = entry.getKey();
                if (largest == null || tree.get(largest).getAsString().length() < 1024)
                    throw new McpException(McpErrorCode.RESULT_TOO_LARGE, "The result exceeds 4 MiB. Use a narrower query.");
                String text = tree.get(largest).getAsString();
                tree.addProperty(largest, bounded(text, text.getBytes(StandardCharsets.UTF_8).length / 2));
            }
            trimmed = true;
        }
        if (trimmed) tree.addProperty("truncated", true);
        return GSON.fromJson(tree, new TypeToken<Map<String, Object>>() {}.getType());
    }

    private static JsonArray largestArray(JsonElement element, JsonArray largest) {
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (largest == null || array.size() > largest.size()) largest = array;
            for (JsonElement child : array) largest = largestArray(child, largest);
        } else if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) largest = largestArray(entry.getValue(), largest);
        }
        return largest;
    }
}

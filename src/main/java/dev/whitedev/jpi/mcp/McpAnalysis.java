package dev.whitedev.jpi.mcp;

import java.io.ByteArrayInputStream;

import dev.whitedev.jpi.mcp.dto.ClassSummary;
import dev.whitedev.jpi.mcp.dto.MethodSummary;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpAnalysis {
    private McpAnalysis() {}

    static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    static List<ClassSummary> inventory(String raw) {
        List<ClassSummary> result = new ArrayList<>();
        for (String row : raw.split("\n")) {
            String[] v = row.split("\t", -1);
            if (v.length != 9) continue;
            result.add(new ClassSummary(v[0], v[1], null, v[2], v[3],
                    Boolean.parseBoolean(v[4]), Boolean.parseBoolean(v[5]), v[6]));
        }
        return List.copyOf(result);
    }

    static List<String> modifiers(int access) {
        List<String> result = new ArrayList<>();
        int[] masks = {Opcodes.ACC_PUBLIC, Opcodes.ACC_PROTECTED, Opcodes.ACC_PRIVATE, Opcodes.ACC_STATIC,
                Opcodes.ACC_FINAL, Opcodes.ACC_ABSTRACT, Opcodes.ACC_SYNTHETIC, Opcodes.ACC_NATIVE};
        String[] names = {"public", "protected", "private", "static", "final", "abstract", "synthetic", "native"};
        for (int i = 0; i < masks.length; i++) if ((access & masks[i]) != 0) result.add(names[i]);
        return List.copyOf(result);
    }

    static MethodSummary method(ClassSummary type, MethodNode method) {
        int instructions = 0;
        for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) instructions++;
        return new MethodSummary(methodId(type.classId(), method.name, method.desc), type.classId(), type.name(),
                method.name, method.desc, modifiers(method.access), instructions, method.tryCatchBlocks.size());
    }

    static String methodId(String id, String name, String descriptor) { return "m:" + id + ':' + name + ':' + descriptor; }

    static Map<String, Object> frame(String raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("raw", raw);
        result.put("className", null);
        result.put("methodName", null);
        result.put("fileName", null);
        result.put("line", -1);
        int opening = raw.lastIndexOf('(');
        int separator = opening < 0 ? -1 : raw.lastIndexOf('.', opening);
        if (opening < 0 || separator < 0 || !raw.endsWith(")")) return result;
        String owner = raw.substring(0, separator);
        result.put("className", owner.substring(owner.lastIndexOf('/') + 1));
        result.put("methodName", raw.substring(separator + 1, opening));
        String location = raw.substring(opening + 1, raw.length() - 1);
        result.put("native", location.equals("Native Method"));
        if (!location.equals("Native Method") && !location.equals("Unknown Source")) {
            int colon = location.lastIndexOf(':');
            result.put("fileName", colon < 0 ? location : location.substring(0, colon));
            if (colon >= 0) try { result.put("line", Integer.parseInt(location.substring(colon + 1))); }
            catch (NumberFormatException ignored) {}
        }
        return result;
    }

    static int codeLength(byte[] bytes, String name, String descriptor) throws IOException {
        var input = new DataInputStream(new ByteArrayInputStream(bytes));
        input.skipNBytes(8);
        String[] constants = new String[input.readUnsignedShort()];
        for (int i = 1; i < constants.length; i++) {
            switch (input.readUnsignedByte()) {
                case 1 -> constants[i] = input.readUTF();
                case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                case 5, 6 -> { input.skipNBytes(8); i++; }
                case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
                case 15 -> input.skipNBytes(3);
                default -> throw new IOException("Unsupported class constant");
            }
        }
        input.skipNBytes(6);
        input.skipNBytes(2L * input.readUnsignedShort());
        int fields = input.readUnsignedShort();
        for (int i = 0; i < fields; i++) { input.skipNBytes(6); skipAttributes(input); }
        int methods = input.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            input.readUnsignedShort();
            String method = constants[input.readUnsignedShort()];
            String desc = constants[input.readUnsignedShort()];
            int attributes = input.readUnsignedShort();
            for (int j = 0; j < attributes; j++) {
                String attribute = constants[input.readUnsignedShort()];
                long length = Integer.toUnsignedLong(input.readInt());
                if (method.equals(name) && desc.equals(descriptor) && attribute.equals("Code")) {
                    input.skipNBytes(4);
                    return input.readInt();
                }
                input.skipNBytes(length);
            }
        }
        return 0;
    }

    private static void skipAttributes(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        for (int i = 0; i < count; i++) { input.readUnsignedShort(); input.skipNBytes(Integer.toUnsignedLong(input.readInt())); }
    }

    static List<Map<String, Object>> references(String raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String row : raw.split("\n")) {
            String[] v = row.split("\t", -1);
            if (v.length != 9 || !v[0].equals("R")) continue;
            result.add(new LinkedHashMap<>(Map.of("layer", v[1], "relation", v[2], "count", Long.parseLong(v[3]),
                    "classId", decode(v[4]), "className", decode(v[5]), "methodName", decode(v[6]),
                    "descriptor", decode(v[7]), "detail", decode(v[8]))));
        }
        return result;
    }

    static Map<String, Object> cfg(String raw) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        int complexity = 0;
        for (String row : raw.split("\n")) {
            String[] v = row.split("\t", -1);
            if (v.length == 8 && v[0].equals("G")) complexity = Integer.parseInt(v[6]);
            if (v.length == 10 && v[0].equals("B")) blocks.add(new LinkedHashMap<>(Map.of(
                    "id", v[1], "startInstruction", Integer.parseInt(v[2]), "endInstruction", Integer.parseInt(v[3]),
                    "startLine", Integer.parseInt(v[4]), "endLine", Integer.parseInt(v[5]),
                    "reachable", Boolean.parseBoolean(v[6]), "immediateDominator", v[7],
                    "dominators", decode(v[8]), "instructions", decode(v[9]))));
            if (v.length == 5 && v[0].equals("E")) edges.add(Map.of("from", v[1], "to", v[2], "type", v[3], "label", decode(v[4])));
        }
        if (blocks.isEmpty()) throw new McpException(McpErrorCode.BACKEND_UNAVAILABLE, "No CFG was returned for this method.");
        return new LinkedHashMap<>(Map.of("entryBlock", blocks.getFirst().get("id"), "blocks", blocks,
                "edges", edges, "complexity", complexity, "truncated", false));
    }
}

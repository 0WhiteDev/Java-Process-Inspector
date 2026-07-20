package dev.whitedev.jpi.deobfuscation.io;

import dev.whitedev.jpi.deobfuscation.MappingEntry;
import dev.whitedev.jpi.deobfuscation.MappingKind;

import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MappingFormats {
    private static final Pattern JSON_VALUE = Pattern.compile(
            "\"([A-Za-z]+)\"\\s*:\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|(true|false|-?\\d+))");

    private MappingFormats() {}

    public static void write(Path path, Format format, List<MappingEntry> entries) throws IOException {
        String value;
        if (format == Format.JSON) value = json(entries);
        else if (format == Format.TINY_V2) value = tiny(entries);
        else if (format == Format.TSRG2) value = tsrg(entries);
        else value = proguard(entries);
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }

    public static List<MappingEntry> readJson(Path path) throws IOException {
        String document = Files.readString(path, StandardCharsets.UTF_8);
        List<MappingEntry> entries = new ArrayList<>();
        for (String line : document.split("\\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("{") || !trimmed.contains("\"kind\"")) continue;
            Map<String, String> values = new HashMap<>();
            Matcher matcher = JSON_VALUE.matcher(trimmed);
            while (matcher.find()) values.put(matcher.group(1),
                    matcher.group(2) == null ? matcher.group(3) : unescape(matcher.group(2)));
            try {
                entries.add(new MappingEntry(MappingKind.valueOf(values.get("kind")),
                        value(values, "owner"), value(values, "original"), value(values, "descriptor"),
                        integer(values, "parameterIndex", -1), integer(values, "access", 0),
                        value(values, "mapped"), value(values, "comment"), value(values, "tags"),
                        value(values, "color"), Boolean.parseBoolean(values.getOrDefault("enabled", "true"))));
            } catch (RuntimeException error) {
                throw new IOException("Invalid mapping entry: " + trimmed, error);
            }
        }
        if (entries.isEmpty() && !document.contains("\"format\": \"jpi-deobfuscation-workspace\"")) {
            throw new IOException("The JSON file contains no JPI mapping entries");
        }
        return entries;
    }

    private static String json(List<MappingEntry> entries) {
        StringBuilder output = new StringBuilder("{\n  \"format\": \"jpi-deobfuscation-workspace\",\n  \"version\": 1,\n  \"entries\": [\n");
        for (int index = 0; index < entries.size(); index++) {
            MappingEntry entry = entries.get(index);
            output.append("    {\"kind\":\"").append(entry.kind()).append("\",\"owner\":\"")
                    .append(escape(entry.owner())).append("\",\"original\":\"")
                    .append(escape(entry.originalName())).append("\",\"descriptor\":\"")
                    .append(escape(entry.descriptor())).append("\",\"parameterIndex\":")
                    .append(entry.parameterIndex()).append(",\"access\":").append(entry.access())
                    .append(",\"mapped\":\"").append(escape(entry.mappedName()))
                    .append("\",\"comment\":\"").append(escape(entry.comment()))
                    .append("\",\"tags\":\"").append(escape(entry.tags()))
                    .append("\",\"color\":\"").append(escape(entry.color()))
                    .append("\",\"enabled\":").append(entry.enabled()).append('}');
            if (index + 1 < entries.size()) output.append(',');
            output.append('\n');
        }
        return output.append("  ]\n}\n").toString();
    }

    private static String tiny(List<MappingEntry> entries) {
        StringBuilder output = new StringBuilder("tiny\t2\t0\toriginal\tmapped\n");
        for (String owner : owners(entries)) {
            output.append("c\t").append(internal(owner)).append('\t')
                    .append(internal(mappedClass(owner, entries))).append('\n');
            for (MappingEntry entry : members(entries, owner, MappingKind.FIELD)) {
                if (!mapped(entry)) continue;
                output.append("\tf\t").append(entry.descriptor()).append('\t')
                        .append(entry.originalName()).append('\t').append(entry.mappedName()).append('\n');
            }
            for (MappingEntry entry : members(entries, owner, MappingKind.METHOD)) {
                if (!mapped(entry) || entry.originalName().startsWith("<")) continue;
                output.append("\tm\t").append(entry.descriptor()).append('\t')
                        .append(entry.originalName()).append('\t').append(entry.mappedName()).append('\n');
                for (MappingEntry parameter : parameters(entries, entry)) {
                    if (mapped(parameter)) output.append("\t\tp\t").append(parameter.parameterIndex()).append('\t')
                            .append("arg").append(parameter.parameterIndex()).append('\t')
                            .append(parameter.mappedName()).append('\n');
                }
            }
        }
        return output.toString();
    }

    private static String tsrg(List<MappingEntry> entries) {
        StringBuilder output = new StringBuilder("tsrg2 original mapped\n");
        for (String owner : owners(entries)) {
            output.append(internal(owner)).append(' ').append(internal(mappedClass(owner, entries))).append('\n');
            for (MappingEntry entry : members(entries, owner, MappingKind.FIELD)) {
                if (mapped(entry)) output.append('\t').append(entry.originalName()).append(' ')
                        .append(entry.mappedName()).append('\n');
            }
            for (MappingEntry entry : members(entries, owner, MappingKind.METHOD)) {
                if (mapped(entry) && !entry.originalName().startsWith("<")) {
                    output.append('\t').append(entry.originalName()).append(' ').append(entry.descriptor())
                            .append(' ').append(entry.mappedName()).append('\n');
                }
            }
        }
        return output.toString();
    }

    private static String proguard(List<MappingEntry> entries) {
        StringBuilder output = new StringBuilder();
        for (String owner : owners(entries)) {
            output.append(owner).append(" -> ").append(mappedClass(owner, entries)).append(":\n");
            for (MappingEntry entry : members(entries, owner, MappingKind.FIELD)) {
                if (!mapped(entry)) continue;
                String type;
                try { type = Type.getType(entry.descriptor()).getClassName(); }
                catch (RuntimeException ignored) { type = "java.lang.Object"; }
                output.append("    ").append(type).append(' ').append(entry.originalName())
                        .append(" -> ").append(entry.mappedName()).append('\n');
            }
            for (MappingEntry entry : members(entries, owner, MappingKind.METHOD)) {
                if (!mapped(entry) || entry.originalName().startsWith("<")) continue;
                String result;
                String arguments;
                try {
                    result = Type.getReturnType(entry.descriptor()).getClassName();
                    Type[] types = Type.getArgumentTypes(entry.descriptor());
                    StringBuilder joined = new StringBuilder();
                    for (int index = 0; index < types.length; index++) {
                        if (index > 0) joined.append(',');
                        joined.append(types[index].getClassName());
                    }
                    arguments = joined.toString();
                } catch (RuntimeException ignored) {
                    result = "java.lang.Object";
                    arguments = "";
                }
                output.append("    ").append(result).append(' ').append(entry.originalName())
                        .append('(').append(arguments).append(") -> ").append(entry.mappedName()).append('\n');
            }
        }
        return output.toString();
    }

    private static Set<String> owners(List<MappingEntry> entries) {
        Set<String> owners = new LinkedHashSet<>();
        List<String> ordered = new ArrayList<>();
        for (MappingEntry entry : entries) {
            if (entry.kind() == MappingKind.CLASS) ordered.add(entry.originalName());
            else if (entry.kind() != MappingKind.PACKAGE && !entry.owner().isEmpty()) ordered.add(entry.owner());
        }
        Collections.sort(ordered);
        owners.addAll(ordered);
        return owners;
    }

    private static List<MappingEntry> members(List<MappingEntry> entries, String owner, MappingKind kind) {
        List<MappingEntry> result = new ArrayList<>();
        for (MappingEntry entry : entries) if (entry.kind() == kind && owner.equals(entry.owner())) result.add(entry);
        result.sort(Comparator.comparing(MappingEntry::originalName).thenComparing(MappingEntry::descriptor));
        return result;
    }

    private static List<MappingEntry> parameters(List<MappingEntry> entries, MappingEntry method) {
        List<MappingEntry> result = new ArrayList<>();
        for (MappingEntry entry : entries) {
            if (entry.kind() == MappingKind.PARAMETER && entry.owner().equals(method.owner())
                    && entry.originalName().equals(method.originalName())
                    && entry.descriptor().equals(method.descriptor())) result.add(entry);
        }
        result.sort(Comparator.comparingInt(MappingEntry::parameterIndex));
        return result;
    }

    private static String mappedClass(String original, List<MappingEntry> entries) {
        String className = null;
        for (MappingEntry entry : entries) {
            if (entry.kind() == MappingKind.CLASS && entry.originalName().equals(original) && mapped(entry)) {
                className = entry.mappedName();
                break;
            }
        }
        int separator = original.lastIndexOf('.');
        if (separator < 0) return className == null ? original : className;
        String packageName = original.substring(0, separator);
        String mappedPackage = null;
        for (MappingEntry entry : entries) {
            if (entry.kind() == MappingKind.PACKAGE && entry.originalName().equals(packageName) && mapped(entry)) {
                mappedPackage = entry.mappedName();
                break;
            }
        }
        if (className != null && className.indexOf('.') >= 0) return className;
        if (mappedPackage != null) {
            String simpleName = className == null ? original.substring(separator + 1) : className;
            return mappedPackage + "." + simpleName;
        }
        return className == null ? original : className;
    }

    private static boolean mapped(MappingEntry entry) {
        return entry.enabled() && !entry.mappedName().isEmpty();
    }

    private static String internal(String value) {
        return value.replace('.', '/');
    }

    private static int integer(Map<String, String> values, String key, int fallback) {
        String value = values.get(key);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static String value(Map<String, String> values, String key) {
        return values.getOrDefault(key, "");
    }

    private static String escape(String value) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '"' || current == '\\') output.append('\\').append(current);
            else if (current == '\n') output.append("\\n");
            else if (current == '\r') output.append("\\r");
            else if (current == '\t') output.append("\\t");
            else if (current < 32) output.append(String.format("\\u%04x", (int) current));
            else output.append(current);
        }
        return output.toString();
    }

    private static String unescape(String value) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) {
                output.append(current);
                continue;
            }
            char next = value.charAt(++index);
            if (next == 'n') output.append('\n');
            else if (next == 'r') output.append('\r');
            else if (next == 't') output.append('\t');
            else if (next == 'u' && index + 4 < value.length()) {
                output.append((char) Integer.parseInt(value.substring(index + 1, index + 5), 16));
                index += 4;
            } else output.append(next);
        }
        return output.toString();
    }

    public enum Format {
        JSON("JPI JSON", "json"),
        TINY_V2("Tiny v2", "tiny"),
        TSRG2("TSRG2", "tsrg"),
        PROGUARD("ProGuard", "map");

        private final String label;
        private final String extension;

        Format(String label, String extension) {
            this.label = label;
            this.extension = extension;
        }

        public String extension() { return extension; }
        @Override public String toString() { return label; }
    }
}
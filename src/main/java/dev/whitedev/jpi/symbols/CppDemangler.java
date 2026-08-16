package dev.whitedev.jpi.symbols;

import dev.whitedev.jpi.symbols.model.SymbolKind;

import java.util.ArrayList;
import java.util.List;

public final class CppDemangler {
    private CppDemangler() {}

    public static String demangle(String name) {
        if (name == null || name.isBlank()) return "";
        if (name.startsWith("??_7")) return "vftable for " + msvcScope(name.substring(4));
        if (name.startsWith("??_8")) return "vbtable for " + msvcScope(name.substring(4));
        if (name.startsWith("??_R")) return "RTTI " + msvcScope(name.substring(5));
        if (name.startsWith("?")) return msvc(name);
        if (name.startsWith("_ZTV")) return "vtable for " + itaniumType(name.substring(4));
        if (name.startsWith("_ZTI")) return "typeinfo for " + itaniumType(name.substring(4));
        if (name.startsWith("_ZTS")) return "typeinfo name for " + itaniumType(name.substring(4));
        if (name.startsWith("_Z")) return itanium(name.substring(2));
        return name;
    }

    public static SymbolKind kind(String rawName, String demangled, SymbolKind fallback) {
        String raw = rawName == null ? "" : rawName;
        String display = demangled == null ? "" : demangled.toLowerCase();
        if (raw.startsWith("??_7") || raw.startsWith("??_8") || raw.startsWith("_ZTV")
                || display.contains("vftable") || display.contains("vtable for")) return SymbolKind.VTABLE;
        if (raw.startsWith("??_R") || raw.startsWith("_ZTI") || raw.startsWith("_ZTS")
                || display.startsWith("rtti ") || display.contains("typeinfo")) return SymbolKind.RTTI;
        return fallback == null ? SymbolKind.UNKNOWN : fallback;
    }

    private static String msvc(String value) {
        int end = value.indexOf("@@");
        String encoded = end < 0 ? value : value.substring(0, end);
        String[] components = encoded.split("@");
        if (components.length == 0) return value;
        StringBuilder output = new StringBuilder();
        for (int index = components.length - 1; index >= 1; index--) {
            if (components[index].isEmpty()) continue;
            if (output.length() > 0) output.append("::");
            output.append(components[index]);
        }
        if (output.length() > 0) output.append("::");
        output.append(cleanMsvcComponent(components[0]));
        return output.toString();
    }

    private static String msvcScope(String value) {
        int end = value.indexOf("@@");
        String encoded = end < 0 ? value : value.substring(0, end);
        String[] components = encoded.split("@");
        StringBuilder output = new StringBuilder();
        for (int index = components.length - 1; index >= 0; index--) {
            String component = cleanMsvcComponent(components[index]);
            if (component.isEmpty()) continue;
            if (output.length() > 0) output.append("::");
            output.append(component);
        }
        return output.length() == 0 ? encoded : output.toString();
    }

    private static String cleanMsvcComponent(String value) {
        int marker = value.lastIndexOf('?');
        String result = marker >= 0 ? value.substring(marker + 1) : value;
        while (!result.isEmpty() && !Character.isJavaIdentifierStart(result.charAt(0))) result = result.substring(1);
        return result;
    }

    private static String itanium(String encoded) {
        ParseResult names = parseNames(encoded, 0);
        if (names.parts.isEmpty()) return "_Z" + encoded;
        StringBuilder output = new StringBuilder(String.join("::", names.parts));
        String arguments = encoded.substring(Math.min(names.offset, encoded.length()));
        output.append('(').append(arguments(arguments)).append(')');
        return output.toString();
    }

    private static String itaniumType(String encoded) {
        ParseResult names = parseNames(encoded, 0);
        return names.parts.isEmpty() ? encoded : String.join("::", names.parts);
    }

    private static ParseResult parseNames(String encoded, int offset) {
        boolean nested = offset < encoded.length() && encoded.charAt(offset) == 'N';
        if (nested) offset++;
        List<String> parts = new ArrayList<>();
        while (offset < encoded.length()) {
            if (nested && encoded.charAt(offset) == 'E') {
                offset++;
                break;
            }
            int start = offset;
            while (offset < encoded.length() && Character.isDigit(encoded.charAt(offset))) offset++;
            if (start == offset) break;
            int length;
            try {
                length = Integer.parseInt(encoded.substring(start, offset));
            } catch (NumberFormatException error) {
                break;
            }
            if (length < 1 || offset + length > encoded.length()) break;
            parts.add(encoded.substring(offset, offset + length));
            offset += length;
            if (!nested) break;
        }
        return new ParseResult(parts, offset);
    }

    private static String arguments(String encoded) {
        if (encoded.isEmpty() || "v".equals(encoded)) return "";
        List<String> values = new ArrayList<>();
        for (int index = 0; index < encoded.length(); index++) {
            char type = encoded.charAt(index);
            String value = switch (type) {
                case 'b' -> "bool";
                case 'c' -> "char";
                case 'a' -> "signed char";
                case 'h' -> "unsigned char";
                case 's' -> "short";
                case 't' -> "unsigned short";
                case 'i' -> "int";
                case 'j' -> "unsigned int";
                case 'l' -> "long";
                case 'm' -> "unsigned long";
                case 'x' -> "long long";
                case 'y' -> "unsigned long long";
                case 'f' -> "float";
                case 'd' -> "double";
                case 'e' -> "long double";
                case 'P' -> "pointer";
                case 'R' -> "reference";
                case 'K' -> "const";
                default -> null;
            };
            if (value == null) break;
            values.add(value);
        }
        return String.join(", ", values);
    }

    private record ParseResult(List<String> parts, int offset) {}
}

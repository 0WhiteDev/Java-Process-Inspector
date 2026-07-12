package dev.whitedev.jpi.decompile;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MethodSourceExtractor {
    private MethodSourceExtractor() { }

    public static String extract(String source, String methodName, String descriptor) {
        int expectedParameters = descriptorParameterCount(descriptor);
        int from = 0;
        while (from < source.length()) {
            int name = source.indexOf(methodName, from);
            if (name < 0) break;
            from = name + methodName.length();
            if (!identifierBoundary(source, name - 1) || !identifierBoundary(source, from)) continue;
            int openingParenthesis = skipWhitespace(source, from);
            if (openingParenthesis >= source.length() || source.charAt(openingParenthesis) != '(') continue;
            int closingParenthesis = matching(source, openingParenthesis, '(', ')');
            if (closingParenthesis < 0) continue;
            if (sourceParameterCount(source, openingParenthesis + 1, closingParenthesis) != expectedParameters) continue;
            int openingBrace = bodyOpeningBrace(source, closingParenthesis + 1);
            if (openingBrace < 0) continue;
            int closingBrace = matching(source, openingBrace, '{', '}');
            if (closingBrace < 0) continue;
            String body = source.substring(openingBrace, closingBrace + 1);
            return normalizeParameters(body, source.substring(openingParenthesis + 1, closingParenthesis));
        }
        throw new IllegalArgumentException("CFR did not expose a unique body for " + methodName + descriptor);
    }

    private static int descriptorParameterCount(String descriptor) {
        int count = 0;
        int index = descriptor.indexOf('(') + 1;
        int end = descriptor.indexOf(')', index);
        if (index <= 0 || end < 0) throw new IllegalArgumentException("Invalid method descriptor: " + descriptor);
        while (index < end) {
            while (descriptor.charAt(index) == '[') index++;
            if (descriptor.charAt(index) == 'L') {
                index = descriptor.indexOf(';', index);
                if (index < 0 || index > end) throw new IllegalArgumentException("Invalid method descriptor: " + descriptor);
            }
            index++;
            count++;
        }
        return count;
    }

    private static int sourceParameterCount(String source, int start, int end) {
        if (source.substring(start, end).trim().isEmpty()) return 0;
        int count = 1;
        int round = 0;
        int square = 0;
        int angle = 0;
        boolean string = false;
        boolean character = false;
        boolean escaped = false;
        for (int index = start; index < end; index++) {
            char value = source.charAt(index);
            if (escaped) { escaped = false; continue; }
            if ((string || character) && value == '\\') { escaped = true; continue; }
            if (!character && value == '"') { string = !string; continue; }
            if (!string && value == '\'') { character = !character; continue; }
            if (string || character) continue;
            if (value == '(') round++;
            else if (value == ')') round--;
            else if (value == '[') square++;
            else if (value == ']') square--;
            else if (value == '<') angle++;
            else if (value == '>') angle = Math.max(0, angle - 1);
            else if (value == ',' && round == 0 && square == 0 && angle == 0) count++;
        }
        return count;
    }

    private static String normalizeParameters(String body, String parameters) {
        Map<String, String> replacements = new LinkedHashMap<>();
        String[] values = parameters.trim().isEmpty() ? new String[0] : parameters.split(",");
        for (int index = 0; index < values.length; index++) {
            String value = values[index].trim();
            int end = value.length() - 1;
            while (end >= 0 && (Character.isWhitespace(value.charAt(end)) || value.charAt(end) == ']')) end--;
            while (end >= 0 && value.charAt(end) == '[') end--;
            int start = end;
            while (start >= 0 && Character.isJavaIdentifierPart(value.charAt(start))) start--;
            if (end >= start + 1) replacements.put(value.substring(start + 1, end + 1), "$" + (index + 1));
        }
        return replaceIdentifiers(body, replacements);
    }

    private static String replaceIdentifiers(String source, Map<String, String> replacements) {
        StringBuilder output = new StringBuilder(source.length());
        boolean string = false;
        boolean character = false;
        boolean escaped = false;
        for (int index = 0; index < source.length();) {
            char value = source.charAt(index);
            if (escaped) {
                output.append(value);
                escaped = false;
                index++;
                continue;
            }
            if ((string || character) && value == '\\') {
                output.append(value);
                escaped = true;
                index++;
                continue;
            }
            if (!character && value == '"') {
                string = !string;
                output.append(value);
                index++;
                continue;
            }
            if (!string && value == '\'') {
                character = !character;
                output.append(value);
                index++;
                continue;
            }
            if (!string && !character && Character.isJavaIdentifierStart(value)) {
                int end = index + 1;
                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
                String identifier = source.substring(index, end);
                String replacement = replacements.get(identifier);
                output.append(replacement == null ? identifier : replacement);
                index = end;
                continue;
            }
            output.append(value);
            index++;
        }
        return output.toString();
    }

    private static int bodyOpeningBrace(String source, int start) {
        for (int index = start; index < source.length(); index++) {
            char value = source.charAt(index);
            if (value == '{') return index;
            if (value == ';' || value == '=') return -1;
        }
        return -1;
    }

    private static int matching(String source, int opening, char open, char close) {
        int depth = 0;
        boolean string = false;
        boolean character = false;
        boolean escaped = false;
        for (int index = opening; index < source.length(); index++) {
            char value = source.charAt(index);
            if (escaped) { escaped = false; continue; }
            if ((string || character) && value == '\\') { escaped = true; continue; }
            if (!character && value == '"') { string = !string; continue; }
            if (!string && value == '\'') { character = !character; continue; }
            if (string || character) continue;
            if (value == open) depth++;
            else if (value == close && --depth == 0) return index;
        }
        return -1;
    }

    private static boolean identifierBoundary(String source, int index) {
        return index < 0 || index >= source.length() || !Character.isJavaIdentifierPart(source.charAt(index));
    }

    private static int skipWhitespace(String source, int index) {
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        return index;
    }
}

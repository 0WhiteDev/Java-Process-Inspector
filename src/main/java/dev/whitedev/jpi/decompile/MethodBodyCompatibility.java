package dev.whitedev.jpi.decompile;

public final class MethodBodyCompatibility {
    private MethodBodyCompatibility() { }

    public static String unsupportedReason(String body) {
        boolean string = false;
        boolean character = false;
        boolean lineComment = false;
        boolean blockComment = false;
        boolean escaped = false;
        for (int index = 0; index < body.length(); index++) {
            char value = body.charAt(index);
            char next = index + 1 < body.length() ? body.charAt(index + 1) : 0;
            if (lineComment) {
                if (value == 10) lineComment = false;
                continue;
            }
            if (blockComment) {
                if (value == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (escaped) {
                escaped = false;
                continue;
            }
            if ((string || character) && value == 92) {
                escaped = true;
                continue;
            }
            if (!character && value == 34) {
                string = !string;
                continue;
            }
            if (!string && value == 39) {
                character = !character;
                continue;
            }
            if (string || character) continue;
            if (value == '/' && next == '/') {
                lineComment = true;
                index++;
                continue;
            }
            if (value == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            if (value == '-' && next == '>') {
                return "Lambda edits must preserve the original number and captured-variable shape";
            }
            if (value == ':' && next == ':') {
                return "Method references must target methods that already exist in the loaded class schema";
            }
        }
        return null;
    }
}
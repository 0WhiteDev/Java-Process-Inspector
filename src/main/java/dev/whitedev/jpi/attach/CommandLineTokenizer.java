package dev.whitedev.jpi.attach;

import java.util.ArrayList;
import java.util.List;

public final class CommandLineTokenizer {
    private CommandLineTokenizer() {}

    public static List<String> parse(String commandLine) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaping = false;
        for (int index = 0; index < commandLine.length(); index++) {
            char value = commandLine.charAt(index);
            if (escaping) {
                current.append(value);
                escaping = false;
            } else if (value == '\\' && quoted) {
                escaping = true;
            } else if (value == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(value) && !quoted) {
                add(result, current);
            } else {
                current.append(value);
            }
        }
        if (escaping) current.append('\\');
        if (quoted) throw new IllegalArgumentException("Unclosed quote in arguments");
        add(result, current);
        return result;
    }

    private static void add(List<String> result, StringBuilder value) {
        if (value.length() == 0) return;
        result.add(value.toString());
        value.setLength(0);
    }
}

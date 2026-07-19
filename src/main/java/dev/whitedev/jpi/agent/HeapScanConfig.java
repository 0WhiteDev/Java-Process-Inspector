package dev.whitedev.jpi.agent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class HeapScanConfig {
    final String rootFilter;
    final String classFilter;
    final String valueFilter;
    final String fieldFilter;
    final int maxDepth;
    final int maxObjects;
    final int maxResults;
    final int maxArrayElements;
    final int maxValueLength;
    final int maxRootFields;
    final long timeoutMillis;

    private HeapScanConfig(Map<String, String> values) throws IOException {
        rootFilter = required(values, "root");
        classFilter = text(values, "class");
        valueFilter = text(values, "value");
        fieldFilter = text(values, "field");
        maxDepth = integer(values, "maxDepth", 4, 0, 16);
        maxObjects = integer(values, "maxObjects", 10000, 1, 100000);
        maxResults = integer(values, "maxResults", 250, 1, 5000);
        maxArrayElements = integer(values, "maxArrayElements", 64, 1, 4096);
        maxValueLength = integer(values, "maxValueLength", 512, 32, 8192);
        maxRootFields = integer(values, "maxRootFields", 2000, 1, 20000);
        timeoutMillis = integer(values, "timeoutMillis", 5000, 100, 30000);
    }

    static HeapScanConfig parse(String payload) throws IOException {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (payload != null) {
            for (String line : payload.split("\n")) {
                if (line.trim().isEmpty()) continue;
                int separator = line.indexOf('=');
                if (separator <= 0) throw new IOException("Invalid heap scan setting: " + line);
                values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        return new HeapScanConfig(values);
    }

    boolean matchesClass(String className) {
        return classFilter.isEmpty() || contains(className, classFilter);
    }

    boolean matchesRoot(String className) {
        return contains(className, rootFilter);
    }

    boolean matchesField(String fieldName) {
        return fieldFilter.isEmpty() || contains(fieldName, fieldFilter);
    }

    boolean matchesValue(String value) {
        return valueFilter.isEmpty() || contains(value, valueFilter);
    }

    private static boolean contains(String value, String query) {
        return value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static String required(Map<String, String> values, String key) throws IOException {
        String value = text(values, key);
        if (value.isEmpty()) throw new IOException("Static root class or package filter is required");
        return value;
    }

    private static String text(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null ? "" : value.trim();
    }

    private static int integer(Map<String, String> values, String key, int fallback, int minimum, int maximum)
            throws IOException {
        String text = values.get(key);
        if (text == null || text.isEmpty()) return fallback;
        try {
            int value = Integer.parseInt(text);
            if (value < minimum || value > maximum) {
                throw new IOException(key + " must be between " + minimum + " and " + maximum);
            }
            return value;
        } catch (NumberFormatException error) {
            throw new IOException(key + " must be a number", error);
        }
    }
}

package dev.whitedev.jpi.agent.field;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class FieldWriteConfig {
    final int maxEvents;
    final int rateLimit;
    final long stopAfterMillis;
    final int maxValueLength;
    final int maxStackDepth;
    final int maxClasses;

    private FieldWriteConfig(int maxEvents, int rateLimit, long stopAfterMillis,
                             int maxValueLength, int maxStackDepth, int maxClasses) {
        this.maxEvents = maxEvents;
        this.rateLimit = rateLimit;
        this.stopAfterMillis = stopAfterMillis;
        this.maxValueLength = maxValueLength;
        this.maxStackDepth = maxStackDepth;
        this.maxClasses = maxClasses;
    }

    static FieldWriteConfig parse(String raw) throws IOException {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (raw != null) {
            for (String line : raw.split("[;\\n]")) {
                int separator = line.indexOf('=');
                if (separator > 0) values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        return new FieldWriteConfig(
                integer(values, "maxEvents", 500, 1, 10_000),
                integer(values, "rateLimit", 100, 1, 100_000),
                integer(values, "stopAfterSeconds", 60, 1, 3_600) * 1000L,
                integer(values, "maxValueLength", 2_048, 64, 65_536),
                integer(values, "maxStackDepth", 24, 1, 256),
                integer(values, "maxClasses", 500, 1, 2_000));
    }

    private static int integer(Map<String, String> values, String key, int fallback,
                               int minimum, int maximum) throws IOException {
        String value = values.get(key);
        if (value == null || value.isEmpty()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new IOException(key + " must be between " + minimum + " and " + maximum);
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IOException("Invalid " + key, error);
        }
    }
}

package dev.whitedev.jpi.agent.trace;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

final class TraceConfig {
    final boolean captureArguments;
    final boolean captureReturn;
    final boolean captureException;
    final boolean captureDuration;
    final boolean captureThread;
    final boolean captureStack;
    final boolean captureIdentity;
    final int sampleEvery;
    final int maxEvents;
    final int maxValueLength;
    final int maxArrayElements;
    final int maxStackDepth;
    final int rateLimit;
    final long stopAfterMillis;
    final String condition;

    private TraceConfig(Map<String, String> values) {
        captureArguments = bool(values, "captureArguments", true);
        captureReturn = bool(values, "captureReturn", true);
        captureException = bool(values, "captureException", true);
        captureDuration = bool(values, "captureDuration", true);
        captureThread = bool(values, "captureThread", true);
        captureStack = bool(values, "captureStack", true);
        captureIdentity = bool(values, "captureIdentity", true);
        sampleEvery = integer(values, "sampleEvery", 1, 1, 1_000_000);
        maxEvents = integer(values, "maxEvents", 500, 1, 10_000);
        maxValueLength = integer(values, "maxValueLength", 2048, 64, 65_536);
        maxArrayElements = integer(values, "maxArrayElements", 32, 1, 1024);
        maxStackDepth = integer(values, "maxStackDepth", 24, 1, 256);
        rateLimit = integer(values, "rateLimit", 100, 1, 100_000);
        stopAfterMillis = longValue(values, "stopAfterMillis", 60_000L, 1000L, 3_600_000L);
        condition = decode(values.get("condition"));
    }

    static TraceConfig parse(String value) {
        Map<String, String> values = new HashMap<String, String>();
        for (String line : value.split("\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        return new TraceConfig(values);
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        if (value == null) return fallback;
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("Invalid boolean trace option: " + key);
    }

    private static int integer(Map<String, String> values, String key, int fallback, int minimum, int maximum) {
        String value = values.get(key);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Trace option " + key + " must be between " + minimum + " and " + maximum);
        }
    }

    private static long longValue(Map<String, String> values, String key, long fallback, long minimum, long maximum) {
        String value = values.get(key);
        if (value == null) return fallback;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Trace option " + key + " must be between " + minimum + " and " + maximum);
        }
    }

    private static String decode(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8).trim();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid trace condition encoding", error);
        }
    }
}
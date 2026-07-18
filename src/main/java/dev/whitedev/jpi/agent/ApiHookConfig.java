package dev.whitedev.jpi.agent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class ApiHookConfig {
    final int maxEvents;
    final int rateLimit;
    final long stopAfterMillis;
    final int maxClasses;
    final int maxSites;

    private ApiHookConfig(int maxEvents, int rateLimit, long stopAfterMillis, int maxClasses, int maxSites) {
        this.maxEvents = maxEvents;
        this.rateLimit = rateLimit;
        this.stopAfterMillis = stopAfterMillis;
        this.maxClasses = maxClasses;
        this.maxSites = maxSites;
    }

    static ApiHookConfig parse(String settings) throws IOException {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (settings != null) {
            for (String entry : settings.split(";")) {
                if (entry.trim().isEmpty()) continue;
                int separator = entry.indexOf('=');
                if (separator <= 0 || separator == entry.length() - 1) {
                    throw new IOException("Invalid API hook setting: " + entry);
                }
                values.put(entry.substring(0, separator).trim(), entry.substring(separator + 1).trim());
            }
        }
        return new ApiHookConfig(
                integer(values, "maxEvents", 1000, 1, 10000),
                integer(values, "rateLimit", 200, 1, 100000),
                integer(values, "stopAfterSeconds", 120, 1, 3600) * 1000L,
                integer(values, "maxClasses", 1500, 1, 10000),
                integer(values, "maxSites", 20000, 1, 100000));
    }

    private static int integer(Map<String, String> values, String key, int fallback, int minimum, int maximum)
            throws IOException {
        String text = values.get(key);
        if (text == null) return fallback;
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

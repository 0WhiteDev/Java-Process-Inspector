package dev.whitedev.jpi.profiler;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record ProfilerStatus(String state, boolean available, long startedAt, long durationMillis, String message) {
    public static ProfilerStatus parse(String raw) {
        String[] values = raw.split("\t", -1);
        if (values.length != 5) throw new IllegalArgumentException("Invalid JFR profiler status");
        return new ProfilerStatus(values[0], Boolean.parseBoolean(values[1]), Long.parseLong(values[2]),
                Long.parseLong(values[3]), decoded(values[4]));
    }

    public boolean active() {
        return "RECORDING".equals(state) || "ANALYZING".equals(state);
    }

    private static String decoded(String value) {
        return value.isEmpty() ? "" : new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}

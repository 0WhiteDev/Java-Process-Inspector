package dev.whitedev.jpi.exceptions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ExceptionSnapshot(long timestamp, String state, int windowSeconds, long dropped, String message,
                                List<Entry> entries) {
    public static ExceptionSnapshot parse(String raw) {
        long timestamp = 0, dropped = 0;
        int window = 30;
        String state = "idle", message = "";
        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, List<Location>> locations = new LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            String[] v = line.split("\t", -1);
            switch (v[0]) {
                case "S" -> {
                    timestamp = Long.parseLong(v[1]); state = v[2]; window = Integer.parseInt(v[3]);
                    dropped = Long.parseLong(v[4]); message = decode(v[5]);
                }
                case "E" -> {
                    String type = decode(v[1]);
                    entries.put(type, new Entry(type, Long.parseLong(v[2]), Long.parseLong(v[3]),
                            Long.parseLong(v[4]), Long.parseLong(v[5]), Integer.parseInt(v[6]),
                            Boolean.parseBoolean(v[7]), List.of()));
                }
                case "L" -> locations.computeIfAbsent(decode(v[1]), ignored -> new ArrayList<>()).add(
                        new Location(Long.parseLong(v[2]), decode(v[3]), decode(v[4]), decode(v[5]),
                                Integer.parseInt(v[6]), decode(v[7])));
                default -> { }
            }
        }
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries.values()) result.add(new Entry(entry.type, entry.windowCount, entry.total,
                entry.firstSeen, entry.lastSeen, entry.threads, entry.threadOverflow,
                List.copyOf(locations.getOrDefault(entry.type, List.of()))));
        return new ExceptionSnapshot(timestamp, state, window, dropped, message, List.copyOf(result));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public record Entry(String type, long windowCount, long total, long firstSeen, long lastSeen, int threads,
                        boolean threadOverflow, List<Location> locations) {}
    public record Location(long count, String owner, String method, String descriptor, int line, String stack) {
        @Override public String toString() { return owner + '.' + method + descriptor + ':' + line; }
    }
}

package dev.whitedev.jpi.profiler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class ProfilerReport {
    private final long startedAt;
    private final long finishedAt;
    private final long durationMillis;
    private final int processedEvents;
    private final boolean truncated;
    private final Map<ProfilerCategory, CategoryData> categories;

    private ProfilerReport(long startedAt, long finishedAt, long durationMillis, int processedEvents,
                           boolean truncated, Map<ProfilerCategory, CategoryData> categories) {
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.durationMillis = durationMillis;
        this.processedEvents = processedEvents;
        this.truncated = truncated;
        this.categories = Collections.unmodifiableMap(categories);
    }

    public static ProfilerReport parse(String raw) {
        long started = 0L;
        long finished = 0L;
        long duration = 0L;
        int processed = 0;
        boolean truncated = false;
        Map<ProfilerCategory, MutableCategory> mutable = new EnumMap<>(ProfilerCategory.class);
        for (String line : raw.split("\n")) {
            String[] values = line.split("\t", -1);
            try {
                if (values.length == 6 && "R".equals(values[0])) {
                    started = Long.parseLong(values[1]);
                    finished = Long.parseLong(values[2]);
                    duration = Long.parseLong(values[3]);
                    processed = Integer.parseInt(values[4]);
                    truncated = Boolean.parseBoolean(values[5]);
                } else if (values.length == 4 && "C".equals(values[0])) {
                    ProfilerCategory category = ProfilerCategory.valueOf(values[1]);
                    MutableCategory data = mutable.computeIfAbsent(category, ignored -> new MutableCategory());
                    data.events = Long.parseLong(values[2]);
                    data.total = Long.parseLong(values[3]);
                } else if (values.length == 5 && ("F".equals(values[0]) || "I".equals(values[0]))) {
                    ProfilerCategory category = ProfilerCategory.valueOf(values[1]);
                    Metric metric = new Metric(Long.parseLong(values[2]), Long.parseLong(values[3]), decoded(values[4]));
                    MutableCategory data = mutable.computeIfAbsent(category, ignored -> new MutableCategory());
                    if ("F".equals(values[0])) data.flames.add(metric);
                    else data.items.add(metric);
                }
            } catch (RuntimeException ignored) {
            }
        }
        Map<ProfilerCategory, CategoryData> result = new EnumMap<>(ProfilerCategory.class);
        for (Map.Entry<ProfilerCategory, MutableCategory> entry : mutable.entrySet()) {
            MutableCategory value = entry.getValue();
            result.put(entry.getKey(), new CategoryData(value.events, value.total,
                    List.copyOf(value.flames), List.copyOf(value.items)));
        }
        return new ProfilerReport(started, finished, duration, processed, truncated, result);
    }

    public long startedAt() {
        return startedAt;
    }

    public long finishedAt() {
        return finishedAt;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public int processedEvents() {
        return processedEvents;
    }

    public boolean truncated() {
        return truncated;
    }

    public CategoryData category(ProfilerCategory category) {
        CategoryData data = categories.get(category);
        return data == null ? new CategoryData(0L, 0L, List.of(), List.of()) : data;
    }

    public record Metric(long value, long count, String label) {
        public List<String> path() {
            return label.isEmpty() ? List.of() : List.of(label.split("\n"));
        }
    }

    public record CategoryData(long events, long total, List<Metric> flames, List<Metric> items) {
    }

    private static String decoded(String value) {
        return value.isEmpty() ? "" : new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static final class MutableCategory {
        long events;
        long total;
        final List<Metric> flames = new ArrayList<>();
        final List<Metric> items = new ArrayList<>();
    }
}

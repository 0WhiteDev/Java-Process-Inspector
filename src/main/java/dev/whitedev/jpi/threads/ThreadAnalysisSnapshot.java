package dev.whitedev.jpi.threads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ThreadAnalysisSnapshot(long timestamp, long intervalMillis, boolean cpuSupported,
                                     boolean cpuEnabled, boolean contentionSupported,
                                     boolean contentionEnabled, List<ThreadEntry> threads,
                                     List<Lifetime> lifetimes) {
    public record ThreadEntry(long id, String name, String state, boolean daemon, int priority,
                              long blockedCount, long blockedTime, long waitedCount, long waitedTime,
                              long cpuDeltaNanos, String lock, long ownerId, String ownerName,
                              boolean parked, boolean nativeThread, boolean suspended,
                              boolean deadlocked, long firstSeen, List<String> frames,
                              List<HeldLock> heldLocks, List<StateChange> history) {
        public double cpuPercent(long intervalMillis) {
            return intervalMillis <= 0L ? 0d : Math.min(100d, cpuDeltaNanos / (intervalMillis * 1_000_000d) * 100d);
        }
    }

    public record HeldLock(String kind, String identity, int stackDepth) {}

    public record StateChange(long timestamp, String state, String lock, String owner) {}

    public record Lifetime(long id, String name, long firstSeen, long endedAt) {}

    public static ThreadAnalysisSnapshot parse(String raw) {
        Meta meta = new Meta();
        Map<Long, Builder> threads = new LinkedHashMap<>();
        Map<Long, List<StateChange>> histories = new LinkedHashMap<>();
        List<Lifetime> lifetimes = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            if (line.isBlank()) continue;
            String[] value = line.split("\\t", -1);
            try {
                switch (value[0]) {
                    case "M" -> meta.read(value);
                    case "T" -> threads.put(number(value, 1), Builder.read(value));
                    case "S" -> threads.computeIfAbsent(number(value, 1), Builder::empty).frames.add(text(value, 3));
                    case "L" -> threads.computeIfAbsent(number(value, 1), Builder::empty).locks.add(
                            new HeldLock(text(value, 2), text(value, 3), integer(value, 4)));
                    case "H" -> histories.computeIfAbsent(number(value, 1), ignored -> new ArrayList<>()).add(
                            new StateChange(number(value, 2), text(value, 3), text(value, 4), text(value, 5)));
                    case "C" -> lifetimes.add(new Lifetime(number(value, 1), text(value, 2),
                            number(value, 3), number(value, 4)));
                    default -> {
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        List<ThreadEntry> entries = new ArrayList<>();
        for (Map.Entry<Long, Builder> entry : threads.entrySet()) {
            Builder builder = entry.getValue();
            builder.history.addAll(histories.getOrDefault(entry.getKey(), List.of()));
            entries.add(builder.build());
        }
        entries.sort(Comparator.comparingLong(ThreadEntry::cpuDeltaNanos).reversed()
                .thenComparing(ThreadEntry::name, String.CASE_INSENSITIVE_ORDER));
        lifetimes.sort(Comparator.comparingLong(Lifetime::firstSeen).reversed());
        return new ThreadAnalysisSnapshot(meta.timestamp, meta.interval, meta.cpuSupported, meta.cpuEnabled,
                meta.contentionSupported, meta.contentionEnabled, List.copyOf(entries), List.copyOf(lifetimes));
    }

    public int deadlockCount() {
        return (int) threads.stream().filter(ThreadEntry::deadlocked).count();
    }

    public int blockedCount() {
        return (int) threads.stream().filter(value -> "BLOCKED".equals(value.state())).count();
    }

    public int parkedCount() {
        return (int) threads.stream().filter(ThreadEntry::parked).count();
    }

    private static long number(String[] values, int index) {
        return Long.parseLong(text(values, index));
    }

    private static int integer(String[] values, int index) {
        return Integer.parseInt(text(values, index));
    }

    private static boolean bool(String[] values, int index) {
        return Boolean.parseBoolean(text(values, index));
    }

    private static String text(String[] values, int index) {
        return index < values.length ? values[index] : "";
    }

    private static final class Meta {
        long timestamp;
        long interval;
        boolean cpuSupported;
        boolean cpuEnabled;
        boolean contentionSupported;
        boolean contentionEnabled;

        void read(String[] value) {
            timestamp = number(value, 1);
            interval = number(value, 2);
            cpuSupported = bool(value, 7);
            cpuEnabled = bool(value, 8);
            contentionSupported = bool(value, 9);
            contentionEnabled = bool(value, 10);
        }
    }

    private static final class Builder {
        long id;
        String name = "";
        String state = "UNKNOWN";
        boolean daemon;
        int priority;
        long blockedCount;
        long blockedTime = -1L;
        long waitedCount;
        long waitedTime = -1L;
        long cpuDelta;
        String lock = "";
        long ownerId = -1L;
        String ownerName = "";
        boolean parked;
        boolean nativeThread;
        boolean suspended;
        boolean deadlocked;
        long firstSeen;
        final List<String> frames = new ArrayList<>();
        final List<HeldLock> locks = new ArrayList<>();
        final List<StateChange> history = new ArrayList<>();

        static Builder empty(long id) {
            Builder result = new Builder();
            result.id = id;
            return result;
        }

        static Builder read(String[] value) {
            Builder result = empty(number(value, 1));
            result.name = text(value, 2);
            result.state = text(value, 3);
            result.daemon = bool(value, 4);
            result.priority = integer(value, 5);
            result.blockedCount = number(value, 6);
            result.blockedTime = number(value, 7);
            result.waitedCount = number(value, 8);
            result.waitedTime = number(value, 9);
            result.cpuDelta = number(value, 10);
            result.lock = text(value, 11);
            result.ownerId = number(value, 12);
            result.ownerName = text(value, 13);
            result.parked = bool(value, 14);
            result.nativeThread = bool(value, 15);
            result.suspended = bool(value, 16);
            result.deadlocked = bool(value, 17);
            result.firstSeen = number(value, 18);
            return result;
        }

        ThreadEntry build() {
            return new ThreadEntry(id, name, state, daemon, priority, blockedCount, blockedTime,
                    waitedCount, waitedTime, cpuDelta, lock, ownerId, ownerName, parked, nativeThread,
                    suspended, deadlocked, firstSeen, List.copyOf(frames), List.copyOf(locks),
                    List.copyOf(history));
        }
    }
}

package dev.whitedev.jpi.agent.profiler;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JfrProfilerManager {
    private static final int MAX_EVENTS = 250000;
    private static final int MAX_RESULTS = 300;
    private static final int MAX_UNIQUE_METRICS = 1000;
    private static final int MAX_STACK_CHARACTERS = 4096;
    private final boolean available;
    private volatile Object recording;
    private volatile Thread worker;
    private volatile String state;
    private volatile String message;
    private volatile String report;
    private volatile long startedAt;
    private volatile long durationMillis;
    private volatile Set<JfrCategory> categories = EnumSet.allOf(JfrCategory.class);

    public JfrProfilerManager() {
        available = present("jdk.jfr.Recording") && present("jdk.jfr.consumer.RecordingFile");
        state = available ? "IDLE" : "UNSUPPORTED";
        message = available ? "Java Flight Recorder is available" : "JFR requires a compatible Java 11 or newer target JVM";
    }

    public synchronized String start(String settings) throws Exception {
        if (!available) throw new IOException(message);
        if (recording != null) throw new IOException("A JFR recording is already active");
        int seconds = number(settings, "durationSeconds", 15, 1, 3600);
        categories = selected(settings);
        Object created = Class.forName("jdk.jfr.Recording").getConstructor().newInstance();
        try {
            invoke(created, "setName", new Class<?>[]{String.class}, "JPI Profiler");
            invoke(created, "setToDisk", new Class<?>[]{boolean.class}, Boolean.TRUE);
            invoke(created, "setMaxSize", new Class<?>[]{long.class}, Long.valueOf(128L * 1024L * 1024L));
            enable(created, categories);
            invoke(created, "start", new Class<?>[0]);
        } catch (Exception error) {
            optional(created, "close", new Class<?>[0]);
            state = "FAILED";
            message = rootMessage(error);
            throw error;
        }
        recording = created;
        startedAt = System.currentTimeMillis();
        durationMillis = seconds * 1000L;
        report = "";
        state = "RECORDING";
        message = "Recording JFR events";
        final Object expected = created;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(durationMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                complete(expected);
            }
        }, "jpi-jfr-profiler");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
        return status();
    }

    public String status() {
        return state + "\t" + available + "\t" + startedAt + "\t" + durationMillis + "\t" + encoded(message);
    }

    public String report() throws IOException {
        if (!"COMPLETE".equals(state)) throw new IOException("JFR report is not ready: " + state);
        return report;
    }

    public String stop() {
        Object current;
        synchronized (this) {
            current = recording;
        }
        if (current != null) complete(current);
        return status();
    }

    public void close() {
        stop();
    }

    private void complete(Object expected) {
        synchronized (this) {
            if (recording != expected) return;
            recording = null;
            state = "ANALYZING";
            message = "Aggregating JFR events";
        }
        Path file = null;
        try {
            invoke(expected, "stop", new Class<?>[0]);
            file = Files.createTempFile("jpi-profiler-", ".jfr");
            invoke(expected, "dump", new Class<?>[]{Path.class}, file);
            invoke(expected, "close", new Class<?>[0]);
            report = analyze(file, startedAt, System.currentTimeMillis(), categories);
            state = "COMPLETE";
            message = "JFR report is ready";
        } catch (Throwable error) {
            state = "FAILED";
            message = rootMessage(error);
            try {
                invoke(expected, "close", new Class<?>[0]);
            } catch (Throwable ignored) {
            }
        } finally {
            worker = null;
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String analyze(Path file, long started, long finished, Set<JfrCategory> selected) throws Exception {
        Map<JfrCategory, Stats> stats = new EnumMap<JfrCategory, Stats>(JfrCategory.class);
        for (JfrCategory category : selected) stats.put(category, new Stats());
        Class<?> recordingFile = Class.forName("jdk.jfr.consumer.RecordingFile");
        Constructor<?> constructor = recordingFile.getConstructor(Path.class);
        Object reader = constructor.newInstance(file);
        Method more = recordingFile.getMethod("hasMoreEvents");
        Method read = recordingFile.getMethod("readEvent");
        int processed = 0;
        boolean truncated = false;
        try {
            while (Boolean.TRUE.equals(more.invoke(reader))) {
                if (processed >= MAX_EVENTS) {
                    truncated = true;
                    break;
                }
                Object event = read.invoke(reader);
                consume(event, stats);
                processed++;
            }
        } finally {
            invoke(reader, "close", new Class<?>[0]);
        }
        StringBuilder output = new StringBuilder();
        output.append('R').append('\t').append(started).append('\t').append(finished).append('\t')
                .append(Math.max(0L, finished - started)).append('\t').append(processed).append('\t')
                .append(truncated).append('\n');
        for (JfrCategory category : JfrCategory.values()) {
            Stats categoryStats = stats.get(category);
            if (categoryStats == null) continue;
            output.append('C').append('\t').append(category).append('\t').append(categoryStats.events)
                    .append('\t').append(categoryStats.total).append('\n');
            appendMetrics(output, 'F', category, categoryStats.paths);
            appendMetrics(output, 'I', category, categoryStats.items);
        }
        return output.toString();
    }

    private static void consume(Object event, Map<JfrCategory, Stats> stats) {
        try {
            Object eventType = invoke(event, "getEventType", new Class<?>[0]);
            String name = String.valueOf(invoke(eventType, "getName", new Class<?>[0]));
            JfrCategory category = category(name);
            Stats target = stats.get(category);
            if (target == null) return;
            long value = value(event, category, name);
            String item = item(event, category, name);
            String path = stack(event);
            target.events++;
            target.total += value;
            target.add(target.items, item, value);
            if (!path.isEmpty()) target.add(target.paths, path, value);
        } catch (Throwable ignored) {
        }
    }

    private static JfrCategory category(String name) {
        if (name.endsWith("ExecutionSample") || name.endsWith("NativeMethodSample")) return JfrCategory.CPU;
        if (name.contains("ObjectAllocation")) return JfrCategory.ALLOCATIONS;
        if (name.endsWith("JavaMonitorEnter") || name.endsWith("JavaMonitorWait") || name.endsWith("ThreadPark")) return JfrCategory.LOCKS;
        if (name.endsWith("JavaExceptionThrow") || name.endsWith("JavaErrorThrow")) return JfrCategory.EXCEPTIONS;
        if (name.contains("GarbageCollection") || name.endsWith("GCHeapSummary")) return JfrCategory.GC;
        if (name.endsWith("ThreadStart") || name.endsWith("ThreadEnd") || name.endsWith("ThreadSleep")) return JfrCategory.THREADS;
        if (name.endsWith("FileRead") || name.endsWith("FileWrite") || name.endsWith("SocketRead") || name.endsWith("SocketWrite")) return JfrCategory.IO;
        return null;
    }

    private static long value(Object event, JfrCategory category, String name) {
        if (category == JfrCategory.ALLOCATIONS) {
            long value = longField(event, "weight", -1L);
            if (value < 0L) value = longField(event, "allocationSize", -1L);
            if (value < 0L) value = longField(event, "tlabSize", 1L);
            return Math.max(1L, value);
        }
        if (category == JfrCategory.LOCKS || category == JfrCategory.GC) {
            return Math.max(1L, duration(event));
        }
        if (category == JfrCategory.IO) {
            return Math.max(0L, longField(event, name.endsWith("Read") ? "bytesRead" : "bytesWritten", 0L));
        }
        return 1L;
    }

    private static String item(Object event, JfrCategory category, String name) {
        if (category == JfrCategory.ALLOCATIONS) return namedField(event, "objectClass", "Unknown allocation");
        if (category == JfrCategory.LOCKS) return namedField(event, "monitorClass", shortName(name));
        if (category == JfrCategory.EXCEPTIONS) return namedField(event, "thrownClass", shortName(name));
        if (category == JfrCategory.GC) return textField(event, "name", shortName(name)) + " | "
                + textField(event, "cause", "unknown cause");
        if (category == JfrCategory.THREADS) return namedField(event, "thread", shortName(name));
        if (category == JfrCategory.IO) {
            String endpoint = textField(event, "path", "");
            if (endpoint.isEmpty()) endpoint = textField(event, "host", "");
            if (endpoint.isEmpty()) endpoint = textField(event, "address", "");
            String port = textField(event, "port", "");
            if (!endpoint.isEmpty() && !port.isEmpty()) endpoint += ":" + port;
            return shortName(name) + (endpoint.isEmpty() ? "" : " | " + endpoint);
        }
        return shortName(name);
    }

    private static String stack(Object event) {
        try {
            Object trace = invoke(event, "getStackTrace", new Class<?>[0]);
            if (trace == null) return "";
            List<?> frames = (List<?>) invoke(trace, "getFrames", new Class<?>[0]);
            StringBuilder output = new StringBuilder();
            int start = Math.max(0, frames.size() - 64);
            for (int index = frames.size() - 1; index >= start; index--) {
                Object frame = frames.get(index);
                Object method = invoke(frame, "getMethod", new Class<?>[0]);
                Object type = invoke(method, "getType", new Class<?>[0]);
                String owner = String.valueOf(invoke(type, "getName", new Class<?>[0]));
                String methodName = String.valueOf(invoke(method, "getName", new Class<?>[0]));
                if (output.length() > 0) output.append('\n');
                output.append(owner).append('.').append(methodName);
                if (output.length() >= MAX_STACK_CHARACTERS) {
                    output.setLength(MAX_STACK_CHARACTERS);
                    break;
                }
            }
            return output.toString();
        } catch (Throwable error) {
            return "";
        }
    }

    private static long duration(Object event) {
        try {
            Duration duration = (Duration) invoke(event, "getDuration", new Class<?>[0]);
            return Math.max(0L, duration.toNanos());
        } catch (Throwable error) {
            return 0L;
        }
    }

    private static long longField(Object event, String field, long fallback) {
        try {
            Object value = invoke(event, "getValue", new Class<?>[]{String.class}, field);
            return value instanceof Number ? ((Number) value).longValue() : fallback;
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static String namedField(Object event, String field, String fallback) {
        try {
            Object value = invoke(event, "getValue", new Class<?>[]{String.class}, field);
            if (value == null) return fallback;
            try {
                return String.valueOf(invoke(value, "getName", new Class<?>[0]));
            } catch (Throwable ignored) {
                try {
                    return String.valueOf(invoke(value, "getJavaName", new Class<?>[0]));
                } catch (Throwable ignoredAgain) {
                    return bounded(String.valueOf(value));
                }
            }
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static String textField(Object event, String field, String fallback) {
        try {
            Object value = invoke(event, "getValue", new Class<?>[]{String.class}, field);
            return value == null ? fallback : bounded(String.valueOf(value));
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static void appendMetrics(StringBuilder output, char kind, JfrCategory category,
                                      Map<String, Metric> source) {
        List<Map.Entry<String, Metric>> ordered = new ArrayList<Map.Entry<String, Metric>>(source.entrySet());
        Collections.sort(ordered, new Comparator<Map.Entry<String, Metric>>() {
            @Override public int compare(Map.Entry<String, Metric> left, Map.Entry<String, Metric> right) {
                int value = Long.compare(right.getValue().value, left.getValue().value);
                return value == 0 ? left.getKey().compareTo(right.getKey()) : value;
            }
        });
        int limit = Math.min(MAX_RESULTS, ordered.size());
        for (int index = 0; index < limit; index++) {
            Map.Entry<String, Metric> entry = ordered.get(index);
            output.append(kind).append('\t').append(category).append('\t').append(entry.getValue().value)
                    .append('\t').append(entry.getValue().count).append('\t').append(encoded(entry.getKey())).append('\n');
        }
    }

    private static void enable(Object recording, Set<JfrCategory> categories) throws Exception {
        if (categories.contains(JfrCategory.CPU)) {
            event(recording, "jdk.ExecutionSample", 20L, true);
            event(recording, "jdk.NativeMethodSample", 20L, true);
        }
        if (categories.contains(JfrCategory.ALLOCATIONS)) {
            event(recording, "jdk.ObjectAllocationSample", 0L, true);
        }
        if (categories.contains(JfrCategory.LOCKS)) {
            event(recording, "jdk.JavaMonitorEnter", 1L, true);
            event(recording, "jdk.JavaMonitorWait", 1L, true);
            event(recording, "jdk.ThreadPark", 1L, true);
        }
        if (categories.contains(JfrCategory.EXCEPTIONS)) {
            event(recording, "jdk.JavaExceptionThrow", 0L, true);
            event(recording, "jdk.JavaErrorThrow", 0L, true);
        }
        if (categories.contains(JfrCategory.GC)) {
            event(recording, "jdk.GarbageCollection", 0L, true);
            event(recording, "jdk.GCHeapSummary", 0L, false);
        }
        if (categories.contains(JfrCategory.THREADS)) {
            event(recording, "jdk.ThreadStart", 0L, true);
            event(recording, "jdk.ThreadEnd", 0L, true);
            event(recording, "jdk.ThreadSleep", 1L, true);
        }
        if (categories.contains(JfrCategory.IO)) {
            event(recording, "jdk.FileRead", 1L, true);
            event(recording, "jdk.FileWrite", 1L, true);
            event(recording, "jdk.SocketRead", 1L, true);
            event(recording, "jdk.SocketWrite", 1L, true);
        }
    }

    private static void event(Object recording, String name, long millis, boolean stack) throws Exception {
        Object settings = invoke(recording, "enable", new Class<?>[]{String.class}, name);
        if (stack) optional(settings, "withStackTrace", new Class<?>[0]);
        if (millis > 0L) {
            if (name.endsWith("ExecutionSample") || name.endsWith("NativeMethodSample")) {
                optional(settings, "withPeriod", new Class<?>[]{Duration.class}, Duration.ofMillis(millis));
            } else {
                optional(settings, "withThreshold", new Class<?>[]{Duration.class}, Duration.ofMillis(millis));
            }
        }
    }

    private static Set<JfrCategory> selected(String settings) {
        String value = setting(settings, "categories");
        if (value.isEmpty() || "ALL".equalsIgnoreCase(value)) return EnumSet.allOf(JfrCategory.class);
        Set<JfrCategory> result = EnumSet.noneOf(JfrCategory.class);
        for (String item : value.split(",")) result.add(JfrCategory.valueOf(item.trim().toUpperCase()));
        if (result.isEmpty()) throw new IllegalArgumentException("Select at least one profiler category");
        return result;
    }

    private static int number(String settings, String key, int fallback, int minimum, int maximum) {
        String value = setting(settings, key);
        if (value.isEmpty()) return fallback;
        int parsed = Integer.parseInt(value);
        if (parsed < minimum || parsed > maximum) throw new IllegalArgumentException(key + " is outside its allowed range");
        return parsed;
    }

    private static String setting(String settings, String key) {
        if (settings == null) return "";
        for (String item : settings.split("[;\n]")) {
            int separator = item.indexOf('=');
            if (separator > 0 && key.equals(item.substring(0, separator).trim())) {
                return item.substring(separator + 1).trim();
            }
        }
        return "";
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, arguments);
    }

    private static void optional(Object target, String name, Class<?>[] types, Object... arguments) {
        try {
            invoke(target, name, types, arguments);
        } catch (Throwable ignored) {
        }
    }

    private static boolean present(String type) {
        try {
            Class.forName(type, false, ClassLoader.getSystemClassLoader());
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String bounded(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private static String shortName(String value) {
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String value = current.getMessage();
        return value == null || value.isEmpty() ? current.getClass().getSimpleName() : value;
    }

    private static final class Stats {
        long events;
        long total;
        final Map<String, Metric> paths = new LinkedHashMap<String, Metric>();
        final Map<String, Metric> items = new LinkedHashMap<String, Metric>();

        void add(Map<String, Metric> target, String key, long value) {
            Metric metric = target.get(key);
            if (metric == null && target.size() >= MAX_UNIQUE_METRICS) {
                key = "<other>";
                metric = target.get(key);
            }
            if (metric == null) {
                metric = new Metric();
                target.put(key, metric);
            }
            metric.count++;
            metric.value += value;
        }
    }

    private static final class Metric {
        long value;
        long count;
    }
}

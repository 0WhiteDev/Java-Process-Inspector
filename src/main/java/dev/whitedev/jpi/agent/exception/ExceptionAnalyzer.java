package dev.whitedev.jpi.agent.exception;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class ExceptionAnalyzer implements AutoCloseable {
    private static final int MAX_TYPES = 256;
    private static final int MAX_LOCATIONS = 32;
    private static final int MAX_THREADS = 128;
    private static final int MAX_EVENTS = 50000;
    private static final int MAX_TOTAL_LOCATIONS = 512;
    private static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private final Object dataLock = new Object();
    private final Map<String, Summary> summaries = new LinkedHashMap<>();
    private final Deque<Sample> samples = new ArrayDeque<>();
    private volatile Object stream;
    private volatile String state = "idle";
    private volatile String message = "JFR records exception creation, not every rethrow. Target Java 14+ required.";
    private Thread deadline;
    private long dropped;
    private long generation;
    private long clearedAt;
    private int locationCount;

    public synchronized String start(String settings) throws Exception {
        if (stream != null) throw new IOException("Exception analysis is already running");
        int seconds = duration(settings);
        Class<?> type;
        try {
            type = Class.forName("jdk.jfr.consumer.RecordingStream");
        } catch (ClassNotFoundException error) {
            throw new IOException("Live Exception Analyzer requires JFR and target Java 14 or newer", error);
        }
        final Object recording = type.getConstructor().newInstance();
        clear();
        final long epoch;
        synchronized (dataLock) { epoch = ++generation; }
        try {
            invoke(recording, "setMaxSize", new Class<?>[]{long.class}, 32L * 1024 * 1024);
            invoke(recording, "setMaxAge", new Class<?>[]{Duration.class}, Duration.ofSeconds(60));
            Consumer<Object> consumer = event -> consume(event, epoch);
            String name = "jdk.JavaExceptionThrow";
            Object enabled = invoke(recording, "enable", new Class<?>[]{String.class}, name);
            Class<?> eventSettings = Class.forName("jdk.jfr.EventSettings");
            eventSettings.getMethod("withStackTrace").invoke(enabled);
            eventSettings.getMethod("withoutThreshold").invoke(enabled);
            invoke(recording, "onEvent", new Class<?>[]{String.class, Consumer.class}, name, consumer);
            invoke(recording, "onError", new Class<?>[]{Consumer.class}, (Consumer<Throwable>) error -> {
                if (stream == recording) {
                    state = "recording-error";
                    message = "JFR stream failed: " + error.getClass().getSimpleName();
                }
            });
            stream = recording;
            state = "recording";
            message = "Recording exception creation events. Counts can be incomplete under high load.";
            invoke(recording, "startAsync", new Class<?>[0]);
            deadline = new Thread(() -> {
                try {
                    Thread.sleep(seconds * 1000L);
                    stopIfCurrent(recording);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "JPI Exception Analyzer deadline");
            deadline.setDaemon(true);
            deadline.start();
            return snapshot(30);
        } catch (Exception error) {
            stream = null;
            state = "error";
            try { invoke(recording, "close", new Class<?>[0]); } catch (Exception ignored) { }
            throw error;
        }
    }

    public synchronized String stop() {
        Object current = stream;
        stream = null;
        if (deadline != null && deadline != Thread.currentThread()) deadline.interrupt();
        deadline = null;
        if (current != null) {
            try {
                invoke(current, "close", new Class<?>[0]);
                state = "stopped";
                message = "Recording stopped. JFR delivery is asynchronous; final buffered events may be unavailable.";
            } catch (Exception error) {
                state = "error";
                message = "Unable to close JFR stream: " + error.getClass().getSimpleName();
            }
        }
        return snapshot(30);
    }

    private synchronized void stopIfCurrent(Object expected) {
        if (stream == expected) stop();
    }

    public void clear() {
        synchronized (dataLock) {
            summaries.clear();
            samples.clear();
            dropped = 0;
            locationCount = 0;
            clearedAt = System.currentTimeMillis();
        }
    }

    public String snapshot(int windowSeconds) {
        if (windowSeconds < 1 || windowSeconds > 300) throw new IllegalArgumentException("Window must be between 1 and 300 seconds");
        long now = System.currentTimeMillis();
        synchronized (dataLock) {
            long cutoff = now - windowSeconds * 1000L;
            Map<Summary, Long> counts = new LinkedHashMap<>();
            for (Sample sample : samples) {
                if (sample.timestamp >= cutoff) counts.put(sample.summary, counts.getOrDefault(sample.summary, 0L) + 1);
            }
            StringBuilder out = new StringBuilder();
            long omitted = 0;
            for (Summary summary : summaries.values()) {
                StringBuilder entry = new StringBuilder("E\t").append(encode(summary.type)).append('\t').append(counts.getOrDefault(summary, 0L))
                        .append('\t').append(summary.total).append('\t').append(summary.first).append('\t').append(summary.last)
                        .append('\t').append(summary.threads.size()).append('\t').append(summary.threadOverflow).append('\n');
                if (out.length() + entry.length() > MAX_SNAPSHOT_BYTES) { omitted++; continue; }
                out.append(entry);
                for (Location location : summary.locations.values()) {
                    StringBuilder detail = new StringBuilder("L\t").append(encode(summary.type)).append('\t').append(location.count).append('\t')
                            .append(encode(location.owner)).append('\t').append(encode(location.method)).append('\t')
                            .append(encode(location.descriptor)).append('\t').append(location.line).append('\t')
                            .append(encode(location.stack)).append('\n');
                    if (out.length() + detail.length() > MAX_SNAPSHOT_BYTES) { omitted++; continue; }
                    out.append(detail);
                }
            }
            return "S\t" + now + '\t' + state + '\t' + windowSeconds + '\t' + (dropped + omitted)
                    + '\t' + encode(message) + '\n' + out;
        }
    }

    private void consume(Object event, long epoch) {
        try {
            Object thrown = invoke(event, "getValue", new Class<?>[]{String.class}, "thrownClass");
            String type = (String) invoke(thrown, "getName", new Class<?>[0]);
            Object thread = invoke(event, "getThread", new Class<?>[0]);
            long threadId = thread == null ? -1L : (Long) invoke(thread, "getJavaThreadId", new Class<?>[0]);
            long timestamp = ((Instant) invoke(event, "getStartTime", new Class<?>[0])).toEpochMilli();
            Object trace = invoke(event, "getStackTrace", new Class<?>[0]);
            String owner = "", method = "", descriptor = "";
            int line = -1;
            StringBuilder stack = new StringBuilder();
            if (trace != null) {
                List<?> frames = (List<?>) invoke(trace, "getFrames", new Class<?>[0]);
                int size = Math.min(48, frames.size());
                for (int i = 0; i < size; i++) {
                    Object frame = frames.get(i);
                    Object recordedMethod = invoke(frame, "getMethod", new Class<?>[0]);
                    Object declaringType = invoke(recordedMethod, "getType", new Class<?>[0]);
                    String frameOwner = (String) invoke(declaringType, "getName", new Class<?>[0]);
                    String frameMethod = (String) invoke(recordedMethod, "getName", new Class<?>[0]);
                    int frameLine = (Integer) invoke(frame, "getLineNumber", new Class<?>[0]);
                    stack.append(frameOwner).append('.').append(frameMethod).append(':').append(frameLine).append('\n');
                    if (owner.isEmpty() && !"<init>".equals(frameMethod)
                            && !frameOwner.equals(ExceptionAnalyzer.class.getName())
                            && !frameOwner.equals("java.lang.Throwable")) {
                        owner = frameOwner;
                        method = frameMethod;
                        descriptor = (String) invoke(recordedMethod, "getDescriptor", new Class<?>[0]);
                        line = frameLine;
                    }
                }
            }
            record(epoch, type, timestamp, threadId, owner, method, descriptor, line, stack.toString());
        } catch (Exception ignored) {
            synchronized (dataLock) { dropped++; }
        }
    }

    void record(long epoch, String type, long timestamp, long threadId, String owner, String method,
                String descriptor, int line, String stack) {
        synchronized (dataLock) {
            if (epoch != generation || timestamp < clearedAt) return;
            Summary summary = summaries.get(type);
            if (summary == null) {
                if (summaries.size() >= MAX_TYPES) { dropped++; return; }
                summary = new Summary(type, timestamp);
                summaries.put(type, summary);
            }
            summary.total++;
            summary.first = Math.min(summary.first, timestamp);
            summary.last = Math.max(summary.last, timestamp);
            if (!summary.threads.contains(threadId)) {
                if (summary.threads.size() < MAX_THREADS) summary.threads.add(threadId);
                else summary.threadOverflow = true;
            }
            String key = owner + '\n' + method + descriptor + ':' + line;
            Location location = summary.locations.get(key);
            if (location == null && summary.locations.size() < MAX_LOCATIONS && locationCount < MAX_TOTAL_LOCATIONS) {
                location = new Location(owner, method, descriptor, line,
                        stack.length() > 8192 ? stack.substring(0, 8192) : stack);
                summary.locations.put(key, location);
                locationCount++;
            }
            if (location != null) location.count++;
            else dropped++;
            while (!samples.isEmpty() && samples.peekFirst().timestamp < timestamp - 300000L) samples.removeFirst();
            if (samples.size() >= MAX_EVENTS) { samples.removeFirst(); dropped++; }
            samples.addLast(new Sample(summary, timestamp));
        }
    }

    long generation() { synchronized (dataLock) { return generation; } }

    @Override public void close() { stop(); }

    private static int duration(String settings) throws IOException {
        for (String part : settings.split("[;\n]")) {
            if (!part.startsWith("durationSeconds=")) continue;
            try {
                int value = Integer.parseInt(part.substring(16));
                if (value >= 1 && value <= 3600) return value;
            } catch (NumberFormatException ignored) { }
            throw new IOException("durationSeconds must be between 1 and 3600");
        }
        return 60;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = target.getClass().getMethod(name, types);
        return method.invoke(target, arguments);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Summary {
        final String type;
        final Set<Long> threads = new LinkedHashSet<>();
        final Map<String, Location> locations = new LinkedHashMap<>();
        long first, last, total;
        boolean threadOverflow;
        Summary(String type, long timestamp) { this.type = type; first = last = timestamp; }
    }

    private static final class Location {
        final String owner, method, descriptor, stack;
        final int line;
        long count;
        Location(String owner, String method, String descriptor, int line, String stack) {
            this.owner = owner; this.method = method; this.descriptor = descriptor; this.line = line; this.stack = stack;
        }
    }

    private static final class Sample {
        final Summary summary;
        final long timestamp;
        Sample(Summary summary, long timestamp) { this.summary = summary; this.timestamp = timestamp; }
    }
}

package dev.whitedev.jpi.agent.field;

import dev.whitedev.jpi.agent.trace.TraceRuntime;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public final class FieldWriteRuntime {
    private static final int MAX_QUEUED_EVENTS = 10_000;
    private static final ConcurrentMap<String, ProbeState> STATES = new ConcurrentHashMap<String, ProbeState>();
    private static final ArrayDeque<WriteEvent> EVENTS = new ArrayDeque<WriteEvent>();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private FieldWriteRuntime() { }

    public static void record(String probeId, String writerClass, String writerMethod, String writerDescriptor,
                              String opcode, int line, Object receiver, Object previous, Object current) {
        try {
            ProbeState state = STATES.get(probeId);
            if (state == null || !state.active) return;
            state.calls.incrementAndGet();
            if (!state.acquire()) {
                state.dropped.incrementAndGet();
                return;
            }
            FieldWriteConfig config = state.probe.config;
            StackTraceElement[] frames = Thread.currentThread().getStackTrace();
            WriteEvent event = new WriteEvent(SEQUENCE.incrementAndGet(), System.currentTimeMillis(), state.probe,
                    Thread.currentThread().getName(), writerClass, writerMethod, writerDescriptor, opcode, line,
                    value(previous, config, 0), value(current, config, 0), identity(receiver),
                    stack(frames, writerClass, writerMethod, config.maxStackDepth), TraceRuntime.currentCallId());
            synchronized (EVENTS) {
                EVENTS.addLast(event);
                while (EVENTS.size() > MAX_QUEUED_EVENTS) EVENTS.removeFirst();
            }
        } catch (Throwable ignored) {
        }
    }

    static void register(FieldWriteProbe probe) {
        ProbeState state = new ProbeState(probe);
        if (STATES.putIfAbsent(probe.id, state) != null) {
            throw new IllegalArgumentException("Field write probe already exists: " + probe.id);
        }
    }

    static void unregister(String probeId) {
        ProbeState state = STATES.remove(probeId);
        if (state != null) state.active = false;
    }

    static String statusAndDrain() {
        StringBuilder output = new StringBuilder();
        for (ProbeState state : STATES.values()) {
            FieldWriteProbe probe = state.probe;
            output.append('S').append('\t').append(probe.id).append('\t').append(state.active).append('\t')
                    .append(state.calls.get()).append('\t').append(state.captured.get()).append('\t')
                    .append(state.dropped.get()).append('\t').append(probe.expiresAt).append('\t')
                    .append(probe.classes).append('\t').append(probe.sites).append('\t')
                    .append(encoded(probe.targetIdentifier)).append('\t').append(encoded(probe.owner)).append('\t')
                    .append(encoded(probe.fieldName)).append('\t').append(encoded(probe.descriptor)).append('\n');
        }
        synchronized (EVENTS) {
            while (!EVENTS.isEmpty()) output.append(EVENTS.removeFirst().line()).append('\n');
        }
        return output.toString();
    }

    static void clear() {
        for (ProbeState state : STATES.values()) state.active = false;
        STATES.clear();
        synchronized (EVENTS) {
            EVENTS.clear();
        }
    }

    private static String value(Object value, FieldWriteConfig config, int depth) {
        if (value == null) return "null";
        if (value instanceof String) return limited("\"" + escaped((String) value) + "\"", config.maxValueLength);
        if (value instanceof Character) return "'" + escaped(String.valueOf(value)) + "'";
        if (value instanceof Enum<?>) {
            Enum<?> constant = (Enum<?>) value;
            return constant.getDeclaringClass().getName() + "." + constant.name();
        }
        Class<?> type = value.getClass();
        if (value instanceof Boolean || type == Byte.class || type == Short.class || type == Integer.class
                || type == Long.class || type == Float.class || type == Double.class) {
            return limited(String.valueOf(value), config.maxValueLength);
        }
        if (type.isArray()) {
            int length = Array.getLength(value);
            int shown = Math.min(length, 16);
            StringBuilder output = new StringBuilder(type.getComponentType().getTypeName())
                    .append('[').append(length).append("] {");
            if (depth < 1) {
                for (int index = 0; index < shown; index++) {
                    if (index > 0) output.append(", ");
                    output.append(value(Array.get(value, index), config, depth + 1));
                    if (output.length() >= config.maxValueLength) break;
                }
                if (shown < length) output.append(", ...");
            }
            return limited(output.append('}').toString(), config.maxValueLength);
        }
        return limited(type.getName() + "@" + Integer.toHexString(System.identityHashCode(value)),
                config.maxValueLength);
    }

    private static String identity(Object value) {
        return value == null ? "static" : value.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(value));
    }

    private static String stack(StackTraceElement[] frames, String writerClass,
                                String writerMethod, int maximum) {
        List<String> selected = new ArrayList<String>();
        boolean writerSkipped = false;
        for (StackTraceElement frame : frames) {
            String owner = frame.getClassName();
            if (owner.equals(Thread.class.getName()) || owner.equals(FieldWriteRuntime.class.getName())) continue;
            if (!writerSkipped && owner.equals(writerClass) && frame.getMethodName().equals(writerMethod)) {
                writerSkipped = true;
                continue;
            }
            selected.add(frame.toString());
            if (selected.size() >= maximum) break;
        }
        StringBuilder output = new StringBuilder();
        for (String frame : selected) {
            if (output.length() > 0) output.append('\n');
            output.append(frame);
        }
        return output.toString();
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r")
                .replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String limited(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, Math.max(0, maximum - 3)) + "...";
    }

    private static String encoded(String value) {
        if (value == null || value.isEmpty()) return "";
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class ProbeState {
        final FieldWriteProbe probe;
        final AtomicLong calls = new AtomicLong();
        final AtomicLong captured = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
        volatile boolean active = true;
        private long rateWindow;
        private int rateCount;

        ProbeState(FieldWriteProbe probe) {
            this.probe = probe;
        }

        synchronized boolean acquire() {
            if (captured.get() >= probe.config.maxEvents) return false;
            long current = System.currentTimeMillis() / 1000L;
            if (current != rateWindow) {
                rateWindow = current;
                rateCount = 0;
            }
            if (rateCount >= probe.config.rateLimit) return false;
            rateCount++;
            captured.incrementAndGet();
            return true;
        }
    }

    private static final class WriteEvent {
        final long sequence;
        final long timestamp;
        final FieldWriteProbe probe;
        final String thread;
        final String writerClass;
        final String writerMethod;
        final String writerDescriptor;
        final String opcode;
        final int line;
        final String previous;
        final String current;
        final String receiver;
        final String stack;
        final long callId;

        WriteEvent(long sequence, long timestamp, FieldWriteProbe probe, String thread, String writerClass,
                   String writerMethod, String writerDescriptor, String opcode, int line, String previous,
                   String current, String receiver, String stack, long callId) {
            this.sequence = sequence;
            this.timestamp = timestamp;
            this.probe = probe;
            this.thread = thread;
            this.writerClass = writerClass;
            this.writerMethod = writerMethod;
            this.writerDescriptor = writerDescriptor;
            this.opcode = opcode;
            this.line = line;
            this.previous = previous;
            this.current = current;
            this.receiver = receiver;
            this.stack = stack;
            this.callId = callId;
        }

        String line() {
            return new StringBuilder().append('E').append('\t').append(sequence).append('\t')
                    .append(timestamp).append('\t').append(probe.id).append('\t').append(encoded(thread)).append('\t')
                    .append(encoded(probe.owner)).append('\t').append(encoded(probe.fieldName)).append('\t')
                    .append(encoded(probe.descriptor)).append('\t').append(encoded(writerClass)).append('\t')
                    .append(encoded(writerMethod)).append('\t').append(encoded(writerDescriptor)).append('\t')
                    .append(line).append('\t').append(encoded(previous)).append('\t').append(encoded(current)).append('\t')
                    .append(encoded(receiver)).append('\t').append(encoded(stack)).append('\t').append(callId).append('\t')
                    .append(opcode).toString();
        }
    }
}

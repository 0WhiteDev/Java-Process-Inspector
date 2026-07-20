package dev.whitedev.jpi.agent.trace;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class TraceRuntime {
    private static final int MAX_QUEUED_EVENTS = 5000;
    private static final int MAX_GRAPH_EDGES = 20000;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Map<String, ProbeState> STATES = new ConcurrentHashMap<String, ProbeState>();
    private static final Map<Long, ActiveCall> CALLS = new ConcurrentHashMap<Long, ActiveCall>();
    private static final Map<EdgeKey, DynamicEdge> GRAPH = new ConcurrentHashMap<EdgeKey, DynamicEdge>();
    private static final ArrayDeque<TraceEvent> EVENTS = new ArrayDeque<TraceEvent>();
    private static final ThreadLocal<ArrayDeque<Long>> CALL_STACK = new ThreadLocal<ArrayDeque<Long>>() {
        @Override protected ArrayDeque<Long> initialValue() {
            return new ArrayDeque<Long>();
        }
    };

    private TraceRuntime() {}

    public static long enter(String probeId, Object receiver, Object[] arguments) {
        try {
            ProbeState state = STATES.get(probeId);
            if (state == null || !state.active) return 0L;
            long callNumber = state.calls.incrementAndGet();
            if ((callNumber - 1L) % state.probe.config.sampleEvery != 0L) {
                state.dropped.incrementAndGet();
                return 0L;
            }
            if (!state.acquireRateSlot() || state.limitReached()) {
                state.dropped.incrementAndGet();
                return 0L;
            }

            long token = SEQUENCE.incrementAndGet();
            ArrayDeque<Long> stack = CALL_STACK.get();
            Long parent = stack.peekLast();
            Thread thread = Thread.currentThread();
            StackTraceElement[] frames = thread.getStackTrace();
            ActiveCall call = new ActiveCall(token, parent == null ? 0L : parent.longValue(), state,
                    receiver, arguments == null ? new Object[0] : arguments,
                    System.currentTimeMillis(), System.nanoTime(), thread.getName(),
                    state.probe.config.captureStack ? stackTrace(frames, state.probe.config.maxStackDepth) : "");
            CALLS.put(Long.valueOf(token), call);
            recordIncoming(call, frames);
            stack.addLast(Long.valueOf(token));
            return token;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static void exit(long token, Object returnValue) {
        complete(token, returnValue, null);
    }

    public static void fail(long token, Throwable exception) {
        complete(token, null, exception);
    }

    public static void edge(long token, String owner, String method, String descriptor, boolean reflective) {
        if (token == 0L) return;
        try {
            ActiveCall call = CALLS.get(Long.valueOf(token));
            if (call == null) return;
            TraceProbe probe = call.state.probe;
            DynamicEdge value = edge(probe.className, probe.methodName, probe.descriptor,
                    owner.replace('/', '.'), method, descriptor, reflective);
            if (value != null) call.lastEdge = value;
        } catch (Throwable ignored) {
        }
    }

    static void register(TraceProbe probe) {
        List<TraceCondition> conditions = TraceCondition.parse(probe.config.condition);
        ProbeState state = new ProbeState(probe, conditions);
        if (STATES.putIfAbsent(probe.id, state) != null) {
            throw new IllegalArgumentException("Trace probe already exists: " + probe.id);
        }
    }

    static void unregister(String probeId) {
        ProbeState state = STATES.remove(probeId);
        if (state != null) state.active = false;
    }

    static String statusAndDrain() {
        StringBuilder output = new StringBuilder();
        for (ProbeState state : STATES.values()) {
            TraceProbe probe = state.probe;
            output.append('S').append('\t').append(probe.id).append('\t')
                    .append(state.calls.get()).append('\t')
                    .append(state.captured.get()).append('\t')
                    .append(state.dropped.get()).append('\t')
                    .append(probe.expiresAt).append('\t')
                    .append(encoded(probe.targetIdentifier)).append('\t')
                    .append(encoded(probe.className)).append('\t')
                    .append(encoded(probe.methodName)).append('\t')
                    .append(encoded(probe.descriptor)).append('\n');
        }
        synchronized (EVENTS) {
            while (!EVENTS.isEmpty()) output.append(EVENTS.removeFirst().line()).append('\n');
        }
        return output.toString();
    }

    public static String dynamicGraph(String className, String method, String descriptor) {
        StringBuilder output = new StringBuilder();
        for (DynamicEdge edge : GRAPH.values()) {
            String layer = edge.failures.get() > 0L ? "FAILED" : edge.reflective ? "REFLECTIVE" : "DYNAMIC";
            if (className.equals(edge.callerClass) && method.equals(edge.callerMethod)
                    && descriptor.equals(edge.callerDescriptor)) {
                appendGraph(output, layer, "CALLS", edge.count.get(), edge.calleeClass,
                        edge.calleeClass, edge.calleeMethod, edge.calleeDescriptor, "observed");
            }
            if (className.equals(edge.calleeClass) && method.equals(edge.calleeMethod)
                    && (descriptor.equals(edge.calleeDescriptor) || edge.calleeDescriptor.isEmpty())) {
                appendGraph(output, layer, "CALLED_BY", edge.count.get(), edge.callerClass,
                        edge.callerClass, edge.callerMethod, edge.callerDescriptor, "observed");
            }
        }
        return output.toString();
    }

    static void clear() {
        for (ProbeState state : STATES.values()) state.active = false;
        STATES.clear();
        CALLS.clear();
        GRAPH.clear();
        synchronized (EVENTS) {
            EVENTS.clear();
        }
        CALL_STACK.remove();
    }

    private static void complete(long token, Object returnValue, Throwable exception) {
        if (token == 0L) return;
        try {
            ActiveCall call = CALLS.remove(Long.valueOf(token));
            removeFromStack(token);
            if (call == null) return;
            long duration = Math.max(0L, System.nanoTime() - call.startedNanos);
            if (exception != null && call.lastEdge != null) call.lastEdge.failures.incrementAndGet();
            TraceCondition.Context context = new TraceCondition.Context(call.receiver, call.arguments,
                    returnValue, exception, duration, call.threadName);
            if (!TraceCondition.matches(call.state.conditions, context)) {
                call.state.dropped.incrementAndGet();
                return;
            }
            if (!call.state.acquireCaptureSlot()) {
                call.state.dropped.incrementAndGet();
                return;
            }

            TraceConfig config = call.state.probe.config;
            String receiver = config.captureIdentity ? value(call.receiver, config, 0) : "";
            String arguments = config.captureArguments ? arguments(call.arguments, config) : "";
            String result = config.captureReturn && exception == null ? value(returnValue, config, 0) : "";
            String failure = config.captureException && exception != null ? throwable(exception, config) : "";
            String thread = config.captureThread ? call.threadName : "";
            long reportedDuration = config.captureDuration ? duration : -1L;
            TraceEvent event = new TraceEvent(call.token, call.parent, call.startedMillis,
                    call.state.probe, reportedDuration, exception == null ? "return" : "throw",
                    thread, receiver, arguments, result, failure, call.stack);
            synchronized (EVENTS) {
                EVENTS.addLast(event);
                while (EVENTS.size() > MAX_QUEUED_EVENTS) EVENTS.removeFirst();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void removeFromStack(long token) {
        ArrayDeque<Long> stack = CALL_STACK.get();
        Long last = stack.peekLast();
        if (last != null && last.longValue() == token) stack.removeLast();
        else stack.remove(Long.valueOf(token));
        if (stack.isEmpty()) CALL_STACK.remove();
    }

    private static String arguments(Object[] values, TraceConfig config) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) output.append('\n');
            output.append('$').append(index + 1).append(" = ").append(value(values[index], config, 0));
            if (output.length() >= config.maxValueLength) return limited(output.toString(), config.maxValueLength);
        }
        return output.toString();
    }

    private static String value(Object value, TraceConfig config, int depth) {
        if (value == null) return "null";
        if (value instanceof String) return limited("\"" + escaped((String) value) + "\"", config.maxValueLength);
        if (value instanceof Character) return "'" + escaped(String.valueOf(value)) + "'";
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return limited(String.valueOf(value), config.maxValueLength);
        }
        if (value instanceof byte[]) return bytes((byte[]) value, config);

        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            int shown = Math.min(length, config.maxArrayElements);
            StringBuilder output = new StringBuilder(type.getComponentType().getTypeName())
                    .append('[').append(length).append("] {");
            if (depth < 2) {
                for (int index = 0; index < shown; index++) {
                    if (index > 0) output.append(", ");
                    output.append(value(Array.get(value, index), config, depth + 1));
                    if (output.length() >= config.maxValueLength) break;
                }
                if (shown < length) output.append(", ...");
            }
            return limited(output.append('}').toString(), config.maxValueLength);
        }

        String identity = config.captureIdentity
                ? "@" + Integer.toHexString(System.identityHashCode(value)) : "";
        return limited(type.getName() + identity, config.maxValueLength);
    }

    private static String bytes(byte[] value, TraceConfig config) {
        int shown = Math.min(value.length, config.maxArrayElements);
        StringBuilder output = new StringBuilder("byte[").append(value.length).append("] = ");
        for (int index = 0; index < shown; index++) {
            if (index > 0) output.append(' ');
            int current = value[index] & 0xff;
            if (current < 16) output.append('0');
            output.append(Integer.toHexString(current).toUpperCase());
        }
        if (shown < value.length) output.append(" ...");
        return limited(output.toString(), config.maxValueLength);
    }

    private static String throwable(Throwable throwable, TraceConfig config) {
        String message;
        try {
            message = throwable.getMessage();
        } catch (Throwable ignored) {
            message = null;
        }
        return limited(throwable.getClass().getName() + (message == null ? "" : ": " + escaped(message)),
                config.maxValueLength);
    }

    private static String stackTrace(StackTraceElement[] frames, int maximum) {
        List<String> selected = new ArrayList<String>();
        for (StackTraceElement frame : frames) {
            String owner = frame.getClassName();
            if (Thread.class.getName().equals(owner) || TraceRuntime.class.getName().equals(owner)) continue;
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

    private static void recordIncoming(ActiveCall call, StackTraceElement[] frames) {
        TraceProbe probe = call.state.probe;
        boolean seenTarget = false;
        boolean reflective = false;
        for (StackTraceElement frame : frames) {
            String owner = frame.getClassName();
            if (owner.equals(probe.className) && frame.getMethodName().equals(probe.methodName)) {
                seenTarget = true;
                continue;
            }
            if (!seenTarget) continue;
            if (isRuntimeFrame(owner)) continue;
            if (isReflective(owner)) {
                reflective = true;
                continue;
            }
            edge(owner, frame.getMethodName(), "", probe.className, probe.methodName,
                    probe.descriptor, reflective);
            return;
        }
    }

    private static boolean isRuntimeFrame(String owner) {
        return owner.equals(Thread.class.getName()) || owner.equals(TraceRuntime.class.getName());
    }

    private static boolean isReflective(String owner) {
        return owner.startsWith("java.lang.reflect.") || owner.startsWith("jdk.internal.reflect.")
                || owner.startsWith("java.lang.invoke.");
    }

    private static DynamicEdge edge(String callerClass, String callerMethod, String callerDescriptor,
                                    String calleeClass, String calleeMethod, String calleeDescriptor,
                                    boolean reflective) {
        EdgeKey key = new EdgeKey(callerClass, callerMethod, callerDescriptor,
                calleeClass, calleeMethod, calleeDescriptor, reflective);
        DynamicEdge current = GRAPH.get(key);
        if (current == null) {
            if (GRAPH.size() >= MAX_GRAPH_EDGES) return null;
            DynamicEdge created = new DynamicEdge(key);
            DynamicEdge raced = GRAPH.putIfAbsent(key, created);
            current = raced == null ? created : raced;
        }
        current.count.incrementAndGet();
        return current;
    }

    private static void appendGraph(StringBuilder output, String layer, String relation, long count,
                                    String targetIdentifier, String className, String member,
                                    String descriptor, String detail) {
        output.append('R').append('\t').append(layer).append('\t').append(relation).append('\t')
                .append(count).append('\t').append(encoded(targetIdentifier)).append('\t')
                .append(encoded(className)).append('\t').append(encoded(member)).append('\t')
                .append(encoded(descriptor)).append('\t').append(encoded(detail)).append('\n');
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r")
                .replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String limited(String value, int maximum) {
        if (value.length() <= maximum) return value;
        return value.substring(0, Math.max(0, maximum - 3)) + "...";
    }

    private static String encoded(String value) {
        if (value == null || value.isEmpty()) return "";
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class ProbeState {
        final TraceProbe probe;
        final List<TraceCondition> conditions;
        final AtomicLong calls = new AtomicLong();
        final AtomicLong captured = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
        volatile boolean active = true;
        private long rateWindow;
        private int rateCount;

        ProbeState(TraceProbe probe, List<TraceCondition> conditions) {
            this.probe = probe;
            this.conditions = conditions;
        }

        synchronized boolean acquireRateSlot() {
            long current = System.currentTimeMillis() / 1000L;
            if (current != rateWindow) {
                rateWindow = current;
                rateCount = 0;
            }
            if (rateCount >= probe.config.rateLimit) return false;
            rateCount++;
            return true;
        }

        boolean limitReached() {
            return captured.get() >= probe.config.maxEvents;
        }

        synchronized boolean acquireCaptureSlot() {
            if (captured.get() >= probe.config.maxEvents) return false;
            captured.incrementAndGet();
            return true;
        }
    }

    private static final class ActiveCall {
        final long token;
        final long parent;
        final ProbeState state;
        final Object receiver;
        final Object[] arguments;
        final long startedMillis;
        final long startedNanos;
        final String threadName;
        final String stack;
        volatile DynamicEdge lastEdge;

        ActiveCall(long token, long parent, ProbeState state, Object receiver, Object[] arguments,
                   long startedMillis, long startedNanos, String threadName, String stack) {
            this.token = token;
            this.parent = parent;
            this.state = state;
            this.receiver = receiver;
            this.arguments = arguments;
            this.startedMillis = startedMillis;
            this.startedNanos = startedNanos;
            this.threadName = threadName;
            this.stack = stack;
        }
    }

    private static final class EdgeKey {
        final String callerClass;
        final String callerMethod;
        final String callerDescriptor;
        final String calleeClass;
        final String calleeMethod;
        final String calleeDescriptor;
        final boolean reflective;

        EdgeKey(String callerClass, String callerMethod, String callerDescriptor,
                String calleeClass, String calleeMethod, String calleeDescriptor, boolean reflective) {
            this.callerClass = callerClass;
            this.callerMethod = callerMethod;
            this.callerDescriptor = callerDescriptor;
            this.calleeClass = calleeClass;
            this.calleeMethod = calleeMethod;
            this.calleeDescriptor = calleeDescriptor;
            this.reflective = reflective;
        }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof EdgeKey)) return false;
            EdgeKey other = (EdgeKey) value;
            return reflective == other.reflective && callerClass.equals(other.callerClass)
                    && callerMethod.equals(other.callerMethod) && callerDescriptor.equals(other.callerDescriptor)
                    && calleeClass.equals(other.calleeClass) && calleeMethod.equals(other.calleeMethod)
                    && calleeDescriptor.equals(other.calleeDescriptor);
        }

        @Override public int hashCode() {
            int result = callerClass.hashCode();
            result = 31 * result + callerMethod.hashCode();
            result = 31 * result + callerDescriptor.hashCode();
            result = 31 * result + calleeClass.hashCode();
            result = 31 * result + calleeMethod.hashCode();
            result = 31 * result + calleeDescriptor.hashCode();
            return 31 * result + (reflective ? 1 : 0);
        }
    }

    private static final class DynamicEdge {
        final String callerClass;
        final String callerMethod;
        final String callerDescriptor;
        final String calleeClass;
        final String calleeMethod;
        final String calleeDescriptor;
        final boolean reflective;
        final AtomicLong count = new AtomicLong();
        final AtomicLong failures = new AtomicLong();

        DynamicEdge(EdgeKey key) {
            callerClass = key.callerClass;
            callerMethod = key.callerMethod;
            callerDescriptor = key.callerDescriptor;
            calleeClass = key.calleeClass;
            calleeMethod = key.calleeMethod;
            calleeDescriptor = key.calleeDescriptor;
            reflective = key.reflective;
        }
    }

    private static final class TraceEvent {
        final long sequence;
        final long parent;
        final long timestamp;
        final TraceProbe probe;
        final long duration;
        final String outcome;
        final String thread;
        final String receiver;
        final String arguments;
        final String result;
        final String exception;
        final String stack;

        TraceEvent(long sequence, long parent, long timestamp, TraceProbe probe, long duration,
                   String outcome, String thread, String receiver, String arguments, String result,
                   String exception, String stack) {
            this.sequence = sequence;
            this.parent = parent;
            this.timestamp = timestamp;
            this.probe = probe;
            this.duration = duration;
            this.outcome = outcome;
            this.thread = thread;
            this.receiver = receiver;
            this.arguments = arguments;
            this.result = result;
            this.exception = exception;
            this.stack = stack;
        }

        String line() {
            return new StringBuilder().append('E').append('\t')
                    .append(sequence).append('\t').append(parent).append('\t')
                    .append(timestamp).append('\t').append(probe.id).append('\t')
                    .append(duration).append('\t').append(outcome).append('\t')
                    .append(encoded(probe.targetIdentifier)).append('\t')
                    .append(encoded(probe.className)).append('\t')
                    .append(encoded(probe.methodName)).append('\t')
                    .append(encoded(probe.descriptor)).append('\t')
                    .append(encoded(thread)).append('\t')
                    .append(encoded(receiver)).append('\t')
                    .append(encoded(arguments)).append('\t')
                    .append(encoded(result)).append('\t')
                    .append(encoded(exception)).append('\t')
                    .append(encoded(stack)).toString();
        }
    }
}
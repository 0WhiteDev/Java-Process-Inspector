package dev.whitedev.jpi.agent.file;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

public final class FileEventCollector {
    private final AtomicLong ids = new AtomicLong();
    private final ArrayDeque<Event> events = new ArrayDeque<Event>();
    private volatile int limit = 5000;
    private volatile long dropped;

    void configure(int eventLimit) {
        limit = eventLimit;
        clear();
    }

    void add(FileOperation operation, String path, String normalizedPath, String callerClass,
             String callerMethod, String callerDescriptor, String thread, FileDecision decision,
             long requestedBytes, String redirectedPath, String error, String stack, String preview,
             long callId) {
        Event event = new Event(ids.incrementAndGet(), System.currentTimeMillis(), operation, path, normalizedPath,
                callerClass, callerMethod, callerDescriptor, thread, decision, requestedBytes, redirectedPath,
                error, stack, preview, callId);
        synchronized (events) {
            if (events.size() >= limit) {
                events.removeFirst();
                dropped++;
            }
            events.addLast(event);
        }
    }

    String drain() {
        StringBuilder output = new StringBuilder();
        synchronized (events) {
            output.append('S').append('\t').append(dropped).append('\t').append(events.size()).append('\n');
            while (!events.isEmpty()) output.append(events.removeFirst().line()).append('\n');
        }
        return output.toString();
    }

    void clear() {
        synchronized (events) {
            events.clear();
            dropped = 0L;
        }
    }

    private static String encoded(String value) {
        String safe = value == null ? "" : value;
        return Base64.getEncoder().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Event {
        final long id;
        final long timestamp;
        final FileOperation operation;
        final String path;
        final String normalizedPath;
        final String callerClass;
        final String callerMethod;
        final String callerDescriptor;
        final String thread;
        final FileDecision decision;
        final long requestedBytes;
        final String redirectedPath;
        final String error;
        final String stack;
        final String preview;
        final long callId;

        Event(long id, long timestamp, FileOperation operation, String path, String normalizedPath,
              String callerClass, String callerMethod, String callerDescriptor, String thread,
              FileDecision decision, long requestedBytes, String redirectedPath, String error,
              String stack, String preview, long callId) {
            this.id = id;
            this.timestamp = timestamp;
            this.operation = operation;
            this.path = path;
            this.normalizedPath = normalizedPath;
            this.callerClass = callerClass;
            this.callerMethod = callerMethod;
            this.callerDescriptor = callerDescriptor;
            this.thread = thread;
            this.decision = decision;
            this.requestedBytes = requestedBytes;
            this.redirectedPath = redirectedPath;
            this.error = error;
            this.stack = stack;
            this.preview = preview;
            this.callId = callId;
        }

        String line() {
            return new StringBuilder().append('E').append('\t').append(id).append('\t').append(timestamp).append('\t')
                    .append(operation).append('\t').append(encoded(path)).append('\t').append(encoded(normalizedPath))
                    .append('\t').append(encoded(callerClass)).append('\t').append(encoded(callerMethod)).append('\t')
                    .append(encoded(callerDescriptor)).append('\t').append(encoded(thread)).append('\t')
                    .append(decision).append('\t').append(requestedBytes).append('\t').append(encoded(redirectedPath))
                    .append('\t').append(encoded(error)).append('\t').append(encoded(stack)).append('\t')
                    .append(encoded(preview)).append('\t').append(callId).toString();
        }
    }
}

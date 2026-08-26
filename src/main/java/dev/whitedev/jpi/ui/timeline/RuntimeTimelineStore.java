package dev.whitedev.jpi.ui.timeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RuntimeTimelineStore {
    private static final int DEFAULT_LIMIT = 20_000;
    private static final long THREADED_WINDOW_MILLIS = 2_000L;
    private static final long UNTHREADED_WINDOW_MILLIS = 350L;
    private final int limit;
    private final LinkedHashMap<String, TimelineEvent> events = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private String sessionId = "";

    public RuntimeTimelineStore() {
        this(DEFAULT_LIMIT);
    }

    RuntimeTimelineStore(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Timeline limit must be positive");
        this.limit = limit;
    }

    public void beginSession(String sessionId) {
        String next = sessionId == null ? "" : sessionId;
        synchronized (this) {
            if (this.sessionId.equals(next)) return;
            this.sessionId = next;
            events.clear();
        }
        notifyListeners();
    }

    public void publish(TimelineEvent event) {
        boolean changed;
        synchronized (this) {
            changed = !events.containsKey(event.key());
            if (changed) {
                events.put(event.key(), event);
                while (events.size() > limit) events.remove(events.keySet().iterator().next());
            }
        }
        if (changed) notifyListeners();
    }

    public void clear() {
        synchronized (this) {
            events.clear();
        }
        notifyListeners();
    }

    public synchronized List<CorrelatedEvent> snapshot() {
        List<TimelineEvent> ordered = new ArrayList<>(events.values());
        ordered.sort(Comparator.comparingLong(TimelineEvent::timestamp).thenComparing(TimelineEvent::key));
        Map<String, TimelineEvent> calls = new LinkedHashMap<>();
        Map<String, List<TimelineEvent>> callsByThread = new LinkedHashMap<>();
        List<TimelineEvent> allCalls = new ArrayList<>();
        for (TimelineEvent event : ordered) {
            if (!event.callId().isEmpty() && event.source() == TimelineSource.TRACE
                    && !calls.containsKey(event.callId())) {
                calls.put(event.callId(), event);
                allCalls.add(event);
                if (!event.thread().isEmpty()) {
                    callsByThread.computeIfAbsent(event.thread(), ignored -> new ArrayList<>()).add(event);
                }
            }
        }
        Map<String, String> roots = new LinkedHashMap<>();
        List<CorrelatedEvent> output = new ArrayList<>(ordered.size());
        for (TimelineEvent event : ordered) {
            String correlation = directCorrelation(event, calls, roots);
            String basis = correlation.isEmpty() ? "" : "call ID";
            if (correlation.isEmpty()) {
                List<TimelineEvent> candidates = event.thread().isEmpty()
                        ? allCalls : callsByThread.getOrDefault(event.thread(), List.of());
                TimelineEvent nearest = nearestCall(event, candidates);
                if (nearest != null) {
                    correlation = "#" + rootCall(nearest.callId(), calls, roots);
                    basis = event.thread().isEmpty() ? "time" : "thread + time";
                }
            }
            output.add(new CorrelatedEvent(event, correlation, basis));
        }
        return List.copyOf(output);
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private String directCorrelation(TimelineEvent event, Map<String, TimelineEvent> calls,
                                     Map<String, String> roots) {
        return event.callId().isEmpty() ? "" : "#" + rootCall(event.callId(), calls, roots);
    }

    private String rootCall(String callId, Map<String, TimelineEvent> calls, Map<String, String> roots) {
        String known = roots.get(callId);
        if (known != null) return known;
        String current = callId;
        List<String> path = new ArrayList<>();
        for (int depth = 0; depth < 256; depth++) {
            path.add(current);
            TimelineEvent event = calls.get(current);
            if (event == null || event.parentCallId().isEmpty() || "0".equals(event.parentCallId())
                    || event.parentCallId().equals(current)) break;
            String parentRoot = roots.get(event.parentCallId());
            if (parentRoot != null) {
                current = parentRoot;
                break;
            }
            current = event.parentCallId();
        }
        for (String item : path) roots.put(item, current);
        return current;
    }

    private TimelineEvent nearestCall(TimelineEvent event, List<TimelineEvent> calls) {
        if (calls.isEmpty()) return null;
        boolean threaded = !event.thread().isEmpty();
        long window = threaded ? THREADED_WINDOW_MILLIS : UNTHREADED_WINDOW_MILLIS;
        if (event.source() == TimelineSource.MARKER) window = THREADED_WINDOW_MILLIS;
        int low = 0;
        int high = calls.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (calls.get(middle).timestamp() < event.timestamp()) low = middle + 1;
            else high = middle;
        }
        TimelineEvent before = low > 0 ? calls.get(low - 1) : null;
        TimelineEvent after = low < calls.size() ? calls.get(low) : null;
        TimelineEvent nearest = before;
        if (nearest == null || after != null && Math.abs(after.timestamp() - event.timestamp())
                < Math.abs(nearest.timestamp() - event.timestamp())) nearest = after;
        return nearest != null && Math.abs(nearest.timestamp() - event.timestamp()) <= window ? nearest : null;
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) listener.run();
    }

    public record CorrelatedEvent(TimelineEvent event, String correlation, String correlationBasis) { }
}

package dev.whitedev.jpi.agent.hook;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ApiHookRuntime {
    private static final int MAX_QUEUED_EVENTS = 5000;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Map<String, ProfileState> STATES = new ConcurrentHashMap<String, ProfileState>();
    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<Event>();

    private ApiHookRuntime() {}

    public static void hit(String profile, String callerClass, String callerMethod, String callerDescriptor,
                           String apiClass, String apiMethod, String apiDescriptor, String invocationKind) {
        try {
            ProfileState state = STATES.get(profile);
            if (state == null || !state.active || System.currentTimeMillis() >= state.expiresAt) return;
            state.calls.incrementAndGet();
            if (!state.acquire()) {
                state.dropped.incrementAndGet();
                return;
            }
            long number = SEQUENCE.incrementAndGet();
            Event event = new Event(number, System.currentTimeMillis(), profile,
                    Thread.currentThread().getName(), callerClass, callerMethod, callerDescriptor,
                    apiClass, apiMethod, apiDescriptor, invocationKind);
            synchronized (EVENTS) {
                if (EVENTS.size() >= MAX_QUEUED_EVENTS) {
                    EVENTS.removeFirst();
                    state.dropped.incrementAndGet();
                }
                EVENTS.addLast(event);
            }
        } catch (Throwable ignored) {
        }
    }

    static void configure(Set<ApiHookProfile> profiles, ApiHookConfig config, int classes, int sites) {
        clear();
        long expiresAt = System.currentTimeMillis() + config.stopAfterMillis;
        for (ApiHookProfile profile : profiles) {
            STATES.put(profile.name(), new ProfileState(profile, config.maxEvents, config.rateLimit,
                    expiresAt, classes, sites));
        }
    }

    static String statusAndDrain() {
        StringBuilder output = new StringBuilder();
        for (ProfileState state : STATES.values()) {
            output.append('S').append('\t').append(state.profile.name()).append('\t')
                    .append(state.active && System.currentTimeMillis() < state.expiresAt).append('\t')
                    .append(state.calls.get()).append('\t').append(state.captured.get()).append('\t')
                    .append(state.dropped.get()).append('\t').append(state.expiresAt).append('\t')
                    .append(state.classes).append('\t').append(state.sites).append('\n');
        }
        synchronized (EVENTS) {
            while (!EVENTS.isEmpty()) output.append(EVENTS.removeFirst().line()).append('\n');
        }
        return output.toString();
    }

    static void clear() {
        for (ProfileState state : STATES.values()) state.active = false;
        STATES.clear();
        synchronized (EVENTS) {
            EVENTS.clear();
        }
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class ProfileState {
        final ApiHookProfile profile;
        final int maxEvents;
        final int rateLimit;
        final long expiresAt;
        final int classes;
        final int sites;
        final AtomicLong calls = new AtomicLong();
        final AtomicLong captured = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
        volatile boolean active = true;
        long windowSecond;
        int windowCount;

        ProfileState(ApiHookProfile profile, int maxEvents, int rateLimit, long expiresAt,
                     int classes, int sites) {
            this.profile = profile;
            this.maxEvents = maxEvents;
            this.rateLimit = rateLimit;
            this.expiresAt = expiresAt;
            this.classes = classes;
            this.sites = sites;
        }

        synchronized boolean acquire() {
            long second = System.currentTimeMillis() / 1000L;
            if (second != windowSecond) {
                windowSecond = second;
                windowCount = 0;
            }
            if (windowCount >= rateLimit || captured.get() >= maxEvents) return false;
            windowCount++;
            captured.incrementAndGet();
            return true;
        }
    }

    private static final class Event {
        final long sequence;
        final long timestamp;
        final String profile;
        final String thread;
        final String callerClass;
        final String callerMethod;
        final String callerDescriptor;
        final String apiClass;
        final String apiMethod;
        final String apiDescriptor;
        final String invocationKind;

        Event(long sequence, long timestamp, String profile, String thread, String callerClass,
              String callerMethod, String callerDescriptor, String apiClass, String apiMethod,
              String apiDescriptor, String invocationKind) {
            this.sequence = sequence;
            this.timestamp = timestamp;
            this.profile = profile;
            this.thread = thread;
            this.callerClass = callerClass;
            this.callerMethod = callerMethod;
            this.callerDescriptor = callerDescriptor;
            this.apiClass = apiClass;
            this.apiMethod = apiMethod;
            this.apiDescriptor = apiDescriptor;
            this.invocationKind = invocationKind;
        }

        String line() {
            return new StringBuilder().append('E').append('\t').append(sequence).append('\t')
                    .append(timestamp).append('\t').append(profile).append('\t').append(encoded(thread)).append('\t')
                    .append(encoded(callerClass)).append('\t').append(encoded(callerMethod)).append('\t')
                    .append(encoded(callerDescriptor)).append('\t').append(encoded(apiClass)).append('\t')
                    .append(encoded(apiMethod)).append('\t').append(encoded(apiDescriptor)).append('\t')
                    .append(invocationKind).toString();
        }
    }
}

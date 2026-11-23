package dev.whitedev.jpi.ui.timeline;

import dev.whitedev.jpi.evidence.EvidenceStore.Symbol;

public record TimelineEvent(String key, long timestamp, TimelineSource source, String thread,
                            String callId, String parentCallId, String summary, String details,
                            String subject, String phase, String value, Symbol symbol) {
    public TimelineEvent(String key, long timestamp, TimelineSource source, String thread,
                         String callId, String parentCallId, String summary, String details,
                         String subject, String phase, String value) {
        this(key, timestamp, source, thread, callId, parentCallId, summary, details, subject, phase, value, null);
    }

    public TimelineEvent(String key, long timestamp, TimelineSource source, String thread,
                         String callId, String parentCallId, String summary, String details) {
        this(key, timestamp, source, thread, callId, parentCallId, summary, details, "", "", "");
    }

    public TimelineEvent {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Timeline event key is required");
        if (timestamp < 0L) throw new IllegalArgumentException("Timeline timestamp cannot be negative");
        if (source == null) throw new IllegalArgumentException("Timeline source is required");
        thread = clean(thread);
        callId = clean(callId);
        parentCallId = clean(parentCallId);
        summary = clean(summary);
        details = clean(details);
        subject = clean(subject);
        phase = clean(phase);
        value = clean(value);
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}

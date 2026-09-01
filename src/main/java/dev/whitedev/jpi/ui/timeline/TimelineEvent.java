package dev.whitedev.jpi.ui.timeline;

public record TimelineEvent(String key, long timestamp, TimelineSource source, String thread,
                            String callId, String parentCallId, String summary, String details,
                            String subject, String phase, String value) {
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

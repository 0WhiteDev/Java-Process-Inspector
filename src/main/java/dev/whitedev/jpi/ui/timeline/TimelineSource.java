package dev.whitedev.jpi.ui.timeline;

public enum TimelineSource {
    TRACE("Trace"),
    API_HOOK("API hook"),
    NETWORK("Network"),
    CLASS_LOAD("Class load"),
    FIELD("Static field"),
    FIELD_WRITE("Field write"),
    SNAPSHOT("Snapshot"),
    MARKER("Marker");

    private final String label;

    TimelineSource(String label) {
        this.label = label;
    }

    @Override public String toString() {
        return label;
    }
}

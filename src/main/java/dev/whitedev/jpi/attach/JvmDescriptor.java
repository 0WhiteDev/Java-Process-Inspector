package dev.whitedev.jpi.attach;

public final class JvmDescriptor {
    private final String id;
    private final String displayName;

    public JvmDescriptor(String id, String displayName) {
        this.id = id;
        this.displayName = displayName == null || displayName.trim().isEmpty() ? "<unknown>" : displayName;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    @Override public String toString() { return id + "  -  " + displayName; }
}

package dev.whitedev.jpi.attach;

public record JvmDescriptor(String id, String displayName) {
    public JvmDescriptor {
        displayName = displayName == null || displayName.isBlank() ? "<unknown>" : displayName;
    }

    @Override public String toString() { return id + "  -  " + displayName; }
}

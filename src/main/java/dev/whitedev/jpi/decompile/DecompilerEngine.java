package dev.whitedev.jpi.decompile;

public enum DecompilerEngine {
    CFR("CFR 0.152", "/assets/cfr-0.152.jar"),
    VINEFLOWER("Vineflower 1.12.0", "/assets/vineflower-1.12.0.jar"),
    PROCYON("Procyon 0.6.0", "/assets/procyon-compilertools-0.6.0.jar", "/assets/procyon-core-0.6.0.jar");

    private final String displayName;
    private final String[] resources;

    DecompilerEngine(String displayName, String... resources) {
        this.displayName = displayName;
        this.resources = resources.clone();
    }

    String[] resources() {
        return resources.clone();
    }

    @Override public String toString() {
        return displayName;
    }
}
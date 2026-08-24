package dev.whitedev.jpi.plugin.runtime;

import java.nio.file.Path;

public record PluginDescriptor(String id, String name, String version, Path source,
                               Status status, String message, int extensions) {
    public enum Status {
        LOADED,
        FAILED,
        EMPTY
    }
}

package dev.whitedev.jpi.symbols.model;

import java.nio.file.Path;

public record DebugArtifact(String type, Path path, String identifier, boolean available) {
    public DebugArtifact {
        type = type == null ? "" : type;
        identifier = identifier == null ? "" : identifier;
    }
}

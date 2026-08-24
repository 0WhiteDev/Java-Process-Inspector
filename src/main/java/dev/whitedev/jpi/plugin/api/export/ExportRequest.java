package dev.whitedev.jpi.plugin.api.export;

import dev.whitedev.jpi.plugin.api.JpiSession;

import java.nio.file.Path;
import java.util.Optional;

public record ExportRequest(Path destination, Optional<JpiSession> session) {
    public ExportRequest {
        if (destination == null) throw new IllegalArgumentException("Destination is required");
        session = session == null ? Optional.empty() : session;
    }
}

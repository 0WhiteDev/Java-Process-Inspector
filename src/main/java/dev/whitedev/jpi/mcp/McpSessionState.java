package dev.whitedev.jpi.mcp;

import dev.whitedev.jpi.attach.InspectorSession;
import java.util.UUID;

public final class McpSessionState {
    public record Snapshot(String id, InspectorSession session) {}
    private volatile Snapshot active = new Snapshot("", null);

    public void setSession(InspectorSession session) {
        active = new Snapshot(session == null ? "" : UUID.randomUUID().toString(), session);
    }

    public Snapshot snapshot() { return active; }
    public void verify(Snapshot expected) {
        if (active != expected) throw new McpException(McpErrorCode.SESSION_CHANGED,
                "The active JPI target changed while the operation was running.");
    }
}

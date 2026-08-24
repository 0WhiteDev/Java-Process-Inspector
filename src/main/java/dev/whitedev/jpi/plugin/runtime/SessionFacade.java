package dev.whitedev.jpi.plugin.runtime;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.plugin.api.JpiSession;
import dev.whitedev.jpi.protocol.Operation;

import java.io.IOException;

final class SessionFacade implements JpiSession {
    private final InspectorSession session;

    SessionFacade(InspectorSession session) {
        this.session = session;
    }

    @Override public String targetId() {
        return session.target().id();
    }

    @Override public String targetDisplayName() {
        return session.target().displayName();
    }

    @Override public byte[] request(Operation operation, String payload) throws IOException {
        return session.request(operation, payload);
    }

    @Override public String requestText(Operation operation, String payload) throws IOException {
        return session.requestText(operation, payload);
    }
}

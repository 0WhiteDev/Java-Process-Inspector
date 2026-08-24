package dev.whitedev.jpi.plugin.api;

import dev.whitedev.jpi.protocol.Operation;

import java.io.IOException;

public interface JpiSession {
    String targetId();
    String targetDisplayName();
    byte[] request(Operation operation, String payload) throws IOException;
    String requestText(Operation operation, String payload) throws IOException;
}

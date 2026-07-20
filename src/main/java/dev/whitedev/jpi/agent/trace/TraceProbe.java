package dev.whitedev.jpi.agent.trace;

import java.util.concurrent.ScheduledFuture;

final class TraceProbe {
    final String id;
    final String targetIdentifier;
    final String className;
    final String methodName;
    final String descriptor;
    final TraceConfig config;
    final long createdAt;
    final long expiresAt;
    volatile ScheduledFuture<?> expiration;

    TraceProbe(String id, String targetIdentifier, String className, String methodName,
               String descriptor, TraceConfig config) {
        this.id = id;
        this.targetIdentifier = targetIdentifier;
        this.className = className;
        this.methodName = methodName;
        this.descriptor = descriptor;
        this.config = config;
        this.createdAt = System.currentTimeMillis();
        this.expiresAt = createdAt + config.stopAfterMillis;
    }

    String methodKey() {
        return methodName + descriptor;
    }
}
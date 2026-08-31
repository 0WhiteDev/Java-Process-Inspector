package dev.whitedev.jpi.agent.field;

import java.util.concurrent.ScheduledFuture;

final class FieldWriteProbe {
    final String id;
    final String targetIdentifier;
    final String owner;
    final String fieldName;
    final String descriptor;
    final FieldWriteConfig config;
    final long expiresAt;
    volatile int classes;
    volatile int sites;
    volatile ScheduledFuture<?> expiration;

    FieldWriteProbe(String id, String targetIdentifier, String owner, String fieldName,
                    String descriptor, FieldWriteConfig config) {
        this.id = id;
        this.targetIdentifier = targetIdentifier;
        this.owner = owner;
        this.fieldName = fieldName;
        this.descriptor = descriptor;
        this.config = config;
        this.expiresAt = System.currentTimeMillis() + config.stopAfterMillis;
    }
}

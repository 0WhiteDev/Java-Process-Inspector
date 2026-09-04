package dev.whitedev.jpi.file;

public record FileRule(String id, FileOperation operation, String pathPattern, String callerPattern,
                       FileDecision decision, String redirectRoot) {
}

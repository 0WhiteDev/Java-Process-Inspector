package dev.whitedev.jpi.file;

public record FileEvent(long id, long timestamp, FileOperation operation, String path, String normalizedPath,
                        String callerClass, String callerMethod, String callerDescriptor, String threadName,
                        FileDecision decision, long requestedBytes, String redirectedPath, String error,
                        String stackTrace, byte[] payloadPreview, long callId) {
    public String caller() {
        return callerClass + "." + callerMethod + callerDescriptor;
    }
}

package dev.whitedev.jpi.debug;

public record DebugEvent(Type type, long timestamp, long threadId, String threadName,
                         String location, String details) {
    public enum Type {
        CONNECTED, BREAK, STEP, PAUSE, RESUME, VALUE_CHANGE, FORCE_RETURN,
        THREAD_START, THREAD_DEATH, CLASS_PREPARE, EXCEPTION, DISCONNECTED
    }
}

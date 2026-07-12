package dev.whitedev.jpi.protocol;

public enum Operation {
    PING(1), METRICS(2), CLASSES(3), CLASS_BYTES(4), EXECUTE(5), FIELDS(6), THREAD_DUMP(7),
    DISCONNECT(8), CLASS_EVENTS(9), ENVIRONMENT(10), CONSTANT_SEARCH(11),
    REDEFINE_SOURCE(12), ROLLBACK_CLASS(13), CLASS_METHODS(14), PATCH_METHOD(15),
    APPLY_CLASS_BYTES(16);

    private final int code;

    Operation(int code) { this.code = code; }
    public int code() { return code; }

    public static Operation fromCode(int code) {
        for (Operation operation : values()) {
            if (operation.code == code) return operation;
        }
        throw new IllegalArgumentException("Unknown operation: " + code);
    }
}

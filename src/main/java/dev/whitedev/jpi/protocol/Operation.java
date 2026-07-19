package dev.whitedev.jpi.protocol;

public enum Operation {
    PING(1), METRICS(2), CLASSES(3), CLASS_BYTES(4), EXECUTE(5), FIELDS(6), THREAD_DUMP(7),
    DISCONNECT(8), CLASS_EVENTS(9), ENVIRONMENT(10), CONSTANT_SEARCH(11),
    REDEFINE_SOURCE(12), ROLLBACK_CLASS(13), CLASS_METHODS(14), PATCH_METHOD(15),
    APPLY_CLASS_BYTES(16), TRACE_START(17), TRACE_STOP(18), TRACE_EVENTS(19),
    METHOD_XREFS(20), XREF_SEARCH(21), DEOBFUSCATION_INVENTORY(22),
    API_HOOK_START(23), API_HOOK_STOP(24), API_HOOK_EVENTS(25),
    HEAP_SCAN(26), HEAP_OBJECT(27), HEAP_DUMP(28);

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

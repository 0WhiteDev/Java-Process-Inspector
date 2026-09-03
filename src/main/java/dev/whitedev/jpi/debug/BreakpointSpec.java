package dev.whitedev.jpi.debug;

public record BreakpointSpec(String className, String methodName, String descriptor,
                             Integer sourceLine, Long codeIndex, Type type,
                             SuspendPolicy suspendPolicy, boolean enabled) {
    public BreakpointSpec {
        className = clean(className);
        methodName = clean(methodName);
        descriptor = clean(descriptor);
        if (className.isEmpty()) throw new IllegalArgumentException("Breakpoint class is required");
        if (type == null) type = Type.METHOD;
        if (suspendPolicy == null) suspendPolicy = SuspendPolicy.THREAD;
        if (type != Type.LINE && type != Type.EXCEPTION && methodName.isEmpty()) {
            throw new IllegalArgumentException("Breakpoint method is required");
        }
        if (type == Type.LINE && (sourceLine == null || sourceLine.intValue() < 1)) {
            throw new IllegalArgumentException("Source line must be positive");
        }
        if (type == Type.BYTECODE && (codeIndex == null || codeIndex.longValue() < 0L)) {
            throw new IllegalArgumentException("Bytecode index cannot be negative");
        }
    }

    public String location() {
        if (type == Type.EXCEPTION) return "exception " + className;
        if (type == Type.LINE) return className + ":" + sourceLine;
        String method = className + "." + methodName + descriptor;
        return type == Type.BYTECODE ? method + " @ BCI " + codeIndex : method;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public enum Type { METHOD, LINE, BYTECODE, EXCEPTION }
    public enum SuspendPolicy { THREAD, ALL }
}

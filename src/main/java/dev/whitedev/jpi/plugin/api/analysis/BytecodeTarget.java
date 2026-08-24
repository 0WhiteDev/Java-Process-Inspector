package dev.whitedev.jpi.plugin.api.analysis;

public record BytecodeTarget(String classId, String className, byte[] bytecode,
                             String methodName, String methodDescriptor) {
    public BytecodeTarget {
        classId = value(classId);
        className = value(className);
        bytecode = bytecode == null ? new byte[0] : bytecode.clone();
        methodName = value(methodName);
        methodDescriptor = value(methodDescriptor);
    }

    @Override public byte[] bytecode() {
        return bytecode.clone();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}

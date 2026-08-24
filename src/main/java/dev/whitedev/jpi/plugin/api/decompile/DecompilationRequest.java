package dev.whitedev.jpi.plugin.api.decompile;

public record DecompilationRequest(String className, byte[] bytecode,
                                   String methodName, String methodDescriptor) {
    public DecompilationRequest {
        className = className == null ? "" : className;
        bytecode = bytecode == null ? new byte[0] : bytecode.clone();
        methodName = methodName == null ? "" : methodName;
        methodDescriptor = methodDescriptor == null ? "" : methodDescriptor;
    }

    @Override public byte[] bytecode() {
        return bytecode.clone();
    }

    public boolean methodOnly() {
        return !methodName.isEmpty();
    }
}

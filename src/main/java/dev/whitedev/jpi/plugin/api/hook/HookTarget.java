package dev.whitedev.jpi.plugin.api.hook;

public record HookTarget(String owner, String methodName) {
    public HookTarget {
        owner = owner == null ? "" : owner.trim().replace('.', '/');
        methodName = methodName == null ? "" : methodName.trim();
        if (owner.isEmpty() || methodName.isEmpty()) throw new IllegalArgumentException("Hook owner and method are required");
    }
}

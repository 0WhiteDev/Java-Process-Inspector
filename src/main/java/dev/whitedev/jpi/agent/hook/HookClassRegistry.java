package dev.whitedev.jpi.agent.hook;

public interface HookClassRegistry {
    byte[] bytecodeFor(Class<?> type);

    void captureIfAbsent(Class<?> type, byte[] bytecode);

    void recordApplied(Class<?> type, byte[] bytecode);
}
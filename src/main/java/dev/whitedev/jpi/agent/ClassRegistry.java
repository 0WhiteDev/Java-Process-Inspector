package dev.whitedev.jpi.agent;

import dev.whitedev.jpi.agent.hook.HookClassRegistry;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ClassRegistry implements ClassFileTransformer, HookClassRegistry {


    private static final long MAX_CAPTURED_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_EVENTS = 10_000;

    private final LinkedHashMap<String, CapturedClass> captured = new LinkedHashMap<>();
    private final ArrayDeque<ClassEvent> events = new ArrayDeque<>();
    private long capturedBytes;

    @Override
    public byte[] transform(ClassLoader loader, String internalName, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) throws IllegalClassFormatException {
        String binaryName = internalName == null
                ? classBeingRedefined == null ? "<anonymous>" : classBeingRedefined.getName()
                : internalName.replace('/', '.');
        String key = key(binaryName, loader);
        boolean redefinition = classBeingRedefined != null;
        record(key, binaryName, loaderLabel(loader), classfileBuffer, redefinition);
        return null;
    }

    public synchronized byte[] bytecodeFor(Class<?> type) {
        CapturedClass value = captured.get(keyFor(type));
        return value == null ? null : value.bytecode.clone();
    }

    synchronized byte[] originalBytecodeFor(Class<?> type) {
        CapturedClass value = captured.get(keyFor(type));
        return value == null ? null : value.originalBytecode.clone();
    }

    synchronized byte[] bytecodeByKey(String key) {
        CapturedClass value = captured.get(key);
        return value == null ? null : value.bytecode.clone();
    }

    synchronized List<CapturedClass> capturedClasses() {
        return new ArrayList<>(captured.values());
    }

    public synchronized void captureIfAbsent(Class<?> type, byte[] bytecode) {
        String key = keyFor(type);
        if (captured.containsKey(key)) return;
        byte[] copy = bytecode.clone();
        captured.put(key, new CapturedClass(key, type.getName(), loaderLabel(type.getClassLoader()), copy, System.currentTimeMillis()));
        capturedBytes += copy.length;
        evictIfNeeded();
    }

    public synchronized void recordApplied(Class<?> type, byte[] bytecode) {
        captureIfAbsent(type, bytecode);
        CapturedClass value = captured.get(keyFor(type));
        if (value != null) {
            capturedBytes -= value.storageSize();
            value.bytecode = Arrays.equals(value.originalBytecode, bytecode)
                    ? value.originalBytecode : bytecode.clone();
            capturedBytes += value.storageSize();
            evictIfNeeded();
        }
    }

    synchronized String events() {
        StringBuilder output = new StringBuilder();
        for (ClassEvent event : events) {
            output.append(event.timestamp).append('\t')
                    .append(escape(event.className)).append('\t')
                    .append(escape(event.loader)).append('\t')
                    .append(event.byteSize).append('\t')
                    .append(event.redefinition ? "retransform" : "define")
                    .append('\n');
        }
        return output.toString();
    }

    String keyFor(Class<?> type) {
        return key(type.getName(), type.getClassLoader());
    }

    private synchronized void record(String key, String binaryName, String loader, byte[] bytecode,
                                     boolean redefinition) {
        if (!captured.containsKey(key)) {
            byte[] copy = bytecode.clone();
            captured.put(key, new CapturedClass(key, binaryName, loader, copy, System.currentTimeMillis()));
            capturedBytes += copy.length;
            evictIfNeeded();
        }
        events.addLast(new ClassEvent(System.currentTimeMillis(), binaryName, loader,
                bytecode.length, redefinition));
        while (events.size() > MAX_EVENTS) events.removeFirst();
    }

    private void evictIfNeeded() {
        while (capturedBytes > MAX_CAPTURED_BYTES && !captured.isEmpty()) {
            Map.Entry<String, CapturedClass> eldest = captured.entrySet().iterator().next();
            capturedBytes -= eldest.getValue().storageSize();
            captured.remove(eldest.getKey());
        }
    }

    private static String key(String binaryName, ClassLoader loader) {
        return binaryName + "@" + (loader == null ? "bootstrap"
                : Integer.toHexString(System.identityHashCode(loader)));
    }

    static String loaderLabel(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private static String escape(String value) {
        return value.replace("\t", " ").replace("\r", " ").replace("\n", " ");
    }

    static final class CapturedClass {
        final String key;
        final String className;
        final String loader;
        final byte[] originalBytecode;
        byte[] bytecode;
        final long capturedAt;

        CapturedClass(String key, String className, String loader, byte[] bytecode, long capturedAt) {
            this.key = key;
            this.className = className;
            this.loader = loader;
            this.originalBytecode = bytecode;
            this.bytecode = bytecode;
            this.capturedAt = capturedAt;
        }

        long storageSize() {
            return originalBytecode.length + (bytecode == originalBytecode ? 0 : bytecode.length);
        }
    }

    private static final class ClassEvent {
        final long timestamp;
        final String className;
        final String loader;
        final int byteSize;
        final boolean redefinition;

        ClassEvent(long timestamp, String className, String loader, int byteSize, boolean redefinition) {
            this.timestamp = timestamp;
            this.className = className;
            this.loader = loader;
            this.byteSize = byteSize;
            this.redefinition = redefinition;
        }
    }
}

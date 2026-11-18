package dev.whitedev.jpi.agent;

import dev.whitedev.jpi.agent.jvmti.NativeJvmtiBridge;
import dev.whitedev.jpi.agent.patch.ClassSchema;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;

public final class ClassTransformationCoordinator {
    public enum Layer {
        CFG, FIELD_WRITES, LIVE_TRACER, API_HOOKS, FILE_MONITOR
    }

    @FunctionalInterface
    public interface Transformation {
        byte[] apply(byte[] bytecode) throws Exception;
    }

    private static final int MAX_CLASS_BYTES = 24 * 1024 * 1024;
    private static final long MAX_ACTIVE_BYTES = 128L * 1024 * 1024;

    private final RuntimeAccess runtime;
    private final ClassRegistry registry;
    private final Map<Class<?>, ActiveDefinition> active = new IdentityHashMap<>();
    private long activeBytes;

    ClassTransformationCoordinator(RuntimeAccess runtime, ClassRegistry registry) {
        this.runtime = runtime;
        this.registry = registry;
    }

    public synchronized byte[] bytecodeFor(Class<?> type) {
        ActiveDefinition definition = active.get(type);
        return definition == null ? registry.bytecodeFor(type) : definition.base.clone();
    }

    synchronized byte[] captureBytecode(Class<?> type) throws Exception {
        byte[] captured = bytecodeFor(type);
        if (captured != null) return captured;
        if (runtime.isNativeOnly()) {
            byte[] bytes = NativeJvmtiBridge.classBytes(type);
            registry.captureIfAbsent(type, bytes);
            return bytes;
        }
        if (runtime.isRetransformClassesSupported() && runtime.isModifiableClass(type)) {
            runtime.retransformClasses(type);
            return registry.bytecodeFor(type);
        }
        return null;
    }

    synchronized byte[] originalBytecodeFor(Class<?> type) {
        ActiveDefinition definition = active.get(type);
        return definition == null ? registry.originalBytecodeFor(type) : definition.original.clone();
    }

    public synchronized void install(Class<?> type, Layer layer, Transformation transformation) throws Exception {
        ActiveDefinition previous = active.get(type);
        EnumMap<Layer, Transformation> layers = layersOf(previous);
        layers.put(layer, transformation);
        byte[] current = previous == null ? captureBytecode(type) : previous.base;
        if (current == null) throw new IOException("Bytecode is unavailable for " + type.getName());
        byte[] original = previous == null ? registry.originalBytecodeFor(type) : previous.original;
        if (original == null) original = current;
        apply(type, previous, original, current, current, layers);
    }

    public synchronized void remove(Class<?> type, Layer layer) throws Exception {
        ActiveDefinition previous = active.get(type);
        if (previous == null || !previous.layers.containsKey(layer)) return;
        EnumMap<Layer, Transformation> layers = layersOf(previous);
        layers.remove(layer);
        apply(type, previous, previous.original, previous.base, previous.base, layers);
    }

    synchronized void patch(Class<?> type, byte[] current, byte[] replacement) throws Exception {
        ActiveDefinition previous = active.get(type);
        byte[] base = previous == null ? current : previous.base;
        byte[] original = previous == null ? registry.originalBytecodeFor(type) : previous.original;
        if (original == null) original = base;
        apply(type, previous, original, base, replacement, layersOf(previous));
    }

    synchronized void restoreAll() {
        for (Class<?> type : new ArrayList<>(active.keySet())) {
            ActiveDefinition definition = active.get(type);
            try {
                apply(type, definition, definition.original, definition.base, definition.base, layersOf(null));
            } catch (Exception error) {
                System.err.println("[JPI agent] Could not remove probes from " + type.getName() + ": " + error.getMessage());
            }
        }
    }

    private void apply(Class<?> type, ActiveDefinition previous, byte[] original, byte[] current,
                       byte[] base, EnumMap<Layer, Transformation> layers) throws Exception {
        if (Arrays.equals(original, base)) original = base;
        long nextSize = activeBytes - (previous == null ? 0 : previous.size())
                + (layers.isEmpty() ? 0 : retainedSize(original, base));
        if (nextSize > MAX_ACTIVE_BYTES) throw new IOException("Active transformation bytecode budget exceeded");
        if (base.length > MAX_CLASS_BYTES)
            throw new IOException("Class bytecode exceeds the transformation size limit");
        ClassSchema.verifyCompatible(current, base);

        byte[] composed = base;
        for (Map.Entry<Layer, Transformation> entry : layers.entrySet()) {
            try {
                composed = entry.getValue().apply(composed.clone());
                if (composed == null || composed.length > MAX_CLASS_BYTES) {
                    throw new IOException("Invalid transformed class size");
                }
            } catch (Exception | LinkageError error) {
                throw new IOException("Transformation conflict in " + entry.getKey() + " for "
                        + type.getName() + ": " + error.getMessage(), error);
            }
        }
        ClassSchema.verifyCompatible(base, composed);
        registry.captureIfAbsent(type, original);
        try {
            runtime.redefineClass(type, composed);
        } catch (Exception | LinkageError error) {
            throw new IOException("JVM rejected " + type.getName() + " with layers " + layers.keySet()
                    + ": " + error, error);
        }

        registry.recordApplied(type, base);
        if (layers.isEmpty()) {
            active.remove(type);
        } else {
            active.put(type, new ActiveDefinition(original, base, layers));
        }
        activeBytes = nextSize;
    }

    private static EnumMap<Layer, Transformation> layersOf(ActiveDefinition definition) {
        return definition == null ? new EnumMap<>(Layer.class) : new EnumMap<>(definition.layers);
    }

    private static long retainedSize(byte[] original, byte[] base) {
        return original.length + (original == base ? 0L : base.length);
    }

    private static final class ActiveDefinition {
        final byte[] original;
        final byte[] base;
        final EnumMap<Layer, Transformation> layers;

        ActiveDefinition(byte[] original, byte[] base, EnumMap<Layer, Transformation> layers) {
            this.original = original.clone();
            this.base = original == base ? this.original : base.clone();
            this.layers = layers;
        }

        long size() {
            return retainedSize(original, base);
        }
    }
}

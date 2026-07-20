package dev.whitedev.jpi.agent.cfg;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class CfgManager {
    private static final AtomicLong IDS = new AtomicLong();

    private final Instrumentation instrumentation;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "jpi-cfg-expiration");
            thread.setDaemon(true);
            thread.setContextClassLoader(CfgRuntime.class.getClassLoader());
            return thread;
        }
    });
    private Probe active;

    public CfgManager(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    public synchronized String start(Class<?> target, String methodName, String descriptor,
                                     byte[] bytecode, long durationMillis) throws Exception {
        if (durationMillis < 1000L || durationMillis > 600000L) {
            throw new IOException("CFG trace duration must be between 1 and 600 seconds");
        }
        requireRuntimeVisibility(target);
        stopAll();
        if (active != null) throw new IOException("The previous CFG trace could not be restored");
        String id = "cfg-" + IDS.incrementAndGet();
        BytecodeCfgAnalyzer.Instrumented replacement =
                BytecodeCfgAnalyzer.instrument(bytecode, methodName, descriptor, id);
        CfgRuntime.register(id, replacement.blockCount);
        try {
            instrumentation.redefineClasses(new ClassDefinition(target, replacement.bytecode));
        } catch (Throwable error) {
            CfgRuntime.discard(id);
            throw error;
        }
        Probe probe = new Probe(id, target, bytecode.clone(),
                System.currentTimeMillis() + durationMillis);
        active = probe;
        probe.expiration = scheduler.schedule(new Runnable() {
            @Override public void run() {
                try {
                    stop(id);
                } catch (Throwable ignored) {
                }
            }
        }, durationMillis, TimeUnit.MILLISECONDS);
        return "P\t" + id + "\t" + replacement.blockCount + "\t" + probe.expiresAt;
    }

    public synchronized String stop(String probeId) throws Exception {
        Probe current = active;
        if (current == null) {
            if (probeId != null && !probeId.trim().isEmpty()) CfgRuntime.deactivate(probeId.trim());
            return "CFG block trace is not active";
        }
        if (probeId != null && !probeId.trim().isEmpty() && !current.id.equals(probeId.trim())) {
            throw new IOException("CFG block trace is no longer active: " + probeId);
        }
        instrumentation.redefineClasses(new ClassDefinition(current.target, current.baseBytecode));
        if (current.expiration != null) current.expiration.cancel(false);
        active = null;
        CfgRuntime.deactivate(current.id);
        return "Stopped " + current.id + " and restored " + current.target.getName();
    }

    public synchronized void stopForClass(Class<?> target) throws Exception {
        if (active != null && active.target == target) stop(active.id);
    }

    public synchronized String stopAll() {
        Probe current = active;
        if (current == null) return "CFG block trace is not active";
        try {
            return stop(current.id);
        } catch (Throwable error) {
            return "Could not restore " + current.target.getName() + ": " + message(error);
        }
    }

    public String snapshot(String probeId) {
        return CfgRuntime.snapshot(probeId == null ? "" : probeId.trim());
    }

    public synchronized void close() {
        stopAll();
        scheduler.shutdownNow();
        CfgRuntime.clear();
    }

    private static void requireRuntimeVisibility(Class<?> target) throws Exception {
        ClassLoader loader = target.getClassLoader();
        if (loader == null) throw new IOException("Bootstrap classes cannot use CFG block tracing");
        Class<?> visible;
        try {
            visible = Class.forName(CfgRuntime.class.getName(), false, loader);
        } catch (ClassNotFoundException error) {
            throw new IOException("The selected classloader cannot access the JPI CFG runtime", error);
        }
        if (visible != CfgRuntime.class) {
            throw new IOException("The selected classloader resolves an incompatible JPI CFG runtime");
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class Probe {
        final String id;
        final Class<?> target;
        final byte[] baseBytecode;
        final long expiresAt;
        volatile ScheduledFuture<?> expiration;

        Probe(String id, Class<?> target, byte[] baseBytecode, long expiresAt) {
            this.id = id;
            this.target = target;
            this.baseBytecode = baseBytecode;
            this.expiresAt = expiresAt;
        }
    }
}


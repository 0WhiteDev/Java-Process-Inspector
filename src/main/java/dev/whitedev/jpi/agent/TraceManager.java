package dev.whitedev.jpi.agent;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class TraceManager {
    private static final AtomicLong IDS = new AtomicLong();

    private final Instrumentation instrumentation;
    private final Map<Class<?>, TracedClass> classes = new IdentityHashMap<Class<?>, TracedClass>();
    private final Map<String, TraceProbe> probes = new LinkedHashMap<String, TraceProbe>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "jpi-trace-expiration");
            thread.setDaemon(true);
            thread.setContextClassLoader(TraceRuntime.class.getClassLoader());
            return thread;
        }
    });

    TraceManager(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    synchronized String start(Class<?> target, String targetIdentifier, String methodName,
                              String descriptor, String settings, byte[] currentBytecode) throws Exception {
        requireRuntimeVisibility(target);
        TraceConfig config = TraceConfig.parse(settings);
        String id = "trace-" + IDS.incrementAndGet();
        TraceProbe probe = new TraceProbe(id, targetIdentifier, target.getName(), methodName, descriptor, config);
        TracedClass traced = classes.get(target);
        boolean created = traced == null;
        if (created) traced = new TracedClass(target, currentBytecode.clone());
        for (TraceProbe active : traced.probes.values()) {
            if (active.methodKey().equals(probe.methodKey())) {
                throw new IOException("This method already has an active trace probe: " + active.id);
            }
        }

        TraceRuntime.register(probe);
        traced.probes.put(probe.id, probe);
        try {
            redefine(traced, traced.probes.values());
        } catch (Throwable error) {
            traced.probes.remove(probe.id);
            TraceRuntime.unregister(probe.id);
            throw error;
        }
        if (created) classes.put(target, traced);
        probes.put(probe.id, probe);
        probe.expiration = scheduler.schedule(new Runnable() {
            @Override public void run() {
                try {
                    stop(id);
                } catch (Throwable ignored) {
                }
            }
        }, config.stopAfterMillis, TimeUnit.MILLISECONDS);
        return probe.id + "	Tracing " + target.getName() + "." + methodName + descriptor;
    }

    synchronized String stop(String probeId) throws Exception {
        if (probeId == null || probeId.trim().isEmpty()) return stopAll();
        TraceProbe probe = probes.get(probeId);
        if (probe == null) return "Trace probe is no longer active: " + probeId;
        TracedClass traced = find(probe);
        List<TraceProbe> remaining = new ArrayList<TraceProbe>(traced.probes.values());
        remaining.remove(probe);
        redefine(traced, remaining);
        traced.probes.remove(probe.id);
        probes.remove(probe.id);
        TraceRuntime.unregister(probe.id);
        if (probe.expiration != null) probe.expiration.cancel(false);
        if (traced.probes.isEmpty()) classes.remove(traced.target);
        return "Stopped " + probe.id + " and restored " + probe.className;
    }

    synchronized void stopForClass(Class<?> target) throws Exception {
        TracedClass traced = classes.get(target);
        if (traced == null) return;
        instrumentation.redefineClasses(new ClassDefinition(target, traced.baseBytecode));
        classes.remove(target);
        for (TraceProbe probe : traced.probes.values()) {
            probes.remove(probe.id);
            TraceRuntime.unregister(probe.id);
            if (probe.expiration != null) probe.expiration.cancel(false);
        }
    }

    synchronized String stopAll() {
        int stopped = 0;
        List<String> failures = new ArrayList<String>();
        for (TracedClass traced : new ArrayList<TracedClass>(classes.values())) {
            try {
                instrumentation.redefineClasses(new ClassDefinition(traced.target, traced.baseBytecode));
                stopped += traced.probes.size();
            } catch (Throwable error) {
                failures.add(traced.target.getName() + ": " + message(error));
            }
            for (TraceProbe probe : traced.probes.values()) {
                TraceRuntime.unregister(probe.id);
                if (probe.expiration != null) probe.expiration.cancel(false);
            }
        }
        classes.clear();
        probes.clear();
        if (!failures.isEmpty()) return "Stopped " + stopped + " probes. Restore failures: " + failures;
        return "Stopped " + stopped + " trace probes and restored all classes";
    }

    String events() {
        return TraceRuntime.statusAndDrain();
    }

    synchronized void close() {
        stopAll();
        scheduler.shutdownNow();
        TraceRuntime.clear();
    }

    private void redefine(TracedClass traced, Collection<TraceProbe> active) throws Exception {
        byte[] replacement = active.isEmpty() ? traced.baseBytecode
                : TraceInstrumenter.instrument(traced.baseBytecode, traced.target.getClassLoader(), active);
        instrumentation.redefineClasses(new ClassDefinition(traced.target, replacement));
    }

    private TracedClass find(TraceProbe probe) throws IOException {
        for (TracedClass traced : classes.values()) {
            if (traced.probes.containsKey(probe.id)) return traced;
        }
        throw new IOException("Trace class state is unavailable for " + probe.id);
    }

    private static void requireRuntimeVisibility(Class<?> target) throws Exception {
        ClassLoader loader = target.getClassLoader();
        if (loader == null) throw new IOException("Bootstrap classes are not supported by the live tracer");
        Class<?> visible;
        try {
            visible = Class.forName(TraceRuntime.class.getName(), false, loader);
        } catch (ClassNotFoundException error) {
            throw new IOException("The selected classloader cannot access the JPI trace runtime", error);
        }
        if (visible != TraceRuntime.class) {
            throw new IOException("The selected classloader resolves an incompatible JPI trace runtime");
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class TracedClass {
        final Class<?> target;
        final byte[] baseBytecode;
        final Map<String, TraceProbe> probes = new LinkedHashMap<String, TraceProbe>();

        TracedClass(Class<?> target, byte[] baseBytecode) {
            this.target = target;
            this.baseBytecode = baseBytecode;
        }
    }
}
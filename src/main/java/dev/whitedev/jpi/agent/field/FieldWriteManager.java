package dev.whitedev.jpi.agent.field;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class FieldWriteManager {
    private static final AtomicLong IDS = new AtomicLong();
    private final Instrumentation instrumentation;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "jpi-field-write-expiration");
            thread.setDaemon(true);
            thread.setContextClassLoader(FieldWriteRuntime.class.getClassLoader());
            return thread;
        }
    });
    private ActiveProbe active;

    public FieldWriteManager(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    public synchronized String start(String targetIdentifier, String owner, String fieldName, String descriptor,
                                     String settings, Map<Class<?>, byte[]> candidates) throws Exception {
        if (active != null) throw new IOException("Stop the active field write probe before starting another one");
        FieldWriteConfig config = FieldWriteConfig.parse(settings);
        FieldWriteProbe probe = new FieldWriteProbe("field-write-" + IDS.incrementAndGet(), targetIdentifier,
                owner, fieldName, descriptor, config);
        ActiveProbe created = new ActiveProbe(probe);
        List<String> failures = new ArrayList<String>();
        FieldWriteRuntime.register(probe);
        for (Map.Entry<Class<?>, byte[]> candidate : candidates.entrySet()) {
            if (created.classes.size() >= config.maxClasses) break;
            Class<?> type = candidate.getKey();
            try {
                requireRuntimeVisibility(type);
                FieldWriteInstrumenter.Result result = FieldWriteInstrumenter.instrument(
                        candidate.getValue(), type.getClassLoader(), probe);
                if (result.sites == 0) continue;
                instrumentation.redefineClasses(new ClassDefinition(type, result.bytecode));
                created.classes.put(type, candidate.getValue().clone());
                probe.classes = created.classes.size();
                probe.sites += result.sites;
            } catch (Throwable error) {
                failures.add(type.getName() + ": " + message(error));
            }
        }
        if (created.classes.isEmpty()) {
            FieldWriteRuntime.unregister(probe.id);
            throw new IOException("No writable call sites could be instrumented"
                    + (failures.isEmpty() ? "" : ". " + failures.get(0)));
        }
        active = created;
        probe.expiration = scheduler.schedule(new Runnable() {
            @Override public void run() {
                stopAll();
            }
        }, config.stopAfterMillis, TimeUnit.MILLISECONDS);
        String result = probe.id + "\tTracing " + owner + "." + fieldName + " across "
                + probe.sites + " write sites in " + probe.classes + " classes";
        return failures.isEmpty() ? result : result + ". Skipped " + failures.size() + " classes";
    }

    public synchronized String stopAll() {
        ActiveProbe current = active;
        if (current == null) return "No field write probe is active";
        int restored = 0;
        List<String> failures = new ArrayList<String>();
        for (Map.Entry<Class<?>, byte[]> entry : current.classes.entrySet()) {
            try {
                instrumentation.redefineClasses(new ClassDefinition(entry.getKey(), entry.getValue()));
                restored++;
            } catch (Throwable error) {
                failures.add(entry.getKey().getName() + ": " + message(error));
            }
        }
        finish(current);
        return failures.isEmpty() ? "Stopped " + current.probe.id + " and restored " + restored + " classes"
                : "Stopped " + current.probe.id + ". Restore failures: " + failures;
    }

    public synchronized void stopForClass(Class<?> target) throws Exception {
        ActiveProbe current = active;
        if (current == null) return;
        byte[] base = current.classes.get(target);
        if (base == null) return;
        instrumentation.redefineClasses(new ClassDefinition(target, base));
        current.classes.remove(target);
        current.probe.classes = current.classes.size();
        if (current.classes.isEmpty()) finish(current);
    }

    public String events() {
        return FieldWriteRuntime.statusAndDrain();
    }

    public synchronized void close() {
        stopAll();
        scheduler.shutdownNow();
        FieldWriteRuntime.clear();
    }

    private void finish(ActiveProbe current) {
        FieldWriteRuntime.unregister(current.probe.id);
        if (current.probe.expiration != null) current.probe.expiration.cancel(false);
        if (active == current) active = null;
    }

    private static void requireRuntimeVisibility(Class<?> target) throws Exception {
        ClassLoader loader = target.getClassLoader();
        if (loader == null) throw new IOException("Bootstrap writer classes are not supported");
        Class<?> visible;
        try {
            visible = Class.forName(FieldWriteRuntime.class.getName(), false, loader);
        } catch (ClassNotFoundException error) {
            throw new IOException("The writer classloader cannot access the JPI field runtime", error);
        }
        if (visible != FieldWriteRuntime.class) throw new IOException("The writer resolves an incompatible field runtime");
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static final class ActiveProbe {
        final FieldWriteProbe probe;
        final Map<Class<?>, byte[]> classes = new LinkedHashMap<Class<?>, byte[]>();

        ActiveProbe(FieldWriteProbe probe) {
            this.probe = probe;
        }
    }
}

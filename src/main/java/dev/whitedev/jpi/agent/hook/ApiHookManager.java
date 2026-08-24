package dev.whitedev.jpi.agent.hook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class ApiHookManager {
    private static final int MAX_SCANNED_CLASSES = 50000;
    private static final long MAX_SCAN_NANOS = 15_000_000_000L;
    private static final long MAX_PLANNED_BYTES = 128L * 1024L * 1024L;

    private final Instrumentation instrumentation;
    private final HookClassRegistry registry;
    private final Map<Class<?>, HookedClass> hooked = new IdentityHashMap<Class<?>, HookedClass>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "jpi-api-hook-expiration");
            thread.setDaemon(true);
            thread.setContextClassLoader(ApiHookRuntime.class.getClassLoader());
            return thread;
        }
    });
    private ScheduledFuture<?> expiration;
    private String summary = "No API hook profile is active";

    public ApiHookManager(Instrumentation instrumentation, HookClassRegistry registry) {
        this.instrumentation = instrumentation;
        this.registry = registry;
    }

    public synchronized String start(String payload) throws Exception {
        String[] sections = payload.split("\n", -1);
        String profileText = sections.length == 0 ? "" : sections[0];
        String settings = sections.length < 2 ? "" : sections[1];
        StringBuilder definitions = new StringBuilder();
        for (int index = 2; index < sections.length; index++) {
            if (definitions.length() > 0) definitions.append('\n');
            definitions.append(sections[index]);
        }
        Set<ApiHookProfile> profiles = ApiHookProfile.parse(profileText, definitions.toString());
        ApiHookConfig config = ApiHookConfig.parse(settings);
        stopAll();
        if (!hooked.isEmpty()) throw new IOException("Previously hooked classes could not be restored");

        Class<?>[] loaded = instrumentation.getAllLoadedClasses();
        List<Class<?>> candidates = new ArrayList<Class<?>>();
        Collections.addAll(candidates, loaded);
        Collections.sort(candidates, new Comparator<Class<?>>() {
            @Override public int compare(Class<?> left, Class<?> right) {
                return left.getName().compareTo(right.getName());
            }
        });

        List<PlannedClass> planned = new ArrayList<PlannedClass>();
        int scanned = 0;
        int unavailable = 0;
        int sites = 0;
        long plannedBytes = 0L;
        long deadline = System.nanoTime() + MAX_SCAN_NANOS;
        for (Class<?> type : candidates) {
            if (scanned >= MAX_SCANNED_CLASSES || System.nanoTime() >= deadline
                    || planned.size() >= config.maxClasses || sites >= config.maxSites) break;
            if (!eligible(type)) continue;
            scanned++;
            byte[] source = bytecode(type);
            if (source == null) {
                unavailable++;
                continue;
            }
            ApiHookInstrumenter.Result result;
            try {
                result = ApiHookInstrumenter.instrument(source, profiles);
            } catch (Throwable error) {
                unavailable++;
                continue;
            }
            if (result.sites == 0) continue;
            if (sites + result.sites > config.maxSites) break;
            long definitionBytes = source.length + result.bytecode.length;
            if (plannedBytes + definitionBytes > MAX_PLANNED_BYTES) break;
            planned.add(new PlannedClass(type, source, result.bytecode, result.sites));
            sites += result.sites;
            plannedBytes += definitionBytes;
        }

        int failures = 0;
        int appliedSites = 0;
        for (PlannedClass plan : planned) {
            try {
                instrumentation.redefineClasses(new ClassDefinition(plan.target, plan.replacement));
                hooked.put(plan.target, new HookedClass(plan.target, plan.baseBytecode));
                appliedSites += plan.sites;
            } catch (Throwable error) {
                failures++;
            }
        }
        if (hooked.isEmpty()) {
            ApiHookRuntime.clear();
            summary = "No matching, safely modifiable application call sites were found"
                    + " | scanned " + scanned + " | unavailable " + unavailable + " | failures " + failures;
            return summary;
        }

        ApiHookRuntime.configure(profiles, config, hooked.size(), appliedSites);
        expiration = scheduler.schedule(new Runnable() {
            @Override public void run() {
                stopAll();
            }
        }, config.stopAfterMillis, TimeUnit.MILLISECONDS);
        summary = "Active " + names(profiles) + " hooks | " + hooked.size() + " classes | "
                + appliedSites + " call sites | scanned " + scanned + " | unavailable " + unavailable
                + " | failures " + failures;
        return summary;
    }

    public synchronized String stopAll() {
        if (expiration != null) {
            expiration.cancel(false);
            expiration = null;
        }
        int restored = 0;
        int failures = 0;
        for (HookedClass value : new ArrayList<HookedClass>(hooked.values())) {
            try {
                instrumentation.redefineClasses(new ClassDefinition(value.target, value.baseBytecode));
                registry.recordApplied(value.target, value.baseBytecode);
                hooked.remove(value.target);
                restored++;
            } catch (Throwable error) {
                failures++;
            }
        }
        ApiHookRuntime.clear();
        summary = "Stopped API hooks and restored " + restored + " classes"
                + (failures == 0 ? "" : " | restore failures " + failures);
        return summary;
    }

    public synchronized void stopForClass(Class<?> target) throws Exception {
        HookedClass value = hooked.get(target);
        if (value == null) return;
        instrumentation.redefineClasses(new ClassDefinition(target, value.baseBytecode));
        registry.recordApplied(target, value.baseBytecode);
        hooked.remove(target);
        if (hooked.isEmpty()) {
            if (expiration != null) expiration.cancel(false);
            expiration = null;
            ApiHookRuntime.clear();
            summary = "No API hook profile is active";
        }
    }

    public synchronized String events() {
        return "M\t" + summary + "\n" + ApiHookRuntime.statusAndDrain();
    }

    public synchronized void close() {
        stopAll();
        scheduler.shutdownNow();
    }

    private boolean eligible(Class<?> type) {
        String name = type.getName();
        if (type.getClassLoader() == null || type.isArray() || type.isPrimitive()) return false;
        if (!instrumentation.isModifiableClass(type)) return false;
        if (name.startsWith("dev.whitedev.jpi.agent.") || name.startsWith("dev.whitedev.jpi.protocol.")
                || name.startsWith("org.objectweb.asm.")) return false;
        try {
            return Class.forName(ApiHookRuntime.class.getName(), false, type.getClassLoader()) == ApiHookRuntime.class;
        } catch (Throwable error) {
            return false;
        }
    }

    private byte[] bytecode(Class<?> type) {
        byte[] captured = registry.bytecodeFor(type);
        if (captured != null) return captured;
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        InputStream stream = type.getResourceAsStream(resource);
        if (stream == null) return null;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) >= 0) output.write(buffer, 0, read);
            byte[] value = output.toByteArray();
            registry.captureIfAbsent(type, value);
            return value;
        } catch (IOException error) {
            return null;
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String names(Set<ApiHookProfile> profiles) {
        StringBuilder output = new StringBuilder();
        for (ApiHookProfile profile : profiles) {
            if (output.length() > 0) output.append(", ");
            output.append(profile.displayName);
        }
        return output.toString();
    }

    private static final class PlannedClass {
        final Class<?> target;
        final byte[] baseBytecode;
        final byte[] replacement;
        final int sites;

        PlannedClass(Class<?> target, byte[] baseBytecode, byte[] replacement, int sites) {
            this.target = target;
            this.baseBytecode = baseBytecode;
            this.replacement = replacement;
            this.sites = sites;
        }
    }

    private static final class HookedClass {
        final Class<?> target;
        final byte[] baseBytecode;

        HookedClass(Class<?> target, byte[] baseBytecode) {
            this.target = target;
            this.baseBytecode = baseBytecode;
        }
    }
}

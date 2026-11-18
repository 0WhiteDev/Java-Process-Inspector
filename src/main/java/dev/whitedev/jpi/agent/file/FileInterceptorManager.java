package dev.whitedev.jpi.agent.file;

import dev.whitedev.jpi.agent.ClassTransformationCoordinator;
import dev.whitedev.jpi.agent.ClassTransformationCoordinator.Layer;
import dev.whitedev.jpi.agent.RuntimeAccess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public final class FileInterceptorManager {
    private static final int MAX_SCANNED = 50000;
    private static final long MAX_SCAN_NANOS = 15_000_000_000L;
    private static final long MAX_BYTES = 128L * 1024L * 1024L;
    private final RuntimeAccess instrumentation;
    private final ClassTransformationCoordinator coordinator;
    private final URL agentLocation;
    private final Set<Class<?>> transformed = Collections.newSetFromMap(new IdentityHashMap<>());
    private String summary = "File Monitor is stopped";

    public FileInterceptorManager(RuntimeAccess instrumentation, ClassTransformationCoordinator coordinator) {
        this.instrumentation = instrumentation;
        this.coordinator = coordinator;
        this.agentLocation = location(FileInterceptorRuntime.class);
    }

    public synchronized String start(String settings) throws Exception {
        Settings config = Settings.parse(settings);
        stop();
        if (!transformed.isEmpty()) throw new IOException("Previously intercepted classes could not be restored");
        List<Class<?>> classes = new ArrayList<Class<?>>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) classes.add(type);
        Collections.sort(classes, new Comparator<Class<?>>() {
            @Override
            public int compare(Class<?> left, Class<?> right) {
                return left.getName().compareTo(right.getName());
            }
        });
        int scanned = 0;
        int sites = 0;
        int failures = 0;
        String firstFailure = "";
        long bytes = 0L;
        long deadline = System.nanoTime() + MAX_SCAN_NANOS;
        FileInterceptorRuntime.configure(config.maxEvents, config.captureContent, config.previewBytes);
        for (Class<?> type : classes) {
            if (scanned >= MAX_SCANNED || transformed.size() >= config.maxClasses || sites >= config.maxSites
                    || bytes >= MAX_BYTES || System.nanoTime() >= deadline) break;
            if (!eligible(type)) continue;
            scanned++;
            byte[] source = bytecode(type);
            if (source == null) continue;
            try {
                FileCallSiteTransformer.Result result = FileCallSiteTransformer.transform(source);
                if (result.sites == 0) continue;
                if (sites + result.sites > config.maxSites || bytes + source.length + result.bytecode.length > MAX_BYTES)
                    break;
                int expectedSites = result.sites;
                coordinator.install(type, Layer.FILE_MONITOR, definition -> {
                    FileCallSiteTransformer.Result next = FileCallSiteTransformer.transform(definition);
                    if (next.sites != expectedSites) {
                        throw new IOException("Active layers changed the number of file call sites");
                    }
                    return next.bytecode;
                });
                transformed.add(type);
                sites += result.sites;
                bytes += source.length + result.bytecode.length;
            } catch (Throwable error) {
                if (failures++ == 0) firstFailure = " | " + error.getMessage();
            }
        }
        if (transformed.isEmpty()) {
            FileInterceptorRuntime.deactivate();
            throw new IOException("No safely modifiable Java file call sites were found" + firstFailure);
        }
        summary = "File Monitor active | " + transformed.size() + " classes | " + sites
                + " call sites | scanned " + scanned + " | failures " + failures + firstFailure;
        return summary;
    }

    public synchronized String stop() {
        int restored = 0;
        int failures = 0;
        for (Class<?> type : new ArrayList<>(transformed)) {
            try {
                coordinator.remove(type, Layer.FILE_MONITOR);
                transformed.remove(type);
                restored++;
            } catch (Throwable error) {
                failures++;
            }
        }
        if (transformed.isEmpty()) FileInterceptorRuntime.deactivate();
        summary = "File Monitor probes removed from " + restored + " classes"
                + (failures == 0 ? "" : " | restore failures " + failures);
        return summary;
    }

    public String events() {
        return "M\t" + encoded(summary) + "\n" + FileInterceptorRuntime.events();
    }

    public String clearEvents() {
        FileInterceptorRuntime.clearEvents();
        return "File Monitor events cleared";
    }

    public String putRule(String payload) {
        FileRule rule = parseRule(payload);
        if (rule.decision == FileDecision.SPOOF)
            throw new IllegalArgumentException("SPOOF is not enabled in the stable MVP");
        FileInterceptorRuntime.rules().put(rule);
        return rule.id;
    }

    public String removeRule(String id) {
        return FileInterceptorRuntime.rules().remove(id.trim()) ? "Removed " + id.trim() : "Rule not found";
    }

    public String rules() {
        StringBuilder output = new StringBuilder();
        for (FileRule rule : FileInterceptorRuntime.rules().rules()) output.append(ruleLine(rule)).append('\n');
        return output.toString();
    }

    public String setPolicy(String value) {
        FileDecision decision = FileDecision.valueOf(value.trim().toUpperCase());
        if (decision == FileDecision.SPOOF || decision == FileDecision.REDIRECT) {
            throw new IllegalArgumentException("Global policy supports only ALLOW or BLOCK");
        }
        FileInterceptorRuntime.rules().policy(decision);
        return decision.name();
    }

    public String policy() {
        return FileInterceptorRuntime.rules().policy().name();
    }

    public synchronized void close() {
        stop();
        FileInterceptorRuntime.clear();
    }

    private boolean eligible(Class<?> type) {
        String name = type.getName();
        if (type.getClassLoader() == null || type.isArray() || type.isPrimitive()) return false;
        if (!instrumentation.isModifiableClass(type)) return false;
        if (name.startsWith("dev.whitedev.jpi.agent.") || name.startsWith("dev.whitedev.jpi.protocol.")
                || name.startsWith("org.objectweb.asm.") || name.startsWith("org.eclipse.jdt.")
                || name.startsWith("javassist.") || name.startsWith("com.google.")
                || name.startsWith("com.formdev.") || name.startsWith("org.fife.")
                || name.startsWith("com.sun.jna.") || name.startsWith("com.sun.tools.")) return false;
        URL source = location(type);
        if (agentLocation != null && agentLocation.equals(source)) return false;
        try {
            return Class.forName(FileInterceptorRuntime.class.getName(), false, type.getClassLoader())
                    == FileInterceptorRuntime.class;
        } catch (Throwable error) {
            return false;
        }
    }

    private static URL location(Class<?> type) {
        try {
            return type.getProtectionDomain().getCodeSource().getLocation();
        } catch (Throwable error) {
            return null;
        }
    }

    private byte[] bytecode(Class<?> type) {
        byte[] known = coordinator.bytecodeFor(type);
        if (known != null) return known;
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            if (input == null) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            return output.toByteArray();
        } catch (Throwable error) {
            return null;
        }
    }

    private static FileRule parseRule(String line) {
        String[] values = line.split("\\t", -1);
        if (values.length != 6) throw new IllegalArgumentException("Invalid file rule payload");
        Set<FileOperation> operations = EnumSet.noneOf(FileOperation.class);
        if (!values[1].isEmpty() && !"ALL".equals(values[1])) {
            for (String operation : values[1].split(",")) operations.add(FileOperation.valueOf(operation));
        }
        return new FileRule(values[0], operations, decoded(values[2]), decoded(values[3]),
                FileDecision.valueOf(values[4]), decoded(values[5]));
    }

    private static String ruleLine(FileRule rule) {
        StringBuilder operations = new StringBuilder();
        for (FileOperation operation : rule.operations) {
            if (operations.length() > 0) operations.append(',');
            operations.append(operation);
        }
        return rule.id + "\t" + operations + "\t" + encoded(rule.pathPattern) + "\t"
                + encoded(rule.callerPattern) + "\t" + rule.decision + "\t" + encoded(rule.redirectRoot);
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static final class Settings {
        final int maxEvents;
        final int maxClasses;
        final int maxSites;
        final int previewBytes;
        final boolean captureContent;

        Settings(int maxEvents, int maxClasses, int maxSites, int previewBytes, boolean captureContent) {
            this.maxEvents = maxEvents;
            this.maxClasses = maxClasses;
            this.maxSites = maxSites;
            this.previewBytes = previewBytes;
            this.captureContent = captureContent;
        }

        static Settings parse(String value) {
            return new Settings(number(value, "maxEvents", 5000, 100, 50000),
                    number(value, "maxClasses", 2000, 1, 10000),
                    number(value, "maxSites", 10000, 1, 50000),
                    number(value, "previewBytes", 4096, 256, 16384), truth(value, "captureContent"));
        }

        private static int number(String source, String key, int fallback, int minimum, int maximum) {
            String value = setting(source, key);
            if (value.isEmpty()) return fallback;
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum)
                throw new IllegalArgumentException(key + " is outside its allowed range");
            return parsed;
        }

        private static boolean truth(String source, String key) {
            return Boolean.parseBoolean(setting(source, key));
        }

        private static String setting(String source, String key) {
            if (source == null) return "";
            for (String item : source.split("[;\\n]")) {
                int separator = item.indexOf('=');
                if (separator > 0 && key.equals(item.substring(0, separator).trim())) {
                    return item.substring(separator + 1).trim();
                }
            }
            return "";
        }
    }
}

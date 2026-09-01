package dev.whitedev.jpi.agent;

import dev.whitedev.jpi.agent.analysis.ConstantPoolSearch;
import dev.whitedev.jpi.agent.analysis.DeobfuscationInventory;
import dev.whitedev.jpi.agent.analysis.FieldWriteAnalyzer;
import dev.whitedev.jpi.agent.analysis.XrefAnalyzer;
import dev.whitedev.jpi.agent.cfg.BytecodeCfgAnalyzer;
import dev.whitedev.jpi.agent.cfg.CfgManager;
import dev.whitedev.jpi.agent.heap.HeapDumpService;
import dev.whitedev.jpi.agent.heap.HeapObjectInspector;
import dev.whitedev.jpi.agent.field.FieldWriteManager;
import dev.whitedev.jpi.agent.hook.ApiHookManager;
import dev.whitedev.jpi.agent.patch.ClassSchema;
import dev.whitedev.jpi.agent.patch.MethodBodyPatcher;
import dev.whitedev.jpi.agent.patch.ModernMethodPatcher;
import dev.whitedev.jpi.agent.patch.RuntimeJavaCompiler;
import dev.whitedev.jpi.agent.trace.TraceManager;
import dev.whitedev.jpi.agent.trace.TraceRuntime;
import org.objectweb.asm.Type;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.ClassDefinition;
import java.lang.management.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class TargetInspector {
    private static final String[] BUNDLED_COMPILER_TYPES = {
            "javax.annotation.CheckForNull",
            "javax.annotation.CheckReturnValue",
            "javax.annotation.Generated",
            "javax.annotation.Nonnull",
            "javax.annotation.Nullable",
            "javax.annotation.ParametersAreNonnullByDefault",
            "javax.annotation.RegEx",
            "javax.annotation.Signed",
            "javax.annotation.Syntax",
            "javax.annotation.Tainted",
            "javax.annotation.Untainted",
            "javax.annotation.WillClose",
            "javax.annotation.WillCloseWhenClosed",
            "javax.annotation.WillNotClose"
    };

    private final Instrumentation instrumentation;
    private final ClassRegistry registry;
    private final TraceManager traceManager;
    private final ApiHookManager apiHookManager;
    private final CfgManager cfgManager;
    private final FieldWriteManager fieldWriteManager;
    private final HeapObjectInspector heapInspector;
    private final Map<String, WeakReference<Class<?>>> classIndex = new ConcurrentHashMap<>();

    TargetInspector(Instrumentation instrumentation, ClassRegistry registry) {
        this.instrumentation = instrumentation;
        this.registry = registry;
        this.traceManager = new TraceManager(instrumentation);
        this.apiHookManager = new ApiHookManager(instrumentation, registry);
        this.cfgManager = new CfgManager(instrumentation);
        this.fieldWriteManager = new FieldWriteManager(instrumentation);
        this.heapInspector = new HeapObjectInspector(instrumentation);
    }

    String loadedClasses() {
        Class<?>[] loaded = instrumentation.getAllLoadedClasses();
        Arrays.sort(loaded, new Comparator<Class<?>>() {
            @Override public int compare(Class<?> left, Class<?> right) {
                int byName = left.getName().compareTo(right.getName());
                if (byName != 0) return byName;
                return ClassRegistry.loaderLabel(left.getClassLoader())
                        .compareTo(ClassRegistry.loaderLabel(right.getClassLoader()));
            }
        });
        Map<String, ClassRegistry.CapturedClass> captured = new HashMap<>();
        for (ClassRegistry.CapturedClass value : registry.capturedClasses()) {
            captured.put(value.key, value);
        }
        Set<String> matchedCaptures = new HashSet<>();
        StringBuilder output = new StringBuilder();
        classIndex.clear();
        for (Class<?> type : loaded) {
            String id = "c:" + Integer.toHexString(System.identityHashCode(type));
            classIndex.put(id, new WeakReference<>(type));
            String captureKey = registry.keyFor(type);
            ClassRegistry.CapturedClass bytes = captured.get(captureKey);
            if (bytes != null) matchedCaptures.add(captureKey);
            appendClass(output, id, type.getName(), ClassRegistry.loaderLabel(type.getClassLoader()),
                    moduleName(type), instrumentation.isModifiableClass(type), bytes != null,
                    classKind(type), bytes == null ? -1 : bytes.bytecode.length,
                    bytes == null ? -1 : bytes.capturedAt);
        }
        for (ClassRegistry.CapturedClass value : captured.values()) {
            if (matchedCaptures.contains(value.key)) continue;
            appendClass(output, "b:" + value.key, value.className, value.loader,
                    "<captured>", false, true, "captured-only",
                    value.bytecode.length, value.capturedAt);
        }
        return output.toString();
    }

    byte[] classBytes(String identifier) throws Exception {
        if (identifier.startsWith("b:")) {
            byte[] captured = registry.bytecodeByKey(identifier.substring(2));
            if (captured == null) throw new IOException("Captured bytecode has been evicted");
            return captured;
        }
        Class<?> target = resolveClass(identifier);
        byte[] captured = registry.bytecodeFor(target);
        if (captured != null) return captured;
        if (instrumentation.isRetransformClassesSupported() && instrumentation.isModifiableClass(target)) {
            instrumentation.retransformClasses(target);
            captured = registry.bytecodeFor(target);
            if (captured != null) return captured;
        }
        String resource = "/" + target.getName().replace('.', '/') + ".class";
        InputStream stream = target.getResourceAsStream(resource);
        if (stream == null) throw new IOException("Bytecode is unavailable for " + target.getName());
        try {
            byte[] bytes = readAll(stream);
            registry.captureIfAbsent(target, bytes);
            return bytes;
        } finally { stream.close(); }
    }

    String redefineSource(String payload) throws Exception {
        int separator = payload.indexOf('\n');
        if (separator <= 0 || separator == payload.length() - 1) throw new IOException("Missing class identifier or Java source");
        String identifier = payload.substring(0, separator);
        String source = payload.substring(separator + 1);
        Class<?> target = resolveClass(identifier);
        requireRedefinable(target);
        apiHookManager.stopForClass(target);
        byte[] current = classBytes(identifier);
        byte[] replacement = RuntimeJavaCompiler.compile(target.getName(), source, runtimeClassPath(target));
        applyDefinition(target, current, replacement);
        return "Redefined " + target.getName() + " with " + replacement.length + " bytes";
    }

    String classMethods(String identifier) throws Exception {
        Class<?> target = resolveClass(identifier);
        return MethodBodyPatcher.methods(classBytes(identifier), target.getClassLoader());
    }

    String patchMethod(String payload) throws Exception {
        int first = payload.indexOf('\n');
        int second = first < 0 ? -1 : payload.indexOf('\n', first + 1);
        int third = second < 0 ? -1 : payload.indexOf('\n', second + 1);
        if (first <= 0 || second <= first || third <= second || third == payload.length() - 1) {
            throw new IOException("Missing class, method, descriptor, or method body");
        }
        String identifier = payload.substring(0, first);
        String method = payload.substring(first + 1, second);
        String descriptor = payload.substring(second + 1, third);
        String body = payload.substring(third + 1);
        Class<?> target = resolveClass(identifier);
        requireRedefinable(target);
        apiHookManager.stopForClass(target);
        byte[] current = classBytes(identifier);
        byte[] replacement;
        String compiler;
        try {
            replacement = ModernMethodPatcher.patch(
                    current, target, method, descriptor, body, runtimeClassPath(target));
            compiler = "modern Java compiler";
        } catch (Exception modernError) {
            try {
                replacement = MethodBodyPatcher.patch(current, target.getClassLoader(), method, descriptor, body);
                compiler = "Javassist";
            } catch (Exception legacyError) {
                String modernMessage = modernError.getMessage() == null
                        ? modernError.getClass().getSimpleName() : modernError.getMessage();
                String legacyMessage = legacyError.getMessage() == null
                        ? legacyError.getClass().getSimpleName() : legacyError.getMessage();
                throw new IOException("Modern Java method compilation failed: " + modernMessage
                        + (char) 10 + "Javassist fallback failed: " + legacyMessage, modernError);
            }
        }
        applyDefinition(target, current, replacement);
        return "Patched " + target.getName() + "." + method + descriptor + " with " + compiler;
    }

    String startTrace(String payload) throws Exception {
        int first = payload.indexOf('\n');
        int second = first < 0 ? -1 : payload.indexOf('\n', first + 1);
        int third = second < 0 ? -1 : payload.indexOf('\n', second + 1);
        if (first <= 0 || second <= first || third <= second) {
            throw new IOException("Missing class, method, descriptor, or trace settings");
        }
        String identifier = payload.substring(0, first);
        String method = payload.substring(first + 1, second);
        String descriptor = payload.substring(second + 1, third);
        String settings = payload.substring(third + 1);
        Class<?> target = resolveClass(identifier);
        requireRedefinable(target);
        cfgManager.stopForClass(target);
        apiHookManager.stopForClass(target);
        fieldWriteManager.stopForClass(target);
        return traceManager.start(target, identifier, method, descriptor, settings, classBytes(identifier));
    }

    String stopTrace(String probeId) throws Exception {
        return traceManager.stop(probeId);
    }

    String traceEvents() {
        return traceManager.events();
    }

    String traceGraph() {
        return traceManager.graph();
    }

    String clearTraceGraph() {
        return traceManager.clearGraph();
    }

    String bytecodeCfg(String payload) throws Exception {
        String[] values = payload.split("\n", 3);
        if (values.length != 3 || values[0].isEmpty() || values[1].isEmpty() || values[2].isEmpty()) {
            throw new IOException("Missing class, method, or descriptor");
        }
        return BytecodeCfgAnalyzer.analyze(classBytes(values[0]), values[1], values[2]);
    }

    String startCfgTrace(String payload) throws Exception {
        String[] values = payload.split("\n", 4);
        if (values.length != 4 || values[0].isEmpty() || values[1].isEmpty()
                || values[2].isEmpty() || values[3].isEmpty()) {
            throw new IOException("Missing class, method, descriptor, or CFG trace duration");
        }
        long duration;
        try {
            duration = Long.parseLong(values[3]);
        } catch (NumberFormatException error) {
            throw new IOException("Invalid CFG trace duration", error);
        }
        Class<?> target = resolveClass(values[0]);
        requireRedefinable(target);
        traceManager.stopForClass(target);
        apiHookManager.stopForClass(target);
        fieldWriteManager.stopForClass(target);
        return cfgManager.start(target, values[1], values[2], classBytes(values[0]), duration);
    }

    String stopCfgTrace(String probeId) throws Exception {
        return cfgManager.stop(probeId);
    }

    String cfgSnapshot(String probeId) {
        return cfgManager.snapshot(probeId);
    }

    String startApiHooks(String payload) throws Exception {
        cfgManager.stopAll();
        traceManager.stopAll();
        fieldWriteManager.stopAll();
        return apiHookManager.start(payload);
    }

    String stopApiHooks() {
        return apiHookManager.stopAll();
    }

    String apiHookEvents() {
        return apiHookManager.events();
    }

    String fieldWriteSites(String payload) throws Exception {
        FieldTarget field = fieldTarget(payload, false);
        StringBuilder output = new StringBuilder();
        output.append('F').append('\t').append(encoded(field.targetIdentifier)).append('\t')
                .append(encoded(field.owner)).append('\t').append(encoded(field.name)).append('\t')
                .append(encoded(field.descriptor)).append('\t').append(field.modifiers).append('\n');
        int scanned = 0;
        int results = 0;
        long deadline = System.nanoTime() + 10_000_000_000L;
        String owner = field.owner.replace('.', '/');
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            if (++scanned > 10_000 || results >= 1_000 || System.nanoTime() > deadline) break;
            byte[] bytecode = availableBytes(candidate);
            if (bytecode == null) continue;
            String identifier = index(candidate);
            try {
                for (FieldWriteAnalyzer.WriteSite site : FieldWriteAnalyzer.find(bytecode, identifier,
                        candidate.getName(), owner, field.name, field.descriptor)) {
                    output.append('W').append('\t').append(encoded(site.classIdentifier)).append('\t')
                            .append(encoded(site.className)).append('\t').append(encoded(site.methodName)).append('\t')
                            .append(encoded(site.methodDescriptor)).append('\t').append(site.line).append('\t')
                            .append(site.opcode).append('\t').append(site.count).append('\n');
                    if (++results >= 1_000) break;
                }
            } catch (IOException ignored) {
            }
        }
        return output.toString();
    }

    String startFieldTrace(String payload) throws Exception {
        FieldTarget field = fieldTarget(payload, true);
        fieldWriteManager.stopAll();
        Map<Class<?>, byte[]> candidates = new LinkedHashMap<Class<?>, byte[]>();
        String owner = field.owner.replace('.', '/');
        int maximumClasses = setting(field.settings, "maxClasses", 500, 1, 2_000);
        int scanned = 0;
        long deadline = System.nanoTime() + 10_000_000_000L;
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            if (++scanned > 10_000 || candidates.size() >= maximumClasses || System.nanoTime() > deadline) break;
            if (!instrumentation.isModifiableClass(candidate) || candidate.isArray() || candidate.isPrimitive()) continue;
            byte[] bytecode = availableBytes(candidate);
            if (bytecode == null) continue;
            try {
                if (FieldWriteAnalyzer.count(bytecode, owner, field.name, field.descriptor) > 0) {
                    candidates.put(candidate, bytecode);
                }
            } catch (IOException ignored) {
            }
        }
        if (candidates.isEmpty()) throw new IOException("No loaded bytecode writes to " + field.owner + "." + field.name);
        for (Class<?> candidate : candidates.keySet()) {
            cfgManager.stopForClass(candidate);
            traceManager.stopForClass(candidate);
            apiHookManager.stopForClass(candidate);
        }
        return fieldWriteManager.start(field.targetIdentifier, field.owner, field.name, field.descriptor,
                field.settings, candidates);
    }

    String stopFieldTrace() {
        return fieldWriteManager.stopAll();
    }

    String fieldTraceEvents() {
        return fieldWriteManager.events();
    }

    String heapScan(String payload) throws Exception {
        return heapInspector.scan(payload);
    }

    String heapObject(String objectId) throws Exception {
        return heapInspector.object(objectId);
    }

    String heapDump(String path) throws Exception {
        return HeapDumpService.dump(path);
    }

    String methodXrefs(String payload) throws Exception {
        String[] values = payload.split("\\n", 3);
        if (values.length != 3 || values[0].isEmpty() || values[1].isEmpty() || values[2].isEmpty()) {
            throw new IOException("Missing class, method, or descriptor");
        }
        Class<?> target = resolveClass(values[0]);
        StringBuilder output = new StringBuilder();
        for (XrefAnalyzer.Reference reference : XrefAnalyzer.references(classBytes(values[0]), values[1], values[2])) {
            appendXref(output, "STATIC", reference);
        }
        String owner = target.getName().replace('.', '/');
        int scanned = 0;
        int results = 0;
        long deadline = System.nanoTime() + 10_000_000_000L;
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            if (++scanned > 5000 || System.nanoTime() > deadline || results >= 1000) break;
            byte[] bytecode = availableBytes(candidate);
            if (bytecode == null) continue;
            String id = index(candidate);
            try {
                List<XrefAnalyzer.Reference> callers = XrefAnalyzer.callers(bytecode, id,
                        candidate.getName(), owner, values[1], values[2]);
                for (XrefAnalyzer.Reference reference : callers) {
                    appendXref(output, "STATIC", reference);
                    results++;
                    if (results >= 1000) break;
                }
            } catch (IOException ignored) {
            }
        }
        output.append(TraceRuntime.dynamicGraph(target.getName(), values[1], values[2]));
        return output.toString();
    }

    String deobfuscationInventory(String payload) throws IOException {
        String[] values = payload == null ? new String[0] : payload.split("\n", -1);
        String scope = values.length == 0 ? "" : values[0].trim();
        if (scope.isEmpty()) throw new IOException("Enter a package or class prefix for the mapping scope");
        if (scope.length() > 300) throw new IOException("Mapping scope is limited to 300 characters");
        List<String> exclusions = new ArrayList<String>();
        if (values.length > 1) {
            for (String value : values[1].split("[,;]")) {
                String exclusion = value.trim();
                if (!exclusion.isEmpty()) exclusions.add(exclusion);
            }
        }
        StringBuilder output = new StringBuilder();
        Set<String> visited = new HashSet<String>();
        int classes = 0;
        int entries = 0;
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (output.length() >= 12 * 1024 * 1024) break;
            String name = type.getName();
            if (name.startsWith("[") || !within(name, scope) || excluded(name, exclusions)) continue;
            if (!visited.add(name) || ++classes > 5000 || entries >= 100000) continue;
            byte[] bytecode = availableBytes(type);
            if (bytecode != null) entries += DeobfuscationInventory.append(output, bytecode, 100000 - entries);
        }
        return output.toString();
    }

    static boolean within(String className, String scope) {
        if ("<default>".equals(scope)) return className.indexOf('.') < 0;
        return className.equals(scope) || className.startsWith(scope + ".") || className.startsWith(scope + "$");
    }

    private static boolean excluded(String className, List<String> exclusions) {
        for (String exclusion : exclusions) {
            if (className.equals(exclusion) || className.startsWith(exclusion + ".")
                    || className.startsWith(exclusion + "$")) return true;
        }
        return false;
    }
    String xrefSearch(String query) throws IOException {
        String needle = query == null ? "" : query.trim();
        if (needle.length() < 2) throw new IOException("Enter at least two characters");
        if (needle.length() > 200) throw new IOException("Search query is limited to 200 characters");
        StringBuilder output = new StringBuilder();
        int scanned = 0;
        int results = 0;
        long deadline = System.nanoTime() + 10_000_000_000L;
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            if (++scanned > 5000 || System.nanoTime() > deadline || results >= 1000) break;
            byte[] bytecode = availableBytes(candidate);
            if (bytecode == null) continue;
            String id = index(candidate);
            try {
                for (XrefAnalyzer.Reference reference : XrefAnalyzer.stringUsers(
                        bytecode, id, candidate.getName(), needle)) {
                    appendXref(output, "STATIC", reference);
                    results++;
                    if (results >= 1000) break;
                }
            } catch (IOException ignored) {
            }
        }
        return output.toString();
    }

    void close() {
        cfgManager.close();
        traceManager.close();
        apiHookManager.close();
        fieldWriteManager.close();
        heapInspector.clear();
    }

    String applyClassBytes(String payload) throws Exception {
        int separator = payload.indexOf('\n');
        if (separator <= 0 || separator == payload.length() - 1) throw new IOException("Missing class identifier or bytecode");
        String identifier = payload.substring(0, separator);
        byte[] replacement;
        try {
            replacement = Base64.getDecoder().decode(payload.substring(separator + 1));
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid Base64 class bytecode", error);
        }
        if (replacement.length < 16 || replacement.length > 24 * 1024 * 1024) {
            throw new IOException("Class bytecode size is outside the supported range");
        }
        Class<?> target = resolveClass(identifier);
        requireRedefinable(target);
        apiHookManager.stopForClass(target);
        byte[] current = classBytes(identifier);
        applyDefinition(target, current, replacement);
        return "Applied " + replacement.length + " byte class definition to " + target.getName();
    }

    String rollbackClass(String identifier) throws Exception {
        Class<?> target = resolveClass(identifier);
        requireRedefinable(target);
        cfgManager.stopForClass(target);
        traceManager.stopForClass(target);
        apiHookManager.stopForClass(target);
        fieldWriteManager.stopForClass(target);
        if (registry.originalBytecodeFor(target) == null) classBytes(identifier);
        byte[] original = registry.originalBytecodeFor(target);
        if (original == null) throw new IOException("Original bytecode is unavailable for " + target.getName());
        byte[] current = registry.bytecodeFor(target);
        if (current != null) ClassSchema.verifyCompatible(current, original);
        instrumentation.redefineClasses(new ClassDefinition(target, original));
        registry.recordApplied(target, original);
        return "Restored the original definition of " + target.getName();
    }

    String classEvents() {
        return registry.events();
    }

    String constantSearch(String query) throws IOException {
        String needle = query == null ? "" : query.trim();
        if (needle.length() < 2) throw new IOException("Enter at least two characters");
        if (needle.length() > 200) throw new IOException("Search query is limited to 200 characters");
        final int resultLimit = 1_000;
        final int classLimit = 50_000;
        final long deadline = System.nanoTime() + 10_000_000_000L;
        StringBuilder output = new StringBuilder();
        Set<String> searched = new HashSet<>();
        int results = 0;
        int scanned = 0;

        for (ClassRegistry.CapturedClass captured : registry.capturedClasses()) {
            if (++scanned > classLimit || System.nanoTime() > deadline) return searchStopped(output, scanned);
            searched.add(captured.key);
            results = appendConstantMatches(output, captured.className, captured.loader,
                    captured.bytecode, needle, results, resultLimit);
            if (results >= resultLimit) return limited(output, resultLimit);
        }
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.isArray() || type.isPrimitive() || !searched.add(registry.keyFor(type))) continue;
            if (++scanned > classLimit || System.nanoTime() > deadline) return searchStopped(output, scanned);
            byte[] bytes = resourceBytes(type);
            if (bytes == null) continue;
            results = appendConstantMatches(output, type.getName(), ClassRegistry.loaderLabel(type.getClassLoader()),
                    bytes, needle, results, resultLimit);
            if (results >= resultLimit) return limited(output, resultLimit);
        }
        return output.toString();
    }

    String metrics() {
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        RuntimeMXBean vm = ManagementFactory.getRuntimeMXBean();
        StringBuilder out = new StringBuilder();
        metric(out, "pid", vm.getName().split("@", 2)[0]);
        metric(out, "jvm", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        metric(out, "uptime.ms", vm.getUptime());
        metric(out, "heap.used", memory.getHeapMemoryUsage().getUsed());
        metric(out, "heap.committed", memory.getHeapMemoryUsage().getCommitted());
        metric(out, "heap.max", memory.getHeapMemoryUsage().getMax());
        metric(out, "nonheap.used", memory.getNonHeapMemoryUsage().getUsed());
        metric(out, "threads.live", threads.getThreadCount());
        metric(out, "threads.peak", threads.getPeakThreadCount());
        metric(out, "classes.loaded", ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
        metric(out, "processors", runtime.availableProcessors());
        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0) gcCount += gc.getCollectionCount();
            if (gc.getCollectionTime() > 0) gcTime += gc.getCollectionTime();
        }
        metric(out, "gc.count", gcCount);
        metric(out, "gc.time.ms", gcTime);
        return out.toString();
    }

    String environment() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        StringBuilder output = new StringBuilder();
        output.append("JVM\n");
        output.append("  Name: ").append(runtime.getVmName()).append('\n');
        output.append("  Vendor: ").append(runtime.getVmVendor()).append('\n');
        output.append("  Version: ").append(runtime.getVmVersion()).append('\n');
        output.append("  Start time: ").append(new Date(runtime.getStartTime())).append('\n');
        output.append("  Command: ").append(System.getProperty("sun.java.command", "<unknown>")).append("\n\n");
        output.append("VM ARGUMENTS\n");
        for (String argument : runtime.getInputArguments()) output.append("  ").append(argument).append('\n');
        output.append("\nCLASS LOADERS\n");
        Map<String, Integer> loaders = new TreeMap<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            String loader = ClassRegistry.loaderLabel(type.getClassLoader());
            loaders.put(loader, loaders.containsKey(loader) ? loaders.get(loader) + 1 : 1);
        }
        for (Map.Entry<String, Integer> entry : loaders.entrySet()) {
            output.append("  ").append(entry.getValue()).append(" classes  ").append(entry.getKey()).append('\n');
        }
        output.append("\nSYSTEM PROPERTIES\n");
        Properties properties = System.getProperties();
        List<String> names = new ArrayList<>(properties.stringPropertyNames());
        Collections.sort(names);
        for (String name : names) {
            String value = sensitive(name) ? "<redacted>" : properties.getProperty(name, "");
            output.append("  ").append(name).append(" = ").append(value).append('\n');
        }
        return output.toString();
    }

    String staticFields(String query) {
        String filter = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (filter.isEmpty() && isPlatformClass(type.getName())) continue;
            if (!filter.isEmpty() && !type.getName().toLowerCase(Locale.ROOT).contains(filter)) continue;
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    out.append(escape(type.getName())).append('\t').append(escape(field.getName())).append('\t')
                            .append(escape(field.getType().getTypeName())).append('\t').append(safeValue(value)).append('\n');
                    if (++count >= 1000) {
                        return out.append("...\t...\t...\tResult limited to 1000 fields\n").toString();
                    }
                } catch (Throwable ignored) { }
            }
        }
        return out.toString();
    }

    String threadDump() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        StringBuilder out = new StringBuilder();
        long[] deadlocked = bean.findDeadlockedThreads();
        if (deadlocked != null) out.append("DEADLOCKED: ").append(Arrays.toString(deadlocked)).append("\n\n");
        for (ThreadInfo info : bean.dumpAllThreads(true, true)) {
            out.append(info.getThreadName()).append(" #").append(info.getThreadId())
                    .append(' ').append(info.getThreadState()).append('\n');
            for (StackTraceElement frame : info.getStackTrace()) out.append("    at ").append(frame).append('\n');
            out.append('\n');
        }
        return out.toString();
    }

    private RuntimeJavaCompiler.ClassPath runtimeClassPath(final Class<?> target) {
        final Map<String, Class<?>> classes = new LinkedHashMap<String, Class<?>>();
        Class<?>[] loaded = instrumentation.getAllLoadedClasses();
        for (Class<?> type : loaded) {
            if (type.getClassLoader() == target.getClassLoader()) classes.put(type.getName(), type);
        }
        for (Class<?> type : loaded) {
            if (!classes.containsKey(type.getName())) classes.put(type.getName(), type);
        }
        final Map<String, byte[]> cache = new HashMap<String, byte[]>();
        return new RuntimeJavaCompiler.ClassPath() {
            @Override public byte[] find(String binaryName) {
                byte[] cached = cache.get(binaryName);
                if (cached != null) return cached;
                Class<?> type = classes.get(binaryName);
                byte[] bytecode = type == null ? null : registry.bytecodeFor(type);
                if (bytecode == null && type != null) bytecode = resourceBytes(type);
                if (bytecode == null) bytecode = loaderResource(target.getClassLoader(), binaryName);
                if (bytecode == null) bytecode = loaderResource(RuntimeJavaCompiler.class.getClassLoader(), binaryName);
                if (bytecode == null) bytecode = loaderResource(ClassLoader.getSystemClassLoader(), binaryName);
                if (bytecode != null) cache.put(binaryName, bytecode);
                return bytecode;
            }

            @Override public Collection<String> list(String packageName, boolean recurse) {
                List<String> names = new ArrayList<String>();
                for (String name : classes.keySet()) {
                    int separator = name.lastIndexOf('.');
                    String typePackage = separator < 0 ? "" : name.substring(0, separator);
                    if (typePackage.equals(packageName)
                            || recurse && typePackage.startsWith(packageName.isEmpty() ? "" : packageName + ".")) {
                        names.add(name);
                    }
                }
                for (String name : BUNDLED_COMPILER_TYPES) {
                    int separator = name.lastIndexOf('.');
                    String typePackage = separator < 0 ? "" : name.substring(0, separator);
                    if (typePackage.equals(packageName)
                            || recurse && typePackage.startsWith(packageName.isEmpty() ? "" : packageName + ".")) {
                        names.add(name);
                    }
                }
                return names;
            }
        };
    }

    private static byte[] loaderResource(ClassLoader loader, String binaryName) {
        if (loader == null) return null;
        String resource = binaryName.replace('.', '/') + ".class";
        try (InputStream input = loader.getResourceAsStream(resource)) {
            return input == null ? null : readAll(input);
        } catch (Throwable ignored) {
            return null;
        }
    }
    private byte[] availableBytes(Class<?> type) {
        byte[] bytecode = registry.bytecodeFor(type);
        return bytecode == null ? resourceBytes(type) : bytecode;
    }

    private String index(Class<?> type) {
        String id = "c:" + Integer.toHexString(System.identityHashCode(type));
        classIndex.put(id, new WeakReference<Class<?>>(type));
        return id;
    }

    private static void appendXref(StringBuilder output, String layer, XrefAnalyzer.Reference reference) {
        output.append('R').append('\t').append(layer).append('\t')
                .append(reference.relation).append('\t').append(reference.count).append('\t')
                .append(encoded(reference.targetIdentifier)).append('\t')
                .append(encoded(reference.className)).append('\t')
                .append(encoded(reference.member)).append('\t')
                .append(encoded(reference.descriptor)).append('\t')
                .append(encoded(reference.detail)).append('\n');
    }

    private static String encoded(String value) {
        if (value == null || value.isEmpty()) return "";
        return Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Class<?> resolveClass(String identifier) throws ClassNotFoundException {
        WeakReference<Class<?>> reference = classIndex.get(identifier);
        Class<?> indexed = reference == null ? null : reference.get();
        if (indexed != null) return indexed;
        Class<?> fallback = null;
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.getName().equals(identifier)) {
                if (instrumentation.isModifiableClass(type)) return type;
                fallback = type;
            }
        }
        if (fallback != null) return fallback;
        throw new ClassNotFoundException(identifier);
    }

    private void requireRedefinable(Class<?> target) throws IOException {
        if (!instrumentation.isRedefineClassesSupported()) throw new IOException("This JVM does not support class redefinition");
        if (!instrumentation.isModifiableClass(target)) throw new IOException("The selected class is not modifiable");
        if (target.isArray() || target.isPrimitive()) throw new IOException("Array and primitive classes cannot be redefined");
    }

    private void applyDefinition(Class<?> target, byte[] current, byte[] replacement) throws Exception {
        cfgManager.stopForClass(target);
        traceManager.stopForClass(target);
        apiHookManager.stopForClass(target);
        fieldWriteManager.stopForClass(target);
        ClassSchema.verifyCompatible(current, replacement);
        instrumentation.redefineClasses(new ClassDefinition(target, replacement));
        registry.recordApplied(target, replacement);
    }

    private static void appendClass(StringBuilder output, String id, String name, String loader,
                                    String module, boolean modifiable, boolean captured, String kind,
                                    int bytes, long capturedAt) {
        output.append(escape(id)).append('\t').append(escape(name)).append('\t')
                .append(escape(loader)).append('\t').append(escape(module)).append('\t')
                .append(modifiable).append('\t').append(captured).append('\t')
                .append(kind).append('\t').append(bytes).append('\t').append(capturedAt).append('\n');
    }

    private static String moduleName(Class<?> type) {
        try {
            Method getModule = Class.class.getMethod("getModule");
            Object module = getModule.invoke(type);
            Method getName = module.getClass().getMethod("getName");
            Object name = getName.invoke(module);
            return name == null ? "<unnamed>" : String.valueOf(name);
        } catch (Throwable ignored) {
            return "<Java 8>";
        }
    }

    private static String classKind(Class<?> type) {
        if (type.isArray()) return "array";
        if (type.isSynthetic()) return "synthetic";
        try {
            Method isHidden = Class.class.getMethod("isHidden");
            if (Boolean.TRUE.equals(isHidden.invoke(type))) return "hidden";
        } catch (Throwable ignored) { }
        return type.isInterface() ? "interface" : type.isEnum() ? "enum" : "class";
    }

    private static boolean sensitive(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("secret")
                || lower.contains("token") || lower.endsWith(".key");
    }

    private static boolean isPlatformClass(String name) {
        return name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("jdk.") || name.startsWith("sun.");
    }

    private static String safeValue(Object value) {
        if (value == null) return "null";
        String text;
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?>) {
            text = String.valueOf(value);
        } else {
            text = "<" + value.getClass().getName() + "@"
                    + Integer.toHexString(System.identityHashCode(value)) + ">";
        }
        if (text.length() > 500) text = text.substring(0, 500) + "...";
        return escape(text);
    }

    private static int appendConstantMatches(StringBuilder output, String className, String loader,
                                             byte[] bytecode, String query, int count, int limit) {
        try {
            Set<String> unique = new LinkedHashSet<>(ConstantPoolSearch.find(bytecode, query, Math.min(25, limit - count)));
            for (String value : unique) {
                output.append(escape(className)).append('\t').append(escape(loader)).append('\t')
                        .append(value).append('\n');
                if (++count >= limit) break;
            }
        } catch (IOException ignored) { }
        return count;
    }

    private static String limited(StringBuilder output, int limit) {
        return output.append("<limit>\t<limit>\tResult limited to ").append(limit).append(" matches\n").toString();
    }

    private static String searchStopped(StringBuilder output, int scanned) {
        return output.append("<limit>\t<limit>\tSearch stopped after ").append(scanned)
                .append(" classes or 10 seconds\n").toString();
    }

    private static byte[] resourceBytes(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream stream = type.getResourceAsStream(resource)) {
            return stream == null ? null : readAll(stream);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\r", "").replace("\n", "\\n");
    }

    private static void metric(StringBuilder out, String key, Object value) {
        out.append(key).append('=').append(value).append('\n');
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) >= 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private FieldTarget fieldTarget(String payload, boolean settingsRequired) throws Exception {
        String[] values = payload == null ? new String[0] : payload.split("\\n", 3);
        if (values.length < 2 || values[0].isEmpty() || values[1].isEmpty()
                || settingsRequired && values.length < 3) {
            throw new IOException("Missing field owner, field name, or trace settings");
        }
        Class<?> owner = resolveClass(values[0]);
        Field selected;
        try {
            selected = owner.getDeclaredField(values[1]);
        } catch (NoSuchFieldException error) {
            throw new IOException("Field is not declared by " + owner.getName() + ": " + values[1], error);
        }
        return new FieldTarget(values[0], owner.getName(), selected.getName(), Type.getDescriptor(selected.getType()),
                selected.getModifiers(), values.length < 3 ? "" : values[2]);
    }

    private static int setting(String settings, String key, int fallback, int minimum, int maximum) throws IOException {
        for (String item : settings.split("[;\\n]")) {
            int separator = item.indexOf('=');
            if (separator <= 0 || !key.equals(item.substring(0, separator).trim())) continue;
            try {
                int value = Integer.parseInt(item.substring(separator + 1).trim());
                if (value < minimum || value > maximum) {
                    throw new IOException(key + " must be between " + minimum + " and " + maximum);
                }
                return value;
            } catch (NumberFormatException error) {
                throw new IOException("Invalid " + key, error);
            }
        }
        return fallback;
    }

    private static final class FieldTarget {
        final String targetIdentifier;
        final String owner;
        final String name;
        final String descriptor;
        final int modifiers;
        final String settings;

        FieldTarget(String targetIdentifier, String owner, String name, String descriptor,
                    int modifiers, String settings) {
            this.targetIdentifier = targetIdentifier;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.modifiers = modifiers;
            this.settings = settings;
        }
    }
}

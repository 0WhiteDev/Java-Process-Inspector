package dev.whitedev.jpi.mcp;

import com.google.gson.reflect.TypeToken;
import java.nio.charset.StandardCharsets;
import org.objectweb.asm.Type;

import com.google.gson.JsonObject;
import dev.whitedev.jpi.decompile.DecompilerEngine;
import dev.whitedev.jpi.decompile.DecompilerService;
import dev.whitedev.jpi.exceptions.ExceptionSnapshot;
import dev.whitedev.jpi.fingerprint.TargetFingerprint;
import dev.whitedev.jpi.investigation.assistant.InvestigationAssistant;
import dev.whitedev.jpi.loader.ClassLoaderSnapshot;
import dev.whitedev.jpi.mcp.dto.ClassSummary;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.threads.ThreadAnalysisSnapshot;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class JpiMcpFacade {
    private static final Set<Operation> READS = EnumSet.of(Operation.SESSION_INFO, Operation.CLASSES,
            Operation.CLASS_BYTES, Operation.CONSTANT_SEARCH, Operation.METHOD_XREFS, Operation.XREF_SEARCH,
            Operation.CFG_ANALYZE, Operation.THREAD_SNAPSHOT, Operation.EXCEPTION_SNAPSHOT,
            Operation.TARGET_FINGERPRINT, Operation.TRACE_HISTORY, Operation.CLASSLOADER_SNAPSHOT);
    private final McpSessionState state;
    private final DecompilerService decompiler = new DecompilerService();

    public JpiMcpFacade(McpSessionState state) { this.state = state; }

    public Map<String, Object> execute(String tool, JsonObject arguments) throws Exception {
        return execute(tool, arguments, state.snapshot());
    }

    public Map<String, Object> execute(String tool, JsonObject arguments, McpSessionState.Snapshot expected) throws Exception {
        Scope scope = new Scope(expected);
        scope.check();
        if (!tool.equals("jpi_get_session")) scope.requireSession();
        Map<String, Object> result = switch (tool) {
            case "jpi_get_session" -> scope.session();
            case "jpi_search_classes" -> scope.searchClasses(text(arguments, "query", 1, 512), integer(arguments, "limit", 50, 1, 500));
            case "jpi_get_class" -> scope.getClass(classId(arguments));
            case "jpi_get_method" -> scope.getMethod(classId(arguments), member(arguments), descriptor(arguments));
            case "jpi_search_constants" -> scope.constants(text(arguments, "query", 2, 200), integer(arguments, "limit", 100, 1, 500));
            case "jpi_decompile_class", "jpi_decompile_method" -> scope.decompile(classId(arguments),
                    tool.endsWith("method") ? member(arguments) : null,
                    tool.endsWith("method") ? descriptor(arguments) : null, engine(arguments));
            case "jpi_find_xrefs" -> scope.xrefs(classId(arguments), optional(arguments, "methodName"), optional(arguments, "descriptor"));
            case "jpi_build_cfg" -> scope.cfg(classId(arguments), member(arguments), descriptor(arguments));
            case "jpi_build_call_graph" -> scope.callGraph(classId(arguments), member(arguments), descriptor(arguments),
                    integer(arguments, "maxDepth", 3, 0, 8), integer(arguments, "maxNodes", 200, 1, 2000));
            case "jpi_get_threads" -> scope.threads();
            case "jpi_get_exceptions" -> scope.exceptions(integer(arguments, "limit", 100, 1, 500));
            case "jpi_get_target_fingerprint" -> scope.fingerprint();
            case "jpi_investigate" -> scope.investigate(text(arguments, "query", 2, 200), integer(arguments, "maxResults", 20, 1, 100));
            default -> throw new McpException(McpErrorCode.INVALID_ARGUMENT, "Unknown JPI tool: " + tool);
        };
        scope.check();
        result = new LinkedHashMap<>(result);
        if (scope.snapshot.session() != null) result.put("context", scope.context());
        return McpJson.fit(result);
    }

    public Map<String, Object> session() throws Exception { return execute("jpi_get_session", new JsonObject()); }

    private final class Scope {
        private final McpSessionState.Snapshot snapshot;
        private final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        private List<ClassSummary> inventory;
        private final Map<String, ClassSummary> byId = new LinkedHashMap<>();
        private final Map<String, List<ClassSummary>> byName = new LinkedHashMap<>();
        private final Map<String, List<ClassSummary>> byNameLoader = new LinkedHashMap<>();
        private String fingerprint;
        private TargetFingerprint capturedFingerprint;

        private Scope(McpSessionState.Snapshot snapshot) { this.snapshot = snapshot; }
        private JpiMcpContext context() { return new JpiMcpContext(snapshot.id(), snapshot.session().target().id(), fingerprint); }
        private void requireSession() {
            if (snapshot.session() == null) throw new McpException(McpErrorCode.NO_ACTIVE_SESSION, "No JVM is currently attached in JPI.");
        }
        private void check() {
            state.verify(snapshot);
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
                throw new McpException(McpErrorCode.OPERATION_TIMEOUT, "The operation exceeded its 30 second budget.");
        }
        private byte[] request(Operation operation, String payload) throws Exception {
            check();
            requireSession();
            if (!READS.contains(operation)) throw new McpException(McpErrorCode.INVALID_ARGUMENT, "Operation is not available through MCP.");
            if (operation != Operation.SESSION_INFO && operation != Operation.TARGET_FINGERPRINT) captureFingerprint();
            try {
                byte[] value = snapshot.session().request(operation, payload);
                check();
                if (operation == Operation.CLASS_BYTES && value.length > 4 * 1024 * 1024)
                    throw new McpException(McpErrorCode.RESULT_TOO_LARGE, "The selected class exceeds the 4 MiB analysis limit.");
                return value;
            } catch (IOException error) {
                check();
                throw new McpException(McpErrorCode.JPI_OPERATION_FAILED,
                        McpJson.bounded(error.getMessage() == null ? "JPI request failed." : error.getMessage(), 1024));
            }
        }
        private String read(Operation operation, String payload) throws Exception {
            return new String(request(operation, payload), StandardCharsets.UTF_8);
        }
        private List<ClassSummary> inventory() throws Exception {
            if (inventory == null) {
                var definitions = McpAnalysis.inventory(read(Operation.CLASSES, ""));
                var loaders = ClassLoaderSnapshot.parse(read(Operation.CLASSLOADER_SNAPSHOT, ""));
                Map<String, String> loaderIds = new LinkedHashMap<>();
                Map<String, String> loaderLabels = new LinkedHashMap<>();
                for (var definition : loaders.definitions()) loaderIds.put(definition.id(), definition.loaderId());
                for (var loader : loaders.loaders()) loaderLabels.put(loader.label(), loader.id());
                inventory = definitions.stream().map(type -> new ClassSummary(type.classId(), type.name(),
                        loaderIds.getOrDefault(type.classId(), type.loaderName().equals("bootstrap") ? "l:0" : loaderLabels.get(type.loaderName())),
                        type.loaderName(), type.module(), type.modifiable(), type.captured(), type.kind())).toList();
                for (ClassSummary type : inventory) {
                    byId.put(type.classId(), type);
                    byName.computeIfAbsent(type.name(), ignored -> new ArrayList<>()).add(type);
                    byNameLoader.computeIfAbsent(type.name() + '\0' + type.loaderName(), ignored -> new ArrayList<>()).add(type);
                }
            }
            return inventory;
        }
        private ClassSummary type(String id) throws Exception {
            inventory();
            ClassSummary type = byId.get(id);
            if (type == null) throw new McpException(McpErrorCode.CLASS_NOT_FOUND, "Class " + id + " is no longer present in the active JVM.");
            return type;
        }
        private ClassNode node(String id) throws Exception {
            type(id);
            ClassNode node = new ClassNode();
            new ClassReader(request(Operation.CLASS_BYTES, id)).accept(node, 0);
            return node;
        }
        private MethodNode method(ClassNode node, String name, String descriptor) {
            return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst()
                    .orElseThrow(() -> new McpException(McpErrorCode.METHOD_NOT_FOUND, "Method " + name + descriptor + " is not present in the selected definition."));
        }
        private Map<String, Object> session() throws Exception {
            if (snapshot.session() == null) return Map.of("connected", false);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", true);
            result.put("pid", snapshot.session().target().id());
            result.put("displayName", snapshot.session().target().displayName());
            result.put("tunneled", snapshot.session().isTunneled());
            for (String line : read(Operation.SESSION_INFO, "").split("\n")) {
                String[] v = line.split("=", 2);
                if (v.length != 2) continue;
                switch (v[0]) {
                    case "communication", "runtime" -> result.put(v[0], v[1]);
                    case "attach_disabled" -> result.put("attachDisabled", booleanValue(v[1]));
                    case "dynamic_agent_loading" -> result.put("dynamicAgentLoading", booleanValue(v[1]));
                    default -> { }
                }
            }
            return result;
        }
        private Map<String, Object> searchClasses(String query, int limit) throws Exception {
            String needle = query.toLowerCase(Locale.ROOT);
            List<ClassSummary> matches = new ArrayList<>();
            boolean truncated = false;
            for (ClassSummary type : inventory()) if ((type.name() + " " + type.loaderName()).toLowerCase(Locale.ROOT).contains(needle)) {
                if (matches.size() == limit) { truncated = true; break; }
                matches.add(type);
            }
            return Map.of("query", query, "matches", matches, "truncated", truncated);
        }
        private Map<String, Object> getClass(String id) throws Exception {
            ClassSummary type = type(id);
            ClassNode node = node(id);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("classId", id);
            result.put("name", type.name());
            Map<String, Object> loader = new LinkedHashMap<>();
            loader.put("id", type.loaderId());
            loader.put("name", type.loaderName());
            result.put("loader", loader);
            result.put("superClass", node.superName == null ? null : node.superName.replace('/', '.'));
            result.put("interfaces", node.interfaces.stream().map(n -> n.replace('/', '.')).toList());
            result.put("modifiers", McpAnalysis.modifiers(node.access));
            result.put("modifiable", type.modifiable());
            result.put("methods", node.methods.stream().map(m -> McpAnalysis.method(type, m)).limit(2000).toList());
            result.put("fields", node.fields.stream().limit(2000).map(f -> Map.of("name", f.name, "descriptor", f.desc,
                    "modifiers", McpAnalysis.modifiers(f.access))).toList());
            result.put("truncated", node.methods.size() > 2000 || node.fields.size() > 2000);
            return result;
        }
        private Map<String, Object> getMethod(String id, String name, String descriptor) throws Exception {
            ClassSummary type = type(id);
            byte[] bytes = request(Operation.CLASS_BYTES, id);
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            Map<String, Object> result = McpJson.GSON.fromJson(McpJson.GSON.toJson(McpAnalysis.method(type, method(node, name, descriptor))),
                    new TypeToken<Map<String, Object>>() {}.getType());
            result.put("bytecodeSize", McpAnalysis.codeLength(bytes, name, descriptor));
            return result;
        }
        private Map<String, Object> constants(String query, int limit) throws Exception {
            String raw = read(Operation.CONSTANT_SEARCH, query);
            List<Map<String, Object>> matches = new ArrayList<>();
            inventory();
            List<Map<String, Object>> users = McpAnalysis.references(read(Operation.XREF_SEARCH, query));
            boolean truncated = raw.contains("<limit>");
            for (String row : raw.split("\n")) {
                String[] v = row.split("\t", 3);
                if (v.length != 3 || v[0].startsWith("<")) continue;
                if (matches.size() >= limit) { truncated = true; break; }
                List<ClassSummary> owners = byNameLoader.getOrDefault(v[0] + '\0' + v[1], List.of());
                Map<String, Object> match = new LinkedHashMap<>();
                match.put("type", "UTF8");
                match.put("value", v[2]);
                match.put("encoding", "escaped-control-characters");
                match.put("className", v[0]);
                match.put("classId", owners.size() == 1 ? owners.getFirst().classId() : null);
                match.put("candidateClassIds", owners.stream().map(ClassSummary::classId).toList());
                match.put("loaderName", v[1]);
                List<Map<String, Object>> methodUsers = users.stream().filter(user -> owners.stream().anyMatch(owner -> owner.classId().equals(user.get("classId")))
                        && user.get("detail").toString().replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n").equals(v[2])).toList();
                if (methodUsers.isEmpty()) matches.add(match);
                else for (var user : methodUsers) {
                    if (matches.size() >= limit) { truncated = true; break; }
                    Map<String, Object> located = new LinkedHashMap<>(match);
                    located.put("type", "STRING");
                    located.put("value", user.get("detail"));
                    located.put("encoding", "plain");
                    located.put("classId", user.get("classId"));
                    located.put("methodName", user.get("methodName"));
                    located.put("descriptor", user.get("descriptor"));
                    located.put("methodId", McpAnalysis.methodId(user.get("classId").toString(), user.get("methodName").toString(), user.get("descriptor").toString()));
                    matches.add(located);
                }
            }
            return Map.of("query", query, "matches", matches, "truncated", truncated,
                    "methodAttributionPartial", inventory.size() > 5000 || users.size() >= 1000,
                    "notice", "Existing Constant Search returns UTF8 matches; method-level string literals are correlated through existing Xrefs. Not every match has a known method.");
        }
        private Map<String, Object> decompile(String id, String name, String descriptor, DecompilerEngine engine) throws Exception {
            ClassSummary type = type(id);
            byte[] bytes = request(Operation.CLASS_BYTES, id);
            if (name != null) {
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
                MethodNode selected = method(node, name, descriptor);
                node.methods.removeIf(m -> m != selected && !m.name.startsWith("lambda$"));
                ClassWriter writer = new ClassWriter(0);
                node.accept(writer);
                bytes = writer.toByteArray();
            }
            String source = decompiler.decompile(engine, type.name(), bytes);
            String bounded = McpJson.bounded(source, 2 * 1024 * 1024);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("classId", id);
            result.put("className", type.name());
            result.put("engine", engine.name().toLowerCase(Locale.ROOT));
            result.put("source", bounded);
            result.put("truncated", !source.equals(bounded));
            if (name != null) {
                result.put("methodName", name);
                result.put("descriptor", descriptor);
                result.put("scope", "Selected method in a class shell, including synthetic lambda helpers.");
            }
            return result;
        }
        private Map<String, Object> xrefs(String id, String name, String descriptor) throws Exception {
            type(id);
            if (!name.isEmpty()) method(node(id), name, validateDescriptor(descriptor));
            else if (!descriptor.isEmpty()) throw new McpException(McpErrorCode.INVALID_ARGUMENT, "A descriptor requires a methodName.");
            List<Map<String, Object>> references = references(id, name, descriptor, false);
            return Map.of("target", target(id, name, descriptor), "callers", references.stream()
                    .filter(r -> r.get("relation").equals("CALLED_BY")).toList(), "references", references,
                    "truncated", references.size() >= 1000 || inventory.size() > 5000,
                    "notice", "Static reverse search is bounded to 5000 classes and 10 seconds. Runtime evidence can aggregate names across classloaders.");
        }
        private List<Map<String, Object>> references(String id, String name, String descriptor, boolean outgoing) throws Exception {
            var refs = McpAnalysis.references(read(Operation.METHOD_XREFS, id + "\n" + name + "\n" + descriptor + (outgoing ? "\noutgoing" : "")));
            for (var ref : refs) {
                String candidate = (String) ref.get("classId");
                String owner = (String) ref.get("className");
                inventory();
                List<ClassSummary> types = byName.getOrDefault(owner, List.of());
                if (!candidate.startsWith("c:") && !candidate.startsWith("b:")) {
                    boolean sameDefinition = ref.get("layer").equals("STATIC") && owner.equals(type(id).name());
                    ref.put("classId", sameDefinition ? id : types.size() == 1 ? types.getFirst().classId() : null);
                    ref.put("candidateClassIds", types.stream().map(ClassSummary::classId).toList());
                    ref.put("ambiguous", !sameDefinition && types.size() > 1);
                }
                Object resolved = ref.get("classId");
                if (resolved != null && !ref.get("methodName").equals(""))
                    ref.put("methodId", McpAnalysis.methodId(resolved.toString(), ref.get("methodName").toString(), ref.get("descriptor").toString()));
            }
            return refs;
        }
        private Map<String, Object> cfg(String id, String name, String descriptor) throws Exception {
            method(node(id), name, descriptor);
            Map<String, Object> result = McpAnalysis.cfg(read(Operation.CFG_ANALYZE, id + "\n" + name + "\n" + descriptor));
            result.put("method", target(id, name, descriptor));
            return result;
        }
        private Map<String, Object> callGraph(String id, String name, String descriptor, int depth, int maxNodes) throws Exception {
            method(node(id), name, descriptor);
            record Pending(String id, String name, String descriptor, int depth) {}
            Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
            List<Map<String, Object>> edges = new ArrayList<>();
            ArrayDeque<Pending> queue = new ArrayDeque<>();
            Set<String> expanded = new LinkedHashSet<>();
            String root = McpAnalysis.methodId(id, name, descriptor);
            nodes.put(root, target(id, name, descriptor));
            queue.add(new Pending(id, name, descriptor, 0));
            boolean truncated = false;
            List<String> notices = new ArrayList<>();
            while (!queue.isEmpty()) {
                check();
                Pending pending = queue.removeFirst();
                String from = McpAnalysis.methodId(pending.id, pending.name, pending.descriptor);
                if (!expanded.add(from)) continue;
                if (pending.depth >= depth || expanded.size() > 128) { truncated = true; continue; }
                List<Map<String, Object>> relatedReferences;
                try { relatedReferences = references(pending.id, pending.name, pending.descriptor, true); }
                catch (McpException error) {
                    if (error.code() == McpErrorCode.SESSION_CHANGED || error.code() == McpErrorCode.OPERATION_TIMEOUT) throw error;
                    truncated = true;
                    if (notices.size() < 100) notices.add("Could not expand " + from + ": " + error.getMessage());
                    continue;
                }
                for (var ref : relatedReferences) {
                    if (!ref.get("relation").equals("CALLS")) continue;
                    if (edges.size() >= 8000) { truncated = true; break; }
                    String relatedId = (String) ref.get("classId");
                    String methodName = (String) ref.get("methodName");
                    String desc = (String) ref.get("descriptor");
                    String to = relatedId == null ? "unresolved:" + ref.get("className") + '.' + methodName + desc
                            : McpAnalysis.methodId(relatedId, methodName, desc);
                    if (!nodes.containsKey(to)) {
                        if (nodes.size() >= maxNodes) { truncated = true; continue; }
                        Map<String, Object> related = new LinkedHashMap<>(ref);
                        related.put("methodId", to);
                        nodes.put(to, related);
                        if (relatedId != null) queue.add(new Pending(relatedId, methodName, desc, pending.depth + 1));
                    }
                    edges.add(Map.of("from", from, "to", to, "layer", ref.get("layer"), "count", ref.get("count")));
                }
            }
            return Map.of("root", root, "nodes", nodes.values(), "edges", edges, "truncated", truncated, "notices", notices,
                    "notice", "Uses the existing Xrefs engine. Unresolved or ambiguous classloader references are not expanded. Depth and 128-request bounds apply.");
        }
        private Map<String, Object> threads() throws Exception {
            var snapshot = ThreadAnalysisSnapshot.parse(read(Operation.THREAD_SNAPSHOT, ""));
            return Map.of("timestamp", snapshot.timestamp(), "threads", snapshot.threads().stream().limit(2048)
                    .map(t -> Map.of("id", t.id(), "name", t.name(), "state", t.state(), "daemon", t.daemon(),
                            "priority", t.priority(), "lockName", t.lock(), "lockOwnerId", t.ownerId(),
                            "stack", t.frames().stream().limit(128).map(McpAnalysis::frame).toList())).toList(),
                    "truncated", snapshot.threads().size() >= 2048, "stackDepthLimit", 24);
        }
        private Map<String, Object> exceptions(int limit) throws Exception {
            var snapshot = ExceptionSnapshot.parse(read(Operation.EXCEPTION_SNAPSHOT, "30"));
            return Map.of("state", snapshot.state(), "timestamp", snapshot.timestamp(), "exceptions", snapshot.entries().stream().limit(limit).toList(),
                    "truncated", snapshot.entries().size() > limit, "dropped", snapshot.dropped(), "message", snapshot.message());
        }
        private Map<String, Object> fingerprint() throws Exception {
            captureFingerprint();
            var value = capturedFingerprint;
            return Map.of("pid", snapshot.session().target().id(), "fingerprint", fingerprint, "mainClass", value.mainClass(),
                    "javaVersion", value.javaVersion(), "classCount", value.loadedCount(),
                    "loaderCount", value.definitions().stream().map(TargetFingerprint.Definition::loader).distinct().count(),
                    "modules", inventory().stream().map(ClassSummary::module).filter(m -> !m.isBlank()).distinct().sorted().toList(),
                    "truncated", value.truncated(), "stable", false,
                    "notice", "Fingerprint depends on the currently loaded definitions; it is not a complete build identity.");
        }
        private void captureFingerprint() throws Exception {
            if (capturedFingerprint == null) {
                capturedFingerprint = TargetFingerprint.parse(read(Operation.TARGET_FINGERPRINT, ""));
                fingerprint = capturedFingerprint.id();
            }
        }
        private Map<String, Object> investigate(String query, int limit) throws Exception {
            var report = new InvestigationAssistant((operation, payload) -> read(
                    operation == Operation.TRACE_EVENTS ? Operation.TRACE_HISTORY : operation, payload), InvestigationAssistant.Limits.defaults())
                    .run(query, progress -> check(), () -> Thread.currentThread().isInterrupted() || System.nanoTime() > deadline);
            var entries = report.investigation().entryPoints();
            return Map.of("query", query, "results", entries.stream().limit(limit).map(t -> Map.of(
                    "classId", t.classIdentifier(), "className", t.className(), "methodName", t.methodName(), "descriptor", t.descriptor(),
                    "score", t.confidence() / 100.0, "runtimeHits", t.runtimeHits(), "matchedConstant", t.matchedConstant(),
                    "confidence", t.confidence() >= 80 ? "HIGH" : t.confidence() >= 60 ? "MEDIUM" : "LOW",
                    "reasons", t.runtimeHits() > 0 ? List.of("contains queried constant", "existing runtime evidence available") : List.of("contains queried constant"))).toList(),
                    "chains", report.chains(), "findings", report.findings(), "notices", report.notices(),
                    "truncated", report.partial() || entries.size() > limit,
                    "notice", "Scores are heuristic ranks, not probabilities or proof. No probes are installed.");
        }
        private Map<String, Object> target(String id, String name, String descriptor) throws Exception {
            return Map.of("classId", id, "className", type(id).name(), "methodName", name,
                    "descriptor", descriptor, "methodId", McpAnalysis.methodId(id, name, descriptor));
        }
    }

    private static Object booleanValue(String value) {
        return value.equals("true") ? Boolean.TRUE : value.equals("false") ? Boolean.FALSE : null;
    }
    private static DecompilerEngine engine(JsonObject arguments) {
        String engine = optional(arguments, "engine");
        if (engine.isEmpty() || engine.equals("default")) return DecompilerEngine.CFR;
        try { return DecompilerEngine.valueOf(engine.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new McpException(McpErrorCode.INVALID_ARGUMENT, "Supported engines: default, cfr, vineflower, procyon."); }
    }
    private static String classId(JsonObject args) {
        String id = text(args, "classId", 3, 1024);
        if (!id.startsWith("c:") && !id.startsWith("b:")) throw new McpException(McpErrorCode.INVALID_ARGUMENT, "Use a canonical classId returned by jpi_search_classes.");
        return id;
    }
    private static String member(JsonObject args) { return text(args, "methodName", 1, 512); }
    private static String descriptor(JsonObject args) { return validateDescriptor(text(args, "descriptor", 3, 4096)); }
    private static String validateDescriptor(String descriptor) {
        try {
            if (!descriptor.startsWith("(")) throw new IllegalArgumentException();
            var arguments = Type.getArgumentTypes(descriptor);
            var result = Type.getReturnType(descriptor);
            if (!Type.getMethodDescriptor(result, arguments).equals(descriptor)) throw new IllegalArgumentException();
            for (var argument : arguments) {
                if (argument.getSort() == Type.VOID || argument.getSort() == Type.METHOD
                        || argument.getSort() == Type.ARRAY && argument.getElementType().getSort() == Type.VOID)
                    throw new IllegalArgumentException();
            }
            return descriptor;
        } catch (RuntimeException error) { throw new McpException(McpErrorCode.INVALID_ARGUMENT, "Invalid JVM method descriptor."); }
    }
    private static String optional(JsonObject args, String key) {
        if (!args.has(key)) return "";
        return text(args, key, 0, key.equals("descriptor") ? 4096 : 512);
    }
    private static String text(JsonObject args, String key, int min, int max) {
        var value = args.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new McpException(McpErrorCode.INVALID_ARGUMENT, key + " must be a string.");
        String text = value.getAsString();
        if (text.length() < min || text.length() > max || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0)
            throw new McpException(McpErrorCode.INVALID_ARGUMENT, key + " must contain " + min + " to " + max + " characters without line breaks.");
        return text;
    }
    private static int integer(JsonObject args, String key, int fallback, int min, int max) {
        if (!args.has(key)) return fallback;
        try {
            var value = args.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            if (value.getAsString().length() > 16) throw new ArithmeticException();
            int number = value.getAsBigDecimal().intValueExact();
            if (number < min || number > max) throw new ArithmeticException();
            return number;
        } catch (RuntimeException error) { throw new McpException(McpErrorCode.INVALID_ARGUMENT, key + " must be an integer between " + min + " and " + max + '.'); }
    }
}

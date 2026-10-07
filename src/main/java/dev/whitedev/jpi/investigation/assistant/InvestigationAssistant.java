package dev.whitedev.jpi.investigation.assistant;

import dev.whitedev.jpi.investigation.InvestigationAnalyzer;
import dev.whitedev.jpi.investigation.InvestigationTarget;
import dev.whitedev.jpi.loader.ClassLoaderSnapshot;
import dev.whitedev.jpi.protocol.Operation;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static dev.whitedev.jpi.investigation.assistant.AssistantReport.*;

public final class InvestigationAssistant {
    @FunctionalInterface
    public interface Requests {
        String request(Operation operation, String payload) throws Exception;
    }

    public record Limits(int entryPoints, int requests, int depth, int nodes, int edges, Duration time) {
        public Limits {
            if (entryPoints < 1 || entryPoints > 20 || requests < 1 || requests > 100 || depth < 0 || depth > 6
                    || nodes < 2 || nodes > 500 || edges < 1 || edges > 2000 || time.isNegative() || time.isZero()
                    || time.compareTo(Duration.ofMinutes(2)) > 0)
                throw new IllegalArgumentException("Invalid investigation limits");
        }

        public static Limits defaults() { return new Limits(5, 16, 3, 80, 200, Duration.ofSeconds(30)); }
    }

    public record Progress(int step, String detail) { }

    private final Requests requests;
    private final Limits limits;

    public InvestigationAssistant(Requests requests, Limits limits) {
        this.requests = requests;
        this.limits = limits;
    }

    public AssistantReport run(String query, Consumer<Progress> progress, BooleanSupplier cancelled) throws Exception {
        if (query == null || query.trim().length() < 2) throw new IllegalArgumentException("Enter at least two characters");
        query = query.trim();
        long started = System.nanoTime();
        check(cancelled);
        progress.accept(new Progress(1, "Constant search running"));
        String constants = requests.request(Operation.CONSTANT_SEARCH, query);
        check(cancelled);
        progress.accept(new Progress(1, "Constant search complete"));
        String users = requests.request(Operation.XREF_SEARCH, query);
        check(cancelled);
        String traces = requests.request(Operation.TRACE_EVENTS, "");
        var base = InvestigationAnalyzer.analyze(query, constants, users, traces);
        progress.accept(new Progress(2, "Found " + base.entryPoints().size() + " ranked method users (up to 100)"));
        List<InvestigationTarget> seeds = base.entryPoints().stream().limit(limits.entryPoints).toList();
        progress.accept(new Progress(3, "Selected " + seeds.size() + " likely entry points for bounded analysis"));
        check(cancelled);
        Graph graph = new Graph(limits);
        if (constants.contains("<limit>") || users.contains("<limit>")) {
            graph.partial = true;
            graph.notice("The initial search returned a truncation marker; some usages may be missing.");
        }
        Map<String, List<ClassLoaderSnapshot.Definition>> definitions = new LinkedHashMap<>();
        if (!seeds.isEmpty()) {
            try {
                ClassLoaderSnapshot snapshot = ClassLoaderSnapshot.parse(requests.request(Operation.CLASSLOADER_SNAPSHOT, ""));
                for (var definition : snapshot.definitions()) {
                    if ("definition".equals(definition.kind()))
                        definitions.computeIfAbsent(definition.name(), ignored -> new ArrayList<>()).add(definition);
                }
            } catch (Exception error) {
                graph.partial = true;
                graph.notice("Classloader inventory unavailable: " + message(error));
            }
        }
        ArrayDeque<Pending> queue = new ArrayDeque<>();
        Set<String> seedKeys = new LinkedHashSet<>();
        for (InvestigationTarget seed : seeds) {
            Method method = new Method(seed.classIdentifier(), seed.className(), seed.methodName(), seed.descriptor());
            if (graph.node(method)) queue.add(new Pending(method, 0));
            seedKeys.add(method.key());
        }
        Set<String> scanned = new LinkedHashSet<>();
        int count = 0;
        int completed = 0;
        while (!queue.isEmpty()) {
            check(cancelled);
            if (count >= limits.requests || System.nanoTime() - started >= limits.time.toNanos()) {
                graph.partial = true;
                graph.notice("Exploration stopped at the request or time budget; remaining methods were not analyzed.");
                break;
            }
            Pending pending = queue.removeFirst();
            Method method = pending.method;
            if (!scanned.add(method.key()) || boundary(method.owner()) || method.identifier().equals(method.owner())) continue;
            count++;
            progress.accept(new Progress(4, "Call graph: inspecting " + count + "/" + limits.requests + " methods"));
            String payload = method.identifier() + "\n" + method.name() + "\n" + method.descriptor()
                    + (pending.depth == 0 ? "" : "\noutgoing");
            try {
                String raw = requests.request(Operation.METHOD_XREFS, payload);
                check(cancelled);
                completed++;
                for (String line : raw.split("\n")) {
                    String[] values = line.split("\t", -1);
                    if (values.length != 9 || !"R".equals(values[0])
                            || !("CALLS".equals(values[2]) || "CALLED_BY".equals(values[2]))) continue;
                    try {
                        String owner = decode(values[5]);
                        String name = decode(values[6]);
                        String descriptor = decode(values[7]);
                        if (owner.isEmpty() || owner.startsWith("<") || name.isEmpty() || !descriptor.startsWith("(")) continue;
                        String identifier = "STATIC".equals(values[1]) && owner.equals(method.owner())
                                ? method.identifier() : resolve(decode(values[4]), owner, definitions, graph);
                        Method related = new Method(identifier, owner, name, descriptor);
                        Method caller = "CALLS".equals(values[2]) ? method : related;
                        Method callee = "CALLS".equals(values[2]) ? related : method;
                        boolean added = graph.edge(caller, callee, values[1], Long.parseLong(values[3]));
                        if (added && pending.depth < limits.depth && !boundary(owner)
                                && !identifier.equals(owner)) queue.addLast(new Pending(related, pending.depth + 1));
                        else if (added && pending.depth >= limits.depth && !boundary(owner)) {
                            graph.partial = true;
                            graph.notice("Depth limit reached; some application methods were not expanded.");
                        }
                    } catch (IllegalArgumentException ignored) { }
                }
            } catch (CancellationException error) {
                throw error;
            } catch (Exception error) {
                graph.partial = true;
                graph.notice("Could not inspect " + method.display() + ": " + message(error));
            }
        }
        check(cancelled);
        List<Edge> edges = List.copyOf(graph.edges.values());
        progress.accept(new Progress(4, "Generated call graph: " + graph.nodes.size() + " nodes, " + edges.size() + " edges"));
        List<Finding> findings = new ArrayList<>();
        LinkedHashMap<String, InvestigationTarget> probes = new LinkedHashMap<>();
        for (InvestigationTarget seed : seeds) probes.put(
                new Method(seed.classIdentifier(), seed.className(), seed.methodName(), seed.descriptor()).key(), seed);
        for (Edge edge : edges) {
            String category = category(edge.callee().owner());
            if (category.isEmpty()) continue;
            findings.add(new Finding(category, edge.caller(), edge.callee(), edge.observedHits() > 0, edge.observedHits()));
            if (!boundary(edge.caller().owner()) && !edge.caller().identifier().equals(edge.caller().owner()))
                probes.putIfAbsent(edge.caller().key(), edge.caller().target());
        }
        List<InvestigationTarget> suggested = probes.values().stream().limit(12).toList();
        progress.accept(new Progress(5, "Suggested " + suggested.size() + " trace points; no probes installed"));
        long crypto = findings.stream().filter(finding -> "Crypto".equals(finding.category())).count();
        long network = findings.stream().filter(finding -> "Network".equals(finding.category())).count();
        progress.accept(new Progress(6, crypto == 0 ? "No supported crypto API found within scan bounds"
                : "Detected " + crypto + " crypto API references"));
        progress.accept(new Progress(7, network == 0 ? "No supported network API found within scan bounds"
                : "Found " + network + " network caller references"));
        if (seeds.isEmpty()) graph.notice("No method-level string users found. Try a shorter marker or inspect available bytecode.");
        if (base.entryPoints().size() > seeds.size()) {
            graph.partial = true;
            graph.notice("Only the top " + seeds.size() + " ranked entry points were expanded.");
        }
        graph.notice("Reverse callers inherit the Xrefs limits: 5,000 loaded classes, 1,000 results, 10 seconds per root.");
        return new AssistantReport(base, constants, users, edges,
                chains(edges, seedKeys, seeds), suggested, List.copyOf(findings), List.copyOf(graph.notices), completed, graph.partial);
    }

    private static String resolve(String identifier, String owner,
                                  Map<String, List<ClassLoaderSnapshot.Definition>> definitions, Graph graph) {
        if (!identifier.isEmpty() && !identifier.equals(owner)) return identifier;
        var matches = definitions.getOrDefault(owner, List.of());
        if (matches.size() == 1) return matches.getFirst().id();
        if (!boundary(owner)) {
            graph.partial = true;
            graph.notice(matches.isEmpty() ? "No unique loaded definition for " + owner
                    : "Ambiguous classloader for " + owner + "; reference retained but not expanded or suggested as a probe.");
        }
        return owner;
    }

    private static List<Chain> chains(List<Edge> edges, Set<String> seeds, List<InvestigationTarget> targets) {
        Map<String, List<Edge>> outgoing = new LinkedHashMap<>();
        Map<String, Method> nodes = new LinkedHashMap<>();
        Set<String> incoming = new LinkedHashSet<>();
        for (Edge edge : edges) {
            outgoing.computeIfAbsent(edge.caller().key(), ignored -> new ArrayList<>()).add(edge);
            nodes.put(edge.caller().key(), edge.caller());
            nodes.put(edge.callee().key(), edge.callee());
            incoming.add(edge.callee().key());
        }
        outgoing.values().forEach(list -> list.sort(Comparator.comparing(edge -> edge.callee().key())));
        List<Chain> paths = new ArrayList<>();
        List<Method> roots = nodes.values().stream().filter(node -> !incoming.contains(node.key())).toList();
        if (roots.isEmpty()) roots = nodes.values().stream().filter(node -> seeds.contains(node.key())).toList();
        for (Method root : roots) walk(root, outgoing, seeds, targets, new ArrayList<>(), new ArrayList<>(), paths, new int[]{0});
        return paths.stream().sorted(Comparator.comparingInt(Chain::confidence).reversed()
                        .thenComparing(chain -> chain.methods().stream().map(Method::key).reduce("", String::concat)))
                .limit(8).toList();
    }

    private static void walk(Method node, Map<String, List<Edge>> outgoing, Set<String> seeds,
                             List<InvestigationTarget> targets, List<Method> path, List<Edge> evidence,
                             List<Chain> results, int[] visited) {
        if (++visited[0] > 512 || results.size() >= 128 || path.stream().anyMatch(value -> value.key().equals(node.key()))) return;
        path.add(node);
        List<Edge> next = outgoing.getOrDefault(node.key(), List.of());
        boolean terminal = next.isEmpty() || path.size() >= 7 || !category(node.owner()).isEmpty();
        if (terminal && path.size() > 1 && path.stream().anyMatch(value -> seeds.contains(value.key()))) {
            int score = targets.stream().filter(target -> path.stream().anyMatch(value ->
                    value.identifier().equals(target.classIdentifier()) && value.owner().equals(target.className())
                            && value.name().equals(target.methodName()) && value.descriptor().equals(target.descriptor())))
                    .mapToInt(InvestigationTarget::confidence).max().orElse(50) * 3 / 4;
            if (path.stream().anyMatch(value -> "Network".equals(category(value.owner())))) score += 12;
            if (path.stream().anyMatch(value -> "Crypto".equals(category(value.owner())))) score += 8;
            int observed = (int) evidence.stream().filter(edge -> edge.observedHits() > 0).count();
            score += evidence.isEmpty() ? 0 : observed * 18 / evidence.size();
            results.add(new Chain(List.copyOf(path), Math.min(observed == 0 ? 85 : 95, score), observed, evidence.size()));
        } else if (!terminal) {
            for (Edge edge : next) {
                evidence.add(edge);
                walk(edge.callee(), outgoing, seeds, targets, path, evidence, results, visited);
                evidence.removeLast();
            }
        }
        path.removeLast();
    }

    static String category(String owner) {
        if (owner.startsWith("javax.crypto.") || Set.of("java.security.MessageDigest", "java.security.Signature",
                "java.security.KeyFactory", "java.security.KeyPairGenerator", "java.security.SecureRandom").contains(owner)) return "Crypto";
        if (Set.of("java.net.Socket", "java.net.SocketChannel", "java.nio.channels.SocketChannel", "java.net.URL",
                "java.net.URLConnection", "java.net.HttpURLConnection", "java.net.DatagramSocket").contains(owner)
                || owner.startsWith("java.net.http.") || owner.startsWith("okhttp3.")
                || owner.startsWith("org.apache.http.client.") || owner.startsWith("org.apache.hc.client5.http.")) return "Network";
        return "";
    }

    private static boolean boundary(String owner) {
        return owner.startsWith("java.") || owner.startsWith("javax.") || owner.startsWith("jdk.")
                || owner.startsWith("sun.") || owner.startsWith("dev.whitedev.jpi.agent.") || !category(owner).isEmpty();
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String message(Exception error) {
        String value = error.getMessage();
        return value == null ? error.getClass().getSimpleName() : value.substring(0, Math.min(value.length(), 300));
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Investigation cancelled");
    }

    private record Pending(Method method, int depth) { }

    private static final class Graph {
        final Limits limits;
        final Map<String, Method> nodes = new LinkedHashMap<>();
        final Map<String, Edge> edges = new LinkedHashMap<>();
        final Set<String> notices = new LinkedHashSet<>();
        boolean partial;

        Graph(Limits limits) { this.limits = limits; }

        boolean node(Method method) {
            if (nodes.containsKey(method.key())) return true;
            if (nodes.size() >= limits.nodes) { limited(); return false; }
            nodes.put(method.key(), method);
            return true;
        }

        boolean edge(Method caller, Method callee, String layer, long hits) {
            if (!Set.of("STATIC", "DYNAMIC", "REFLECTIVE", "FAILED").contains(layer) || hits < 0) return false;
            String key = caller.key() + "\u0001" + callee.key();
            Edge old = edges.get(key);
            if (old == null && edges.size() >= limits.edges) { limited(); return false; }
            if (!node(caller) || !node(callee)) return false;
            edges.put(key, new Edge(caller, callee, "STATIC".equals(layer) || old != null && old.staticReference(),
                    Math.max(old == null ? 0 : old.observedHits(), "STATIC".equals(layer) ? 0 : hits),
                    "REFLECTIVE".equals(layer) || old != null && old.reflective(),
                    "FAILED".equals(layer) || old != null && old.failed()));
            return true;
        }

        void notice(String message) { if (notices.size() < 32) notices.add(message); }
        void limited() { partial = true; notice("Call graph node or edge limit reached; some references were omitted."); }
    }
}

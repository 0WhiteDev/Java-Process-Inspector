package dev.whitedev.jpi.ui.callgraph;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class CallGraphModel {
    final long capturedAt;
    final List<Node> nodes;
    final List<Edge> edges;
    final Map<String, Node> byId;

    private CallGraphModel(long capturedAt, List<Node> nodes, List<Edge> edges) {
        this.capturedAt = capturedAt;
        this.nodes = Collections.unmodifiableList(nodes);
        this.edges = Collections.unmodifiableList(edges);
        Map<String, Node> indexed = new LinkedHashMap<>();
        for (Node node : nodes) indexed.put(node.id(), node);
        byId = Collections.unmodifiableMap(indexed);
        for (Edge edge : edges) {
            Node caller = indexed.get(edge.callerId());
            Node callee = indexed.get(edge.calleeId());
            if (caller != null) caller.outgoing.add(edge);
            if (callee != null) callee.incoming.add(edge);
        }
    }

    static CallGraphModel parse(String raw) {
        long capturedAt = 0L;
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String[] values = line.split("\t", -1);
            try {
                if (values.length == 4 && "G".equals(values[0])) {
                    capturedAt = Long.parseLong(values[1]);
                } else if (values.length == 10 && "N".equals(values[0])) {
                    nodes.add(new Node(decoded(values[1]), decoded(values[2]), decoded(values[3]),
                            Long.parseLong(values[4]), Long.parseLong(values[5]), Long.parseLong(values[6]),
                            Long.parseLong(values[7]), Integer.parseInt(values[8]),
                            Boolean.parseBoolean(values[9])));
                } else if (values.length == 10 && "E".equals(values[0])) {
                    edges.add(new Edge(decoded(values[1]), decoded(values[2]), decoded(values[3]),
                            decoded(values[4]), decoded(values[5]), decoded(values[6]),
                            Long.parseLong(values[7]), Long.parseLong(values[8]),
                            Boolean.parseBoolean(values[9])));
                }
            } catch (RuntimeException ignored) {
            }
        }
        return new CallGraphModel(capturedAt, nodes, edges);
    }

    View view(String query, long minimumCalls, int maximumNodes, HeatMetric metric) {
        return view(query, minimumCalls, maximumNodes, metric, Node::id);
    }

    View view(String query, long minimumCalls, int maximumNodes, HeatMetric metric,
              Function<Node, String> mappedName) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Node> candidates = new ArrayList<>();
        for (Node node : nodes) {
            String mapped = mappedName.apply(node);
            boolean matches = needle.isEmpty() || node.searchText().contains(needle)
                    || mapped != null && mapped.toLowerCase(Locale.ROOT).contains(needle);
            if (node.calls < minimumCalls || !matches) continue;
            candidates.add(node);
        }
        candidates.sort(Comparator.comparingLong((Node node) -> metric.value(node)).reversed()
                .thenComparing(Node::id));
        if (candidates.size() > maximumNodes) candidates = new ArrayList<>(candidates.subList(0, maximumNodes));
        Set<String> selected = new LinkedHashSet<>();
        for (Node node : candidates) selected.add(node.id());
        List<Edge> visibleEdges = new ArrayList<>();
        for (Edge edge : edges) {
            if (selected.contains(edge.callerId()) && selected.contains(edge.calleeId())) visibleEdges.add(edge);
        }
        visibleEdges.sort(Comparator.comparingLong((Edge edge) -> edge.calls).reversed());
        return new View(List.copyOf(candidates), List.copyOf(visibleEdges), metric);
    }

    long totalCalls() {
        long total = 0L;
        for (Node node : nodes) if (node.measured()) total += node.calls;
        return total;
    }

    long totalNanos() {
        long total = 0L;
        for (Node node : nodes) if (node.totalNanos >= 0L) total += node.totalNanos;
        return total;
    }

    long exceptions() {
        long total = 0L;
        for (Node node : nodes) total += node.exceptions;
        return total;
    }

    private static String decoded(String value) {
        return value.isEmpty() ? "" : new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    enum HeatMetric {
        CALLS("Calls") {
            @Override long value(Node node) { return node.calls; }
        },
        TOTAL_TIME("Total time") {
            @Override long value(Node node) { return Math.max(0L, node.totalNanos); }
        },
        AVERAGE_TIME("Average time") {
            @Override long value(Node node) { return node.averageNanos(); }
        },
        EXCEPTIONS("Exceptions") {
            @Override long value(Node node) { return node.exceptions; }
        };

        private final String label;

        HeatMetric(String label) {
            this.label = label;
        }

        abstract long value(Node node);

        @Override public String toString() {
            return label;
        }
    }

    static final class Node {
        final String className;
        final String methodName;
        final String descriptor;
        final long calls;
        final long completed;
        final long totalNanos;
        final long exceptions;
        final int uniqueCallers;
        final boolean active;
        final List<Edge> incoming = new ArrayList<>();
        final List<Edge> outgoing = new ArrayList<>();

        Node(String className, String methodName, String descriptor, long calls, long completed,
             long totalNanos, long exceptions, int uniqueCallers, boolean active) {
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.calls = calls;
            this.completed = completed;
            this.totalNanos = totalNanos;
            this.exceptions = exceptions;
            this.uniqueCallers = uniqueCallers;
            this.active = active;
        }

        String id() {
            return className + "." + methodName + descriptor;
        }

        boolean measured() {
            return totalNanos >= 0L;
        }

        long averageNanos() {
            return totalNanos < 0L || completed == 0L ? 0L : totalNanos / completed;
        }

        private String searchText() {
            return id().toLowerCase(Locale.ROOT);
        }
    }

    static final class Edge {
        final String callerClass;
        final String callerMethod;
        final String callerDescriptor;
        final String calleeClass;
        final String calleeMethod;
        final String calleeDescriptor;
        final long calls;
        final long failures;
        final boolean reflective;

        Edge(String callerClass, String callerMethod, String callerDescriptor,
             String calleeClass, String calleeMethod, String calleeDescriptor,
             long calls, long failures, boolean reflective) {
            this.callerClass = callerClass;
            this.callerMethod = callerMethod;
            this.callerDescriptor = callerDescriptor;
            this.calleeClass = calleeClass;
            this.calleeMethod = calleeMethod;
            this.calleeDescriptor = calleeDescriptor;
            this.calls = calls;
            this.failures = failures;
            this.reflective = reflective;
        }

        String callerId() {
            return callerClass + "." + callerMethod + callerDescriptor;
        }

        String calleeId() {
            return calleeClass + "." + calleeMethod + calleeDescriptor;
        }
    }

    record View(List<Node> nodes, List<Edge> edges, HeatMetric metric) {
    }
}

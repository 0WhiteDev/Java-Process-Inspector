package dev.whitedev.jpi.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class EvidenceStore {
    private static final int NODE_LIMIT = 5_000;
    private static final int EDGE_LIMIT = 8_000;
    private static final int BYTE_BUDGET = 7 * 1024 * 1024;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Edge> edges = new LinkedHashMap<>();
    private boolean partial;
    private long estimatedBytes;

    public String addNode(String source, String key, String label, String details, Symbol symbol) {
        String id = id(source, key);
        if (nodes.containsKey(id)) return id;
        String title = clip(label, 220);
        String description = clip(details, 320);
        long cost = 180 + 6L * (source.length() + title.length() + description.length());
        if (symbol != null) {
            cost += 80 + 6L * (symbol.definitionId().length() + symbol.owner().length()
                    + symbol.member().length() + symbol.descriptor().length());
        }
        if (nodes.size() >= NODE_LIMIT || estimatedBytes + cost > BYTE_BUDGET) {
            partial = true;
            return "";
        }
        estimatedBytes += cost;
        nodes.put(id, new Node(id, source, title, description, symbol));
        return id;
    }

    public void connect(String from, String to, Kind kind, String source, String basis) {
        if (!nodes.containsKey(from) || !nodes.containsKey(to)) return;
        String id = id(from, to, kind.name(), source, basis);
        if (edges.containsKey(id)) return;
        String description = clip(basis, 240);
        long cost = 280 + 6L * (source.length() + description.length());
        if (edges.size() >= EDGE_LIMIT || estimatedBytes + cost > BYTE_BUDGET) {
            partial = true;
            return;
        }
        estimatedBytes += cost;
        edges.put(id, new Edge(id, from, to, kind, source, description));
    }

    public void markPartial() {
        partial = true;
    }

    public Graph snapshot() {
        return new Graph(List.copyOf(nodes.values()), List.copyOf(edges.values()), partial);
    }

    static String id(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(Integer.toString(part.length()).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(part.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String clip(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 3) + "...";
    }

    public enum Kind {
        STATIC, OBSERVED, TIME_CORRELATED, INFERRED
    }

    public record Symbol(String definitionId, String owner, String member, String descriptor) {
        public Symbol {
            Objects.requireNonNull(definitionId);
            Objects.requireNonNull(owner);
            Objects.requireNonNull(member);
            Objects.requireNonNull(descriptor);
        }

        String key() {
            return id(definitionId, owner, member, descriptor);
        }

        public String label() {
            return owner + "." + member + descriptor;
        }
    }

    public record Node(String id, String source, String label, String details, Symbol symbol) {
        public Node {
            Objects.requireNonNull(id);
            Objects.requireNonNull(source);
            Objects.requireNonNull(label);
            Objects.requireNonNull(details);
        }
    }

    public record Edge(String id, String from, String to, Kind kind, String source, String basis) {
        public Edge {
            Objects.requireNonNull(id);
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(source);
            Objects.requireNonNull(basis);
        }
    }

    public record Graph(List<Node> nodes, List<Edge> edges, boolean partial) {
        public Graph {
            if (nodes.size() > NODE_LIMIT || edges.size() > EDGE_LIMIT)
                throw new IllegalArgumentException("Evidence graph exceeds its record limits");
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
            Set<String> ids = new HashSet<>();
            for (Node node : nodes) {
                if (!ids.add(node.id())) throw new IllegalArgumentException("Duplicate evidence node");
            }
            Set<String> links = new HashSet<>();
            for (Edge edge : edges) {
                if (!links.add(edge.id()) || !ids.contains(edge.from()) || !ids.contains(edge.to()))
                    throw new IllegalArgumentException("Invalid evidence edge");
            }
        }
    }
}

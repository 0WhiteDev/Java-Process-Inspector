package dev.whitedev.jpi.provenance;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ValueTraceAnalyzer {
    private ValueTraceAnalyzer() {
    }

    public static Report analyze(String query, String fieldHistory, String traceHistory, String xrefs) {
        String needle = normalize(query);
        if (needle.isEmpty()) throw new IllegalArgumentException("Value to trace is required");
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Link> links = new ArrayList<>();
        List<StaticReference> references = new ArrayList<>();
        Map<Long, TraceRecord> traces = parseTraces(traceHistory);
        int fieldMatches = 0;
        for (String line : fieldHistory == null ? new String[0] : fieldHistory.split("\\R")) {
            String[] values = line.split("\\t", -1);
            if (values.length != 18 || !"E".equals(values[0])) continue;
            FieldRecord field = FieldRecord.parse(values);
            if (field == null || (!matches(field.current, needle) && !matches(field.previous, needle))) continue;
            fieldMatches++;
            String fieldNode = fieldNode(field.owner, field.name, field.descriptor);
            String writerNode = methodNode(field.writerClass, field.writerMethod, field.writerDescriptor);
            addNode(nodes, fieldNode, field.owner + "." + field.name, "FIELD");
            addNode(nodes, writerNode, field.writerClass + "." + field.writerMethod + field.writerDescriptor, "WRITER");
            String relation = matches(field.current, needle) ? "writes value into" : "reads previous value from";
            links.add(new Link(writerNode, fieldNode, relation, field.timestamp, field.thread, field.callId,
                    field.current));
            if (field.callId > 0L) {
                TraceRecord call = traces.get(Long.valueOf(field.callId));
                if (call != null) {
                    String callNode = methodNode(call.owner, call.method, call.descriptor);
                    addNode(nodes, callNode, call.owner + "." + call.method + call.descriptor, "TRACE");
                    links.add(new Link(callNode, writerNode, "calls", call.timestamp, call.thread, call.sequence, ""));
                }
            }
        }
        for (TraceRecord trace : traces.values()) {
            if (!matches(trace.arguments, needle) && !matches(trace.result, needle)
                    && !matches(trace.exception, needle)) continue;
            String node = methodNode(trace.owner, trace.method, trace.descriptor);
            addNode(nodes, node, trace.owner + "." + trace.method + trace.descriptor, "TRACE");
            if (trace.parent > 0L && traces.containsKey(Long.valueOf(trace.parent))) {
                TraceRecord parent = traces.get(Long.valueOf(trace.parent));
                String parentNode = methodNode(parent.owner, parent.method, parent.descriptor);
                addNode(nodes, parentNode, parent.owner + "." + parent.method + parent.descriptor, "TRACE");
                links.add(new Link(parentNode, node, "passes value to", trace.timestamp, trace.thread,
                        trace.sequence, valueDetail(trace, needle)));
            } else {
                links.add(new Link("VALUE", node, "observed in", trace.timestamp, trace.thread,
                        trace.sequence, valueDetail(trace, needle)));
            }
        }
        for (String line : xrefs == null ? new String[0] : xrefs.split("\\R")) {
            String[] values = line.split("\\t", -1);
            if (values.length < 9 || !"R".equals(values[0])) continue;
            StaticReference reference = StaticReference.parse(values);
            if (reference != null) references.add(reference);
        }
        links.sort(Comparator.comparingLong(Link::timestamp).thenComparing(Link::from).thenComparing(Link::to));
        return new Report(query == null ? "" : query.trim(), needle, fieldMatches, traces.size(),
                List.copyOf(nodes.values()), List.copyOf(links), List.copyOf(references));
    }

    private static Map<Long, TraceRecord> parseTraces(String raw) {
        Map<Long, TraceRecord> result = new LinkedHashMap<>();
        for (String line : raw == null ? new String[0] : raw.split("\\R")) {
            String[] values = line.split("\\t", -1);
            if (values.length != 17 || !"E".equals(values[0])) continue;
            TraceRecord trace = TraceRecord.parse(values);
            if (trace != null) result.put(Long.valueOf(trace.sequence), trace);
        }
        return result;
    }

    private static String valueDetail(TraceRecord trace, String needle) {
        if (matches(trace.arguments, needle)) return "argument: " + compact(trace.arguments);
        if (matches(trace.result, needle)) return "return: " + compact(trace.result);
        return "exception: " + compact(trace.exception);
    }

    private static void addNode(Map<String, Node> nodes, String id, String label, String kind) {
        if (!nodes.containsKey(id)) nodes.put(id, new Node(id, label, kind));
    }

    private static String fieldNode(String owner, String name, String descriptor) {
        return "field:" + owner + "." + name + descriptor;
    }

    private static String methodNode(String owner, String method, String descriptor) {
        return "method:" + owner + "." + method + descriptor;
    }

    private static boolean matches(String value, String needle) {
        if (value == null || value.isEmpty() || needle.isEmpty()) return false;
        String normalized = normalize(value);
        return normalized.equals(needle) || normalized.contains(needle);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.length() >= 2 && normalized.charAt(0) == '"' && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private static String compact(String value) {
        if (value == null) return "";
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        return clean.length() <= 180 ? clean : clean.substring(0, 177) + "...";
    }

    private static String decode(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    public record Report(String query, String normalizedQuery, int fieldMatches, int traceEvents,
                         List<Node> nodes, List<Link> links, List<StaticReference> references) {
        public List<Link> runtimeLinks() {
            return links.stream().filter(value -> !"STATIC".equals(value.kind())).toList();
        }
    }

    public record Node(String id, String label, String kind) {
    }

    public record Link(String from, String to, String kind, long timestamp, String thread,
                       long callId, String detail) {
    }

    public record StaticReference(String layer, String relation, long count, String target,
                                  String owner, String member, String descriptor, String detail) {
        static StaticReference parse(String[] values) {
            try {
                return new StaticReference(values[1], values[2], Long.parseLong(values[3]), decode(values[4]),
                        decode(values[5]), decode(values[6]), decode(values[7]), decode(values[8]));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private record FieldRecord(long sequence, long timestamp, String thread, String owner, String name,
                               String descriptor, String writerClass, String writerMethod,
                               String writerDescriptor, String previous, String current, long callId) {
        static FieldRecord parse(String[] values) {
            try {
                return new FieldRecord(Long.parseLong(values[1]), Long.parseLong(values[2]), decode(values[4]),
                        decode(values[5]), decode(values[6]), decode(values[7]), decode(values[8]),
                        decode(values[9]), decode(values[10]), decode(values[12]), decode(values[13]),
                        Long.parseLong(values[16]));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private record TraceRecord(long sequence, long parent, long timestamp, String owner, String method,
                               String descriptor, String thread, String arguments, String result,
                               String exception) {
        static TraceRecord parse(String[] values) {
            try {
                return new TraceRecord(Long.parseLong(values[1]), Long.parseLong(values[2]),
                        Long.parseLong(values[3]), decode(values[8]), decode(values[9]), decode(values[10]),
                        decode(values[11]), decode(values[13]), decode(values[14]), decode(values[15]));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}

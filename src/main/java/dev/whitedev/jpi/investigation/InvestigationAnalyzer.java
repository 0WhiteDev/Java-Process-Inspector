package dev.whitedev.jpi.investigation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class InvestigationAnalyzer {
    private static final int MAX_ENTRY_POINTS = 100;

    private static final int MAX_CONSTANTS = 100;

    private static final int MAX_PATHS = 100;

    private InvestigationAnalyzer() {}

    public static InvestigationReport analyze(String query, String constantsRaw, String xrefsRaw, String tracesRaw) {
        String normalizedQuery = query == null ? "" : query.trim();
        ConstantData constants = constants(constantsRaw);
        TraceData traces = traces(tracesRaw);
        Map<String, Candidate> candidates = candidates(xrefsRaw);
        List<InvestigationTarget> targets = new ArrayList<>();
        Set<String> runtimePaths = new LinkedHashSet<>();
        for (Candidate candidate : candidates.values()) {
            long hits = traces.hits.getOrDefault(candidate.key(), 0L);
            targets.add(new InvestigationTarget(candidate.identifier, candidate.owner, candidate.method,
                    candidate.descriptor, candidate.constant, confidence(normalizedQuery, candidate, hits), hits));
            Set<String> candidatePaths = traces.paths.get(candidate.key());
            if (candidatePaths != null) for (String path : candidatePaths) {
                if (runtimePaths.size() >= MAX_PATHS) break;
                runtimePaths.add(path);
            }
        }
        targets.sort(Comparator.comparingInt(InvestigationTarget::confidence).reversed()
                .thenComparing(Comparator.comparingLong(InvestigationTarget::runtimeHits).reversed())
                .thenComparing(InvestigationTarget::displayName));
        if (targets.size() > MAX_ENTRY_POINTS) targets = new ArrayList<>(targets.subList(0, MAX_ENTRY_POINTS));
        return new InvestigationReport(normalizedQuery, List.copyOf(targets), List.copyOf(constants.values),
                List.copyOf(runtimePaths), Map.copyOf(traces.hits), constants.classes.size());
    }

    private static ConstantData constants(String raw) {
        Set<String> classes = new LinkedHashSet<>();
        Set<String> values = new LinkedHashSet<>();
        for (String line : lines(raw)) {
            String[] columns = line.split("\t", 3);
            if (columns.length != 3 || columns[0].startsWith("<")) continue;
            classes.add(columns[0]);
            if (values.size() < MAX_CONSTANTS) values.add(unescape(columns[2]));
        }
        return new ConstantData(classes, values);
    }

    private static Map<String, Candidate> candidates(String raw) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (String line : lines(raw)) {
            String[] values = line.split("\t", -1);
            if (values.length != 9 || !"R".equals(values[0]) || !"STRING_USER".equals(values[2])) continue;
            try {
                Candidate candidate = new Candidate(decoded(values[4]), decoded(values[5]), decoded(values[6]),
                        decoded(values[7]), decoded(values[8]));
                candidates.putIfAbsent(candidate.key(), candidate);
            } catch (RuntimeException ignored) {
            }
        }
        return candidates;
    }

    private static TraceData traces(String raw) {
        Map<String, Long> statusHits = new LinkedHashMap<>();
        Map<String, Long> eventHits = new LinkedHashMap<>();
        Map<String, Set<String>> paths = new LinkedHashMap<>();
        for (String line : lines(raw)) {
            String[] values = line.split("\t", -1);
            try {
                if (values.length == 10 && "S".equals(values[0])) {
                    String key = key(decoded(values[7]), decoded(values[8]), decoded(values[9]));
                    statusHits.merge(key, Long.parseLong(values[2]), Math::max);
                } else if (values.length == 17 && "E".equals(values[0])) {
                    String owner = decoded(values[8]);
                    String method = decoded(values[9]);
                    String descriptor = decoded(values[10]);
                    String methodKey = key(owner, method, descriptor);
                    eventHits.merge(methodKey, 1L, Long::sum);
                    String stack = decoded(values[16]);
                    if (!stack.isBlank()) {
                        paths.computeIfAbsent(methodKey, ignored -> new LinkedHashSet<>())
                                .add(stack + "\n  -> " + owner + "." + method + descriptor);
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        Map<String, Long> hits = new LinkedHashMap<>(statusHits);
        for (Map.Entry<String, Long> entry : eventHits.entrySet())
            hits.merge(entry.getKey(), entry.getValue(), Math::max);
        return new TraceData(hits, paths);
    }

    private static int confidence(String query, Candidate candidate, long hits) {
        String needle = query.toLowerCase(Locale.ROOT);
        String constant = candidate.constant.toLowerCase(Locale.ROOT);
        int score = 52;
        if (constant.equals(needle)) score += 18;
        else if (!needle.isEmpty() && constant.contains(needle)) score += 12;
        if (interesting(constant)) score += 8;
        if (candidate.method.length() > 2 && !candidate.method.matches("[a-zA-Z]")) score += 4;
        if (hits > 0) score += Math.min(17, 5 + digits(hits) * 4);
        return Math.max(1, Math.min(99, score));
    }

    private static boolean interesting(String value) {
        return value.contains("license") || value.contains("verify") || value.contains("invalid")
                || value.contains("token") || value.contains("auth") || value.contains("hwid")
                || value.contains("login") || value.contains("crypt") || value.contains("http");
    }

    private static int digits(long value) {
        return String.valueOf(Math.max(1L, value)).length();
    }

    private static String decoded(String value) {
        return value.isEmpty() ? "" : new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String unescape(String value) {
        return value.replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\");
    }

    private static List<String> lines(String raw) {
        return raw == null || raw.isEmpty() ? List.of() : List.of(raw.split("\n"));
    }

    private static String key(String owner, String method, String descriptor) {
        return owner + "\u0000" + method + "\u0000" + descriptor;
    }

    private record Candidate(String identifier, String owner, String method, String descriptor, String constant) {
        String key() {
            return InvestigationAnalyzer.key(owner, method, descriptor);
        }
    }

    private record ConstantData(Set<String> classes, Set<String> values) {
    }

    private record TraceData(Map<String, Long> hits, Map<String, Set<String>> paths) {
    }
}

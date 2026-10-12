package dev.whitedev.jpi.fingerprint;

import dev.whitedev.jpi.debug.BreakpointSpec;
import dev.whitedev.jpi.deobfuscation.MappingEntry;
import dev.whitedev.jpi.deobfuscation.MappingKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record FingerprintComparison(int matchingDefinitions, int baselineDefinitions, int matchingHashes,
                                    int checkedHashes, List<Issue> issues) {
    public FingerprintComparison { issues = List.copyOf(issues); }
    public record Issue(String category, String location, String status, String reason) {}

    public double classMatchPercent() {
        return baselineDefinitions == 0 ? 0 : 100.0 * matchingDefinitions / baselineDefinitions;
    }

    public static FingerprintComparison compare(TargetFingerprint baseline, TargetFingerprint current,
                                                List<MappingEntry> mappings, List<BreakpointSpec> breakpoints) {
        Map<String, List<TargetFingerprint.Definition>> oldKeys = byKey(baseline);
        Map<String, List<TargetFingerprint.Definition>> newKeys = byKey(current);
        Map<String, List<TargetFingerprint.Definition>> names = new HashMap<>();
        for (var type : current.definitions()) names.computeIfAbsent(type.name(), ignored -> new ArrayList<>()).add(type);
        int matched = 0, matchingHashes = 0, checkedHashes = 0;
        List<Issue> issues = new ArrayList<>();
        if (baseline.truncated() || current.truncated()) issues.add(new Issue("Target", "Class catalog", "Unverified", "Catalog was truncated"));
        if (!baseline.mainClass().equals(current.mainClass())) issues.add(new Issue("Target", "Main class", "Changed", baseline.mainClass() + " -> " + current.mainClass()));
        if (!baseline.javaVersion().equals(current.javaVersion())) issues.add(new Issue("Target", "Java version", "Changed", baseline.javaVersion() + " -> " + current.javaVersion()));
        if (baseline.loadedCount() != current.loadedCount()) issues.add(new Issue("Target", "Loaded-class count", "Changed", baseline.loadedCount() + " -> " + current.loadedCount()));
        for (var entry : oldKeys.entrySet()) {
            List<TargetFingerprint.Definition> now = newKeys.getOrDefault(entry.getKey(), List.of());
            matched += Math.min(entry.getValue().size(), now.size());
            for (var before : entry.getValue()) {
                if (!before.inspected()) continue;
                checkedHashes++;
                if (entry.getValue().size() != 1 || now.size() > 1) {
                    issues.add(new Issue("Class", before.name(), "Unverified", "Ambiguous definitions with the same loader hierarchy"));
                } else if (now.isEmpty()) {
                    issues.add(new Issue("Class", before.name(), current.truncated() ? "Unverified" : "Missing", "Definition is absent from the captured catalog"));
                } else if (!now.getFirst().inspected()) {
                    issues.add(new Issue("Class", before.name(), "Unverified", now.getFirst().status()));
                } else if (before.hash().equals(now.getFirst().hash())) matchingHashes++;
                else issues.add(new Issue("Class", before.name(), "Changed", "Selected bytecode SHA-256 differs"));
            }
        }
        if (matched != baseline.definitions().size() || matched != current.definitions().size()) {
            issues.add(new Issue("Target", "Loaded class catalog", "Changed", "Definitions were loaded, unloaded, or moved to a different loader hierarchy"));
        }
        if (checkedHashes == 0) issues.add(new Issue("Target", "Bytecode coverage", "Unverified", "Baseline contains no verified bytecode hashes"));
        for (MappingEntry mapping : mappings) {
            if (!mapping.enabled() || mapping.mappedName().isEmpty() && mapping.comment().isEmpty() && mapping.tags().isEmpty()) continue;
            if (mapping.kind() == MappingKind.PACKAGE) {
                boolean present = names.keySet().stream().anyMatch(name -> name.startsWith(mapping.originalName() + "."));
                if (!present) issues.add(new Issue("Mapping", mapping.location(), current.truncated() ? "Unverified" : "Review", "No loaded class remains in the mapped package"));
                continue;
            }
            String owner = mapping.kind() == MappingKind.CLASS ? mapping.originalName() : mapping.owner();
            List<TargetFingerprint.Definition> candidates = names.getOrDefault(owner, List.of());
            String reason = resolve(candidates, mapping.kind() == MappingKind.CLASS ? "" : mapping.originalName(),
                    mapping.descriptor(), mapping.kind() != MappingKind.FIELD);
            if (candidates.isEmpty() && current.truncated()) reason = "Unverified: class catalog was truncated";
            if (reason == null && mapping.kind() == MappingKind.PARAMETER) {
                try {
                    int count = org.objectweb.asm.Type.getArgumentTypes(mapping.descriptor()).length;
                    if (mapping.parameterIndex() < 0 || mapping.parameterIndex() >= count) reason = "Parameter index no longer resolves";
                } catch (IllegalArgumentException error) { reason = "Invalid mapped method descriptor"; }
            }
            boolean changed = candidates.size() == 1 && oldKeys.getOrDefault(candidates.getFirst().key(), List.of()).stream()
                    .anyMatch(old -> old.inspected() && candidates.getFirst().inspected() && !old.hash().equals(candidates.getFirst().hash()));
            if (reason != null || changed) issues.add(new Issue("Mapping", mapping.location(),
                    reason != null && reason.startsWith("Unverified:") ? "Unverified" : "Review", Objects.requireNonNullElse(reason, "Owning class bytecode changed")));
        }
        for (BreakpointSpec breakpoint : breakpoints) {
            if (!breakpoint.enabled()) continue;
            var candidates = names.getOrDefault(breakpoint.className(), List.of());
            if (breakpoint.className().contains("*")) {
                issues.add(new Issue("Breakpoint", breakpoint.location(), "Unverified", "Wildcard breakpoints require debugger validation"));
                continue;
            }
            String reason = resolve(candidates, breakpoint.type() == BreakpointSpec.Type.METHOD || breakpoint.type() == BreakpointSpec.Type.BYTECODE
                    ? breakpoint.methodName() : "", breakpoint.descriptor(), true);
            if (reason == null && breakpoint.type() == BreakpointSpec.Type.LINE) {
                var lines = candidates.getFirst().members().stream().filter(TargetFingerprint.Member::method).flatMap(member -> member.lines().stream()).toList();
                if (lines.isEmpty()) reason = "Unverified: debug line information is unavailable";
                else if (!lines.contains(breakpoint.sourceLine())) reason = "Source line no longer resolves";
            }
            if (reason == null && breakpoint.type() == BreakpointSpec.Type.BYTECODE) reason = "Unverified: reconnect the debugger to validate the bytecode index";
            if (reason != null) issues.add(new Issue("Breakpoint", breakpoint.location(), candidates.isEmpty()
                    ? current.truncated() ? "Unverified" : "Not loaded" : reason.startsWith("Unverified:") ? "Unverified" : "Unresolved", reason));
        }
        return new FingerprintComparison(matched, baseline.definitions().size(), matchingHashes, checkedHashes, issues);
    }

    private static String resolve(List<TargetFingerprint.Definition> candidates, String member, String descriptor, boolean method) {
        if (candidates.isEmpty()) return "Class is not currently loaded";
        if (candidates.size() != 1) return "Unverified: multiple classloader definitions";
        var type = candidates.getFirst();
        if (!type.inspected()) return "Unverified: bytecode was not inspected";
        if (!member.isEmpty() && type.members().stream().noneMatch(value -> value.method() == method
                && value.name().equals(member) && (descriptor.isEmpty() || value.descriptor().equals(descriptor)))) return "Member signature no longer resolves";
        return null;
    }

    private static Map<String, List<TargetFingerprint.Definition>> byKey(TargetFingerprint fingerprint) {
        Map<String, List<TargetFingerprint.Definition>> result = new HashMap<>();
        for (var type : fingerprint.definitions()) result.computeIfAbsent(type.key(), ignored -> new ArrayList<>()).add(type);
        return result;
    }
}

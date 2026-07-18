package dev.whitedev.jpi.deobfuscation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MappingSearchQuery {
    private static final Pattern STRUCTURED = Pattern.compile(
            "(?i)(?:^|\\s)(class(?:es)?|field(?:s)?|method(?:s)?|package(?:s)?|param(?:eter)?s?)\\s*=\\s*(\"[^\"]*\"|\\S+)");
    private final Map<MappingKind, List<String>> filters;
    private final List<String> freeTerms;

    private MappingSearchQuery(Map<MappingKind, List<String>> filters, List<String> freeTerms) {
        this.filters = filters;
        this.freeTerms = freeTerms;
    }

    public static MappingSearchQuery parse(String input) {
        String value = input == null ? "" : input.trim();
        Map<MappingKind, List<String>> filters = new EnumMap<>(MappingKind.class);
        Matcher matcher = STRUCTURED.matcher(value);
        StringBuffer remaining = new StringBuffer();
        while (matcher.find()) {
            MappingKind kind = kind(matcher.group(1));
            List<String> terms = filters.computeIfAbsent(kind, ignored -> new ArrayList<>());
            String group = unquote(matcher.group(2));
            for (String item : group.split(",")) {
                String term = item.trim().toLowerCase(Locale.ROOT);
                if (!term.isEmpty()) terms.add(term);
            }
            matcher.appendReplacement(remaining, " ");
        }
        matcher.appendTail(remaining);
        List<String> freeTerms = new ArrayList<>();
        for (String item : remaining.toString().trim().split("\\s+")) {
            String term = item.trim().toLowerCase(Locale.ROOT);
            if (!term.isEmpty()) freeTerms.add(term);
        }
        return new MappingSearchQuery(filters, freeTerms);
    }

    public boolean matches(MappingEntry entry, DeobfuscationWorkspace workspace) {
        if (!matchesClassScope(entry, workspace)) return false;
        if (!matchesExplicitKind(entry, workspace)) return false;
        if (freeTerms.isEmpty()) return true;
        String searchable = searchable(entry, workspace).toLowerCase(Locale.ROOT);
        for (String term : freeTerms) if (!searchable.contains(term)) return false;
        return true;
    }

    public boolean structured() {
        return !filters.isEmpty();
    }

    private boolean matchesClassScope(MappingEntry entry, DeobfuscationWorkspace workspace) {
        List<String> classes = filters.get(MappingKind.CLASS);
        if (classes == null || classes.isEmpty()) return true;
        if (entry.kind() == MappingKind.PACKAGE) return false;
        String owner = entry.kind() == MappingKind.CLASS ? entry.originalName() : entry.owner();
        return matchesAny(classes, owner, simple(owner), workspace.classAlias(owner),
                simple(workspace.classAlias(owner)));
    }

    private boolean matchesExplicitKind(MappingEntry entry, DeobfuscationWorkspace workspace) {
        boolean memberFilters = has(MappingKind.FIELD) || has(MappingKind.METHOD)
                || has(MappingKind.PARAMETER) || has(MappingKind.PACKAGE);
        if (!memberFilters) return true;
        if (entry.kind() == MappingKind.CLASS) return has(MappingKind.CLASS);
        if (entry.kind() == MappingKind.PACKAGE) {
            return matchesAny(filters.get(MappingKind.PACKAGE), entry.originalName(), entry.mappedName());
        }
        if (entry.kind() == MappingKind.FIELD) {
            return matchesAny(filters.get(MappingKind.FIELD), entry.originalName(),
                    workspace.fieldAlias(entry.owner(), entry.originalName(), entry.descriptor()));
        }
        if (entry.kind() == MappingKind.METHOD) {
            return matchesAny(filters.get(MappingKind.METHOD), entry.originalName(),
                    workspace.methodAlias(entry.owner(), entry.originalName(), entry.descriptor()));
        }
        if (entry.kind() == MappingKind.PARAMETER) {
            return matchesAny(filters.get(MappingKind.PARAMETER),
                    "arg" + entry.parameterIndex(), entry.mappedName());
        }
        return false;
    }

    private boolean has(MappingKind kind) {
        List<String> values = filters.get(kind);
        return values != null && !values.isEmpty();
    }

    private static boolean matchesAny(List<String> terms, String... candidates) {
        if (terms == null || terms.isEmpty()) return false;
        for (String term : terms) {
            for (String candidate : candidates) {
                if (candidate != null && matches(term, candidate.toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    private static boolean matches(String term, String candidate) {
        if (term.indexOf('*') < 0) return candidate.equals(term);
        int patternIndex = 0;
        int valueIndex = 0;
        int star = -1;
        int checkpoint = 0;
        while (valueIndex < candidate.length()) {
            if (patternIndex < term.length() && term.charAt(patternIndex) == candidate.charAt(valueIndex)) {
                patternIndex++;
                valueIndex++;
            } else if (patternIndex < term.length() && term.charAt(patternIndex) == '*') {
                star = patternIndex++;
                checkpoint = valueIndex;
            } else if (star >= 0) {
                patternIndex = star + 1;
                valueIndex = ++checkpoint;
            } else return false;
        }
        while (patternIndex < term.length() && term.charAt(patternIndex) == '*') patternIndex++;
        return patternIndex == term.length();
    }

    private static String searchable(MappingEntry entry, DeobfuscationWorkspace workspace) {
        String ownerAlias = entry.owner().isEmpty() ? "" : workspace.classAlias(entry.owner());
        String mapped;
        if (entry.kind() == MappingKind.CLASS) mapped = workspace.classAlias(entry.originalName());
        else if (entry.kind() == MappingKind.METHOD) {
            mapped = workspace.methodAlias(entry.owner(), entry.originalName(), entry.descriptor());
        } else if (entry.kind() == MappingKind.FIELD) {
            mapped = workspace.fieldAlias(entry.owner(), entry.originalName(), entry.descriptor());
        } else mapped = entry.mappedName();
        return entry.kind() + " " + entry.location() + " " + ownerAlias + " " + mapped + " "
                + entry.tags() + " " + entry.comment() + " " + entry.descriptor();
    }

    private static MappingKind kind(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("class")) return MappingKind.CLASS;
        if (normalized.startsWith("field")) return MappingKind.FIELD;
        if (normalized.startsWith("method")) return MappingKind.METHOD;
        if (normalized.startsWith("package")) return MappingKind.PACKAGE;
        return MappingKind.PARAMETER;
    }

    private static String simple(String value) {
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'
                ? value.substring(1, value.length() - 1) : value;
    }
}
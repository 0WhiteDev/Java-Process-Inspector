package dev.whitedev.jpi.cases;

import dev.whitedev.jpi.agent.analysis.XrefAnalyzer;
import dev.whitedev.jpi.cases.InvestigationCase.Definition;
import dev.whitedev.jpi.search.SearchCategory;
import dev.whitedev.jpi.search.SearchEverythingService;
import dev.whitedev.jpi.search.SearchHit;
import dev.whitedev.jpi.search.SearchReport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CaseAnalysis {
    private static final int RESULT_LIMIT = 200;
    private static final int ANALYSIS_LIMIT = 1000;

    private CaseAnalysis() {
    }

    public record Method(String name, String descriptor) {
        @Override
        public String toString() {
            return name + descriptor;
        }
    }

    public static List<Method> methods(byte[] bytes) {
        ClassNode node = node(bytes, ClassReader.SKIP_CODE);
        return node.methods.stream().map(method -> new Method(method.name, method.desc))
                .sorted(Comparator.comparing(Method::toString)).limit(ANALYSIS_LIMIT).toList();
    }

    public record Search(List<SearchHit> hits, boolean partial) {
    }

    public static Search search(InvestigationCase data, String query) {
        String text = SearchEverythingService.query(query);
        String[] terms = text.toLowerCase(Locale.ROOT).split("\\s+");
        var counts = new EnumMap<SearchCategory, Integer>(SearchCategory.class);
        List<SearchHit> hits = new ArrayList<>();
        SearchReport local = SearchEverythingService.local(text, data.mappings(), data.timeline())
                .merge(data.exceptions() == null ? SearchReport.empty() : SearchEverythingService.exceptions(text, data.exceptions()));
        hits.addAll(SearchEverythingService.ranked(local, text, null));
        boolean partial = local.counts().values().stream().anyMatch(SearchReport.Count::partial);
        if (!data.notes().isBlank() && matches(terms, data.notes())) {
            hits.add(new SearchHit(SearchCategory.NOTES, "case-notes", "Case notes", "", data.notes(), "", "", "", "", ""));
        }
        for (var evidence : data.evidence()) {
            add(hits, counts, terms, SearchCategory.NOTES, evidence.title(),
                    new Definition(evidence.classId(), evidence.kind(), ""), evidence.method(), evidence.descriptor(), evidence.text());
        }
        for (var bookmark : data.bookmarks()) {
            add(hits, counts, terms, SearchCategory.NOTES, bookmark.label(),
                    new Definition(bookmark.classId(), "Bookmark", ""), bookmark.method(), bookmark.descriptor(), "");
        }
        for (var trace : data.traces()) {
            add(hits, counts, terms, SearchCategory.TIMELINE, trace.symbol(),
                    new Definition(trace.classId(), trace.owner(), ""), trace.method(), trace.descriptor(), trace.details());
        }
        long deadline = System.nanoTime() + 3_000_000_000L;
        for (Definition definition : data.catalog()) {
            if (Thread.currentThread().isInterrupted()) return new Search(List.copyOf(hits), true);
            add(hits, counts, terms, SearchCategory.CLASSES, definition.name(), definition, "", "", "");
            byte[] bytes = data.bytecode().get(definition.id());
            if (bytes == null) continue;
            if (System.nanoTime() >= deadline) {
                partial = true;
                continue;
            }
            try {
                ClassNode node = node(bytes, 0);
                for (var field : node.fields) {
                    add(hits, counts, terms, SearchCategory.FIELDS, field.name + ":" + field.desc,
                            definition, field.name, field.desc, "");
                }
                for (var method : node.methods) {
                    add(hits, counts, terms, SearchCategory.METHODS, method.name + method.desc,
                            definition, method.name, method.desc, "");
                    if (counts.getOrDefault(SearchCategory.STRINGS, 0) >= RESULT_LIMIT) continue;
                    for (var instruction : method.instructions) {
                        if (instruction instanceof LdcInsnNode constant && constant.cst instanceof String value) {
                            add(hits, counts, terms, SearchCategory.STRINGS, CaseRecorder.clip(value, 512),
                                    definition, method.name, method.desc, CaseRecorder.clip(value, 1024));
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                partial = true;
            }
        }
        return new Search(List.copyOf(hits), partial || counts.containsValue(RESULT_LIMIT));
    }

    public static String xrefs(InvestigationCase data, Definition definition, Method method) throws IOException {
        byte[] selected = data.bytecode().get(definition.id());
        if (selected == null) throw new IOException("No bytecode was saved for this definition");
        StringBuilder result = new StringBuilder("References from ").append(definition).append('.').append(method).append("\n\n");
        append(result, XrefAnalyzer.references(selected, method.name(), method.descriptor()), 0);
        result.append("\nPotential callers in saved bytecode\n")
                .append("Static symbolic matches do not prove classloader resolution.\n\n");
        int rows = 0;
        long deadline = System.nanoTime() + 3_000_000_000L;
        for (Definition owner : data.catalog()) {
            if (rows >= ANALYSIS_LIMIT || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
                result.append("\nCaller scan reached its limit.\n");
                break;
            }
            byte[] bytes = data.bytecode().get(owner.id());
            if (bytes == null) continue;
            try {
                List<XrefAnalyzer.Reference> callers = XrefAnalyzer.callers(bytes, owner.id(), owner.name(),
                        definition.name().replace('.', '/'), method.name(), method.descriptor());
                if (!callers.isEmpty()) {
                    result.append(owner).append(" | ").append(owner.id()).append('\n');
                    rows = append(result, callers, rows);
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return result.toString();
    }

    private static int append(StringBuilder result, List<XrefAnalyzer.Reference> references, int rows) {
        for (var reference : references) {
            if (rows >= ANALYSIS_LIMIT || result.length() > 128 * 1024) return ANALYSIS_LIMIT;
            result.append(reference.relation).append("  ").append(reference.className).append('.')
                    .append(reference.member).append(reference.descriptor).append("  ")
                    .append(CaseRecorder.clip(reference.detail, 256)).append('\n');
            rows++;
        }
        return rows;
    }

    private static ClassNode node(byte[] bytes, int flags) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, flags | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static void add(List<SearchHit> hits, Map<SearchCategory, Integer> counts, String[] terms,
                            SearchCategory category, String title, Definition definition,
                            String member, String descriptor, String details) {
        int count = counts.getOrDefault(category, 0);
        if (count >= RESULT_LIMIT || title.isBlank()
                || !matches(terms, title + ' ' + definition + ' ' + member + ' ' + descriptor + ' ' + details)) return;
        hits.add(new SearchHit(category, definition.id() + ":" + count, title, definition.toString(), details,
                definition.id(), definition.name(), member, descriptor, ""));
        counts.put(category, count + 1);
    }

    private static boolean matches(String[] terms, String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String term : terms) if (!normalized.contains(term)) return false;
        return true;
    }
}

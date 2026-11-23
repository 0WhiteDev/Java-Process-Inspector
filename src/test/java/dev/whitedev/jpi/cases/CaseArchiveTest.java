package dev.whitedev.jpi.cases;

import dev.whitedev.jpi.cases.InvestigationCase.Bookmark;
import dev.whitedev.jpi.cases.InvestigationCase.Definition;
import dev.whitedev.jpi.cases.InvestigationCase.Evidence;
import dev.whitedev.jpi.cases.InvestigationCase.Fingerprint;
import dev.whitedev.jpi.cases.InvestigationCase.Manifest;
import dev.whitedev.jpi.deobfuscation.MappingEntry;
import dev.whitedev.jpi.deobfuscation.MappingKind;
import dev.whitedev.jpi.evidence.EvidenceGraphBuilder;
import dev.whitedev.jpi.evidence.EvidenceStore.Kind;
import dev.whitedev.jpi.search.SearchCategory;
import dev.whitedev.jpi.ui.analysis.OfflineCfg;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaseArchiveTest {
    @TempDir
    Path directory;

    @Test
    void roundTripsEvidenceAndKeepsSameNameDefinitionsSeparate() throws Exception {
        String name = Sample.class.getName();
        Definition first = new Definition("c:a", name, "loader A");
        Definition second = new Definition("c:b", name, "loader B");
        byte[] bytes;
        try (var stream = Sample.class.getResourceAsStream("CaseArchiveTest$Sample.class")) {
            assertNotNull(stream);
            bytes = stream.readAllBytes();
        }
        var bookmark = new Bookmark(first.id(), "marker", "()Ljava/lang/String;", "Bookmark marker");
        var evidence = new Evidence("CFG", "Saved evidence", second.id(), "marker", "()Ljava/lang/String;", "Inspect this");
        var annotation = new MappingEntry(MappingKind.CLASS, name, name, "", -1, 0, "Alias", "Mapping note", "", "", true);
        var event = new TimelineEvent("recorded", 1, TimelineSource.MARKER, "", "", "", "Recorded marker", "");
        InvestigationCase data = new InvestigationCase(new Manifest(CaseArchive.VERSION, "Case", 1, 3, false, List.of()),
                new Fingerprint("JPI-test", name, "21", 2), List.of(first, second), Map.of(first.id(), bytes, second.id(), bytes),
                List.of(event), List.of(), null, "", List.of(annotation), "My case note", List.of(bookmark), List.of(evidence));
        Path file = directory.resolve("roundtrip.jpisession");
        CaseArchive.save(file, data);
        InvestigationCase opened = CaseArchive.open(file);
        assertEquals(data.manifest(), opened.manifest());
        assertEquals(data.fingerprint(), opened.fingerprint());
        assertEquals(List.of(first, second), opened.catalog());
        assertArrayEquals(bytes, opened.bytecode().get(second.id()));
        assertEquals(List.of(bookmark), opened.bookmarks());
        assertEquals(List.of(evidence), opened.evidence());
        assertEquals("Mapping note", opened.mappings().getFirst().comment());
        assertEquals(List.of(event), opened.timeline());
        assertTrue(opened.evidenceGraph().edges().stream().anyMatch(edge -> edge.kind() == Kind.STATIC));
        assertEquals(Set.of(first.id(), second.id()), opened.evidenceGraph().nodes().stream()
                .filter(node -> node.symbol() != null && node.symbol().member().equals("marker"))
                .map(node -> node.symbol().definitionId()).collect(Collectors.toSet()));
        Path copied = directory.resolve("copied.jpisession");
        CaseArchive.save(copied, opened);
        assertEquals(opened.evidenceGraph(), CaseArchive.open(copied).evidenceGraph());
        Path legacy = directory.resolve("legacy.jpisession");
        try (ZipFile source = new ZipFile(file.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(legacy))) {
            for (var entry : source.stream().toList()) {
                if (entry.getName().equals("analysis/evidence-graph.json")) continue;
                output.putNextEntry(new ZipEntry(entry.getName()));
                try (var input = source.getInputStream(entry)) {
                    if (entry.getName().equals("manifest.json")) {
                        String manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                        output.write(manifest.replace("\"version\":2", "\"version\":1").getBytes(StandardCharsets.UTF_8));
                    } else {
                        input.transferTo(output);
                    }
                }
                output.closeEntry();
            }
        }
        assertEquals(opened.evidenceGraph(), EvidenceGraphBuilder.saved(CaseArchive.open(legacy)));
        assertEquals(Set.of(first.id(), second.id()), CaseAnalysis.search(opened, "portable-marker").hits().stream()
                .filter(hit -> hit.category() == SearchCategory.STRINGS).map(hit -> hit.classId()).collect(Collectors.toSet()));
        var method = new CaseAnalysis.Method("marker", "()Ljava/lang/String;");
        assertTrue(CaseAnalysis.methods(bytes).contains(method));
        assertTrue(CaseAnalysis.xrefs(opened, second, method).contains("trim"));
        assertTrue(OfflineCfg.analyze(bytes, method.name(), method.descriptor()).report().contains("Complexity:"));
    }

    @Test
    void rejectsArchiveTraversalAndOversizedEntriesWithoutExtracting() throws Exception {
        Path traversal = directory.resolve("traversal.jpisession");
        write(traversal, "../unexpected.txt", 1);
        assertThrows(IOException.class, () -> CaseArchive.open(traversal));
        assertFalse(Files.exists(directory.getParent().resolve("unexpected.txt")));

        Path oversized = directory.resolve("oversized.jpisession");
        write(oversized, "runtime/timeline.jsonl", 9 * 1024 * 1024);
        assertThrows(IOException.class, () -> CaseArchive.open(oversized));
    }

    private static void write(Path target, String entry, int size) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            zip.putNextEntry(new ZipEntry(entry));
            byte[] chunk = new byte[8192];
            for (int written = 0; written < size; written += chunk.length)
                zip.write(chunk, 0, Math.min(chunk.length, size - written));
            zip.closeEntry();
        }
    }

    static final class Sample {
        static String marker() {
            return "portable-marker".trim();
        }
    }
}

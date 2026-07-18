package dev.whitedev.jpi.deobfuscation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingSearchQueryTest {
    @Test void filtersOneClassAndMultipleFieldsAndMethodsAtTheSameTime() {
        DeobfuscationWorkspace workspace = workspace();
        MappingSearchQuery query = MappingSearchQuery.parse(
                "class=abc field=a,b,c method=l,p,av1");

        List<String> matches = workspace.entries().stream()
                .filter(entry -> query.matches(entry, workspace))
                .map(entry -> entry.kind() + ":" + entry.originalName())
                .collect(Collectors.toList());

        assertTrue(matches.contains("CLASS:sample.abc"));
        assertTrue(matches.contains("FIELD:a"));
        assertTrue(matches.contains("FIELD:b"));
        assertTrue(matches.contains("METHOD:l"));
        assertTrue(matches.contains("METHOD:p"));
        assertFalse(matches.contains("FIELD:d"));
        assertFalse(matches.contains("METHOD:x"));
        assertFalse(matches.contains("CLASS:sample.other"));
    }

    @Test void searchesOriginalAndMappedNamesWithWildcards() {
        DeobfuscationWorkspace workspace = workspace();
        map(workspace, MappingKind.CLASS, "", "sample.abc", "", "AuthenticationManager");
        map(workspace, MappingKind.FIELD, "sample.abc", "a", "Ljava/lang/String;", "serverPublicKey");
        map(workspace, MappingKind.METHOD, "sample.abc", "l", "()Z", "validateLicense");

        MappingSearchQuery query = MappingSearchQuery.parse(
                "class=AuthenticationManager field=server* method=validate*");
        List<MappingEntry> matches = workspace.entries().stream()
                .filter(entry -> query.matches(entry, workspace))
                .collect(Collectors.toList());

        assertTrue(matches.stream().anyMatch(entry -> entry.kind() == MappingKind.CLASS));
        assertTrue(matches.stream().anyMatch(entry -> entry.kind() == MappingKind.FIELD
                && "a".equals(entry.originalName())));
        assertTrue(matches.stream().anyMatch(entry -> entry.kind() == MappingKind.METHOD
                && "l".equals(entry.originalName())));
    }

    @Test void keepsFreeTextAsAnAdditionalConstraint() {
        DeobfuscationWorkspace workspace = workspace();
        for (MappingEntry entry : workspace.entries()) {
            if (entry.kind() == MappingKind.METHOD && "l".equals(entry.originalName())) {
                entry.setTags("license auth");
                workspace.update(entry);
                break;
            }
        }

        MappingSearchQuery query = MappingSearchQuery.parse("method=l license");
        assertTrue(workspace.entries().stream().anyMatch(entry -> query.matches(entry, workspace)
                && entry.kind() == MappingKind.METHOD && "l".equals(entry.originalName())));
        assertFalse(workspace.entries().stream().anyMatch(entry -> query.matches(entry, workspace)
                && entry.kind() == MappingKind.METHOD && "p".equals(entry.originalName())));
    }

    private static DeobfuscationWorkspace workspace() {
        DeobfuscationWorkspace workspace = new DeobfuscationWorkspace();
        List<MappingEntry> entries = new ArrayList<>();
        entries.add(new MappingEntry(MappingKind.CLASS, "", "sample.abc", "", -1, 0));
        entries.add(new MappingEntry(MappingKind.FIELD, "sample.abc", "a", "Ljava/lang/String;", -1, 0));
        entries.add(new MappingEntry(MappingKind.FIELD, "sample.abc", "b", "I", -1, 0));
        entries.add(new MappingEntry(MappingKind.FIELD, "sample.abc", "d", "Z", -1, 0));
        entries.add(new MappingEntry(MappingKind.METHOD, "sample.abc", "l", "()Z", -1, 0));
        entries.add(new MappingEntry(MappingKind.METHOD, "sample.abc", "p", "()V", -1, 0));
        entries.add(new MappingEntry(MappingKind.METHOD, "sample.abc", "x", "()V", -1, 0));
        entries.add(new MappingEntry(MappingKind.CLASS, "", "sample.other", "", -1, 0));
        entries.add(new MappingEntry(MappingKind.FIELD, "sample.other", "a", "I", -1, 0));
        workspace.replace(entries);
        return workspace;
    }

    private static void map(DeobfuscationWorkspace workspace, MappingKind kind, String owner,
                            String original, String descriptor, String mapped) {
        MappingEntry entry = new MappingEntry(kind, owner, original, descriptor, -1, 0);
        entry.setMappedName(mapped);
        workspace.update(entry);
    }
}
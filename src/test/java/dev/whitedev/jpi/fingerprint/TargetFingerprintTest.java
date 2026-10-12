package dev.whitedev.jpi.fingerprint;

import dev.whitedev.jpi.agent.fingerprint.TargetFingerprintCapture;
import dev.whitedev.jpi.debug.BreakpointSpec;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.deobfuscation.MappingEntry;
import dev.whitedev.jpi.deobfuscation.MappingKind;
import dev.whitedev.jpi.deobfuscation.io.MappingFormats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TargetFingerprintTest {
    @TempDir Path directory;

    @Test void capturesSelectedHashesMembersAndStableLoaderKeys() throws Exception {
        String wire = capture();
        var fingerprint = TargetFingerprint.parse(wire);
        assertEquals(1, fingerprint.loadedCount());
        var definition = fingerprint.definitions().getFirst();
        assertTrue(definition.inspected());
        assertEquals(64, definition.hash().length());
        assertTrue(definition.members().stream().anyMatch(member -> member.name().equals("capture")));
        assertFalse(definition.key().contains("@"));
        var changedTime = new TargetFingerprint(1, fingerprint.mainClass(), fingerprint.javaVersion(), 1,
                false, fingerprint.definitions(), List.of());
        assertEquals(fingerprint.id(), changedTime.id());
    }

    @Test void preservesCatalogWhenBytecodeIsUnavailableAndRejectsLargeSelections() throws Exception {
        var unavailable = TargetFingerprint.parse(TargetFingerprintCapture.capture(new Class<?>[]{getClass()}, getClass().getName(),
                type -> { throw new java.io.IOException("unavailable"); }));
        assertEquals(1, unavailable.definitions().size());
        assertFalse(unavailable.definitions().getFirst().inspected());
        String selection = java.util.stream.IntStream.range(0, 129).mapToObj(index -> "Class" + index).collect(java.util.stream.Collectors.joining("\n"));
        assertThrows(java.io.IOException.class, () -> TargetFingerprintCapture.capture(new Class<?>[0], selection, type -> null));
        assertThrows(IllegalArgumentException.class, () -> TargetFingerprint.parse("bad"));
    }

    @Test void roundTripsWorkspaceBaselineAndSavedBreakpointsWithoutChangingMappings() throws Exception {
        var breakpoint = breakpoint("capture", "()Ljava/lang/String;", BreakpointSpec.Type.METHOD, null);
        String wire = TargetFingerprint.withBreakpoints(capture(), List.of(breakpoint));
        MappingEntry mapping = new MappingEntry(MappingKind.CLASS, "", getClass().getName(), "", -1, 1);
        mapping.setMappedName("ReadableName");
        Path file = directory.resolve("workspace.json");
        MappingFormats.write(file, MappingFormats.Format.JSON, List.of(mapping), wire);
        assertEquals("ReadableName", MappingFormats.readJson(file).getFirst().mappedName());
        assertEquals(wire, MappingFormats.readFingerprint(file));
        assertEquals(List.of(breakpoint), TargetFingerprint.parse(MappingFormats.readFingerprint(file)).breakpoints());
        DeobfuscationWorkspace workspace = new DeobfuscationWorkspace();
        workspace.setTargetFingerprint(wire);
        assertEquals(wire, workspace.targetFingerprint());
        workspace.setTargetFingerprint("");
        assertEquals("", workspace.targetFingerprint());
        MappingFormats.write(file, MappingFormats.Format.JSON, List.of(mapping));
        assertEquals("", MappingFormats.readFingerprint(file));
    }

    @Test void reportsChangedMethodsAndUnresolvedLinesWithoutTreatingUnknownDataAsVerified() {
        var before = definition("a".repeat(64), List.of(new TargetFingerprint.Member("run", "()V", true, List.of(10))));
        var after = definition("b".repeat(64), List.of(new TargetFingerprint.Member("other", "()V", true, List.of(20))));
        MappingEntry mapping = new MappingEntry(MappingKind.METHOD, getClass().getName(), "run", "()V", -1, 1);
        mapping.setMappedName("execute");
        var result = FingerprintComparison.compare(fingerprint(before), fingerprint(after), List.of(mapping),
                List.of(breakpoint("run", "()V", BreakpointSpec.Type.METHOD, null), breakpoint("", "", BreakpointSpec.Type.LINE, 10)));
        assertEquals(100, result.classMatchPercent());
        assertEquals(0, result.matchingHashes());
        assertEquals(1, result.checkedHashes());
        assertEquals(1, result.issues().stream().filter(issue -> issue.category().equals("Mapping") && issue.status().equals("Review")).count());
        assertEquals(2, result.issues().stream().filter(issue -> issue.status().equals("Unresolved")).count());
        var unknown = definition("", List.of());
        var unverified = FingerprintComparison.compare(fingerprint(before), fingerprint(unknown), List.of(mapping), List.of());
        assertTrue(unverified.issues().stream().filter(issue -> issue.category().equals("Mapping")).allMatch(issue -> issue.status().equals("Unverified")));
    }

    @Test void treatsDuplicateLoaderDefinitionsAndMissingClassesConservatively() {
        var type = definition("a".repeat(64), List.of());
        var duplicate = new TargetFingerprint(1, "Main", "21", 2, false, List.of(type, type), List.of());
        var result = FingerprintComparison.compare(fingerprint(type), duplicate, List.of(),
                List.of(breakpoint("run", "()V", BreakpointSpec.Type.METHOD, null)));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.status().equals("Unverified")));
        var empty = new TargetFingerprint(1, "Main", "21", 0, false, List.of(), List.of());
        var missing = FingerprintComparison.compare(fingerprint(type), empty, List.of(),
                List.of(breakpoint("run", "()V", BreakpointSpec.Type.METHOD, null)));
        assertEquals(0, missing.classMatchPercent());
        assertTrue(missing.issues().stream().anyMatch(issue -> issue.category().equals("Breakpoint") && issue.status().equals("Not loaded")));
    }

    @Test void marksMissingDebugSymbolsAndTruncatedCatalogsAsUnverified() {
        var type = definition("a".repeat(64), List.of(new TargetFingerprint.Member("run", "()V", true, List.of())));
        var truncated = new TargetFingerprint(1, "Main", "21", 1, true, List.of(type), List.of());
        var result = FingerprintComparison.compare(fingerprint(type), truncated, List.of(),
                List.of(breakpoint("", "", BreakpointSpec.Type.LINE, 10)));
        assertEquals(2, result.issues().stream().filter(issue -> issue.status().equals("Unverified")).count());
        assertEquals(0, result.issues().stream().filter(issue -> issue.status().equals("Unresolved")).count());
    }

    @Test void rejectsCorruptBaselineMetadataInsteadOfSilentlyDiscardingIt() throws Exception {
        Path file = directory.resolve("corrupt.json");
        java.nio.file.Files.writeString(file, "{\"targetFingerprint\":\"@\"}");
        assertThrows(java.io.IOException.class, () -> MappingFormats.readFingerprint(file));
        assertThrows(IllegalArgumentException.class, () -> new TargetFingerprint(1, "Main", "21", -1, false, List.of(), List.of()));
    }

    @Test void doesNotClaimBuildVerificationWithoutSelectedBytecode() {
        var type = definition("", List.of());
        var result = FingerprintComparison.compare(fingerprint(type), fingerprint(type), List.of(), List.of());
        assertEquals(100, result.classMatchPercent());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.location().equals("Bytecode coverage") && issue.status().equals("Unverified")));
    }

    private String capture() throws Exception {
        return TargetFingerprintCapture.capture(new Class<?>[]{getClass()}, getClass().getName(), type -> {
            try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                return input.readAllBytes();
            }
        });
    }

    private TargetFingerprint.Definition definition(String hash, List<TargetFingerprint.Member> members) {
        return new TargetFingerprint.Definition(getClass().getName() + "|Loader", getClass().getName(), "Loader", hash,
                hash.isEmpty() ? "not selected" : "hashed", members);
    }

    private TargetFingerprint fingerprint(TargetFingerprint.Definition type) {
        return new TargetFingerprint(1, "Main", "21", 1, false, List.of(type), List.of());
    }

    private BreakpointSpec breakpoint(String method, String descriptor, BreakpointSpec.Type type, Integer line) {
        return new BreakpointSpec(getClass().getName(), method, descriptor, line, null, type, BreakpointSpec.SuspendPolicy.THREAD, true);
    }
}

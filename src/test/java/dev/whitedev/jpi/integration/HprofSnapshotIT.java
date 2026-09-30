package dev.whitedev.jpi.integration;

import dev.whitedev.jpi.fixture.HprofReferenceFixture;
import dev.whitedev.jpi.heap.HprofSnapshot;
import dev.whitedev.jpi.heap.HprofAnalysis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HprofSnapshotIT {
    @TempDir Path directory;

    @Test
    void findsRealIncomingReferencesRootsAndRetainedPayload() throws Exception {
        Path dump = directory.resolve("references.hprof");
        Path log = directory.resolve("fixture.log");
        String executable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.exists(Path.of(executable))) executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(executable, "-Xmx64m", "-cp", System.getProperty("jpi.test.classes"),
                HprofReferenceFixture.class.getName(), dump.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Heap fixture timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        try (HprofAnalysis snapshot = HprofAnalysis.open(dump, 256)) {
            var objects = snapshot.search("HprofReferenceFixture$UserSession", 10);
            assertEquals(1, objects.size());
            var report = snapshot.inspect(objects.getFirst().id(), true, true);
            assertTrue(report.incomingCount() >= 3);
            assertEquals(1, report.outgoingCount());
            assertTrue(report.object().retainedSize() >= 1024 * 1024);
            assertTrue(report.incoming().stream().anyMatch(edge -> edge.edge().endsWith("currentSession")));
            assertTrue(report.incoming().stream().anyMatch(HprofSnapshot.Reference::weak));
            assertFalse(report.paths().isEmpty());
            assertTrue(report.paths().stream().flatMap(path -> path.steps().stream())
                    .anyMatch(step -> step.edgeToChild().endsWith("INSTANCE")));
            assertTrue(report.paths().stream().flatMap(path -> path.steps().stream())
                    .noneMatch(step -> step.edgeToChild().endsWith("referent")));
            assertTrue(snapshot.biggest(10).stream().anyMatch(value -> value.id() == objects.getFirst().id()));
            assertThrows(java.io.IOException.class, () -> snapshot.inspect(0, false, true));
            var loaders = snapshot.search("jdk.internal.loader.ClassLoaders$AppClassLoader", 10);
            assertEquals(1, loaders.size());
            var loaderReport = snapshot.inspect(loaders.getFirst().id(), true, true);
            assertTrue(loaderReport.loaderClasses().stream().anyMatch(type -> type.name().equals(HprofReferenceFixture.class.getName())));
            assertTrue(loaderReport.loaderInstances() >= 1);
        }
        String classpath = System.getProperty("jpi.agent.jar") + java.io.File.pathSeparator + System.getProperty("jpi.test.classes");
        Process packaged = new ProcessBuilder(executable, "-cp", classpath,
                "dev.whitedev.jpi.fixture.HprofPackagedProbe", dump.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(packaged.waitFor(30, TimeUnit.SECONDS), "Packaged analyzer timed out");
            assertEquals(0, packaged.exitValue(), Files.readString(log));
        } finally {
            if (packaged.isAlive()) packaged.destroyForcibly();
        }
        assertTrue(Files.deleteIfExists(dump));
    }

    @Test
    void rejectsInvalidDump() throws Exception {
        Path invalid = directory.resolve("invalid.hprof");
        Files.writeString(invalid, "not a heap dump");
        assertThrows(java.io.IOException.class, () -> HprofAnalysis.open(invalid, 256));
    }
}

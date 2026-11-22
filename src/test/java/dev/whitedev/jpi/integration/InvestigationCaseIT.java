package dev.whitedev.jpi.integration;

import dev.whitedev.jpi.attach.AttachService;
import dev.whitedev.jpi.cases.CaseAnalysis;
import dev.whitedev.jpi.cases.CaseArchive;
import dev.whitedev.jpi.cases.CaseRecorder;
import dev.whitedev.jpi.cases.InvestigationCase;
import dev.whitedev.jpi.decompile.DecompilerService;
import dev.whitedev.jpi.deobfuscation.MappingEntry;
import dev.whitedev.jpi.deobfuscation.MappingKind;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigationCaseIT {
    @TempDir
    Path directory;

    @Test
    @Timeout(60)
    void recordsAndReopensAfterJvmStopsWithoutKeepingPrivateDetailsByDefault() throws Exception {
        Path file = directory.resolve("portable.jpisession");
        AttachService service = new AttachService(new File(System.getProperty("jpi.agent.jar", "target/jpi.jar")));
        try (var prepared = service.prepareEarlyAgent()) {
            String command = prepared.jvmArgument();
            if (command.startsWith("\"")) command = command.substring(1, command.length() - 1);
            String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
            Process target = new ProcessBuilder(java, command, "-cp", System.getProperty("jpi.test.classes", "target/test-classes"),
                    TransformationFixture.class.getName()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try (var session = prepared.await(Long.toString(target.pid()), "Case fixture");
                 var input = target.inputReader(); var output = target.outputWriter()) {
                assertEquals(Long.toString(target.pid()), input.readLine());
                CaseRecorder untouched = CaseRecorder.start(session, "Passive", false, () -> true);
                assertTrue(session.requestText(Operation.TRACE_HISTORY, "").isBlank());
                assertTrue(untouched.finish(session, List.of(), () -> true).traces().isEmpty());
                var definition = untouched.catalog().stream().filter(item -> item.name().equals(TransformationTarget.class.getName())
                        && !item.loader().contains("DuplicateLoader")).findFirst().orElseThrow();
                String probe = session.requestText(Operation.TRACE_START,
                        definition.id() + "\nupdate\n(I)I\nstopAfterMillis=60000").split("\t")[0];
                assertEquals("11", invoke(input, output, 10));

                CaseRecorder recording = CaseRecorder.start(session, "Portable", false, () -> true);
                assertEquals(1, recording.capture(session, List.of(definition), () -> true));
                recording.event(new TimelineEvent("secret-4711", 1, TimelineSource.FILE, "secret-4711",
                        "45", "0", "secret-4711", "secret-4711"));
                assertEquals("21", invoke(input, output, 20));
                var inventory = new MappingEntry(MappingKind.CLASS, "", "Unmodified", "", -1, 0);
                var mapping = new MappingEntry(MappingKind.CLASS, "", definition.name(), "", -1, 0);
                mapping.setMappedName("FixtureAlias");
                InvestigationCase captured = recording.finish(session, List.of(inventory, mapping), () -> true);
                assertEquals(1, captured.traces().size());
                assertEquals(List.of("FixtureAlias"), captured.mappings().stream().map(MappingEntry::mappedName).toList());
                assertEquals("", captured.traces().getFirst().details());
                assertEquals(definition.id(), captured.traces().getFirst().classId());
                assertFalse(captured.timeline().getFirst().toString().contains("secret-4711"));
                assertEquals("45", captured.timeline().getFirst().callId());
                CaseArchive.save(file, captured);

                CaseRecorder optedIn = CaseRecorder.start(session, "Details", true, () -> true);
                assertEquals("31", invoke(input, output, 30));
                var detailed = optedIn.finish(session, List.of(), () -> true);
                assertEquals(1, detailed.traces().size());
                assertTrue(detailed.traces().getFirst().details().contains("Return: 31"));
                session.requestText(Operation.TRACE_STOP, probe);
            } finally {
                target.destroy();
                if (!target.waitFor(3, TimeUnit.SECONDS)) {
                    target.destroyForcibly();
                    assertTrue(target.waitFor(3, TimeUnit.SECONDS));
                }
            }
        }
        InvestigationCase offline = CaseArchive.open(file);
        assertNotNull(offline.fingerprint());
        assertEquals(1, offline.bytecode().size());
        assertTrue(CaseAnalysis.search(offline, "counter").hits().stream().anyMatch(hit -> hit.member().equals("counter")));
        assertFalse(CaseAnalysis.search(offline, "FixtureAlias").hits().isEmpty());
        String source = new DecompilerService().decompile("SavedClass", offline.bytecode().values().iterator().next());
        assertTrue(source.contains("int update"), source);
    }

    private static String invoke(BufferedReader input, BufferedWriter output, int value) throws IOException {
        output.write("primary " + value + "\n");
        output.flush();
        return input.readLine();
    }
}

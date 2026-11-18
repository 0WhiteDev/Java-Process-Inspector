package dev.whitedev.jpi.integration;

import dev.whitedev.jpi.agent.patch.PatchHistory;
import dev.whitedev.jpi.attach.AttachService;
import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact;
import dev.whitedev.jpi.protocol.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ClassTransformationIT {
    private static final String METHOD = "\nupdate\n(I)I\n";
    private static final List<List<Probe>> ORDERS = List.of(
            List.of(Probe.TRACE, Probe.CFG, Probe.FIELD),
            List.of(Probe.TRACE, Probe.FIELD, Probe.CFG),
            List.of(Probe.CFG, Probe.TRACE, Probe.FIELD),
            List.of(Probe.CFG, Probe.FIELD, Probe.TRACE),
            List.of(Probe.FIELD, Probe.TRACE, Probe.CFG),
            List.of(Probe.FIELD, Probe.CFG, Probe.TRACE)
    );

    @Test
    @Timeout(90)
    void composesProbesAndPatchesWithJavaInstrumentation() throws Exception {
        verify(false);
    }

    @Test
    @Timeout(90)
    void composesProbesAndPatchesWithNativeJvmti() throws Exception {
        boolean available = NativeAgentArtifact.class.getResource(NativeAgentArtifact.resourcePath()) != null;
        if (Boolean.getBoolean("jpi.native.required")) assertTrue(available);
        else assumeTrue(available, "Bundled JVMTI library is unavailable");
        verify(true);
    }

    private static void verify(boolean nativeJvmti) throws Exception {
        AttachService service = new AttachService(new File(System.getProperty("jpi.agent.jar", "target/jpi.jar")));
        try (AttachService.PreparedAgentSession prepared = nativeJvmti
                ? service.prepareEarlyNativeAgent(NativeAgentArtifact.extractBundled()) : service.prepareEarlyAgent()) {
            Process process = startFixture(prepared.jvmArgument());
            try (BufferedReader responses = process.inputReader(); BufferedWriter commands = process.outputWriter();
                 InspectorSession session = prepared.await(Long.toString(process.pid()), "Transformation fixture")) {
                assertEquals(Long.toString(process.pid()), responses.readLine());
                String expected = nativeJvmti ? "runtime=JVMTI" : "runtime=Instrumentation";
                String info = session.requestText(Operation.SESSION_INFO, "");
                assertTrue(info.lines().anyMatch(expected::equals), info);
                List<String> definitions = session.requestText(Operation.CLASSES, "").lines()
                        .filter(line -> line.split("\t", 3)[1].equals(TransformationTarget.class.getName())).toList();
                assertEquals(2, definitions.size());
                String primary = identifier(definitions, false);
                String duplicate = identifier(definitions, true);
                assertNotEquals(primary, duplicate);
                byte[] original = session.request(Operation.CLASS_BYTES, primary);
                byte[] originalDuplicate = session.request(Operation.CLASS_BYTES, duplicate);
                for (int index = 0; index < ORDERS.size(); index++) {
                    verifyOrder(session, commands, responses, primary, duplicate, original, ORDERS.get(index),
                            ORDERS.get(ORDERS.size() - index - 1), index == 0);
                }
                assertArrayEquals(originalDuplicate, session.request(Operation.CLASS_BYTES, duplicate));
            } finally {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        }
    }

    private static void verifyOrder(InspectorSession session, BufferedWriter commands, BufferedReader responses,
                                    String primary, String duplicate, byte[] original, List<Probe> startOrder,
                                    List<Probe> stopOrder, boolean testPatch) throws Exception {
        Map<Probe, String> probes = new EnumMap<>(Probe.class);
        for (Probe probe : startOrder) probes.put(probe, probe.start(session, primary));
        Set<Probe> active = EnumSet.allOf(Probe.class);
        assertArrayEquals(original, session.request(Operation.CLASS_BYTES, primary));
        int cfgHits = assertInvocation(session, commands, responses, probes, active, 0, "primary", "5");
        assertInvocation(session, commands, responses, probes, EnumSet.noneOf(Probe.class), cfgHits, "duplicate", "5");
        if (testPatch) {
            byte[] replacement = edit(original, false);
            apply(session, primary, replacement);
            cfgHits = assertInvocation(session, commands, responses, probes, active, cfgHits, "primary", "6");
            assertInvocation(session, commands, responses, probes, EnumSet.noneOf(Probe.class), cfgHits, "duplicate", "5");
            IOException conflict = assertThrows(IOException.class, () -> apply(session, primary, edit(original, true)));
            assertTrue(conflict.getMessage().contains("Transformation conflict in CFG"), conflict.getMessage());
            assertArrayEquals(replacement, session.request(Operation.CLASS_BYTES, primary));
            cfgHits = assertInvocation(session, commands, responses, probes, active, cfgHits, "primary", "6");
            String history = session.requestText(Operation.PATCH_HISTORY, primary);
            String revision = history.lines().findFirst().orElseThrow().split("\t")[0];
            session.requestText(Operation.ROLLBACK_PATCH, primary + "\n" + revision + "\n" + PatchHistory.sha256(replacement));
            assertArrayEquals(original, session.request(Operation.CLASS_BYTES, primary));
            cfgHits = assertInvocation(session, commands, responses, probes, active, cfgHits, "primary", "5");
            assertFalse(session.requestText(Operation.PATCH_HISTORY, duplicate).contains(revision + "\t"));
            verifyFileObservers(session, commands, responses);
            cfgHits = assertInvocation(session, commands, responses, probes, active, cfgHits, "primary", "5");
        }
        for (Probe probe : stopOrder) {
            probe.stop(session, probes.get(probe));
            active.remove(probe);
            cfgHits = assertInvocation(session, commands, responses, probes, active, cfgHits, "primary", "5");
        }
        assertArrayEquals(original, session.request(Operation.CLASS_BYTES, primary));
    }

    private static void verifyFileObservers(InspectorSession session, BufferedWriter commands,
                                            BufferedReader responses) throws Exception {
        Path file = Files.createTempFile("jpi-transformation-", ".txt");
        try {
            Files.writeString(file, "test");
            String hooks = session.requestText(Operation.API_HOOK_START, "FILES\nstopAfterSeconds=180");
            assertTrue(hooks.startsWith("Active"), hooks);
            String monitor = session.requestText(Operation.FILE_MONITOR_START, "captureContent=false");
            assertTrue(monitor.startsWith("File Monitor active"), monitor);
            assertFileRead(session, commands, responses, file, true, true);
            session.requestText(Operation.API_HOOK_STOP, "");
            assertFileRead(session, commands, responses, file, false, true);
            session.requestText(Operation.FILE_MONITOR_STOP, "");
            assertFileRead(session, commands, responses, file, false, false);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void assertFileRead(InspectorSession session, BufferedWriter commands, BufferedReader responses,
                                       Path file, boolean hooks, boolean monitor) throws IOException {
        commands.write("read " + file + "\n");
        commands.flush();
        assertEquals("4", responses.readLine());
        assertEquals(hooks ? 1 : 0, events(session, Operation.API_HOOK_EVENTS));
        assertEquals(monitor ? 1 : 0, events(session, Operation.FILE_EVENT_BATCH));
    }

    private static int assertInvocation(InspectorSession session, BufferedWriter commands, BufferedReader responses,
                                        Map<Probe, String> probes, Set<Probe> active, int previousCfgHits,
                                        String definition, String expected) throws Exception {
        commands.write(definition + " 4\n");
        commands.flush();
        assertEquals(expected, responses.readLine());
        assertEquals(active.contains(Probe.TRACE) ? 1 : 0, events(session, Operation.TRACE_EVENTS));
        assertEquals(active.contains(Probe.FIELD) ? 1 : 0, events(session, Operation.FIELD_TRACE_EVENTS));
        String cfg = session.requestText(Operation.CFG_SNAPSHOT, probes.get(Probe.CFG));
        int hits = Integer.parseInt(cfg.lines().findFirst().orElseThrow().split("\t")[5]);
        assertEquals(previousCfgHits + (active.contains(Probe.CFG) ? 2 : 0), hits, cfg);
        return hits;
    }

    private static long events(InspectorSession session, Operation operation) throws IOException {
        return session.requestText(operation, "").lines().filter(line -> line.startsWith("E\t")).count();
    }

    private static String identifier(List<String> definitions, boolean duplicate) {
        return definitions.stream().filter(line -> line.contains("TransformationFixture$DuplicateLoader@") == duplicate)
                .findFirst().orElseThrow().split("\t")[0];
    }

    private static void apply(InspectorSession session, String id, byte[] replacement) throws IOException {
        session.requestText(Operation.APPLY_CLASS_BYTES, id + "\n" + Base64.getEncoder().encodeToString(replacement));
    }

    private static byte[] edit(byte[] original, boolean changeLayout) {
        ClassNode type = new ClassNode();
        new ClassReader(original).accept(type, 0);
        var method = type.methods.stream().filter(candidate -> candidate.name.equals("update")).findFirst().orElseThrow();
        if (changeLayout) {
            method.instructions.clear();
            method.localVariables = null;
            method.instructions.add(new InsnNode(Opcodes.ICONST_0));
            method.instructions.add(new InsnNode(Opcodes.IRETURN));
        } else {
            var constant = method.instructions.iterator();
            while (constant.hasNext()) {
                var instruction = constant.next();
                if (instruction.getOpcode() == Opcodes.ICONST_1) {
                    constant.set(new InsnNode(Opcodes.ICONST_2));
                    break;
                }
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        type.accept(writer);
        return writer.toByteArray();
    }

    private static Process startFixture(String agentArgument) throws IOException {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        if (agentArgument.startsWith("\"")) agentArgument = agentArgument.substring(1, agentArgument.length() - 1);
        return new ProcessBuilder(java, agentArgument, "-cp", System.getProperty("jpi.test.classes", "target/test-classes"),
                TransformationFixture.class.getName()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    private enum Probe {
        TRACE(Operation.TRACE_START, Operation.TRACE_STOP, METHOD + "stopAfterMillis=180000", 0),
        CFG(Operation.CFG_TRACE_START, Operation.CFG_TRACE_STOP, METHOD + "180000", 1),
        FIELD(Operation.FIELD_TRACE_START, Operation.FIELD_TRACE_STOP, "\ncounter\nstopAfterSeconds=180", 0);

        private final Operation start;
        private final Operation stop;
        private final String settings;
        private final int responseColumn;

        Probe(Operation start, Operation stop, String settings, int responseColumn) {
            this.start = start;
            this.stop = stop;
            this.settings = settings;
            this.responseColumn = responseColumn;
        }

        String start(InspectorSession session, String id) throws IOException {
            return session.requestText(start, id + settings).split("\t")[responseColumn];
        }

        void stop(InspectorSession session, String id) throws IOException {
            String result = session.requestText(stop, id);
            assertTrue(result.startsWith("Stopped"), result);
        }
    }
}

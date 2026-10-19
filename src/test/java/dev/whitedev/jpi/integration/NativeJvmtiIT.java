package dev.whitedev.jpi.integration;

import dev.whitedev.jpi.attach.ImprovedAttachService;
import dev.whitedev.jpi.attach.AttachService;
import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.attach.JvmDescriptor;
import dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact;
import dev.whitedev.jpi.protocol.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.objectweb.asm.ClassReader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class NativeJvmtiIT {
    private final ImprovedAttachService service = new ImprovedAttachService(new File(System.getProperty("jpi.agent.jar", "target/jpi.jar")));

    @Test void earlyLaunchBindsNativeBackendWithoutDynamicLoading() throws Exception {
        NativeAgentArtifact artifact = artifact();
        File targetJar = fixtureJar();
        var result = service.launch(targetJar, List.of("-XX:-EnableDynamicAgentLoading"), List.of(), artifact);
        try (InspectorSession session = result.session()) {
            assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("mode=startup"));
            assertBackend(session);
            assertTrue(result.process().isAlive());
        } finally { stop(result.process()); }
    }

    @Test void loadsThroughAttachApiEnumeratesClassesAndCapturesBytes() throws Exception {
        NativeAgentArtifact artifact = artifact();
        Process process = start(List.of());
        try (InspectorSession session = service.attach(new JvmDescriptor(Long.toString(process.pid()), "JVMTI fixture"))) {
            assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("not-bound"));
            service.loadNativeAgent(session.target().id(), artifact);
            String bound = session.requestText(Operation.JVMTI_BIND, artifact.bindingOptions());
            assertTrue(bound.contains("backend=JVMTI"), bound);
            assertTrue(bound.contains("mode=attach"), bound);
            assertBackend(session);
            Path copy = java.nio.file.Files.createTempFile("jpi-identical-native-", System.mapLibraryName("jpi_jvmti"));
            try {
                java.nio.file.Files.copy(artifact.library(), copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                assertTrue(service.enableNativeBackend(session, NativeAgentArtifact.verify(copy, artifact.sha256())).contains("backend=JVMTI"));
            } finally { java.nio.file.Files.deleteIfExists(copy); }
            assertThrows(IOException.class, () -> session.requestText(Operation.JVMTI_BIND, artifact.library() + "\n" + "0".repeat(64)));
            assertEquals("pong", session.requestText(Operation.PING, ""));
        } finally { stop(process); }
    }

    @Test void supportsAgentpathWithDynamicAgentLoadingDisabled() throws Exception {
        NativeAgentArtifact artifact = artifact();
        try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
            Process process = start(List.of("-XX:-EnableDynamicAgentLoading", artifact.startupArgument(), unquote(prepared.jvmArgument())));
            try (InspectorSession session = prepared.await(Long.toString(process.pid()), "JVMTI startup fixture")) {
                String bound = session.requestText(Operation.JVMTI_BIND, artifact.bindingOptions());
                assertTrue(bound.contains("mode=startup"), bound);
                assertBackend(session);
            } finally { stop(process); }
        }
    }

    @Test void respectsDynamicLoadingPolicyAndKeepsInstrumentationAvailable() throws Exception {
        NativeAgentArtifact artifact = artifact();
        try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
            Process process = start(List.of("-XX:-EnableDynamicAgentLoading", unquote(prepared.jvmArgument())));
            try (InspectorSession session = prepared.await(Long.toString(process.pid()), "JVMTI policy fixture")) {
                assertThrows(IOException.class, () -> service.loadNativeAgent(session.target().id(), artifact));
                assertThrows(IOException.class, () -> session.requestText(Operation.JVMTI_BIND, artifact.bindingOptions()));
                assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("not-bound"));
                assertFalse(session.requestText(Operation.CLASSES, "").isEmpty());
                assertEquals("pong", session.requestText(Operation.PING, ""));
            } finally { stop(process); }
        }
    }

    private NativeAgentArtifact artifact() throws Exception {
        boolean present = NativeAgentArtifact.class.getResource(NativeAgentArtifact.resourcePath()) != null;
        if (Boolean.getBoolean("jpi.native.required")) assertTrue(present, "Native CI requires the bundled library");
        else Assumptions.assumeTrue(present, "Native backend was not compiled for this build");
        return NativeAgentArtifact.extractBundled();
    }

    private File fixtureJar() throws Exception {
        File jar = java.nio.file.Files.createTempFile("jpi-jvmti-early-", ".jar").toFile();
        jar.deleteOnExit();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, AttachTarget.class.getName());
        try (JarOutputStream output = new JarOutputStream(new FileOutputStream(jar), manifest)) {
            for (Class<?> type : List.of(AttachTarget.class, RawMemoryFixture.class)) {
                String resource = type.getName().replace('.', '/') + ".class";
                try (InputStream input = type.getClassLoader().getResourceAsStream(resource)) {
                    assertNotNull(input);
                    output.putNextEntry(new JarEntry(resource));
                    input.transferTo(output);
                    output.closeEntry();
                }
            }
        }
        return jar;
    }

    private void assertBackend(InspectorSession session) throws Exception {
        assertRawMemory(session);
        String status = session.requestText(Operation.JVMTI_STATUS, "");
        assertTrue(status.contains("phase=live"), status);
        assertTrue(status.contains("can_retransform_classes=1"), status);
        assertTrue(status.contains("can_redefine_classes=1"), status);
        assertTrue(status.contains("can_tag_objects=1"), status);
        String classes = session.requestText(Operation.JVMTI_CLASSES, "");
        assertTrue(classes.contains("\t" + AttachTarget.class.getName() + "\t"), classes.substring(0, Math.min(200, classes.length())));
        String classId = java.util.Arrays.stream(classes.split("\n"))
                .filter(line -> line.contains("\t" + AttachTarget.class.getName() + "\t"))
                .findFirst().orElseThrow().split("\t", 2)[0];
        for (int index = 0; index < 2; index++) {
            byte[] bytes = session.request(Operation.JVMTI_CLASS_BYTES, AttachTarget.class.getName());
            assertEquals(AttachTarget.class.getName().replace('.', '/'), new ClassReader(bytes).getClassName());
        }
        String heap = session.requestText(Operation.JVMTI_HEAP_STATS, "java.lang.String\n100000");
        assertTrue(heap.contains("class=java.lang.String\n"), heap);
        assertTrue(metric(heap, "instances") > 0, heap);
        assertTrue(metric(heap, "shallow_bytes") > 0, heap);
        assertTrue(metric(heap, "class_object_shallow_bytes") > 0, heap);
        assertThrows(IOException.class, () -> session.requestText(Operation.JVMTI_HEAP_STATS, "java.lang.String\n0"));
        String methodPatch = classId + "\nruntimeValue\n()Ljava/lang/String;\n{ return \"jvmti-native\"; }";
        assertTrue(session.requestText(Operation.PATCH_METHOD, methodPatch).contains("Patched"));
        String probe = "public class NativePatchProbe { public static void execute(java.io.PrintStream out) { out.print(dev.whitedev.jpi.integration.AttachTarget.runtimeValue()); } }";
        assertEquals("jvmti-native", session.requestText(Operation.EXECUTE, probe));
        assertTrue(session.requestText(Operation.ROLLBACK_CLASS, classId).contains("Restored"));
        assertEquals("before", session.requestText(Operation.EXECUTE, probe));
        assertThrows(IOException.class, () -> session.request(Operation.JVMTI_CLASS_BYTES, "[Ljava.lang.String;"));
        assertEquals("pong", session.requestText(Operation.PING, ""));
    }

    private static long metric(String text, String name) {
        return java.util.Arrays.stream(text.split("\n"))
                .filter(line -> line.startsWith(name + "="))
                .mapToLong(line -> Long.parseLong(line.substring(name.length() + 1)))
                .findFirst().orElseThrow();
    }

    private void assertRawMemory(InspectorSession session) throws Exception {
        String source = "public class RawMemoryProbe { public static void execute(java.io.PrintStream out) throws Exception { out.print(Long.toHexString(dev.whitedev.jpi.integration.RawMemoryFixture.address())); } }";
        String address = session.requestText(Operation.EXECUTE, source);
        assertTrue(address.matches("[0-9a-f]+"), address);
        String maps = session.requestText(Operation.MEMORY_REGIONS, "");
        assertFalse(maps.isEmpty());
        assertArrayEquals(new byte[16], session.request(Operation.MEMORY_READ, address + "\n16"));
        String before = java.util.Base64.getEncoder().encodeToString(new byte[16]);
        byte[] changed = new byte[16]; java.util.Arrays.fill(changed, (byte) 0x5a);
        String after = java.util.Base64.getEncoder().encodeToString(changed);
        assertTrue(session.requestText(Operation.MEMORY_PATCH, address + "\n" + before + "\n" + after).contains("Patched 16"));
        assertArrayEquals(changed, session.request(Operation.MEMORY_READ, address + "\n16"));
        assertThrows(IOException.class, () -> session.request(Operation.MEMORY_PATCH, address + "\n" + before + "\n" + after));
        assertArrayEquals(changed, session.request(Operation.MEMORY_READ, address + "\n16"));
        session.request(Operation.MEMORY_PATCH, address + "\n" + after + "\n" + before);
        assertArrayEquals(new byte[16], session.request(Operation.MEMORY_READ, address + "\n16"));
        for (String invalid : List.of("0\n16", "1\n16", "ffffffffffffffff\n16", address + "\n0", address + "\n1048577", "xyz\n16"))
            assertThrows(IOException.class, () -> session.request(Operation.MEMORY_READ, invalid), invalid);
        assertThrows(IOException.class, () -> session.request(Operation.MEMORY_PATCH, address + "\nAA==\nAAAA"));
        String executable = maps.lines().filter(line -> line.endsWith("r-x")).findFirst().orElseThrow();
        String codeAddress = executable.split("\t")[0];
        byte[] code = session.request(Operation.MEMORY_READ, codeAddress + "\n1");
        String same = java.util.Base64.getEncoder().encodeToString(code);
        assertThrows(IOException.class, () -> session.request(Operation.MEMORY_PATCH, codeAddress + "\n" + same + "\n" + same));
        assertEquals("pong", session.requestText(Operation.PING, ""));
    }

    private Process start(List<String> options) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
        command.addAll(options);
        command.add("-cp");
        command.add(System.getProperty("jpi.test.classes", "target/test-classes"));
        command.add(AttachTarget.class.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            String greeting = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return process.inputReader().readLine(); }
                catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
            }).get(15, TimeUnit.SECONDS);
            if (!Long.toString(process.pid()).equals(greeting)) throw new IOException("JVMTI fixture did not become ready: " + greeting);
            return process;
        } catch (Exception error) {
            process.destroyForcibly();
            throw new IOException("Could not start JVMTI fixture", error);
        }
    }

    private static String unquote(String argument) {
        return argument.startsWith("\"") ? argument.substring(1, argument.length() - 1) : argument;
    }

    private static void stop(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }
}

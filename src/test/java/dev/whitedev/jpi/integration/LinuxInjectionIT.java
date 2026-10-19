package dev.whitedev.jpi.integration;

import dev.whitedev.jpi.attach.*;
import dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact;
import dev.whitedev.jpi.protocol.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Assumptions;
import org.objectweb.asm.ClassReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class LinuxInjectionIT {
    private final ImprovedAttachService service = new ImprovedAttachService(new File(System.getProperty("jpi.agent.jar", "target/jpi.jar")));

    private NativeAgentArtifact artifact() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").equals("Linux") && ProcessInjector.isSupported());
        boolean bundled = getClass().getResource("/native/linux-x86_64/libprocess_injector.so") != null;
        if (Boolean.getBoolean("jpi.native.required")) assertTrue(bundled, "Linux CI requires the ptrace backend");
        else Assumptions.assumeTrue(bundled);
        return NativeAgentArtifact.extractBundled("jvmti_agent");
    }

    @Test void injectsIntoDedicatedJvmAndKeepsItResponsive() throws Exception {
        NativeAgentArtifact bundled = artifact();
        Path directory = Files.createTempDirectory("jpi-\uD83D\uDD0E-");
        Path copy = directory.resolve("agent with spaces.so");
        try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
            Files.copy(bundled.library(), copy);
            NativeAgentArtifact agent = NativeAgentArtifact.verify(copy, bundled.sha256());
            Process target = start(unquote(prepared.jvmArgument()));
            try (InspectorSession session = prepared.await(Long.toString(target.pid()), "Linux injection fixture")) {
                assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("not-bound"));
                new ProcessInjector().inject(Long.toString(target.pid()), agent);
                String status = session.requestText(Operation.JVMTI_BIND, agent.bindingOptions());
                assertTrue(status.contains("backend=JVMTI"), status);
                assertTrue(status.contains("phase=live"), status);
                assertTrue(status.contains("mode=attach"), status);
                assertTrue(status.contains("can_redefine_classes=1"), status);
                assertTrue(status.contains("can_retransform_classes=1"), status);
                assertTrue(session.requestText(Operation.JVMTI_CLASSES, "").contains(AttachTarget.class.getName()));
                byte[] bytes = session.request(Operation.JVMTI_CLASS_BYTES, AttachTarget.class.getName());
                assertEquals(AttachTarget.class.getName().replace('.', '/'), new ClassReader(bytes).getClassName());
                new ProcessInjector().inject(Long.toString(target.pid()), agent);
                assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("phase=live"));
                assertNativePatchAndRollback(session);
                assertTrue(service.enableNativeBackendWithInjection(session, agent).contains("backend=JVMTI"));
                for (int i = 0; i < 10; ++i) assertEquals("pong", session.requestText(Operation.PING, ""));
                assertTrue(target.isAlive());
                assertDetached(target.pid());
            } finally { stop(target); }
        } finally { Files.deleteIfExists(copy); Files.deleteIfExists(directory); }
    }

    @Test void initializesNativeBackendBeforeAnyJavaAgentIsLoaded() throws Exception {
        NativeAgentArtifact agent = artifact();
        Process target = start();
        try {
            new ProcessInjector().inject(Long.toString(target.pid()), agent);
            assertDetached(target.pid());
            try (InspectorSession session = service.attach(new JvmDescriptor(Long.toString(target.pid()), "native-first fixture"))) {
                assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("not-bound"));
                String status = session.requestText(Operation.JVMTI_BIND, agent.bindingOptions());
                assertTrue(status.contains("mode=attach"), status);
                assertTrue(status.contains("phase=live"), status);
                assertTrue(session.requestText(Operation.JVMTI_CLASSES, "").contains(AttachTarget.class.getName()));
                assertTrue(session.request(Operation.JVMTI_CLASS_BYTES, AttachTarget.class.getName()).length > 0);
                assertNativePatchAndRollback(session);
                assertEquals("pong", session.requestText(Operation.PING, ""));
            }
            assertTrue(target.isAlive());
        } finally { stop(target); }
    }

    @Test void dlopenFailureRestoresTargetAndAllowsRetry() throws Exception {
        NativeAgentArtifact agent = artifact();
        Path broken = Files.createTempFile("jpi-invalid-elf-", ".so");
        try {
            byte[] contents = "not an ELF shared library".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(broken, contents);
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(contents));
            NativeAgentArtifact invalid = NativeAgentArtifact.verify(broken, digest);
            try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
                Process target = start(unquote(prepared.jvmArgument()));
                try (InspectorSession session = prepared.await(Long.toString(target.pid()), "dlopen failure fixture")) {
                    IOException error = assertThrows(IOException.class, () -> new ProcessInjector().inject(Long.toString(target.pid()), invalid));
                    assertTrue(error.getMessage().contains("(-2)"), error.toString());
                    assertDetached(target.pid());
                    assertEquals("pong", session.requestText(Operation.PING, ""));
                    new ProcessInjector().inject(Long.toString(target.pid()), agent);
                    assertTrue(session.requestText(Operation.JVMTI_BIND, agent.bindingOptions()).contains("phase=live"));
                    assertDetached(target.pid());
                } finally { stop(target); }
            }
        } finally { Files.deleteIfExists(broken); }
    }

    private void assertNativePatchAndRollback(InspectorSession session) throws Exception {
        String inventory = session.requestText(Operation.JVMTI_CLASSES, "");
        String id = inventory.lines().filter(line -> line.contains("\t" + AttachTarget.class.getName() + "\t"))
                .findFirst().orElseThrow().split("\t", 2)[0];
        String probe = "public class InjectionPatchProbe { public static void execute(java.io.PrintStream out) { out.print(dev.whitedev.jpi.integration.AttachTarget.runtimeValue()); } }";
        assertEquals("before", session.requestText(Operation.EXECUTE, probe));
        session.requestText(Operation.PATCH_METHOD, id + "\nruntimeValue\n()Ljava/lang/String;\n{ return \"linux-injected\"; }");
        assertEquals("linux-injected", session.requestText(Operation.EXECUTE, probe));
        session.requestText(Operation.ROLLBACK_CLASS, id);
        assertEquals("before", session.requestText(Operation.EXECUTE, probe));
    }

    private NativeAgentArtifact lifecycleFixture(String name) throws Exception {
        artifact();
        Path path = Path.of("target", "native-test-fixtures", "libjpi_test_" + name + ".so").toAbsolutePath();
        if (Boolean.getBoolean("jpi.native.required")) assertTrue(Files.isRegularFile(path), "Build with -DJPI_BUILD_TEST_FIXTURES=ON");
        else Assumptions.assumeTrue(Files.isRegularFile(path));
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        return NativeAgentArtifact.verify(path, digest);
    }

    @Test void initializationFailureIsNotReportedAsSuccessfulLibraryLoading() throws Exception {
        NativeAgentArtifact fixture = lifecycleFixture("entry_error");
        try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
            Process target = start(unquote(prepared.jvmArgument()));
            try (InspectorSession session = prepared.await(Long.toString(target.pid()), "initialization failure fixture")) {
                IOException error = assertThrows(IOException.class, () -> new ProcessInjector().inject(Long.toString(target.pid()), fixture));
                assertTrue(error.getMessage().contains("(-17)"), error.toString());
                assertTrue(Files.readString(Path.of("/proc", Long.toString(target.pid()), "maps")).contains(fixture.library().toString()));
                assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("not-bound"));
                assertDetached(target.pid());
                assertEquals("pong", session.requestText(Operation.PING, ""));
            } finally { stop(target); }
        }
    }

    @Test void ownershipAwareEntryCanReleaseItsUnneededLoaderReference() throws Exception {
        NativeAgentArtifact fixture = lifecycleFixture("entry_release");
        try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
            Process target = start(unquote(prepared.jvmArgument()));
            try (InspectorSession session = prepared.await(Long.toString(target.pid()), "loader reference fixture")) {
                new ProcessInjector().inject(Long.toString(target.pid()), fixture);
                assertFalse(Files.readString(Path.of("/proc", Long.toString(target.pid()), "maps")).contains(fixture.library().toString()));
                assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("not-bound"));
                assertDetached(target.pid());
                assertEquals("pong", session.requestText(Operation.PING, ""));
            } finally { stop(target); }
        }
    }

    @Test void timedOutWorkerCanFinishWithoutItsCodeBeingFreed() throws Exception {
        NativeAgentArtifact fixture = lifecycleFixture("entry_slow");
        try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
            Process target = start(unquote(prepared.jvmArgument()));
            try (InspectorSession session = prepared.await(Long.toString(target.pid()), "slow worker fixture")) {
                IOException error = assertThrows(IndeterminateInjectionException.class,
                        () -> service.enableNativeBackendWithInjection(session, fixture));
                assertEquals(0, error.getSuppressed().length);
                assertTrue(error.getMessage().contains("Worker memory retained"), error.toString());
                assertDetached(target.pid());
                assertEquals("pong", session.requestText(Operation.PING, ""));
                assertTrue(hasSlowWorker(target.pid()), "Test worker should still be running after timeout");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (hasSlowWorker(target.pid()) && System.nanoTime() < deadline) Thread.sleep(50);
                assertFalse(hasSlowWorker(target.pid()), "Detached worker should exit naturally");
                assertTrue(target.isAlive());
                assertEquals("pong", session.requestText(Operation.PING, ""));
                assertDetached(target.pid());
            } finally { stop(target); }
        }
    }

    private static boolean hasSlowWorker(long pid) throws IOException {
        try (var tasks = Files.list(Path.of("/proc", Long.toString(pid), "task"))) {
            for (Path task : tasks.toList()) {
                try { if (Files.readString(task.resolve("comm")).trim().equals("jpi-audit-slow")) return true; }
                catch (java.nio.file.NoSuchFileException exited) { }
            }
        }
        return false;
    }

    @Test void missingAgentEntryRestoresTargetAndAllowsAttachFallback() throws Exception {
        artifact();
        NativeAgentArtifact wrongAgent = NativeAgentArtifact.extractBundled("process_injector");
        try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
            Process target = start(unquote(prepared.jvmArgument()));
            try (InspectorSession session = prepared.await(Long.toString(target.pid()), "Linux failure fixture")) {
                IOException error = assertThrows(IOException.class, () -> new ProcessInjector().inject(Long.toString(target.pid()), wrongAgent));
                assertTrue(error.getMessage().contains("-3"), error.toString());
                assertDetached(target.pid());
                assertEquals("pong", session.requestText(Operation.PING, ""));
                assertTrue(service.enableNativeBackend(session, NativeAgentArtifact.extractBundled()).contains("backend=JVMTI"));
                assertEquals("pong", session.requestText(Operation.PING, ""));
            } finally { stop(target); }
        }
    }

    @Test void serviceDoesNotInjectWhenNonJvmPolicyCannotBeEstablished() throws Exception {
        NativeAgentArtifact agent = artifact();
        Process target = new ProcessBuilder("sleep", "60").start();
        try {
            IOException failure = assertThrows(IOException.class, () -> service.loadNativeAgentWithInjection(Long.toString(target.pid()), agent));
            assertEquals(0, failure.getSuppressed().length);
            assertTrue(failure.getMessage().contains("No OS injection fallback"), failure.toString());
            assertTrue(target.isAlive());
        } finally { stop(target); }
    }

    @Test void unsafeAgentPermissionsRejectInjectionAndUseAttachFallback() throws Exception {
        NativeAgentArtifact agent = artifact();
        Path copy = Files.createTempFile("jpi-injection-permissions-", ".so");
        try {
            Files.copy(agent.library(), copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(copy, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));
            NativeAgentArtifact writable = NativeAgentArtifact.verify(copy, agent.sha256());
            try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
                Process target = start(unquote(prepared.jvmArgument()));
                try (InspectorSession session = prepared.await(Long.toString(target.pid()), "Linux fallback fixture")) {
                    IOException failure = assertThrows(IOException.class, () -> new ProcessInjector().inject(Long.toString(target.pid()), writable));
                    assertTrue(failure.getMessage().contains("group/world writable"), failure.toString());
                    assertEquals("pong", session.requestText(Operation.PING, ""));
                    assertTrue(service.enableNativeBackendWithInjection(session, writable).contains("backend=JVMTI"));
                    assertEquals("pong", session.requestText(Operation.PING, ""));
                } finally { stop(target); }
            }
        } finally { Files.deleteIfExists(copy); }
    }

    private Process start(String... earlyAgent) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:-DisableAttachMechanism", "-XX:+EnableDynamicAgentLoading"));
        command.addAll(List.of(earlyAgent));
        command.addAll(List.of("-cp", System.getProperty("jpi.test.classes", "target/test-classes"), AttachTarget.class.getName()));
        Process target = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            String ready = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return target.inputReader().readLine(); }
                catch (IOException error) { throw new java.util.concurrent.CompletionException(error); }
            }).get(15, TimeUnit.SECONDS);
            assertEquals(Long.toString(target.pid()), ready, "Fixture must reach main before native loading");
            return target;
        } catch (Throwable error) { stop(target); throw error; }
    }

    private static void assertDetached(long pid) throws IOException {
        try (var tasks = Files.list(Path.of("/proc", Long.toString(pid), "task"))) {
            for (Path task : tasks.toList()) {
                String status;
                try { status = Files.readString(task.resolve("status")); }
                catch (java.nio.file.NoSuchFileException exited) { continue; }
                assertTrue(status.lines().anyMatch(line -> line.matches("TracerPid:\\s+0")), status);
            }
        }
    }

    private static String unquote(String argument) { return argument.startsWith("\"") ? argument.substring(1, argument.length() - 1) : argument; }
    private static void stop(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
    }
}

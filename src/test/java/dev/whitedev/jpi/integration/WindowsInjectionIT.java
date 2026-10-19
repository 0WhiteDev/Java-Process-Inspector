package dev.whitedev.jpi.integration;

import dev.whitedev.jpi.attach.*;
import dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact;
import dev.whitedev.jpi.protocol.Operation;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.objectweb.asm.ClassReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class WindowsInjectionIT {
    private final ImprovedAttachService service = new ImprovedAttachService(new File(System.getProperty("jpi.agent.jar", "target/jpi.jar")));

    private NativeAgentArtifact artifact() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows") && ProcessInjector.isSupported());
        boolean present = getClass().getResource("/native/windows-x86_64/process_injector.dll") != null;
        if (Boolean.getBoolean("jpi.native.required")) assertTrue(present, "Windows CI requires its injector DLL");
        else Assumptions.assumeTrue(present);
        return NativeAgentArtifact.extractBundled();
    }

    @Test void directlyInjectsInitializesAndBindsWithoutAttachFallback() throws Exception {
        NativeAgentArtifact bundled = artifact();
        Path directory = Files.createTempDirectory("jpi-injection-");
        Path copy = directory.resolve("agent with spaces.dll");
        Files.copy(bundled.library(), copy);
        NativeAgentArtifact agent = NativeAgentArtifact.verify(copy, bundled.sha256());
        try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
            Process target = start(prepared.jvmArgument());
            try (InspectorSession session = prepared.await(Long.toString(target.pid()), "Windows injection fixture")) {
                assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("not-bound"));
                new ProcessInjector().inject(Long.toString(target.pid()), agent);
                String status = session.requestText(Operation.JVMTI_BIND, agent.bindingOptions());
                assertTrue(status.contains("backend=JVMTI"), status);
                assertTrue(status.contains("phase=live"), status);
                assertTrue(status.contains("can_redefine_classes=1"), status);
                assertTrue(session.requestText(Operation.JVMTI_CLASSES, "").contains(AttachTarget.class.getName()));
                byte[] bytes = session.request(Operation.JVMTI_CLASS_BYTES, AttachTarget.class.getName());
                assertEquals(AttachTarget.class.getName().replace('.', '/'), new ClassReader(bytes).getClassName());
                new ProcessInjector().inject(Long.toString(target.pid()), agent);
                assertTrue(service.enableNativeBackendWithInjection(session, agent).contains("backend=JVMTI"));
                assertFalse(session.requestText(Operation.MEMORY_REGIONS, "").isEmpty());
                for (int i = 0; i < 10; i++) assertEquals("pong", session.requestText(Operation.PING, ""));
                assertTrue(target.isAlive());
            } finally { stop(target); }
        } finally { JvmFixtureCleanup.delete(copy, directory); }
    }

    @Test void serviceLoadsAndInitializesTheWindowsBackend() throws Exception {
        NativeAgentArtifact agent = artifact();
        try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
            Process target = start(prepared.jvmArgument());
            try (InspectorSession session = prepared.await(Long.toString(target.pid()), "Windows service fixture")) {
                assertTrue(service.enableNativeBackendWithInjection(session, agent).contains("backend=JVMTI"));
                assertEquals("pong", session.requestText(Operation.PING, ""));
            } finally { stop(target); }
        }
    }

    @Test void unicodeDllPathLoadsAndInitializesWithoutLossyModuleNames() throws Exception {
        NativeAgentArtifact bundled = artifact();
        Path directory = Files.createTempDirectory("jpi-\uD83D\uDD0E-");
        Path copy = directory.resolve("agent with spaces.dll");
        Files.copy(bundled.library(), copy);
        try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
            Process target = start(prepared.jvmArgument());
            try (InspectorSession session = prepared.await(Long.toString(target.pid()), "Windows Unicode fixture")) {
                NativeAgentArtifact agent = NativeAgentArtifact.verify(copy, bundled.sha256());
                new ProcessInjector().inject(Long.toString(target.pid()), agent);
                new ProcessInjector().inject(Long.toString(target.pid()), agent);
                assertEquals("pong", session.requestText(Operation.PING, ""));
            } finally { stop(target); }
        } finally { JvmFixtureCleanup.delete(copy, directory); }
    }

    @Test void missingInitializationEntryLeavesTargetResponsive() throws Exception {
        NativeAgentArtifact agent = artifact();
        NativeAgentArtifact wrong = NativeAgentArtifact.extractBundled("process_injector");
        try (AttachService.PreparedAgentSession prepared = service.prepareEarlyAgent()) {
            Process target = start(prepared.jvmArgument());
            try (InspectorSession session = prepared.await(Long.toString(target.pid()), "Windows failure fixture")) {
                IOException failure = assertThrows(IOException.class, () -> new ProcessInjector().inject(Long.toString(target.pid()), wrong));
                assertTrue(failure.getMessage().contains("JPI_InitializeInjectedAgentThread"), failure.toString());
                assertEquals("pong", session.requestText(Operation.PING, ""));
                assertTrue(service.enableNativeBackendWithInjection(session, agent).contains("backend=JVMTI"));
                assertEquals("pong", session.requestText(Operation.PING, ""));
            } finally { stop(target); }
        }
    }

    private Process start(String earlyAgent) throws IOException {
        if (earlyAgent.startsWith("\"")) earlyAgent = earlyAgent.substring(1, earlyAgent.length() - 1);
        return new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-XX:-DisableAttachMechanism", "-XX:+EnableDynamicAgentLoading",
                earlyAgent, "-cp", System.getProperty("jpi.test.classes", "target/test-classes"), AttachTarget.class.getName()))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static void stop(Process target) throws Exception {
        JvmFixtureCleanup.stop(target);
    }
}

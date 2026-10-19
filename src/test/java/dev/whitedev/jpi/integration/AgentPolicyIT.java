package dev.whitedev.jpi.integration;

import com.sun.tools.attach.VirtualMachine;
import dev.whitedev.jpi.attach.*;
import dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact;
import dev.whitedev.jpi.protocol.Operation;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class AgentPolicyIT {
    private final ImprovedAttachService service = new ImprovedAttachService(new File(System.getProperty("jpi.agent.jar", "target/jpi.jar")));
    private static final String TOKEN = "owner-policy-integration-token-123456";

    @Test void unpreparedAttachDisabledJvmRefusesLoadingAndNeverFallsBackToInjection() throws Exception {
        verifyUnprepared(List.of("-XX:+DisableAttachMechanism", "-XX:+EnableDynamicAgentLoading"), true, true);
    }

    @Test void unpreparedDynamicLoadingDisabledJvmStillAllowsDiagnosticsButRefusesNativeLoading() throws Exception {
        verifyUnprepared(List.of("-XX:-DisableAttachMechanism", "-XX:-EnableDynamicAgentLoading"), false, false);
    }

    @Test void unpreparedJvmWithBothRestrictionsRemainsAliveAndUninstrumented() throws Exception {
        verifyUnprepared(List.of("-XX:+DisableAttachMechanism", "-XX:-EnableDynamicAgentLoading"), true, false);
    }

    private void verifyUnprepared(List<String> flags, boolean attachDisabled, boolean dynamicAllowed) throws Exception {
        NativeAgentArtifact artifact = artifact();
        int port = port();
        try (Fixture target = start(flags)) {
            assertEquals(attachDisabled + "," + dynamicAllowed, target.query("policy"));
            assertEquals("no-jpi-classes", target.query("native"));
            JvmAgentPolicy policy = JvmAgentPolicy.inspect(target.pid());
            assertEquals(attachDisabled ? AttachRestriction.DISABLED : AttachRestriction.EXPLICITLY_ENABLED, policy.attachRestriction());
            assertEquals(dynamicAllowed ? JvmAgentPolicy.DynamicLoading.EXPLICITLY_ENABLED : JvmAgentPolicy.DynamicLoading.DISABLED, policy.dynamicLoading());
            String expectedFlag = attachDisabled ? "-XX:+DisableAttachMechanism" : "-XX:-EnableDynamicAgentLoading";
            AgentLoadingRestrictedException direct = assertThrows(AgentLoadingRestrictedException.class,
                    () -> new ProcessInjector().inject(target.pid(), artifact));
            assertEquals(expectedFlag, direct.vmOption());
            AgentLoadingRestrictedException managed = assertThrows(AgentLoadingRestrictedException.class,
                    () -> service.loadNativeAgentWithInjection(target.pid(), artifact));
            assertEquals(expectedFlag, managed.vmOption());
            assertEquals(0, managed.getSuppressed().length, "A policy refusal must not retry another loader");
            assertThrows(IOException.class, () -> service.loadNativeAgent(target.pid(), artifact));
            assertThrows(IOException.class, () -> service.loadNativeCommunicationAgent(target.pid(), artifact,
                    service.preparePreloadedAgent(port, TOKEN, 30)));
            assertThrows(IOException.class, () -> service.connectNativeAgent(port, TOKEN, target.descriptor()));
            assertUnchanged(target, artifact);
            if (attachDisabled) {
                assertThrows(Exception.class, () -> {
                    VirtualMachine unexpected = VirtualMachine.attach(target.pid());
                    try { fail("HotSpot unexpectedly accepted Attach with DisableAttachMechanism"); }
                    finally { unexpected.detach(); }
                });
            } else {
                VirtualMachine vm = VirtualMachine.attach(target.pid());
                try {
                    assertFalse(vm.getSystemProperties().isEmpty());
                    Exception denied = assertThrows(Exception.class, () -> vm.loadAgentPath(artifact.library().toString(), ""));
                    String message = String.valueOf(denied.getMessage());
                    assertTrue(message.contains("EnableDynamicAgentLoading") || message.toLowerCase(Locale.ROOT).contains("dynamic agent loading"), message);
                } finally { vm.detach(); }
            }
            assertUnchanged(target, artifact);
        }
    }

    @Test void opaqueArgumentsStayUnknownAndJvmEnforcementStillRefusesTheSupportedLoader() throws Exception {
        NativeAgentArtifact artifact = artifact();
        Path options = optionsFile();
        try (Fixture target = start(List.of("@" + options))) {
            assertEquals("true,false", target.query("policy"));
            JvmAgentPolicy observed = JvmAgentPolicy.inspect(target.pid());
            assertEquals(AttachRestriction.UNKNOWN, observed.attachRestriction());
            assertEquals(JvmAgentPolicy.DynamicLoading.UNKNOWN, observed.dynamicLoading());
            IOException managed = assertThrows(IOException.class, () -> service.loadNativeAgentWithInjection(target.pid(), artifact));
            assertTrue(managed.getMessage().contains("No OS injection fallback"), managed.toString());
            assertThrows(IOException.class, () -> service.loadNativeCommunicationAgent(target.pid(), artifact,
                    service.preparePreloadedAgent(port(), TOKEN, 30)));
            assertUnchanged(target, artifact);
        } finally { Files.deleteIfExists(options); }
    }

    @Test void preparedJavaSessionReportsEffectiveHiddenRestrictionsBeforeNativeLoading() throws Exception {
        NativeAgentArtifact artifact = artifact();
        Path options = optionsFile();
        int port = port();
        String argument = service.preloadedAgentArgument(service.preparePreloadedAgent(port, TOKEN, 30));
        try (Fixture target = start(List.of("@" + options, argument));
             InspectorSession session = service.connectPreloadedAgent(port, TOKEN, target.descriptor())) {
            assertEquals(AttachRestriction.UNKNOWN, JvmAgentPolicy.inspect(target.pid()).attachRestriction());
            String info = session.requestText(Operation.SESSION_INFO, "");
            assertTrue(info.contains("attach_disabled=true\n"), info);
            assertTrue(info.contains("dynamic_agent_loading=false\n"), info);
            assertThrows(AgentLoadingRestrictedException.class, () -> service.enableNativeBackendWithInjection(session, artifact));
            assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("not-bound"));
            assertEquals("pong", session.requestText(Operation.PING, ""));
            assertEquals("not-bound", target.query("native"));
            assertNoNativeMapping(target, artifact);
        } finally { Files.deleteIfExists(options); }
    }

    @Test void visibleEnablingFlagsAreNotTreatedAsPermissionWhenEnvironmentOverridesThem() throws Exception {
        NativeAgentArtifact artifact = artifact();
        try (Fixture target = start(List.of("-XX:-DisableAttachMechanism", "-XX:+EnableDynamicAgentLoading"),
                "-XX:+DisableAttachMechanism -XX:-EnableDynamicAgentLoading")) {
            JvmAgentPolicy visible = JvmAgentPolicy.inspect(target.pid());
            assertEquals(AttachRestriction.EXPLICITLY_ENABLED, visible.attachRestriction());
            assertEquals(JvmAgentPolicy.DynamicLoading.EXPLICITLY_ENABLED, visible.dynamicLoading());
            assertEquals("true,false", target.query("policy"));
            IOException refused = assertThrows(IOException.class, () -> service.loadNativeAgentWithInjection(target.pid(), artifact));
            assertFalse(refused instanceof AgentLoadingRestrictedException, "The JVM provider, rather than visible metadata, must enforce this case");
            assertTrue(refused.getMessage().contains("No OS injection fallback"), refused.toString());
            assertUnchanged(target, artifact);
        }
    }

    private void assertUnchanged(Fixture target, NativeAgentArtifact artifact) throws Exception {
        assertEquals("pong", target.query("ping"));
        assertEquals("no-jpi-classes", target.query("native"));
        assertTrue(target.process.isAlive());
        assertNoNativeMapping(target, artifact);
    }

    private static void assertNoNativeMapping(Fixture target, NativeAgentArtifact artifact) throws IOException {
        if (!System.getProperty("os.name").equals("Linux")) return;
        Path proc = Path.of("/proc", target.pid());
        assertFalse(Files.readString(proc.resolve("maps")).contains(artifact.library().toString()));
        try (var threads = Files.list(proc.resolve("task"))) {
            for (Path thread : threads.toList()) {
                try {
                    String tracer = Files.readString(thread.resolve("status")).lines()
                            .filter(line -> line.startsWith("TracerPid:")).findFirst().orElseThrow();
                    assertEquals("0", tracer.substring(tracer.indexOf(':') + 1).trim());
                } catch (NoSuchFileException exited) { }
            }
        }
    }

    private static Path optionsFile() throws IOException {
        Path path = Files.createTempFile("jpi policy options ", ".txt");
        Files.writeString(path, "-XX:+DisableAttachMechanism\n-XX:-EnableDynamicAgentLoading\n", StandardCharsets.UTF_8);
        return path;
    }
    private NativeAgentArtifact artifact() throws Exception {
        Assumptions.assumeTrue(ProcessInjector.isSupported());
        boolean present = getClass().getResource(NativeAgentArtifact.resourcePath()) != null;
        if (Boolean.getBoolean("jpi.native.required")) assertTrue(present); else Assumptions.assumeTrue(present);
        return NativeAgentArtifact.extractBundled();
    }
    private static int port() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
    private Fixture start(List<String> flags) throws Exception {
        return start(flags, null);
    }
    private Fixture start(List<String> flags, String environmentOptions) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path log = Files.createTempFile("jpi-agent-policy-", ".log");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-Xcheck:jni"));
        command.addAll(flags);
        command.addAll(List.of("-cp", System.getProperty("jpi.test.classes", "target/test-classes"), AgentPolicyTarget.class.getName()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(log.toFile());
        if (environmentOptions != null) builder.environment().put("_JAVA_OPTIONS", environmentOptions);
        Process process = builder.start();
        Fixture fixture = new Fixture(process, log);
        try { assertEquals("READY\t" + process.pid(), fixture.line("READY\t")); return fixture; }
        catch (Throwable error) { fixture.close(); throw error; }
    }

    private static final class Fixture implements AutoCloseable {
        final Process process; final Path log; final BufferedReader input; final BufferedWriter output;
        Fixture(Process process, Path log) {
            this.process = process; this.log = log; this.input = process.inputReader(); this.output = process.outputWriter();
        }
        String pid() { return Long.toString(process.pid()); }
        JvmDescriptor descriptor() { return new JvmDescriptor(pid(), "policy-owned JVM"); }
        String query(String command) throws Exception {
            output.write(command); output.newLine(); output.flush();
            return line("ANSWER\t").substring(7);
        }
        String line(String prefix) throws Exception {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    String line;
                    while ((line = input.readLine()) != null) if (line.startsWith(prefix)) return line;
                    throw new EOFException("Policy target ended: " + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
                } catch (IOException error) { throw new CompletionException(error); }
            }).get(20, TimeUnit.SECONDS);
        }
        @Override public void close() throws Exception {
            try { output.write("exit"); output.newLine(); output.flush(); } catch (IOException ignored) { }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
            input.close(); output.close();
            String diagnostic = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            assertFalse(diagnostic.contains("WARNING in native method") || diagnostic.contains("FATAL ERROR in native method"), diagnostic);
            Files.deleteIfExists(log);
        }
    }
}

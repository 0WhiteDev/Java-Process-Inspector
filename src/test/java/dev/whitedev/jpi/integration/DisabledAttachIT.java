package dev.whitedev.jpi.integration;

import dev.whitedev.jpi.attach.*;
import dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact;
import dev.whitedev.jpi.protocol.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Assumptions;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class DisabledAttachIT {
    private final AttachService service = new AttachService(new File(System.getProperty("jpi.agent.jar", "target/jpi.jar")));
    private static final String TOKEN = "owner-provisioned-test-token-12345";

    @Test void connectsToRunningRestrictedJvmRejectsWrongCredentialsAndReconnects() throws Exception {
        int port = port();
        var prepared = service.preparePreloadedAgent(port, TOKEN, 30);
        Process target = start(List.of(service.preloadedAgentArgument(prepared)));
        try {
            JvmDescriptor descriptor = descriptor(target);
            assertThrows(IOException.class, () -> service.connectPreloadedAgent(port, "wrong-credentials-12345", descriptor));
            assertThrows(IOException.class, () -> service.connectPreloadedAgent(port, TOKEN, new JvmDescriptor("1", "wrong JVM")));
            try (InspectorSession session = service.connectPreloadedAgent(port, TOKEN, descriptor)) {
                assertFalse(session.isTunneled());
                assertTrue(session.requestText(Operation.SESSION_INFO, "").contains("attach_disabled=true"));
                assertTrue(session.requestText(Operation.CLASSES, "").contains(AttachTarget.class.getName()));
                ExecutorService workers = Executors.newFixedThreadPool(4);
                try {
                    List<Future<String>> results = new ArrayList<>();
                    for (int i = 0; i < 16; i++) results.add(workers.submit(() -> session.requestText(Operation.PING, "")));
                    for (Future<String> result : results) assertEquals("pong", result.get(10, TimeUnit.SECONDS));
                } finally { workers.shutdownNow(); }
            }
            try (java.net.Socket abrupt = new java.net.Socket("127.0.0.1", port)) {
                abrupt.setSoTimeout(10000);
                java.io.DataOutputStream output = new java.io.DataOutputStream(abrupt.getOutputStream());
                output.writeUTF(TOKEN); output.flush();
                dev.whitedev.jpi.protocol.WireProtocol.writeRequest(output, Operation.PING, new byte[0]);
                assertEquals("pong", dev.whitedev.jpi.protocol.WireProtocol.readResponse(
                        new java.io.DataInputStream(abrupt.getInputStream())).text());
            }
            try (InspectorSession reconnected = service.connectPreloadedAgent(port, TOKEN, descriptor)) {
                assertEquals("pong", reconnected.requestText(Operation.PING, ""));
                assertTrue(reconnected.requestText(Operation.CLASSES, "").contains(AttachTarget.class.getName()));
            }
            assertTrue(target.isAlive());
        } finally { stop(target); }
        try (ServerSocket released = new ServerSocket()) {
            released.setReuseAddress(true);
            released.bind(new java.net.InetSocketAddress("127.0.0.1", port));
        }
    }

    @Test void noPreloadedAgentProducesActionableFailureWithoutChangingTarget() throws Exception {
        Process target = start(List.of());
        try {
            IOException error = assertThrows(IOException.class, () -> service.attach(descriptor(target)));
            assertTrue(error.getMessage().contains("preloaded"), error.toString());
            assertTrue(target.isAlive());
        } finally { stop(target); }
    }

    @Test void idleListenerExpiresWithoutTerminatingTheJvm() throws Exception {
        int port = port();
        var prepared = service.preparePreloadedAgent(port, TOKEN, 10);
        Process target = start(List.of(service.preloadedAgentArgument(prepared)));
        try {
            try (InspectorSession session = service.connectPreloadedAgent(port, TOKEN, descriptor(target))) {
                assertEquals("pong", session.requestText(Operation.PING, ""));
            }
            Thread.sleep(11000);
            assertThrows(IOException.class, () -> service.connectPreloadedAgent(port, TOKEN, descriptor(target)));
            assertTrue(target.isAlive());
        } finally { stop(target); }
    }

    @Test void startupNativeAgentRemainsBoundAcrossRestrictedJvmConnections() throws Exception {
        String os = System.getProperty("os.name", "");
        Assumptions.assumeTrue(os.startsWith("Windows") || os.equals("Linux"), "No bundled native backend for this platform");
        boolean present = getClass().getResource(NativeAgentArtifact.resourcePath()) != null;
        if (Boolean.getBoolean("jpi.native.required")) assertTrue(present);
        else Assumptions.assumeTrue(present);
        NativeAgentArtifact nativeAgent = NativeAgentArtifact.extractBundled();
        int port = port();
        var prepared = service.preparePreloadedAgent(port, TOKEN, 30);
        Process target = start(List.of("-XX:-EnableDynamicAgentLoading", nativeAgent.startupArgument(), service.preloadedAgentArgument(prepared)));
        try {
            for (int i = 0; i < 2; i++) {
                try (InspectorSession session = service.connectPreloadedAgent(port, TOKEN, descriptor(target))) {
                    String status = session.requestText(Operation.JVMTI_BIND, nativeAgent.bindingOptions());
                    assertTrue(status.contains("mode=startup"), status);
                    assertTrue(status.contains("phase=live"), status);
                    assertTrue(session.requestText(Operation.JVMTI_CLASSES, "").contains(AttachTarget.class.getName()));
                    assertTrue(session.request(Operation.JVMTI_CLASS_BYTES, AttachTarget.class.getName()).length > 0);
                    if (i == 0) {
                        session.request(Operation.JVMTI_CAPTURE_START, "");
                        assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("continuous_class_capture=1"));
                    } else {
                        assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("continuous_class_capture=0"));
                        assertTrue(session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "").contains("events=0"));
                    }
                }
            }
        } finally { stop(target); }
    }

    private Process start(List<String> arguments) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-XX:+DisableAttachMechanism"));
        command.addAll(arguments);
        command.addAll(List.of("-cp", System.getProperty("jpi.test.classes", "target/test-classes"), AttachTarget.class.getName()));
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            String pid = CompletableFuture.supplyAsync(() -> {
                try { return process.inputReader().readLine(); }
                catch (IOException error) { throw new CompletionException(error); }
            }).get(15, TimeUnit.SECONDS);
            assertEquals(Long.toString(process.pid()), pid);
            return process;
        } catch (Throwable error) { stop(process); throw error; }
    }

    private static JvmDescriptor descriptor(Process target) { return new JvmDescriptor(Long.toString(target.pid()), "restricted fixture"); }
    private static int port() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
    private static void stop(Process target) throws Exception {
        target.destroy();
        if (!target.waitFor(5, TimeUnit.SECONDS)) { target.destroyForcibly(); assertTrue(target.waitFor(5, TimeUnit.SECONDS)); }
    }
}

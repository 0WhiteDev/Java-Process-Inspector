package dev.whitedev.jpi.integration;

import dev.whitedev.jpi.attach.*;
import dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.protocol.WireProtocol;
import org.junit.jupiter.api.*;
import org.objectweb.asm.ClassReader;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class NativeBootstrapIT {
    private static final String TOKEN = "native-owner-provisioned-test-token-12345";
    private final ImprovedAttachService service = new ImprovedAttachService(new File(System.getProperty("jpi.agent.jar", "target/jpi.jar")));

    @Test void restrictedNativeOnlyJvmUsesExistingApisForInspectionPatchRollbackAndCapture() throws Exception {
        verifyRestrictedNativeInspection(false);
    }

    @Test void ownerPreparedNativeCallbackProvidesFullInspectionWithoutAttachOrJavaAgent() throws Exception {
        verifyRestrictedNativeInspection(true);
    }

    @Test void explicitNativeInjectionBootstrapsAnUnpreparedRestrictedJvm() throws Exception {
        verifyRestrictedNativeInspection(false, true);
    }

    @Test void restrictedNativeOnlySessionDrainsItsArchiveOnDisconnectAndCanReconnect(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        int port = port();
        try (Fixture fixture = start(argument(port, 30))) {
            Path saved;
            try (InspectorSession session = connect(port, fixture)) {
                assertEquals("warmup", NativeCaptureIT.load(session, NativeCaptureIT.payload("WarmArchive", "warmup"), 0));
                session.request(Operation.JVMTI_CAPTURE_ARCHIVE_START, directory + "\n67108864");
                assertEquals("native-archive", NativeCaptureIT.load(session, NativeCaptureIT.payload("NativeArchived", "native-archive"), 51));
                saved = NativeCaptureIT.archiveDirectory(session.requestText(Operation.JVMTI_CAPTURE_EVENTS, ""));
            }
            try (InspectorSession session = connect(port, fixture)) {
                String status = session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "");
                assertTrue(status.startsWith("active=0\nevents=0\n"), status);
                assertTrue(status.contains("\narchive_active=0\n"), status);
                assertTrue(status.contains("\narchive_queued=0\n"), status);
                assertTrue(status.contains("\narchive_errors=0\n"), status);
                boolean found = false;
                for (String row : Files.readAllLines(saved.resolve("manifest.tsv"))) {
                    if (row.startsWith("sequence\t")) continue;
                    if (new ClassReader(Files.readAllBytes(saved.resolve(row.split("\t", -1)[4]))).getClassName().equals("NativeArchived")) found = true;
                }
                assertTrue(found, status);
                assertEquals("pong", session.requestText(Operation.PING, ""));
            }
        }
    }

    @Test void nativeAttachApiConnectsToAnUnpreparedRestrictedJvmWithoutStandardAttach() throws Exception {
        artifact();
        try (Fixture fixture = start(true, List.of());
             InspectorSession session = service.attachNative(descriptor(fixture))) {
            String info = session.requestText(Operation.SESSION_INFO, "");
            assertTrue(info.contains("attach_disabled=true\n"), info);
            assertTrue(info.contains("dynamic_agent_loading=false\n"), info);
            assertEquals("before", runtimeValue(session));
            assertTrue(session.requestText(Operation.JVMTI_CLASSES, "").contains(AttachTarget.class.getName()));
        }
    }

    @Test void nativeAttachApiCanCreateANewSessionAfterTheCallbackHasCleanedUp() throws Exception {
        artifact();
        try (Fixture fixture = start(true, List.of())) {
            try (InspectorSession session = service.attachNative(descriptor(fixture))) {
                assertEquals("scheduled", execute(session, "NativeReattachProbe",
                        "Thread probe = new Thread(() -> { try { "
                        + "java.lang.reflect.Method ready = Class.forName(\"dev.whitedev.jpi.agent.InspectorAgent\").getMethod(\"nativeCommunicationAvailable\"); "
                        + "for (int i=0;i<150;i++) { Thread.sleep(100); "
                        + "if(Boolean.TRUE.equals(ready.invoke(null))) { "
                        + "System.out.println(\"native-ready-for-reattach\"); System.out.flush(); return; } } "
                        + "} catch(Exception error) { System.out.println(error); } }); "
                        + "probe.setDaemon(true); probe.start(); out.print(\"scheduled\");"));
                session.request(Operation.JVMTI_CAPTURE_START, "");
            }
            assertEquals("native-ready-for-reattach", CompletableFuture.supplyAsync(() -> {
                try { return fixture.process.inputReader().readLine(); }
                catch (IOException error) { throw new CompletionException(error); }
            }).get(20, TimeUnit.SECONDS));
            try (InspectorSession session = service.attachNative(descriptor(fixture))) {
                assertEquals("before", runtimeValue(session));
                assertTrue(session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "").contains("active=0\nevents=0\n"));
            }
        }
    }

    private void verifyRestrictedNativeInspection(boolean callback) throws Exception {
        verifyRestrictedNativeInspection(callback, false);
    }

    private void verifyRestrictedNativeInspection(boolean callback, boolean injected) throws Exception {
        int port = port();
        NativeAgentArtifact artifact = artifact();
        try (AttachService.PreparedAgentSession early = callback ? service.prepareEarlyNativeAgent(artifact) : null;
             Fixture fixture = injected ? startInjected(port, artifact) : start(callback ? early.jvmArgument()
                     : service.nativePreloadedAgentArgument(artifact, service.preparePreloadedAgent(port, TOKEN, 30)));
             InspectorSession session = callback
                ? early.await(Long.toString(fixture.process.pid()), "owner-prepared native callback") : connect(port, fixture)) {
            String info = session.requestText(Operation.SESSION_INFO, "");
            assertTrue(info.contains("attach_disabled=true\n"), info);
            assertTrue(info.contains("dynamic_agent_loading=false\n"), info);
            assertTrue(info.contains("communication=native-bootstrap\n"), info);
            assertTrue(info.contains("runtime=JVMTI\n"), info);
            String flags = execute(session, "NativeFlags", "out.print(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());");
            assertFalse(flags.contains("-javaagent:"), flags);
            if (injected) assertFalse(flags.contains("-agentpath:"), flags);
            assertTrue(flags.contains("-XX:-EnableDynamicAgentLoading"), flags);
            assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("communication_bootstrap=started"));
            assertTrue(service.enableNativeBackendWithInjection(session, artifact).contains("backend=JVMTI"));
            String name = AttachTarget.class.getName();
            String classes = session.requestText(Operation.CLASSES, "");
            assertTrue(classes.contains("\t" + name + "\t"));
            assertTrue(session.requestText(Operation.JVMTI_CLASSES, "").contains(name));
            assertEquals(name.replace('.', '/'), new ClassReader(session.request(Operation.CLASS_BYTES, name)).getClassName());
            assertTrue(session.requestText(Operation.JVMTI_HEAP_STATS, "java.lang.String\n100000").contains("class=java.lang.String\n"));
            assertFalse(session.requestText(Operation.CLASSLOADER_SNAPSHOT, "").isEmpty());
            assertTrue(session.requestText(Operation.PATCH_METHOD, name + "\nruntimeValue\n()Ljava/lang/String;\n{ return \"native-communication\"; }").contains("Patched"));
            assertEquals("native-communication", runtimeValue(session));
            assertFalse(session.requestText(Operation.PATCH_HISTORY, name).isEmpty());
            assertTrue(session.requestText(Operation.ROLLBACK_CLASS, name).contains("Restored"));
            assertEquals("before", runtimeValue(session));
            assertThrows(IOException.class, () -> session.request(Operation.JVMTI_CLASS_BYTES, "[Ljava.lang.String;"));
            assertEquals("pong", session.requestText(Operation.PING, ""));

            String address = execute(session, "NativeAddress", "out.print(Long.toHexString(dev.whitedev.jpi.integration.RawMemoryFixture.address()));");
            assertArrayEquals(new byte[16], session.request(Operation.MEMORY_READ, address + "\n16"));
            String zero = Base64.getEncoder().encodeToString(new byte[16]);
            byte[] changed = new byte[16]; Arrays.fill(changed, (byte) 19);
            String bytes = Base64.getEncoder().encodeToString(changed);
            session.request(Operation.MEMORY_PATCH, address + "\n" + zero + "\n" + bytes);
            assertArrayEquals(changed, session.request(Operation.MEMORY_READ, address + "\n16"));
            assertThrows(IOException.class, () -> session.request(Operation.MEMORY_PATCH, address + "\n" + zero + "\n" + bytes));
            session.request(Operation.MEMORY_PATCH, address + "\n" + bytes + "\n" + zero);
            assertArrayEquals(new byte[16], session.request(Operation.MEMORY_READ, address + "\n16"));

            session.request(Operation.JVMTI_CAPTURE_START, "");
            assertEquals("captured", execute(session, "NativeBootstrapCaptured", "out.print(\"captured\");"));
            String encoded = Base64.getEncoder().encodeToString("NativeBootstrapCaptured".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String events = session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "");
            String sequence = events.lines().filter(line -> line.endsWith("\t" + encoded)).findFirst().orElseThrow().split("\t")[0];
            assertEquals("NativeBootstrapCaptured", new ClassReader(session.request(Operation.JVMTI_CAPTURE_BYTES, sequence)).getClassName());
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try {
                List<Future<String>> results = new ArrayList<>();
                for (int i = 0; i < 16; i++) results.add(workers.submit(() -> session.requestText(Operation.PING, "")));
                for (Future<String> result : results) assertEquals("pong", result.get(10, TimeUnit.SECONDS));
            } finally { workers.shutdownNow(); }
        }
    }

    private Fixture startInjected(int port, NativeAgentArtifact artifact) throws Exception {
        Fixture fixture = start(true, List.of());
        try {
            service.injectNativeCommunicationAgent(Long.toString(fixture.process.pid()), artifact,
                    service.preparePreloadedAgent(port, TOKEN, 30));
            return fixture;
        } catch (Throwable error) { fixture.close(); throw error; }
    }

    @Test void injectedRestrictedJvmAuthenticatesReconnectsAndRecoversFromBootstrapFailure() throws Exception {
        NativeAgentArtifact artifact = artifact();
        int port = port();
        try (Fixture fixture = start(true, List.of())) {
            String pid = Long.toString(fixture.process.pid());
            var prepared = service.preparePreloadedAgent(port, TOKEN, 30);
            try (ServerSocket occupied = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
                assertThrows(IOException.class, () -> service.injectNativeCommunicationAgent(pid, artifact, prepared));
            }
            service.injectNativeCommunicationAgent(pid, artifact, prepared);
            assertThrows(IOException.class, () -> service.connectNativeAgent(port, "incorrect-token-12345678", descriptor(fixture)));
            try (InspectorSession session = connect(port, fixture)) {
                assertThrows(IOException.class, () -> service.injectNativeCommunicationAgent(pid, artifact, prepared));
                assertEquals("pong", session.requestText(Operation.PING, ""));
                session.request(Operation.JVMTI_CAPTURE_START, "");
            }
            try (InspectorSession session = connect(port, fixture)) {
                assertTrue(session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "").contains("active=0\nevents=0\n"));
                assertEquals("before", runtimeValue(session));
            }
            assertTrue(fixture.process.isAlive());
        }
    }

    @Test void authenticatesVerifiesPidAndCleansTemporaryInstrumentationOnEofAndDisconnect() throws Exception {
        int port = port();
        try (Fixture fixture = start(argument(port, 30))) {
            assertThrows(IOException.class, () -> service.connectNativeAgent(port, "incorrect-token-12345678", descriptor(fixture)));
            assertThrows(IOException.class, () -> service.connectNativeAgent(port, TOKEN, new JvmDescriptor("1", "wrong PID")));
            try (Socket abrupt = new Socket("127.0.0.1", port)) {
                abrupt.setSoTimeout(15000);
                DataOutputStream output = new DataOutputStream(abrupt.getOutputStream());
                DataInputStream input = new DataInputStream(abrupt.getInputStream());
                output.writeUTF(TOKEN); output.flush();
                WireProtocol.writeRequest(output, Operation.JVMTI_CAPTURE_START, new byte[0]);
                assertTrue(WireProtocol.readResponse(input).success());
                WireProtocol.writeRequest(output, Operation.TRACE_START, WireProtocol.utf8(
                        AttachTarget.class.getName() + "\nruntimeValue\n()Ljava/lang/String;\nstopAfterMillis=60000"));
                WireProtocol.Response traced = WireProtocol.readResponse(input);
                assertTrue(traced.success(), traced.text());
            }
            try (InspectorSession session = connect(port, fixture)) {
                assertTrue(session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "").contains("active=0\nevents=0\n"));
                assertFalse(session.requestText(Operation.TRACE_EVENTS, "").contains("trace-"));
                assertEquals("before", runtimeValue(session));
                org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
                new ClassReader(session.request(Operation.JVMTI_CLASS_BYTES, AttachTarget.class.getName())).accept(node, 0);
                for (var method : node.methods) if (method.name.equals("runtimeValue")) {
                    for (var instruction : method.instructions) if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)
                        assertFalse(call.owner.startsWith("dev/whitedev/jpi/agent/trace/"), "Trace bytecode survived disconnect");
                }
                session.request(Operation.JVMTI_CAPTURE_START, "");
            }
            try (InspectorSession session = connect(port, fixture)) {
                assertTrue(session.requestText(Operation.JVMTI_CAPTURE_EVENTS, "").contains("active=0\nevents=0\n"));
                assertEquals("before", runtimeValue(session));
            }
            assertTrue(fixture.process.isAlive());
        }
    }

    @Test void malformedAuthenticatedRequestDoesNotKillTheReconnectListener() throws Exception {
        int port = port();
        try (Fixture fixture = start(argument(port, 30))) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(15000);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeUTF(TOKEN); output.writeInt(WireProtocol.MAGIC); output.writeShort(WireProtocol.VERSION);
                output.writeByte(255); output.writeInt(0); output.flush();
                assertEquals(-1, socket.getInputStream().read());
            }
            try (InspectorSession session = connect(port, fixture)) { assertEquals("pong", session.requestText(Operation.PING, "")); }
        }
    }

    @Test void idleNativeListenerExpiresAndReleasesItsPortWhileApplicationKeepsRunning() throws Exception {
        int port = port();
        try (Fixture fixture = start(argument(port, 10))) {
            try (InspectorSession session = connect(port, fixture)) {
                session.request(Operation.JVMTI_CAPTURE_START, "");
                assertEquals("scheduled", execute(session, "NativeReleaseProbe",
                        "Thread probe = new Thread(() -> { try { "
                        + "java.lang.reflect.Field field = Class.forName(\"dev.whitedev.jpi.agent.InspectorAgent\").getDeclaredField(\"activeServer\"); field.setAccessible(true); "
                        + "for (int i=0;i<150;i++) { Thread.sleep(100); if(field.get(null)==null) { System.out.println(\"native-server-released\"); System.out.flush(); return; } } "
                        + "System.out.println(\"native-server-retained\"); } catch(Exception error) { System.out.println(error); } }); "
                        + "probe.setDaemon(true); probe.start(); out.print(\"scheduled\");"));
            }
            String releaseMarker = CompletableFuture.supplyAsync(() -> {
                try { return fixture.process.inputReader().readLine(); }
                catch (IOException error) { throw new CompletionException(error); }
            }).get(20, TimeUnit.SECONDS);
            assertEquals("native-server-released", releaseMarker);
            assertThrows(IOException.class, () -> connect(port, fixture));
            try (ServerSocket released = new ServerSocket()) {
                released.setReuseAddress(true); released.bind(new InetSocketAddress("127.0.0.1", port));
            }
            assertTrue(fixture.process.isAlive());
        }
    }

    @Test void occupiedPortRollsBackBootstrapWithoutTerminatingTheApplicationOrLeakingCredentials() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = occupied.getLocalPort();
            try (Fixture fixture = start(argument(port, 30))) {
                occupied.close();
                assertThrows(IOException.class, () -> connect(port, fixture));
                assertBootstrapFailed(fixture);
            }
        }
    }

    @Test void invalidLibraryDigestFailsClosedAndLeavesTheApplicationRunning() throws Exception {
        int port = port();
        NativeAgentArtifact artifact = artifact();
        String argument = service.nativePreloadedAgentArgument(artifact, service.preparePreloadedAgent(port, TOKEN, 30));
        try (Fixture fixture = start(argument.replace(artifact.sha256(), "0".repeat(64)))) {
            assertThrows(IOException.class, () -> connect(port, fixture));
            assertBootstrapFailed(fixture);
        }
    }

    @Test void unconfiguredNativeLibraryDoesNotProvideACommunicationEndpointOrAttachBypass() throws Exception {
        int port = port();
        try (Fixture fixture = start(artifact().startupArgument())) {
            assertThrows(IOException.class, () -> connect(port, fixture));
            IOException error = assertThrows(IOException.class, () -> service.attach(descriptor(fixture)));
            assertTrue(error.getMessage().contains("preloaded"), error.toString());
            assertThrows(IOException.class, () -> service.loadNativeCommunicationAgent(descriptor(fixture).id(), artifact(),
                    service.preparePreloadedAgent(port, TOKEN, 30)));
            assertTrue(fixture.process.isAlive());
        }
    }

    @Test void liveNativeInitializationRecoversFromBindFailureAndRejectsDuplicateLifecycleOwnership() throws Exception {
        NativeAgentArtifact artifact = artifact();
        try (Fixture fixture = start(false, List.of());
             ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = occupied.getLocalPort();
            var prepared = service.preparePreloadedAgent(port, TOKEN, 30);
            assertThrows(IOException.class, () -> service.loadNativeCommunicationAgent(descriptor(fixture).id(), artifact, prepared));
            assertBootstrapFailed(fixture);
            occupied.close();
            service.loadNativeCommunicationAgent(descriptor(fixture).id(), artifact, prepared);
            try (InspectorSession session = connect(port, fixture)) {
                assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("mode=attach"));
                assertTrue(session.requestText(Operation.SESSION_INFO, "").contains("runtime=JVMTI\n"));
                assertTrue(session.requestText(Operation.CLASSES, "").contains(AttachTarget.class.getName()));
                assertThrows(IOException.class, () -> service.loadNativeCommunicationAgent(descriptor(fixture).id(), artifact, prepared));
                assertEquals("pong", session.requestText(Operation.PING, ""));
                assertTrue(session.requestText(Operation.JVMTI_STATUS, "").contains("communication_bootstrap=started"));
            }
            try (InspectorSession session = connect(port, fixture)) { assertEquals("before", runtimeValue(session)); }
        }
    }

    @Test void provisionsJarFromAPathWithSpacesWithoutAnExtraClasspathArgument() throws Exception {
        Path directory = Files.createTempDirectory("jpi native bootstrap ");
        Path jar = directory.resolve("provisioned agent.jar");
        try {
            Files.copy(Path.of(System.getProperty("jpi.agent.jar", "target/jpi.jar")), jar);
            ImprovedAttachService provisioner = new ImprovedAttachService(jar.toFile());
            int port = port();
            String argument = provisioner.nativePreloadedAgentArgument(artifact(), service.preparePreloadedAgent(port, TOKEN, 30));
            try (Fixture fixture = start(argument); InspectorSession session = connect(port, fixture)) {
                assertTrue(session.requestText(Operation.CLASSES, "").contains(AttachTarget.class.getName()));
                String classpath = execute(session, "NativeClasspath", "out.print(System.getProperty(\"java.class.path\"));");
                assertTrue(classpath.startsWith(System.getProperty("jpi.test.classes", "target/test-classes")), classpath);
                assertTrue(classpath.contains("provisioned agent.jar"), classpath);
            }
        } finally { Files.deleteIfExists(jar); Files.deleteIfExists(directory); }
    }

    @Test void rejectsJavaAgentEndpointAsNativeAndKeepsExistingPreloadedApiWorking() throws Exception {
        artifact();
        int port = port();
        String argument = service.preloadedAgentArgument(service.preparePreloadedAgent(port, TOKEN, 30));
        try (Fixture fixture = start(argument)) {
            assertThrows(IOException.class, () -> connect(port, fixture));
            try (InspectorSession session = service.connectPreloadedAgent(port, TOKEN, descriptor(fixture))) {
                assertTrue(session.requestText(Operation.SESSION_INFO, "").contains("communication=java-agent\n"));
                assertEquals("pong", session.requestText(Operation.PING, ""));
            }
        }
    }

    private String argument(int port, int timeout) throws Exception {
        return service.nativePreloadedAgentArgument(artifact(), service.preparePreloadedAgent(port, TOKEN, timeout));
    }
    private InspectorSession connect(int port, Fixture fixture) throws IOException { return service.connectNativeAgent(port, TOKEN, descriptor(fixture)); }
    private static JvmDescriptor descriptor(Fixture fixture) { return new JvmDescriptor(Long.toString(fixture.process.pid()), "native-only owned JVM"); }
    private static int port() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); }
    }
    private NativeAgentArtifact artifact() throws Exception {
        String os = System.getProperty("os.name", "");
        Assumptions.assumeTrue(os.equals("Linux") || os.startsWith("Windows"));
        boolean bundled = getClass().getResource(NativeAgentArtifact.resourcePath()) != null;
        if (Boolean.getBoolean("jpi.native.required")) assertTrue(bundled); else Assumptions.assumeTrue(bundled);
        return NativeAgentArtifact.extractBundled();
    }
    private static String execute(InspectorSession session, String name, String body) throws IOException {
        return session.requestText(Operation.EXECUTE, "public class " + name + " { public static void execute(java.io.PrintStream out) throws Exception { " + body + " } }");
    }
    private static String runtimeValue(InspectorSession session) throws IOException {
        return execute(session, "NativeValue", "out.print(dev.whitedev.jpi.integration.AttachTarget.runtimeValue());");
    }
    private void assertBootstrapFailed(Fixture fixture) throws IOException {
        String log = log(fixture.errorLog);
        assertTrue(log.contains("Communication bootstrap failed"), log);
        assertFalse(log.contains(TOKEN), log);
        assertTrue(fixture.process.isAlive());
    }
    private Fixture start(String argument) throws Exception {
        if (argument.startsWith("\"")) argument = argument.substring(1, argument.length() - 1);
        return start(true, List.of(argument));
    }
    private Fixture start(boolean restricted, List<String> arguments) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path log = Files.createTempFile("jpi-native-bootstrap-", ".log");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-Xcheck:jni"));
        if (restricted) command.addAll(List.of("-XX:+DisableAttachMechanism", "-XX:-EnableDynamicAgentLoading"));
        command.addAll(arguments);
        command.addAll(List.of("-cp", System.getProperty("jpi.test.classes", "target/test-classes"), AttachTarget.class.getName()));
        Process process = new ProcessBuilder(command)
                .redirectError(log.toFile()).start();
        Fixture fixture = new Fixture(process, log);
        try {
            String pid = CompletableFuture.supplyAsync(() -> {
                try { return process.inputReader().readLine(); }
                catch (IOException error) { throw new CompletionException(error); }
            }).get(20, TimeUnit.SECONDS);
            assertEquals(Long.toString(process.pid()), pid, () -> {
                try { return log(log); } catch (IOException error) { return error.toString(); }
            });
            return fixture;
        } catch (Throwable error) { fixture.close(); throw error; }
    }
    private static final class Fixture implements AutoCloseable {
        final Process process; final Path errorLog;
        Fixture(Process process, Path errorLog) { this.process = process; this.errorLog = errorLog; }
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
            String log = log(errorLog);
            assertFalse(log.contains("WARNING in native method") || log.contains("FATAL ERROR in native method"), log);
            Files.deleteIfExists(errorLog);
        }
    }

    private static String log(Path path) throws IOException {
        return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }
}

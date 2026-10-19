package dev.whitedev.jpi.attach;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.VirtualMachine;
import dev.whitedev.jpi.JpiApplication;
import dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact;
import dev.whitedev.jpi.protocol.Operation;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class AttachService {
    private final File configuredAgentJar;

    public AttachService() { this.configuredAgentJar = null; }
    public AttachService(File agentJar) { this.configuredAgentJar = agentJar; }

    public AttachRestriction attachRestriction(String pid) { return AttachRestriction.inspect(pid); }

    private void requireAttachAvailable(String pid) throws IOException {
        if (attachRestriction(pid) == AttachRestriction.DISABLED) throw new IOException(
                "Attach API is disabled by -XX:+DisableAttachMechanism for PID " + pid
                + ". Connect to a preloaded JPI listener with connectPreloadedAgent, or restart with -javaagent"
                + ", or provision ImprovedAttachService.nativePreloadedAgentArgument at startup."
                + " A native library without communication options does not provide a JPI session."
                + " No security-restriction bypass is attempted.");
    }

    public PreparedTunnelAgent preparePreloadedAgent(int port, String token, int idleTimeoutSeconds) {
        PreparedTunnelAgent prepared = prepareTunnelAgent(port, token, idleTimeoutSeconds);
        return new PreparedTunnelAgent(prepared.token(), prepared.options() + ";reconnect=true");
    }

    public String preloadedAgentArgument(PreparedTunnelAgent prepared) throws Exception {
        if (prepared == null) throw new IllegalArgumentException("Prepared listener is required");
        return "-javaagent:" + applicationJar().getAbsolutePath() + "=" + prepared.options();
    }

    public InspectorSession connectPreloadedAgent(int port, String token, JvmDescriptor target) throws IOException {
        if (target == null || !target.id().matches("[1-9][0-9]*")) throw new IllegalArgumentException("A target PID is required");
        InspectorSession session = connectEndpoint(port, token, target, false);
        try {
            String info = session.requestText(Operation.SESSION_INFO, "");
            if (!info.startsWith("pid=" + target.id() + "\n")) throw new IOException("Preloaded endpoint belongs to a different JVM");
            return session;
        } catch (IOException error) { session.close(); throw error; }
    }

    public String enableNativeBackend(InspectorSession session, dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact artifact) throws IOException {
        if (session == null || session.isTunneled()) throw new IOException("Native loading requires a local inspection session. Use startup loading and remote binding for tunnels.");
        dev.whitedev.jpi.agent.jvmti.NativeJvmtiBridge.verifyLibrary(artifact.library().toString(), artifact.sha256());
        String status = session.requestText(Operation.JVMTI_STATUS, "");
        if (!status.startsWith("backend=JVMTI\n")) loadNativeAgent(session.target().id(), artifact);
        return session.requestText(Operation.JVMTI_BIND, artifact.bindingOptions());
    }

    public void loadNativeAgent(String pid, dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact artifact) throws IOException {
        loadNativeAgent(pid, artifact, "");
    }

    protected void loadNativeAgent(String pid, NativeAgentArtifact artifact, String options) throws IOException {
        if (pid == null || !pid.matches("[0-9]+")) throw new IOException("A local numeric target PID is required");
        requireAttachAvailable(pid);
        JvmAgentPolicy.inspect(pid).rejectObservedRestrictions(pid);
        File library = dev.whitedev.jpi.agent.jvmti.NativeJvmtiBridge.verifyLibrary(artifact.library().toString(), artifact.sha256());
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(pid);
            vm.loadAgentPath(library.getAbsolutePath(), options);
        } catch (Exception error) {
            throw new IOException("The JVM rejected the native agent. Match the target architecture and permissions. If dynamic agent loading is disabled, use -agentpath at startup. No OS injection fallback is attempted. Cause: " + error.getMessage(), error);
        } finally {
            if (vm != null) vm.detach();
        }
    }

    public PreparedAgentSession prepareEarlyAgent() throws Exception {
        File agentJar = applicationJar();
        SessionChannel channel = new SessionChannel(120_000);
        String argument = "-javaagent:" + agentJar.getAbsolutePath() + "=" + channel.options();
        if (argument.indexOf(' ') >= 0) argument = '"' + argument + '"';
        return new PreparedAgentSession(channel, argument);
    }

    public PreparedAgentSession prepareEarlyNativeAgent(NativeAgentArtifact artifact) throws Exception {
        NativeAgentArtifact verified = NativeAgentArtifact.verify(artifact.library(), artifact.sha256());
        SessionChannel channel = new SessionChannel(120_000);
        try {
            String argument = "-agentpath:" + verified.library().toFile().getCanonicalPath()
                    + "=" + nativeCommunicationOptions(verified, channel.options());
            if (argument.indexOf(' ') >= 0) argument = '"' + argument + '"';
            return new PreparedAgentSession(channel, argument);
        } catch (Exception error) { channel.close(); throw error; }
    }

    protected String nativeCommunicationOptions(NativeAgentArtifact verified, String options) throws Exception {
        dev.whitedev.jpi.agent.AgentOptions.parse(options);
        File jar = applicationJar().getCanonicalFile();
        if (!jar.isFile() || !jar.canRead() || !jar.getName().toLowerCase(Locale.ROOT).endsWith(".jar"))
            throw new IOException("Native communication requires a readable packaged JPI JAR");
        String library = verified.library().toFile().getCanonicalPath();
        for (String value : new String[] {jar.toString(), library, options}) {
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Native bootstrap options cannot contain control characters");
        }
        if (jar.toString().indexOf(';') >= 0 || library.indexOf(';') >= 0)
            throw new IllegalArgumentException("Native bootstrap paths cannot contain semicolons");
        return "jpi.bootstrap=true;jpi.jar=" + jar + ";jpi.library=" + library
                + ";jpi.sha256=" + verified.sha256() + ";" + options;
    }

    public PreparedTunnelAgent prepareTunnelAgent(int agentPort, int acceptTimeoutSeconds) {
        return prepareTunnelAgent(agentPort, UUID.randomUUID().toString() + UUID.randomUUID(), acceptTimeoutSeconds);
    }

    public PreparedTunnelAgent prepareTunnelAgent(int agentPort, String token, int acceptTimeoutSeconds) {
        if (agentPort < 1 || agentPort > 65535) throw new IllegalArgumentException("Agent port must be between 1 and 65535");
        if (token == null || token.length() < 16 || token.length() > 512 || token.indexOf(';') >= 0) {
            throw new IllegalArgumentException("Invalid tunnel authentication token");
        }
        if (acceptTimeoutSeconds < 10 || acceptTimeoutSeconds > 3600) {
            throw new IllegalArgumentException("Agent timeout must be between 10 and 3600 seconds");
        }
        String options = "mode=listen;host=127.0.0.1"
                + ";port=" + agentPort + ";token=" + token
                + ";acceptTimeoutSeconds=" + acceptTimeoutSeconds;
        return new PreparedTunnelAgent(token, options);
    }

    public void loadTunnelAgent(String pid, PreparedTunnelAgent prepared) throws Exception {
        if (pid == null || pid.isBlank()) throw new IllegalArgumentException("Target PID is required");
        requireAttachAvailable(pid.trim());
        if (prepared == null) throw new IllegalArgumentException("Prepared tunnel agent is required");
        File agentJar = applicationJar();
        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(pid.trim());
            vm.loadAgent(agentJar.getAbsolutePath(), prepared.options());
        } catch (AgentInitializationException exception) {
            throw new IOException("The listening JPI agent could not initialize. Check that its loopback port is free. "
                    + "Initialization code: " + exception.returnValue() + ". Cause: " + exception.getMessage(), exception);
        } catch (AgentLoadException | AttachNotSupportedException | IOException exception) {
            throw new IOException("Could not load the listening JPI agent into PID " + pid.trim()
                    + ". Cause: " + exception.getMessage(), exception);
        } finally {
            if (vm != null) vm.detach();
        }
    }

    public InspectorSession connectTunnel(int localPort, String token, String pid, String displayName) throws IOException {
        String id = pid == null || pid.isBlank() ? "tunneled" : pid.trim();
        String name = displayName == null || displayName.isBlank() ? "Tunneled JVM" : displayName.trim();
        return connectEndpoint(localPort, token, new JvmDescriptor(id, name + " (tunnel)"), true);
    }

    private InspectorSession connectEndpoint(int localPort, String token, JvmDescriptor target, boolean tunneled) throws IOException {
        if (localPort < 1 || localPort > 65535) throw new IllegalArgumentException("Tunnel port must be between 1 and 65535");
        if (token == null || token.length() < 16 || token.length() > 512) {
            throw new IllegalArgumentException("Invalid tunnel authentication token");
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", localPort), 15_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(60_000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeUTF(token);
            output.flush();
            InspectorSession session = new InspectorSession(target, socket, tunneled);
            if (!"pong".equals(session.requestText(Operation.PING, ""))) {
                session.close();
                throw new IOException("Agent did not complete the tunneled protocol handshake");
            }
            return session;
        } catch (IOException error) {
            try { socket.close(); } catch (IOException ignored) {}
            throw new IOException("Could not connect to the local tunnel endpoint on port " + localPort
                    + ". Verify the agent, SSH forwarding, port, and token. Cause: " + error.getMessage(), error);
        }
    }

    public InspectorSession attach(JvmDescriptor target) throws Exception {
        requireAttachAvailable(target.id());
        File agentJar = applicationJar();
        SessionChannel channel = new SessionChannel();
        VirtualMachine vm = null;
        String targetRuntime = "unknown";
        try {
            vm = VirtualMachine.attach(target.id());
            try {
                String specification = vm.getSystemProperties().getProperty("java.specification.version", "unknown");
                String implementation = vm.getSystemProperties().getProperty("java.vm.name", "JVM");
                targetRuntime = implementation + " " + specification;
            } catch (IOException ignored) { }
            vm.loadAgent(agentJar.getAbsolutePath(), channel.options());
            return channel.accept(target);
        } catch (AgentInitializationException exception) {
            throw new IOException("The JPI agent JAR was loaded but could not initialize in " + targetRuntime + ". "
                    + "Restart the target if it previously loaded an older JPI agent, then attach with the newly built JAR. "
                    + "The embedded agent supports Java 8 and newer JVMs. Initialization code: "
                    + exception.returnValue() + ". Cause: " + exception.getMessage(), exception);
        } catch (AgentLoadException exception) {
            throw new IOException("The target could not load the JPI agent JAR. Verify that the packaged JAR is readable "
                    + "and restart the target if Windows still holds an older copy. Cause: " + exception.getMessage(), exception);
        } catch (AttachNotSupportedException | IOException exception) {
            throw new IOException("The target rejected late attach. It may disable Attach API, run under another "
                    + "account, or be isolated by the OS. This error alone does not prove DisableAttachMechanism. "
                    + "Connect to a preloaded listener, or use 'Launch with early agent' when you control startup.\n"
                    + "Cause: " + exception.getMessage(), exception);
        } finally {
            if (vm != null) vm.detach();
            channel.close();
        }
    }

    public LaunchResult launch(File targetJar, List<String> vmArguments,
                               List<String> applicationArguments) throws Exception {
        return launch(targetJar, vmArguments, applicationArguments, null);
    }

    public LaunchResult launch(File targetJar, List<String> vmArguments,
                               List<String> applicationArguments, NativeAgentArtifact nativeArtifact) throws Exception {
        File canonicalTarget = targetJar.getCanonicalFile();
        if (!canonicalTarget.isFile() || !canonicalTarget.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Select an existing executable JAR");
        }
        NativeAgentArtifact verifiedNative = nativeArtifact == null ? null
                : NativeAgentArtifact.verify(nativeArtifact.library(), nativeArtifact.sha256());
        File agentJar = applicationJar();
        SessionChannel channel = new SessionChannel();
        File logFile = Files.createTempFile("jpi-early-agent-", ".log").toFile();
        logFile.deleteOnExit();
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.addAll(vmArguments);
            if (verifiedNative != null) command.add(verifiedNative.startupArgument());
            command.add("-javaagent:" + agentJar.getAbsolutePath() + "=" + channel.options());
            command.add("-jar");
            command.add(canonicalTarget.getAbsolutePath());
            command.addAll(applicationArguments);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(canonicalTarget.getParentFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            process = builder.start();
            JvmDescriptor descriptor = new JvmDescriptor(processId(process),
                    canonicalTarget.getName() + " (early agent)");
            InspectorSession session = channel.accept(descriptor);
            if (verifiedNative != null) {
                try {
                    session.requestText(Operation.JVMTI_BIND, verifiedNative.bindingOptions());
                } catch (Exception error) {
                    session.close();
                    throw error;
                }
            }
            return new LaunchResult(session, process, logFile);
        } catch (Exception exception) {
            if (process != null) process.destroy();
            String tail = readTail(logFile);
            throw new IOException("Early-agent launch failed"
                    + (tail.isEmpty() ? "" : ". Target output:\n" + tail), exception);
        } finally {
            channel.close();
        }
    }

    protected File applicationJar() throws Exception {
        if (configuredAgentJar != null) return configuredAgentJar.getCanonicalFile();
        URI location = JpiApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        File file = new File(location);
        if (!file.isFile() || !file.getName().endsWith(".jar")) {
            throw new IllegalStateException("Agent operations require the packaged application. "
                    + "Run: mvn package, then java -jar target/jpi.jar");
        }
        return file.getCanonicalFile();
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return new File(new File(System.getProperty("java.home"), "bin"), name).getAbsolutePath();
    }

    private static String processId(Process process) {
        try {
            Method pid = Process.class.getMethod("pid");
            return String.valueOf(pid.invoke(process));
        } catch (Throwable ignored) {
            return "launched";
        }
    }

    private static String readTail(File file) {
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - 20);
            StringBuilder output = new StringBuilder();
            for (int index = start; index < lines.size(); index++) {
                output.append(lines.get(index)).append('\n');
            }
            return output.toString().trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    protected static final class SessionChannel {
        private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
        private final ServerSocket listener;

        SessionChannel() throws IOException { this(20_000); }

        SessionChannel(int timeoutMillis) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            listener.setSoTimeout(timeoutMillis);
        }

        String options() {
            return "host=" + InetAddress.getLoopbackAddress().getHostAddress()
                    + ";port=" + listener.getLocalPort() + ";token=" + token;
        }

        InspectorSession accept(JvmDescriptor target) throws Exception {
            Socket socket = listener.accept();
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(30_000);
                String received = new DataInputStream(socket.getInputStream()).readUTF();
                if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        received.getBytes(StandardCharsets.UTF_8))) {
                    throw new SecurityException("Agent authentication failed");
                }
                InspectorSession session = new InspectorSession(target, socket);
                if (!"pong".equals(session.requestText(Operation.PING, ""))) {
                    session.close();
                    throw new IllegalStateException("Agent did not complete the protocol handshake");
                }
                return session;
            } catch (Exception error) {
                try { socket.close(); } catch (IOException ignored) { }
                throw error;
            }
        }

        void close() {
            try { listener.close(); } catch (IOException ignored) { }
        }
    }

    public static final class PreparedAgentSession implements AutoCloseable {
        private final SessionChannel channel;
        private final String jvmArgument;

        private PreparedAgentSession(SessionChannel channel, String jvmArgument) {
            this.channel = channel;
            this.jvmArgument = jvmArgument;
        }

        public String jvmArgument() { return jvmArgument; }

        public InspectorSession await(String pid, String displayName) throws Exception {
            try {
                String id = pid == null || pid.isBlank() ? "external" : pid.trim();
                return channel.accept(new JvmDescriptor(id, displayName + " (manual early agent)"));
            } finally {
                close();
            }
        }

        @Override public void close() { channel.close(); }
    }

    public static final class PreparedTunnelAgent {
        private final String token;
        private final String options;

        private PreparedTunnelAgent(String token, String options) {
            this.token = token;
            this.options = options;
        }

        public String token() { return token; }
        public String options() { return options; }
    }
}

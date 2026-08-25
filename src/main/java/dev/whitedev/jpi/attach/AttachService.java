package dev.whitedev.jpi.attach;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.AgentInitializationException;
import com.sun.tools.attach.AgentLoadException;
import com.sun.tools.attach.VirtualMachine;
import dev.whitedev.jpi.JpiApplication;
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

public final class AttachService {
    private final File configuredAgentJar;

    public AttachService() { this.configuredAgentJar = null; }
    public AttachService(File agentJar) { this.configuredAgentJar = agentJar; }

    public PreparedAgentSession prepareEarlyAgent() throws Exception {
        File agentJar = applicationJar();
        SessionChannel channel = new SessionChannel(120_000);
        String argument = "-javaagent:" + agentJar.getAbsolutePath() + "=" + channel.options();
        if (argument.indexOf(' ') >= 0) argument = '"' + argument + '"';
        return new PreparedAgentSession(channel, argument);
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
            String id = pid == null || pid.isBlank() ? "tunneled" : pid.trim();
            String name = displayName == null || displayName.isBlank() ? "Tunneled JVM" : displayName.trim();
            InspectorSession session = new InspectorSession(new JvmDescriptor(id, name + " (tunnel)"), socket);
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
                    + "account, or be isolated by the OS. Use 'Launch with early agent' when you control startup.\n"
                    + "Cause: " + exception.getMessage(), exception);
        } finally {
            if (vm != null) vm.detach();
            channel.close();
        }
    }

    public LaunchResult launch(File targetJar, List<String> vmArguments,
                               List<String> applicationArguments) throws Exception {
        File canonicalTarget = targetJar.getCanonicalFile();
        if (!canonicalTarget.isFile() || !canonicalTarget.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Select an existing executable JAR");
        }
        File agentJar = applicationJar();
        SessionChannel channel = new SessionChannel();
        File logFile = Files.createTempFile("jpi-early-agent-", ".log").toFile();
        logFile.deleteOnExit();
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(javaExecutable());
            command.addAll(vmArguments);
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

    private File applicationJar() throws Exception {
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

    private static final class SessionChannel {
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
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30_000);
            String received = new DataInputStream(socket.getInputStream()).readUTF();
            if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                    received.getBytes(StandardCharsets.UTF_8))) {
                socket.close();
                throw new SecurityException("Agent authentication failed");
            }
            InspectorSession session = new InspectorSession(target, socket);
            if (!"pong".equals(session.requestText(Operation.PING, ""))) {
                session.close();
                throw new IllegalStateException("Agent did not complete the protocol handshake");
            }
            return session;
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

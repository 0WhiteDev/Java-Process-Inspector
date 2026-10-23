package dev.whitedev.jpi.agent;

import dev.whitedev.jpi.agent.jvmti.NativeJvmtiBridge;
import dev.whitedev.jpi.agent.jvmti.RawMemoryCommand;
import java.lang.management.ManagementFactory;

import dev.whitedev.jpi.agent.patch.SourceExecutor;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.protocol.WireProtocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.Map;

final class AgentServer implements Runnable {
    private final AgentOptions options;
    private TargetInspector inspector;
    private final RuntimeAccess instrumentation;
    private final ClassRegistry classRegistry;
    private final Map<Operation, AgentCommand> commands;
    private volatile Socket socket;
    private volatile ServerSocket listener;
    private volatile boolean closed;

    AgentServer(AgentOptions options, Instrumentation instrumentation, ClassRegistry classRegistry) {
        this(options, RuntimeAccess.instrumentation(instrumentation), classRegistry);
    }

    AgentServer(AgentOptions options, RuntimeAccess instrumentation, ClassRegistry classRegistry) {
        this.options = options;
        this.instrumentation = instrumentation;
        this.classRegistry = classRegistry;
        this.inspector = new TargetInspector(instrumentation, classRegistry);
        this.commands = buildCommands();
    }

    synchronized void prepare() throws IOException {
        if (closed) throw new IOException("Agent server is closed");
        if (options.mode() != AgentOptions.Mode.LISTEN || listener != null) return;
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(false);
        try {
            server.bind(new InetSocketAddress(options.host(), options.port()), 1);
            server.setSoTimeout(options.acceptTimeoutMillis());
            listener = server;
        } catch (IOException error) { server.close(); throw error; }
    }

    @Override
    public void run() {
        try {
            while (!closed) {
                Socket connection = openConnection();
                try { serve(connection); }
                catch (EOFException ignored) { }
                catch (IOException error) {
                    if (!closed) System.err.println("[JPI agent] Connection ended: " + error.getMessage());
                } finally {
                    try { connection.close(); } catch (IOException ignored) { }
                    socket = null;
                }
                if (!options.reconnect()) break;
                resetInspection();
            }
        } catch (IOException exception) {
            if (!closed) System.err.println("[JPI agent] Listener ended: " + exception.getMessage());
        } finally {
            try { close(); }
            finally { InspectorAgent.serverStopped(this); }
        }
    }

    private void serve(Socket connection) throws IOException {
        synchronized (this) {
            if (closed) { connection.close(); return; }
            socket = connection;
        }
        connection.setTcpNoDelay(true);
        connection.setSoTimeout(15_000);
        DataOutputStream output = new DataOutputStream(connection.getOutputStream());
        DataInputStream input = new DataInputStream(connection.getInputStream());
        authenticate(input, output);
        connection.setSoTimeout(0);
        while (!closed) {
            WireProtocol.Request request = WireProtocol.readRequest(input);
            boolean disconnect = request.operation() == Operation.DISCONNECT;
            try {
                byte[] response = dispatch(request);
                WireProtocol.writeResponse(output, true, response);
            } catch (Throwable throwable) {
                WireProtocol.writeResponse(output, false, WireProtocol.utf8(errorMessage(throwable)));
            }
            if (disconnect) break;
        }
    }

    private synchronized void resetInspection() {
        if (closed) return;
        inspector.close();
        inspector = new TargetInspector(instrumentation, classRegistry);
    }

    private Socket openConnection() throws IOException {
        if (options.mode() == AgentOptions.Mode.CONNECT) return new Socket(options.host(), options.port());
        prepare();
        ServerSocket server = listener;
        if (server == null) throw new IOException("Agent listener is unavailable");
        return server.accept();
    }

    private void authenticate(DataInputStream input, DataOutputStream output) throws IOException {
        if (options.mode() == AgentOptions.Mode.CONNECT) {
            output.writeUTF(options.token());
            output.flush();
            return;
        }
        String received = input.readUTF();
        if (!MessageDigest.isEqual(options.token().getBytes(StandardCharsets.UTF_8),
                received.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("Client authentication failed");
        }
    }

    private synchronized byte[] dispatch(WireProtocol.Request request) throws Exception {
        if (closed) throw new IOException("Agent server is closed");
        String payload = WireProtocol.text(request.payload());
        AgentCommand command = commands.get(request.operation());
        if (command == null) throw new IllegalArgumentException("Unsupported operation: " + request.operation());
        return command.execute(payload);
    }

    private Map<Operation, AgentCommand> buildCommands() {
        Map<Operation, AgentCommand> registry = new EnumMap<>(Operation.class);
        registry.put(Operation.PING, payload -> WireProtocol.utf8("pong"));
        registry.put(Operation.SESSION_INFO, payload -> WireProtocol.utf8(
                "pid=" + ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0]
                + "\nattach_disabled=" + booleanVmOption("DisableAttachMechanism")
                + "\ndynamic_agent_loading=" + booleanVmOption("EnableDynamicAgentLoading")
                + "\ncommunication=" + (instrumentation.isNativeOnly() ? "native-bootstrap" : "java-agent")
                + "\nruntime=" + (instrumentation.isNativeOnly() ? "JVMTI" : "Instrumentation") + "\n"));
        registry.put(Operation.METRICS, payload -> WireProtocol.utf8(inspector.metrics()));
        registry.put(Operation.CLASSES, payload -> WireProtocol.utf8(inspector.loadedClasses()));
        registry.put(Operation.TARGET_FINGERPRINT, payload -> WireProtocol.utf8(inspector.targetFingerprint(payload)));
        registry.put(Operation.JVMTI_BIND, payload -> WireProtocol.utf8(inspector.bindNativeBackend(payload)));
        registry.put(Operation.JVMTI_STATUS, payload -> WireProtocol.utf8(NativeJvmtiBridge.status()));
        registry.put(Operation.JVMTI_CLASSES, payload -> WireProtocol.utf8(inspector.nativeLoadedClasses()));
        registry.put(Operation.JVMTI_CLASS_BYTES, inspector::nativeClassBytes);
        registry.put(Operation.JVMTI_HEAP_STATS, payload -> WireProtocol.utf8(inspector.nativeHeapStats(payload)));
        registry.put(Operation.JVMTI_CAPTURE_START, payload -> {
            NativeJvmtiBridge.startCapture();
            return WireProtocol.utf8(NativeJvmtiBridge.captureEvents());
        });
        registry.put(Operation.JVMTI_CAPTURE_STOP, payload -> {
            NativeJvmtiBridge.stopCapture();
            return WireProtocol.utf8(NativeJvmtiBridge.captureEvents());
        });
        registry.put(Operation.JVMTI_CAPTURE_EVENTS, payload -> WireProtocol.utf8(NativeJvmtiBridge.captureEvents()));
        registry.put(Operation.JVMTI_CAPTURE_ARCHIVE_START, payload -> {
            String[] fields = payload.split("\n", -1);
            if (fields.length != 2) throw new IOException("Archive start requires a target directory and byte limit");
            NativeJvmtiBridge.startCaptureArchive(fields[0], Long.parseLong(fields[1]));
            return WireProtocol.utf8(NativeJvmtiBridge.captureEvents());
        });
        registry.put(Operation.JVMTI_CAPTURE_BYTES, payload -> NativeJvmtiBridge.captureBytes(Long.parseLong(payload.trim())));
        registry.put(Operation.MEMORY_REGIONS, payload -> WireProtocol.utf8(NativeJvmtiBridge.memoryRegions()));
        registry.put(Operation.MEMORY_READ, RawMemoryCommand::read);
        registry.put(Operation.MEMORY_PATCH, payload -> WireProtocol.utf8(RawMemoryCommand.patch(payload)));
        registry.put(Operation.CLASS_BYTES, inspector::classBytes);
        registry.put(Operation.EXECUTE, payload -> WireProtocol.utf8(SourceExecutor.execute(payload)));
        registry.put(Operation.FIELDS, payload -> WireProtocol.utf8(inspector.staticFields(payload)));
        registry.put(Operation.THREAD_DUMP, payload -> WireProtocol.utf8(inspector.threadDump()));
        registry.put(Operation.THREAD_ANALYZE, payload -> WireProtocol.utf8(inspector.analyzeThreads()));
        registry.put(Operation.THREAD_SNAPSHOT, payload -> WireProtocol.utf8(inspector.threadSnapshot()));
        registry.put(Operation.THREAD_ANALYZER_CLEAR, payload -> WireProtocol.utf8(inspector.clearThreadAnalysis()));
        registry.put(Operation.CLASS_EVENTS, payload -> WireProtocol.utf8(inspector.classEvents()));
        registry.put(Operation.ENVIRONMENT, payload -> WireProtocol.utf8(inspector.environment()));
        registry.put(Operation.CONSTANT_SEARCH, payload -> WireProtocol.utf8(inspector.constantSearch(payload)));
        registry.put(Operation.REDEFINE_SOURCE, payload -> WireProtocol.utf8(inspector.redefineSource(payload)));
        registry.put(Operation.ROLLBACK_CLASS, payload -> WireProtocol.utf8(inspector.rollbackClass(payload)));
        registry.put(Operation.PATCH_HISTORY, payload -> WireProtocol.utf8(inspector.patchHistory(payload)));
        registry.put(Operation.CLASS_VERSION, inspector::classVersion);
        registry.put(Operation.ROLLBACK_PATCH, payload -> WireProtocol.utf8(inspector.rollbackPatch(payload)));
        registry.put(Operation.PREVIEW_SOURCE, inspector::previewSource);
        registry.put(Operation.PREVIEW_METHOD, inspector::previewMethod);
        registry.put(Operation.CLASS_METHODS, payload -> WireProtocol.utf8(inspector.classMethods(payload)));
        registry.put(Operation.PATCH_METHOD, payload -> WireProtocol.utf8(inspector.patchMethod(payload)));
        registry.put(Operation.APPLY_CLASS_BYTES, payload -> WireProtocol.utf8(inspector.applyClassBytes(payload)));
        registry.put(Operation.TRACE_START, payload -> WireProtocol.utf8(inspector.startTrace(payload)));
        registry.put(Operation.TRACE_STOP, payload -> WireProtocol.utf8(inspector.stopTrace(payload)));
        registry.put(Operation.TRACE_EVENTS, payload -> WireProtocol.utf8(inspector.traceEvents()));
        registry.put(Operation.TRACE_GRAPH, payload -> WireProtocol.utf8(inspector.traceGraph()));
        registry.put(Operation.TRACE_GRAPH_CLEAR, payload -> WireProtocol.utf8(inspector.clearTraceGraph()));
        registry.put(Operation.METHOD_XREFS, payload -> WireProtocol.utf8(inspector.methodXrefs(payload)));
        registry.put(Operation.XREF_SEARCH, payload -> WireProtocol.utf8(inspector.xrefSearch(payload)));
        registry.put(Operation.DEOBFUSCATION_INVENTORY, payload -> WireProtocol.utf8(inspector.deobfuscationInventory(payload)));
        registry.put(Operation.API_HOOK_START, payload -> WireProtocol.utf8(inspector.startApiHooks(payload)));
        registry.put(Operation.API_HOOK_STOP, payload -> WireProtocol.utf8(inspector.stopApiHooks()));
        registry.put(Operation.API_HOOK_EVENTS, payload -> WireProtocol.utf8(inspector.apiHookEvents()));
        registry.put(Operation.HEAP_SCAN, payload -> WireProtocol.utf8(inspector.heapScan(payload)));
        registry.put(Operation.HEAP_OBJECT, payload -> WireProtocol.utf8(inspector.heapObject(payload)));
        registry.put(Operation.HEAP_DUMP, payload -> WireProtocol.utf8(inspector.heapDump(payload)));
        registry.put(Operation.CFG_ANALYZE, payload -> WireProtocol.utf8(inspector.bytecodeCfg(payload)));
        registry.put(Operation.CFG_TRACE_START, payload -> WireProtocol.utf8(inspector.startCfgTrace(payload)));
        registry.put(Operation.CFG_TRACE_STOP, payload -> WireProtocol.utf8(inspector.stopCfgTrace(payload)));
        registry.put(Operation.CFG_SNAPSHOT, payload -> WireProtocol.utf8(inspector.cfgSnapshot(payload)));
        registry.put(Operation.FIELD_WRITE_SITES, payload -> WireProtocol.utf8(inspector.fieldWriteSites(payload)));
        registry.put(Operation.FIELD_TRACE_START, payload -> WireProtocol.utf8(inspector.startFieldTrace(payload)));
        registry.put(Operation.FIELD_TRACE_STOP, payload -> WireProtocol.utf8(inspector.stopFieldTrace()));
        registry.put(Operation.FIELD_TRACE_EVENTS, payload -> WireProtocol.utf8(inspector.fieldTraceEvents()));
        registry.put(Operation.FIELD_TRACE_HISTORY, payload -> WireProtocol.utf8(inspector.fieldTraceHistory()));
        registry.put(Operation.CLASSLOADER_SNAPSHOT, payload -> WireProtocol.utf8(inspector.classLoaderSnapshot()));
        registry.put(Operation.TRACE_HISTORY, payload -> WireProtocol.utf8(inspector.traceHistory()));
        registry.put(Operation.FILE_MONITOR_START, payload -> WireProtocol.utf8(inspector.startFileMonitor(payload)));
        registry.put(Operation.FILE_MONITOR_STOP, payload -> WireProtocol.utf8(inspector.stopFileMonitor()));
        registry.put(Operation.FILE_EVENT_BATCH, payload -> WireProtocol.utf8(inspector.fileEvents()));
        registry.put(Operation.FILE_EVENTS_CLEAR, payload -> WireProtocol.utf8(inspector.clearFileEvents()));
        AgentCommand putFileRule = payload -> WireProtocol.utf8(inspector.putFileRule(payload));
        registry.put(Operation.FILE_RULE_ADD, putFileRule);
        registry.put(Operation.FILE_RULE_UPDATE, putFileRule);
        registry.put(Operation.FILE_RULE_REMOVE, payload -> WireProtocol.utf8(inspector.removeFileRule(payload)));
        registry.put(Operation.FILE_RULE_LIST, payload -> WireProtocol.utf8(inspector.fileRules()));
        registry.put(Operation.FILE_POLICY_SET, payload -> WireProtocol.utf8(inspector.setFilePolicy(payload)));
        registry.put(Operation.FILE_POLICY_GET, payload -> WireProtocol.utf8(inspector.filePolicy()));
        registry.put(Operation.JFR_PROFILE_START, payload -> WireProtocol.utf8(inspector.startJfrProfile(payload)));
        registry.put(Operation.JFR_PROFILE_STATUS, payload -> WireProtocol.utf8(inspector.jfrProfileStatus()));
        registry.put(Operation.JFR_PROFILE_STOP, payload -> WireProtocol.utf8(inspector.stopJfrProfile()));
        registry.put(Operation.JFR_PROFILE_REPORT, payload -> WireProtocol.utf8(inspector.jfrProfileReport()));
        registry.put(Operation.EXCEPTION_START, payload -> WireProtocol.utf8(inspector.startExceptionAnalysis(payload)));
        registry.put(Operation.EXCEPTION_STOP, payload -> WireProtocol.utf8(inspector.stopExceptionAnalysis()));
        registry.put(Operation.EXCEPTION_SNAPSHOT, payload -> WireProtocol.utf8(inspector.exceptionSnapshot(payload)));
        registry.put(Operation.EXCEPTION_CLEAR, payload -> WireProtocol.utf8(inspector.clearExceptions()));
        registry.put(Operation.DISCONNECT, payload -> WireProtocol.utf8("disconnected"));
        return registry;
    }

    private String errorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getName() + (message == null ? "" : ": " + message);
    }

    private static String booleanVmOption(String name) {
        try {
            Class<?> type = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            Object bean = ManagementFactory.class.getMethod("getPlatformMXBean", Class.class).invoke(null, type);
            Object option = type.getMethod("getVMOption", String.class).invoke(bean, name);
            return String.valueOf(option.getClass().getMethod("getValue").invoke(option));
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        String disabled = "unknown";
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (("-XX:+" + name).equals(argument)) disabled = "true";
            if (("-XX:-" + name).equals(argument)) disabled = "false";
        }
        return disabled;
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        ServerSocket currentListener = listener;
        listener = null;
        if (currentListener != null) try { currentListener.close(); } catch (IOException ignored) {}
        Socket current = socket;
        socket = null;
        if (current != null) try { current.close(); } catch (IOException ignored) {}
        inspector.close();
    }
}

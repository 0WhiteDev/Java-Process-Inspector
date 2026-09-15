package dev.whitedev.jpi.agent;

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
    private final TargetInspector inspector;
    private final Map<Operation, AgentCommand> commands;
    private volatile Socket socket;
    private volatile ServerSocket listener;
    private volatile boolean closed;

    AgentServer(AgentOptions options, Instrumentation instrumentation, ClassRegistry classRegistry) {
        this.options = options;
        this.inspector = new TargetInspector(instrumentation, classRegistry);
        this.commands = buildCommands();
    }

    void prepare() throws IOException {
        if (options.mode() != AgentOptions.Mode.LISTEN || listener != null) return;
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(false);
        server.bind(new InetSocketAddress(options.host(), options.port()), 1);
        server.setSoTimeout(options.acceptTimeoutMillis());
        listener = server;
    }

    @Override
    public void run() {
        try {
            Socket connection = openConnection();
            connection.setTcpNoDelay(true);
            connection.setSoTimeout(15_000);
            socket = connection;
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
        } catch (EOFException ignored) {
        } catch (IOException exception) {
            if (!closed) System.err.println("[JPI agent] Session ended: " + exception.getMessage());
        } finally {
            close();
        }
    }

    private Socket openConnection() throws IOException {
        if (options.mode() == AgentOptions.Mode.CONNECT) return new Socket(options.host(), options.port());
        prepare();
        ServerSocket server = listener;
        if (server == null) throw new IOException("Agent listener is unavailable");
        try {
            return server.accept();
        } finally {
            listener = null;
            try { server.close(); } catch (IOException ignored) {}
        }
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

    private byte[] dispatch(WireProtocol.Request request) throws Exception {
        String payload = WireProtocol.text(request.payload());
        AgentCommand command = commands.get(request.operation());
        if (command == null) throw new IllegalArgumentException("Unsupported operation: " + request.operation());
        return command.execute(payload);
    }

    private Map<Operation, AgentCommand> buildCommands() {
        Map<Operation, AgentCommand> registry = new EnumMap<>(Operation.class);
        registry.put(Operation.PING, payload -> WireProtocol.utf8("pong"));
        registry.put(Operation.METRICS, payload -> WireProtocol.utf8(inspector.metrics()));
        registry.put(Operation.CLASSES, payload -> WireProtocol.utf8(inspector.loadedClasses()));
        registry.put(Operation.CLASS_BYTES, inspector::classBytes);
        registry.put(Operation.EXECUTE, payload -> WireProtocol.utf8(SourceExecutor.execute(payload)));
        registry.put(Operation.FIELDS, payload -> WireProtocol.utf8(inspector.staticFields(payload)));
        registry.put(Operation.THREAD_DUMP, payload -> WireProtocol.utf8(inspector.threadDump()));
        registry.put(Operation.THREAD_ANALYZE, payload -> WireProtocol.utf8(inspector.analyzeThreads()));
        registry.put(Operation.THREAD_ANALYZER_CLEAR, payload -> WireProtocol.utf8(inspector.clearThreadAnalysis()));
        registry.put(Operation.CLASS_EVENTS, payload -> WireProtocol.utf8(inspector.classEvents()));
        registry.put(Operation.ENVIRONMENT, payload -> WireProtocol.utf8(inspector.environment()));
        registry.put(Operation.CONSTANT_SEARCH, payload -> WireProtocol.utf8(inspector.constantSearch(payload)));
        registry.put(Operation.REDEFINE_SOURCE, payload -> WireProtocol.utf8(inspector.redefineSource(payload)));
        registry.put(Operation.ROLLBACK_CLASS, payload -> WireProtocol.utf8(inspector.rollbackClass(payload)));
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
        registry.put(Operation.DISCONNECT, payload -> WireProtocol.utf8("disconnected"));
        return registry;
    }

    private String errorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getName() + (message == null ? "" : ": " + message);
    }

    void close() {
        if (closed) return;
        closed = true;
        inspector.close();
        ServerSocket currentListener = listener;
        listener = null;
        if (currentListener != null) try { currentListener.close(); } catch (IOException ignored) {}
        Socket current = socket;
        socket = null;
        if (current != null) try { current.close(); } catch (IOException ignored) {}
    }
}

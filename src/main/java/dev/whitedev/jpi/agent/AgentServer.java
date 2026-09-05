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

    private Map<Operation, AgentCommand> buildCommands() {
        Map<Operation, AgentCommand> map = new EnumMap<Operation, AgentCommand>(Operation.class);

        map.put(Operation.PING,                  new AgentCommand() { public byte[] execute(String p) { return WireProtocol.utf8("pong"); } });
        map.put(Operation.METRICS,               new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.metrics()); } });
        map.put(Operation.CLASSES,               new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.loadedClasses()); } });
        map.put(Operation.CLASS_BYTES,           new AgentCommand() { public byte[] execute(String p) throws Exception { return inspector.classBytes(p); } });
        map.put(Operation.EXECUTE,               new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(SourceExecutor.execute(p)); } });
        map.put(Operation.FIELDS,                new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.staticFields(p)); } });
        map.put(Operation.THREAD_DUMP,           new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.threadDump()); } });
        map.put(Operation.CLASS_EVENTS,          new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.classEvents()); } });
        map.put(Operation.ENVIRONMENT,           new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.environment()); } });
        map.put(Operation.CONSTANT_SEARCH,       new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.constantSearch(p)); } });
        map.put(Operation.REDEFINE_SOURCE,       new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.redefineSource(p)); } });
        map.put(Operation.ROLLBACK_CLASS,        new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.rollbackClass(p)); } });
        map.put(Operation.CLASS_METHODS,         new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.classMethods(p)); } });
        map.put(Operation.PATCH_METHOD,          new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.patchMethod(p)); } });
        map.put(Operation.APPLY_CLASS_BYTES,     new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.applyClassBytes(p)); } });
        map.put(Operation.TRACE_START,           new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.startTrace(p)); } });
        map.put(Operation.TRACE_STOP,            new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.stopTrace(p)); } });
        map.put(Operation.TRACE_EVENTS,          new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.traceEvents()); } });
        map.put(Operation.TRACE_GRAPH,           new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.traceGraph()); } });
        map.put(Operation.TRACE_GRAPH_CLEAR,     new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.clearTraceGraph()); } });
        map.put(Operation.METHOD_XREFS,          new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.methodXrefs(p)); } });
        map.put(Operation.XREF_SEARCH,           new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.xrefSearch(p)); } });
        map.put(Operation.DEOBFUSCATION_INVENTORY, new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.deobfuscationInventory(p)); } });
        map.put(Operation.API_HOOK_START,        new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.startApiHooks(p)); } });
        map.put(Operation.API_HOOK_STOP,         new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.stopApiHooks()); } });
        map.put(Operation.API_HOOK_EVENTS,       new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.apiHookEvents()); } });
        map.put(Operation.HEAP_SCAN,             new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.heapScan(p)); } });
        map.put(Operation.HEAP_OBJECT,           new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.heapObject(p)); } });
        map.put(Operation.HEAP_DUMP,             new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.heapDump(p)); } });
        map.put(Operation.CFG_ANALYZE,           new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.bytecodeCfg(p)); } });
        map.put(Operation.CFG_TRACE_START,       new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.startCfgTrace(p)); } });
        map.put(Operation.CFG_TRACE_STOP,        new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.stopCfgTrace(p)); } });
        map.put(Operation.CFG_SNAPSHOT,          new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.cfgSnapshot(p)); } });
        map.put(Operation.FIELD_WRITE_SITES,     new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.fieldWriteSites(p)); } });
        map.put(Operation.FIELD_TRACE_START,     new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.startFieldTrace(p)); } });
        map.put(Operation.FIELD_TRACE_STOP,      new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.stopFieldTrace()); } });
        map.put(Operation.FIELD_TRACE_EVENTS,    new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.fieldTraceEvents()); } });
        map.put(Operation.FILE_MONITOR_START,    new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.startFileMonitor(p)); } });
        map.put(Operation.FILE_MONITOR_STOP,     new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.stopFileMonitor()); } });
        map.put(Operation.FILE_EVENT_BATCH,      new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.fileEvents()); } });
        map.put(Operation.FILE_EVENTS_CLEAR,     new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.clearFileEvents()); } });
        AgentCommand putFileRule = new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.putFileRule(p)); } };
        map.put(Operation.FILE_RULE_ADD,         putFileRule);
        map.put(Operation.FILE_RULE_UPDATE,      putFileRule);
        map.put(Operation.FILE_RULE_REMOVE,      new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.removeFileRule(p)); } });
        map.put(Operation.FILE_RULE_LIST,        new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.fileRules()); } });
        map.put(Operation.FILE_POLICY_SET,       new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.setFilePolicy(p)); } });
        map.put(Operation.FILE_POLICY_GET,       new AgentCommand() { public byte[] execute(String p) throws Exception { return WireProtocol.utf8(inspector.filePolicy()); } });
        map.put(Operation.DISCONNECT,            new AgentCommand() { public byte[] execute(String p) { return WireProtocol.utf8("disconnected"); } });

        return map;
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
        AgentCommand command = commands.get(request.operation());
        if (command == null) throw new IllegalArgumentException("Unsupported operation: " + request.operation());
        return command.execute(WireProtocol.text(request.payload()));
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

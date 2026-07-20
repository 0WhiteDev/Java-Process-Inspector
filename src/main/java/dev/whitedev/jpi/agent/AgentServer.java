package dev.whitedev.jpi.agent;

import dev.whitedev.jpi.agent.patch.SourceExecutor;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.protocol.WireProtocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.Socket;

final class AgentServer implements Runnable {
    private final AgentOptions options;
    private final TargetInspector inspector;
    private volatile Socket socket;
    private volatile boolean closed;

    AgentServer(AgentOptions options, Instrumentation instrumentation, ClassRegistry classRegistry) {
        this.options = options;
        this.inspector = new TargetInspector(instrumentation, classRegistry);
    }

    @Override
    public void run() {
        try {
            Socket connection = new Socket(options.host(), options.port());
            connection.setTcpNoDelay(true);
            socket = connection;
            DataOutputStream output = new DataOutputStream(connection.getOutputStream());
            DataInputStream input = new DataInputStream(connection.getInputStream());
            output.writeUTF(options.token());
            output.flush();
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

    private byte[] dispatch(WireProtocol.Request request) throws Exception {
        String payload = WireProtocol.text(request.payload());
        switch (request.operation()) {
            case PING: return WireProtocol.utf8("pong");
            case METRICS: return WireProtocol.utf8(inspector.metrics());
            case CLASSES: return WireProtocol.utf8(inspector.loadedClasses());
            case CLASS_BYTES: return inspector.classBytes(payload);
            case EXECUTE: return WireProtocol.utf8(SourceExecutor.execute(payload));
            case FIELDS: return WireProtocol.utf8(inspector.staticFields(payload));
            case THREAD_DUMP: return WireProtocol.utf8(inspector.threadDump());
            case CLASS_EVENTS: return WireProtocol.utf8(inspector.classEvents());
            case ENVIRONMENT: return WireProtocol.utf8(inspector.environment());
            case CONSTANT_SEARCH: return WireProtocol.utf8(inspector.constantSearch(payload));
            case REDEFINE_SOURCE: return WireProtocol.utf8(inspector.redefineSource(payload));
            case ROLLBACK_CLASS: return WireProtocol.utf8(inspector.rollbackClass(payload));
            case CLASS_METHODS: return WireProtocol.utf8(inspector.classMethods(payload));
            case PATCH_METHOD: return WireProtocol.utf8(inspector.patchMethod(payload));
            case APPLY_CLASS_BYTES: return WireProtocol.utf8(inspector.applyClassBytes(payload));
            case TRACE_START: return WireProtocol.utf8(inspector.startTrace(payload));
            case TRACE_STOP: return WireProtocol.utf8(inspector.stopTrace(payload));
            case TRACE_EVENTS: return WireProtocol.utf8(inspector.traceEvents());
            case METHOD_XREFS: return WireProtocol.utf8(inspector.methodXrefs(payload));
            case XREF_SEARCH: return WireProtocol.utf8(inspector.xrefSearch(payload));
            case DEOBFUSCATION_INVENTORY: return WireProtocol.utf8(inspector.deobfuscationInventory(payload));
            case API_HOOK_START: return WireProtocol.utf8(inspector.startApiHooks(payload));
            case API_HOOK_STOP: return WireProtocol.utf8(inspector.stopApiHooks());
            case API_HOOK_EVENTS: return WireProtocol.utf8(inspector.apiHookEvents());
            case HEAP_SCAN: return WireProtocol.utf8(inspector.heapScan(payload));
            case HEAP_OBJECT: return WireProtocol.utf8(inspector.heapObject(payload));
            case HEAP_DUMP: return WireProtocol.utf8(inspector.heapDump(payload));
            case CFG_ANALYZE: return WireProtocol.utf8(inspector.bytecodeCfg(payload));
            case CFG_TRACE_START: return WireProtocol.utf8(inspector.startCfgTrace(payload));
            case CFG_TRACE_STOP: return WireProtocol.utf8(inspector.stopCfgTrace(payload));
            case CFG_SNAPSHOT: return WireProtocol.utf8(inspector.cfgSnapshot(payload));
            case DISCONNECT: return WireProtocol.utf8("disconnected");
            default: throw new IllegalArgumentException("Unsupported operation: " + request.operation());
        }
    }

    private String errorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getName() + (message == null ? "" : ": " + message);
    }

    void close() {
        if (closed) return;
        closed = true;
        inspector.close();
        Socket current = socket;
        socket = null;
        if (current != null) try { current.close(); } catch (IOException ignored) {}
    }
}

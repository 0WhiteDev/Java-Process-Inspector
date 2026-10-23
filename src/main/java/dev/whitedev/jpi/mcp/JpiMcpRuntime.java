package dev.whitedev.jpi.mcp;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

public final class JpiMcpRuntime implements AutoCloseable {
    private final McpSessionState sessions = new McpSessionState();
    private final McpAuditLog audit;
    private volatile JpiMcpServer server;

    public JpiMcpRuntime() {
        this(Path.of(System.getProperty("user.home"), ".jpi", "mcp"));
    }

    public JpiMcpRuntime(Path auditDirectory) { audit = new McpAuditLog(auditDirectory); }
    public McpSessionState sessions() { return sessions; }
    public McpAuditLog audit() { return audit; }
    public boolean running() { return server != null && server.running(); }

    public void serve(InputStream input, OutputStream output) {
        synchronized (this) {
            if (server != null) throw new IllegalStateException("MCP transport is already configured");
            server = new JpiMcpServer(input, output, sessions, audit);
        }
        server.run();
    }

    @Override public void close() {
        JpiMcpServer current = server;
        if (current != null) current.close();
    }
}

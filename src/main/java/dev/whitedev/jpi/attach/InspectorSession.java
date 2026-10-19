package dev.whitedev.jpi.attach;

import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.protocol.WireProtocol;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

public final class InspectorSession implements Closeable {
    private final JvmDescriptor target;
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private boolean closed;
    private final boolean tunneled;

    InspectorSession(JvmDescriptor target, Socket socket) throws IOException {
        this(target, socket, false);
    }

    InspectorSession(JvmDescriptor target, Socket socket, boolean tunneled) throws IOException {
        this.tunneled = tunneled;
        this.target = target;
        this.socket = socket;
        this.input = new DataInputStream(socket.getInputStream());
        this.output = new DataOutputStream(socket.getOutputStream());
    }

    public JvmDescriptor target() { return target; }
    public boolean isTunneled() { return tunneled; }

    public synchronized byte[] request(Operation operation, String payload) throws IOException {
        ensureOpen();
        WireProtocol.Response response;
        try {
            WireProtocol.writeRequest(output, operation, WireProtocol.utf8(payload == null ? "" : payload));
            response = WireProtocol.readResponse(input);
        } catch (IOException error) {
            closed = true;
            try { socket.close(); } catch (IOException ignored) { }
            throw error;
        }
        if (!response.success()) throw new IOException(response.text());
        return response.payload();
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Inspector session is closed");
    }

    public String requestText(Operation operation, String payload) throws IOException {
        return WireProtocol.text(request(operation, payload));
    }

    @Override public synchronized void close() {
        if (closed) return;
        try { WireProtocol.writeRequest(output, Operation.DISCONNECT, new byte[0]); } catch (IOException ignored) {}
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
    }
}

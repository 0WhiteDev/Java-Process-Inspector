package dev.whitedev.jpi.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class WireProtocol {
    public static final int MAGIC = 0x4A504932;
    public static final short VERSION = 1;
    public static final int MAX_PAYLOAD = 32 * 1024 * 1024;

    private WireProtocol() {}

    public static void writeRequest(DataOutputStream out, Operation operation, byte[] payload) throws IOException {
        writeHeader(out, operation.ordinal(), payload);
    }

    public static Request readRequest(DataInputStream in) throws IOException {
        validateHeader(in);
        Operation operation = Operation.fromCode(in.readUnsignedByte());
        return new Request(operation, readPayload(in));
    }

    public static void writeResponse(DataOutputStream out, boolean success, byte[] payload) throws IOException {
        out.writeInt(MAGIC);
        out.writeShort(VERSION);
        out.writeBoolean(success);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    public static Response readResponse(DataInputStream in) throws IOException {
        validateHeader(in);
        boolean success = in.readBoolean();
        return new Response(success, readPayload(in));
    }

    private static void writeHeader(DataOutputStream out, int operation, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) throw new IOException("Payload is too large");
        out.writeInt(MAGIC);
        out.writeShort(VERSION);
        out.writeByte(operation);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    private static void validateHeader(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("Invalid JPI protocol magic");
        short version = in.readShort();
        if (version != VERSION) throw new IOException("Unsupported JPI protocol version: " + version);
    }

    private static byte[] readPayload(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_PAYLOAD) throw new IOException("Invalid payload length: " + length);
        byte[] payload = new byte[length];
        in.readFully(payload);
        return payload;
    }

    public static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    public static String text(byte[] value) { return new String(value, StandardCharsets.UTF_8); }

    public static final class Request {
        private final Operation operation;
        private final byte[] payload;
        public Request(Operation operation, byte[] payload) { this.operation = operation; this.payload = payload; }
        public Operation operation() { return operation; }
        public byte[] payload() { return payload; }
    }

    public static final class Response {
        private final boolean success;
        private final byte[] payload;
        public Response(boolean success, byte[] payload) { this.success = success; this.payload = payload; }
        public boolean success() { return success; }
        public byte[] payload() { return payload; }
        public String text() { return WireProtocol.text(payload); }
    }
}

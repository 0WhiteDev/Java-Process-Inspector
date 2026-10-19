package dev.whitedev.jpi.agent.jvmti;

import java.io.IOException;
import java.util.Base64;

public final class RawMemoryCommand {
    private RawMemoryCommand() {}

    public static byte[] read(String payload) throws IOException {
        String[] fields = fields(payload, 2);
        try {
            return NativeJvmtiBridge.readMemory(address(fields[0]), Integer.parseInt(fields[1]));
        } catch (IllegalArgumentException error) { throw new IOException("Invalid memory read request", error); }
    }

    public static String patch(String payload) throws IOException {
        String[] fields = fields(payload, 3);
        try {
            byte[] expected = Base64.getDecoder().decode(fields[1]);
            byte[] replacement = Base64.getDecoder().decode(fields[2]);
            NativeJvmtiBridge.patchMemory(address(fields[0]), expected, replacement);
            return "Patched " + replacement.length + " bytes\n";
        } catch (IllegalArgumentException error) { throw new IOException("Invalid memory patch request", error); }
    }

    private static String[] fields(String payload, int count) throws IOException {
        if (payload == null || payload.length() > 2800000) throw new IOException("Memory request too large");
        String[] fields = payload.split("\n", -1);
        if (fields.length != count) throw new IOException("Invalid memory request field count");
        return fields;
    }

    private static long address(String value) {
        if (!value.matches("(?:0[xX])?[0-9a-fA-F]{1,16}")) throw new IllegalArgumentException("Expected hexadecimal address");
        return Long.parseUnsignedLong(value.replaceFirst("^0[xX]", ""), 16);
    }
}

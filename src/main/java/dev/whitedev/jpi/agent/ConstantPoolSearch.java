package dev.whitedev.jpi.agent;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ConstantPoolSearch {
    private static final int CLASS_MAGIC = 0xCAFEBABE;
    private static final int MAX_VALUE_LENGTH = 4_096;

    private ConstantPoolSearch() { }

    static List<String> find(byte[] bytecode, String query, int limit) throws IOException {
        String needle = query.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytecode))) {
            if (input.readInt() != CLASS_MAGIC) throw new IOException("Invalid class-file magic");
            input.readUnsignedShort();
            input.readUnsignedShort();
            int count = input.readUnsignedShort();
            for (int index = 1; index < count; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1: {
                        String value = input.readUTF();
                        if (value.toLowerCase(Locale.ROOT).contains(needle)) {
                            matches.add(normalize(value));
                            if (matches.size() >= limit) return matches;
                        }
                        break;
                    }
                    case 3:
                    case 4:
                        skipFully(input, 4);
                        break;
                    case 5:
                    case 6:
                        skipFully(input, 8);
                        index++;
                        break;
                    case 7:
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        skipFully(input, 2);
                        break;
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        skipFully(input, 4);
                        break;
                    case 15:
                        skipFully(input, 3);
                        break;
                    default:
                        throw new IOException("Unsupported constant-pool tag " + tag);
                }
            }
        }
        return matches;
    }

    private static String normalize(String value) {
        String normalized = value.replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
        return normalized.length() <= MAX_VALUE_LENGTH ? normalized
                : normalized.substring(0, MAX_VALUE_LENGTH) + "...";
    }

    private static void skipFully(DataInputStream input, long length) throws IOException {
        while (length > 0) {
            long skipped = input.skip(length);
            if (skipped > 0) {
                length -= skipped;
            } else if (input.read() < 0) {
                throw new IOException("Unexpected end of class file");
            } else {
                length--;
            }
        }
    }
}

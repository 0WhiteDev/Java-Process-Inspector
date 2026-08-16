package dev.whitedev.jpi.symbols.parse;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class BinaryData {
    private static final long MAX_FILE_SIZE = 512L * 1024L * 1024L;

    final byte[] value;
    final ByteOrder order;

    private BinaryData(byte[] value, ByteOrder order) {
        this.value = value;
        this.order = order;
    }

    static BinaryData read(Path path, ByteOrder order) throws IOException {
        long size = Files.size(path);
        if (size > MAX_FILE_SIZE) throw new IOException("Symbol input exceeds the 512 MiB safety limit: " + path);
        return new BinaryData(Files.readAllBytes(path), order);
    }

    BinaryData order(ByteOrder value) {
        return new BinaryData(this.value, value);
    }

    int u8(long offset) throws IOException {
        range(offset, 1);
        return value[(int) offset] & 0xff;
    }

    int u16(long offset) throws IOException {
        range(offset, 2);
        int first = u8(offset);
        int second = u8(offset + 1);
        return order == ByteOrder.LITTLE_ENDIAN ? first | second << 8 : first << 8 | second;
    }

    int i16(long offset) throws IOException {
        return (short) u16(offset);
    }

    long u32(long offset) throws IOException {
        range(offset, 4);
        long result = 0L;
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int index = 3; index >= 0; index--) result = result << 8 | u8(offset + index);
        } else {
            for (int index = 0; index < 4; index++) result = result << 8 | u8(offset + index);
        }
        return result;
    }

    long i32(long offset) throws IOException {
        return (int) u32(offset);
    }

    long u64(long offset) throws IOException {
        range(offset, 8);
        long result = 0L;
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int index = 7; index >= 0; index--) result = result << 8 | u8(offset + index);
        } else {
            for (int index = 0; index < 8; index++) result = result << 8 | u8(offset + index);
        }
        return result;
    }

    String ascii(long offset, int length) throws IOException {
        range(offset, length);
        int end = 0;
        while (end < length && value[(int) offset + end] != 0) end++;
        return new String(value, (int) offset, end, StandardCharsets.ISO_8859_1).trim();
    }

    String asciiZ(long offset, int maximum) throws IOException {
        range(offset, 1);
        int end = (int) offset;
        int limit = Math.min(value.length, end + maximum);
        while (end < limit && value[end] != 0) end++;
        return new String(value, (int) offset, end - (int) offset, StandardCharsets.UTF_8);
    }

    boolean startsWith(long offset, byte[] expected) {
        if (offset < 0 || offset + expected.length > value.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if (value[(int) offset + index] != expected[index]) return false;
        }
        return true;
    }

    boolean contains(long offset, long length) {
        return offset >= 0 && length >= 0 && offset <= value.length && length <= value.length - offset;
    }

    private void range(long offset, long length) throws IOException {
        if (!contains(offset, length)) throw new IOException("Malformed binary structure at file offset 0x"
                + Long.toHexString(offset));
    }
}

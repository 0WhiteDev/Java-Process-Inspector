package dev.whitedev.jpi.nativeaccess;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public enum MemoryDataType {
    STRING, INT32, INT64, FLOAT, DOUBLE, INT16;

    public byte[] encode(String value) {
        switch (this) {
            case STRING: return value.getBytes(StandardCharsets.UTF_8);
            case INT16: return buffer(2).putShort(Short.parseShort(value)).array();
            case INT32: return buffer(4).putInt(Integer.parseInt(value)).array();
            case INT64: return buffer(8).putLong(Long.parseLong(value)).array();
            case FLOAT: return buffer(4).putFloat(Float.parseFloat(value)).array();
            case DOUBLE: return buffer(8).putDouble(Double.parseDouble(value)).array();
            default: throw new IllegalStateException(name());
        }
    }

    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
}

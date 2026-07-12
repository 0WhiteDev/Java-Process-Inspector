package dev.whitedev.jpi.nativeaccess;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class MemoryDataTypeTest {
    @Test void encodesNumbersUsingWindowsLittleEndianLayout() {
        byte[] value = MemoryDataType.INT32.encode("305419896");
        assertArrayEquals(new byte[]{0x78, 0x56, 0x34, 0x12}, value);
    }

    @Test void usesEightBytesForJavaLong() {
        byte[] value = MemoryDataType.INT64.encode("42");
        assertEquals(8, value.length);
        assertEquals(42L, ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getLong());
    }
}

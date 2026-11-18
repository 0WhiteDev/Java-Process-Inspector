package dev.whitedev.jpi.integration;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;


public final class RawMemoryFixture {
    private static final ByteBuffer buffer = ByteBuffer.allocateDirect(16);

    private RawMemoryFixture() {
    }

    public static long address() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        long offset = ((Number) unsafeClass.getMethod("objectFieldOffset", Field.class)
                .invoke(unsafe, Buffer.class.getDeclaredField("address"))).longValue();
        return ((Number) unsafeClass.getMethod("getLong", Object.class, long.class).invoke(unsafe, buffer, offset)).longValue();
    }
}

package dev.whitedev.jpi.integration;

public final class RawMemoryFixture {
    private static final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(16);
    private RawMemoryFixture() {}
    public static long address() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        long offset = ((Number) unsafeClass.getMethod("objectFieldOffset", java.lang.reflect.Field.class)
                .invoke(unsafe, java.nio.Buffer.class.getDeclaredField("address"))).longValue();
        return ((Number) unsafeClass.getMethod("getLong", Object.class, long.class).invoke(unsafe, buffer, offset)).longValue();
    }
}

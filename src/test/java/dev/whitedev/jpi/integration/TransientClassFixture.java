package dev.whitedev.jpi.integration;

import java.util.Base64;

public final class TransientClassFixture {
    private TransientClassFixture() {}

    public static String load(String encoded, int key) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        return define(bytes, key);
    }

    public static String loadFile(String encodedPath) throws Exception {
        String path = new String(Base64.getDecoder().decode(encodedPath), java.nio.charset.StandardCharsets.UTF_8);
        return define(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)), 0);
    }

    private static String define(byte[] bytes, int key) throws Exception {
        for (int i = 0; i < bytes.length; i++) bytes[i] ^= (byte) key;
        Class<?> type = new ClassLoader(TransientClassFixture.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        return (String) type.getMethod("value").invoke(null);
    }
}

package dev.whitedev.jpi.agent.hook;

import java.security.MessageDigest;

public final class ApiHookFixture {
    private ApiHookFixture() {}

    public static byte[] digest(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }
}

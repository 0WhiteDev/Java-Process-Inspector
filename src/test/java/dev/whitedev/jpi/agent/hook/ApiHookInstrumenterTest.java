package dev.whitedev.jpi.agent.hook;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiHookInstrumenterTest {
    @AfterEach
    void clearRuntime() {
        ApiHookRuntime.clear();
    }

    @Test
    void recordsTheExactApplicationCallerWithoutCapturingArguments() throws Exception {
        byte[] source = bytes(ApiHookFixture.class);
        ApiHookInstrumenter.Result result = ApiHookInstrumenter.instrument(
                source, EnumSet.of(ApiHookProfile.CRYPTO));
        ApiHookConfig config = ApiHookConfig.parse("maxEvents=20;rateLimit=20;stopAfterSeconds=30");
        ApiHookRuntime.configure(EnumSet.of(ApiHookProfile.CRYPTO), config, 1, result.sites);

        assertEquals(1, result.sites);
        Class<?> instrumented = new FixtureLoader().define(ApiHookFixture.class.getName(), result.bytecode);
        Method digest = instrumented.getMethod("digest", byte[].class);
        byte[] actual = (byte[]) digest.invoke(null, new Object[]{"JPI".getBytes(StandardCharsets.UTF_8)});
        byte[] expected = ApiHookFixture.digest("JPI".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals(expected, actual);
        String events = ApiHookRuntime.statusAndDrain();
        assertTrue(events.contains("S\tCRYPTO\ttrue\t1\t1\t0"));
        assertTrue(events.contains("\tCRYPTO\t"));
        assertTrue(events.contains(encoded(ApiHookFixture.class.getName())));
        assertTrue(events.contains(encoded("digest")));
        assertTrue(events.contains(encoded("java.security.MessageDigest")));
    }

    private static byte[] bytes(Class<?> type) throws Exception {
        InputStream input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        input.close();
        return output.toByteArray();
    }

    private static String encoded(String value) {
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader() {
            super(ApiHookInstrumenterTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}

package dev.whitedev.jpi.agent.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceInstrumenterTest {
    @AfterEach void resetRuntime() {
        TraceRuntime.clear();
    }

    @Test void capturesReturnsArgumentsAndUncaughtExceptions() throws Exception {
        TraceConfig config = TraceConfig.parse("maxEvents=10\nrateLimit=100\nstopAfterMillis=60000\ncondition=");
        TraceProbe success = new TraceProbe("trace-success", "fixture", TraceFixture.class.getName(),
                "combine", "(Ljava/lang/String;I)Ljava/lang/String;", config);
        TraceProbe failure = new TraceProbe("trace-failure", "fixture", TraceFixture.class.getName(),
                "fail", "(Ljava/lang/String;)Ljava/lang/String;", config);
        TraceRuntime.register(success);
        TraceRuntime.register(failure);

        FixtureLoader loader = new FixtureLoader(getClass().getClassLoader());
        byte[] instrumented = TraceInstrumenter.instrument(fixtureBytes(), loader, Arrays.asList(success, failure));
        Class<?> fixtureType = loader.define(TraceFixture.class.getName(), instrumented);
        java.lang.reflect.Constructor<?> constructor = fixtureType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object fixture = constructor.newInstance();

        Method combine = fixtureType.getDeclaredMethod("combine", String.class, int.class);
        combine.setAccessible(true);
        assertEquals("token:3", combine.invoke(fixture, "token", 3));

        Method fail = fixtureType.getDeclaredMethod("fail", String.class);
        fail.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> fail.invoke(fixture, "expected"));
        assertTrue(thrown.getCause() instanceof IllegalStateException);

        String output = TraceRuntime.statusAndDrain();
        assertTrue(output.contains("\treturn\t"));
        assertTrue(output.contains("\tthrow\t"));
        assertTrue(output.contains(encoded("$1 = \"token\"")));
        assertTrue(output.contains(encoded("java.lang.IllegalStateException: expected")));

        String successGraph = TraceRuntime.dynamicGraph(TraceFixture.class.getName(),
                "combine", "(Ljava/lang/String;I)Ljava/lang/String;");
        assertTrue(successGraph.contains("R\tDYNAMIC\tCALLS\t"));
        String failureGraph = TraceRuntime.dynamicGraph(TraceFixture.class.getName(),
                "fail", "(Ljava/lang/String;)Ljava/lang/String;");
        assertTrue(failureGraph.contains("R\tFAILED\tCALLS\t"));
    }

    private byte[] fixtureBytes() throws Exception {
        String resource = "/" + TraceFixture.class.getName().replace('.', '/') + ".class";
        try (InputStream input = TraceFixture.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Trace fixture bytecode is unavailable");
            return input.readAllBytes();
        }
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
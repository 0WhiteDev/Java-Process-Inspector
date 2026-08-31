package dev.whitedev.jpi.agent.field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldWriteInstrumenterTest {
    @AfterEach void clearRuntime() {
        FieldWriteRuntime.clear();
    }

    @Test void capturesStaticValueBeforeAndAfterTheWrite() throws Exception {
        String output = execute("licensed", "Z", "setLicensed", new Class<?>[]{boolean.class}, new Object[]{true});

        assertTrue(output.contains(encoded("false")));
        assertTrue(output.contains(encoded("true")));
        assertTrue(output.contains("PUTSTATIC"));
    }

    @Test void capturesInstanceIdentityAndValueTransition() throws Exception {
        String output = execute("state", "I", "setState", new Class<?>[]{int.class}, new Object[]{7});

        assertTrue(output.contains(encoded("0")));
        assertTrue(output.contains(encoded("7")));
        assertTrue(output.contains("PUTFIELD"));
        assertTrue(receiver(output).startsWith(Fixture.class.getName() + "@"));
    }

    private String execute(String field, String descriptor, String methodName,
                           Class<?>[] parameters, Object[] arguments) throws Exception {
        FieldWriteConfig config = FieldWriteConfig.parse("maxEvents=10;rateLimit=10;stopAfterSeconds=10");
        FieldWriteProbe probe = new FieldWriteProbe("probe", Fixture.class.getName(), Fixture.class.getName(),
                field, descriptor, config);
        FieldWriteRuntime.register(probe);
        byte[] original = bytes(Fixture.class);
        FieldWriteInstrumenter.Result result = FieldWriteInstrumenter.instrument(
                original, Fixture.class.getClassLoader(), probe);
        assertEquals(1, result.sites);
        FixtureLoader loader = new FixtureLoader(getClass().getClassLoader());
        Class<?> transformed = loader.define(Fixture.class.getName(), result.bytecode);
        Constructor<?> constructor = transformed.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object receiver = constructor.newInstance();
        Method method = transformed.getDeclaredMethod(methodName, parameters);
        method.setAccessible(true);
        method.invoke(receiver, arguments);
        return FieldWriteRuntime.statusAndDrain();
    }

    private static byte[] bytes(Class<?> type) throws Exception {
        try (InputStream input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            return input.readAllBytes();
        }
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String receiver(String output) {
        for (String line : output.split("\\n")) {
            String[] values = line.split("\\t", -1);
            if (values.length == 18 && "E".equals(values[0])) {
                return new String(Base64.getDecoder().decode(values[14]), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    static final class Fixture {
        static boolean licensed;
        int state;

        static void setLicensed(boolean value) {
            licensed = value;
        }

        void setState(int value) {
            state = value;
        }
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

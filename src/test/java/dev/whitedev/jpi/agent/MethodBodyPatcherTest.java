package dev.whitedev.jpi.agent;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodBodyPatcherTest {
    @Test void replacesOneMethodWithoutChangingTheClassSchema() throws Exception {
        String resource = "/" + Fixture.class.getName().replace('.', '/') + ".class";
        byte[] original;
        try (InputStream input = Fixture.class.getResourceAsStream(resource)) {
            original = input.readAllBytes();
        }
        assertTrue(MethodBodyPatcher.methods(original, Fixture.class.getClassLoader()).contains("value\t()I"));
        byte[] replacement = MethodBodyPatcher.patch(original, Fixture.class.getClassLoader(),
                "value", "()I", "{ return this.seed + 6; }");
        ClassSchema.verifyCompatible(original, replacement);
        Class<?> patched = new BytecodeLoader().define(replacement);
        Object instance = patched.getDeclaredConstructor().newInstance();
        assertEquals(7, patched.getMethod("value").invoke(instance));
    }

    public static class Fixture {
        private int seed = 1;
        public int value() { return seed; }
    }

    private static final class BytecodeLoader extends ClassLoader {
        BytecodeLoader() { super(null); }
        Class<?> define(byte[] bytecode) { return defineClass(null, bytecode, 0, bytecode.length); }
    }
}

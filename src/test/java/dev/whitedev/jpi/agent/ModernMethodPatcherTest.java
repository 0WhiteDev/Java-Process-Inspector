package dev.whitedev.jpi.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModernMethodPatcherTest {
    private static final RuntimeJavaCompiler.ClassPath EMPTY = new RuntimeJavaCompiler.ClassPath() {
        public byte[] find(String binaryName) { return null; }
        public Collection<String> list(String packageName, boolean recurse) { return Collections.emptyList(); }
    };

    @Test void replacesAMethodContainingAnExistingLambda() throws Exception {
        byte[] original = fixtureBytes();
        byte[] replacement = ModernMethodPatcher.patch(original, Fixture.class, "value", "(I)I",
                "{ java.util.function.IntUnaryOperator operation = value -> value + 5; return operation.applyAsInt($1); }",
                EMPTY);
        ClassSchema.verifyCompatible(original, replacement);
        Class<?> patched = new BytecodeLoader().define(replacement);
        Object instance = patched.getDeclaredConstructor().newInstance();
        assertEquals(8, patched.getMethod("value", int.class).invoke(instance, 3));
    }

    @Test void rejectsAddingMoreLambdaImplementationsThanHotSwapCanPreserve() throws Exception {
        byte[] original = fixtureBytes();
        assertThrows(IOException.class, () -> ModernMethodPatcher.patch(
                original, Fixture.class, "value", "(I)I",
                "{ java.util.function.IntUnaryOperator a = value -> value + 1; "
                        + "java.util.function.IntUnaryOperator b = value -> value + 2; return b.applyAsInt(a.applyAsInt($1)); }",
                EMPTY));
    }

    private static byte[] fixtureBytes() throws Exception {
        String resource = "/" + Fixture.class.getName().replace('.', '/') + ".class";
        try (InputStream input = Fixture.class.getResourceAsStream(resource)) {
            return input.readAllBytes();
        }
    }

    public static class Fixture {
        public int value(int input) {
            IntUnaryOperator operation = value -> value + 1;
            return operation.applyAsInt(input);
        }
    }

    private static final class BytecodeLoader extends ClassLoader {
        BytecodeLoader() { super(null); }
        Class<?> define(byte[] bytecode) { return defineClass(null, bytecode, 0, bytecode.length); }
    }
}
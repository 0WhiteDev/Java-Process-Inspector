package dev.whitedev.jpi.agent;

import dev.whitedev.jpi.agent.ClassTransformationCoordinator.Layer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ClassTransformationCoordinatorTest {
    @Test
    void failedRedefinitionLeavesPreviousLayersAndLastRemovalSubmitsExactBase() throws Exception {
        Class<?> target = Fixture.class;
        byte[] original;
        try (InputStream input = target.getResourceAsStream("ClassTransformationCoordinatorTest$Fixture.class")) {
            assertNotNull(input);
            original = input.readAllBytes();
        }
        AtomicReference<byte[]> installed = new AtomicReference<>(original);
        AtomicBoolean reject = new AtomicBoolean();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Instrumentation.class}, (proxy, method, arguments) -> {
                    assertEquals("redefineClasses", method.getName());
                    if (reject.get()) throw new VerifyError("Test JVM rejected candidate");
                    ClassDefinition definition = ((ClassDefinition[]) arguments[0])[0];
                    assertSame(target, definition.getDefinitionClass());
                    installed.set(definition.getDefinitionClassFile());
                    return null;
                });
        ClassRegistry registry = new ClassRegistry();
        registry.captureIfAbsent(target, original);
        ClassTransformationCoordinator coordinator =
                new ClassTransformationCoordinator(RuntimeAccess.instrumentation(instrumentation), registry);
        coordinator.install(target, Layer.LIVE_TRACER, bytes -> mark(bytes, "|trace"));
        coordinator.install(target, Layer.CFG, bytes -> mark(bytes, "|cfg"));
        assertTrue(source(installed.get()).endsWith("|cfg|trace"));

        reject.set(true);
        IOException failure = assertThrows(IOException.class, () -> coordinator.remove(target, Layer.CFG));
        assertInstanceOf(VerifyError.class, failure.getCause());
        assertTrue(source(installed.get()).endsWith("|cfg|trace"));
        assertArrayEquals(original, coordinator.bytecodeFor(target));

        reject.set(false);
        coordinator.remove(target, Layer.LIVE_TRACER);
        assertTrue(source(installed.get()).endsWith("|cfg"));
        coordinator.remove(target, Layer.CFG);
        assertArrayEquals(original, installed.get());
    }

    private static byte[] mark(byte[] bytes, String value) {
        ClassNode type = read(bytes);
        type.sourceFile += value;
        ClassWriter writer = new ClassWriter(0);
        type.accept(writer);
        return writer.toByteArray();
    }

    private static String source(byte[] bytes) {
        return read(bytes).sourceFile;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode type = new ClassNode();
        new ClassReader(bytes).accept(type, 0);
        return type;
    }

    private static final class Fixture {
    }
}

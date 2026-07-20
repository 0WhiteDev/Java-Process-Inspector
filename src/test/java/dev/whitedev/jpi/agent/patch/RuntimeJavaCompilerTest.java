package dev.whitedev.jpi.agent.patch;

import org.junit.jupiter.api.Test;
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeJavaCompilerTest {
    @Test void embeddedCompilerSupportsLambdasWithoutTheTargetJdkCompiler() {
        RuntimeJavaCompiler.ClassPath empty = new RuntimeJavaCompiler.ClassPath() {
            public byte[] find(String binaryName) { return null; }
            public Collection<String> list(String packageName, boolean recurse) { return Collections.emptyList(); }
        };
        assertDoesNotThrow(() -> RuntimeJavaCompiler.compile(new EclipseCompiler(), "sample.EcjLambda",
                "package sample; public class EcjLambda { public int value(int input) { "
                        + "java.util.function.IntUnaryOperator operation = value -> value + 3; "
                        + "return operation.applyAsInt(input); } }", empty));
    }
    @Test void resolvesClassesProvidedByATargetClassLoaderIndex() throws Exception {
        byte[] dependency = RuntimeJavaCompiler.compile("external.Hidden",
                "package external; public class Hidden { public int value() { return 7; } }");
        RuntimeJavaCompiler.ClassPath classPath = new RuntimeJavaCompiler.ClassPath() {
            public byte[] find(String binaryName) {
                return "external.Hidden".equals(binaryName) ? dependency : null;
            }

            public Collection<String> list(String packageName, boolean recurse) {
                return "external".equals(packageName)
                        ? Collections.singletonList("external.Hidden") : Collections.<String>emptyList();
            }
        };
        assertDoesNotThrow(() -> RuntimeJavaCompiler.compile("sample.UsesHidden",
                "package sample; public class UsesHidden { public int value(external.Hidden input) { return input.value(); } }",
                classPath));
    }
    @Test void acceptsMethodBodyChangesAndRejectsStructuralChanges() throws Exception {
        byte[] original = RuntimeJavaCompiler.compile("sample.Editable", "package sample; public class Editable { public String value() { return \"before\"; } }");
        byte[] bodyChange = RuntimeJavaCompiler.compile("sample.Editable", "package sample; public class Editable { public String value() { return \"after\"; } }");
        byte[] structuralChange = RuntimeJavaCompiler.compile("sample.Editable", "package sample; public class Editable { public String value() { return \"after\"; } public int added() { return 1; } }");
        assertDoesNotThrow(() -> ClassSchema.verifyCompatible(original, bodyChange));
        assertThrows(IOException.class, () -> ClassSchema.verifyCompatible(original, structuralChange));
    }
}

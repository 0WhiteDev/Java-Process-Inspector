package dev.whitedev.jpi.decompile;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MethodSourceExtractorTest {
    @Test void selectsTheOverloadMatchingTheDescriptorParameterCount() {
        String source = "public class Example { private void b(String value) { call(value); } private void b(int left, int right) { if (left > right) { call(left); } } }";
        assertEquals("{ if ($1 > $2) { call($1); } }",
                MethodSourceExtractor.extract(source, "b", "(II)V"));
    }

    @Test void handlesNoArgumentMethodsAndBracesInsideStrings() {
        String source = "public class Example { boolean active() { String value = \"}\"; return value != null; } }";
        assertEquals("{ String value = \"}\"; return value != null; }",
                MethodSourceExtractor.extract(source, "active", "()Z"));
    }

    @Test void loadsTheCurrentImplementationFromCfrMethodMode() throws Exception {
        String resource = "/" + Fixture.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream input = Fixture.class.getResourceAsStream(resource)) {
            bytecode = input.readAllBytes();
        }
        String decompiled = new DecompilerService().decompileMethod(Fixture.class.getName(), "value", bytecode);
        String body = MethodSourceExtractor.extract(decompiled, "value", "(I)I");
        assertEquals("{\n    return $1 + 1;\n}", body);
    }

    static final class Fixture {
        int value(int input) { return input + 1; }
    }
}

package dev.whitedev.jpi.decompile;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DecompilerServiceTest {
    @Test void everyEmbeddedEngineDecompilesAClassAndMethod() throws Exception {
        String resource = "/" + Fixture.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream input = Fixture.class.getResourceAsStream(resource)) {
            bytecode = input.readAllBytes();
        }
        DecompilerService service = new DecompilerService();
        for (DecompilerEngine engine : DecompilerEngine.values()) {
            String source = service.decompile(engine, Fixture.class.getName(), bytecode);
            assertTrue(source.contains("value"), engine + " did not return the method");
            String method = service.decompileMethod(engine, Fixture.class.getName(), "value", "(I)I", bytecode);
            String body = MethodSourceExtractor.extract(method, "value", "(I)I");
            assertTrue(body.contains("+ 2"), engine + " returned an unexpected method body");
        }
    }

    static final class Fixture {
        int value(int input) {
            return input + 2;
        }
    }
}
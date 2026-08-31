package dev.whitedev.jpi.agent.analysis;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldWriteAnalyzerTest {
    @Test void findsStaticAndInstanceWritesWithWriterMethods() throws Exception {
        byte[] bytecode = bytes(Fixture.class);
        String owner = Type.getInternalName(Fixture.class);

        List<FieldWriteAnalyzer.WriteSite> staticWrites = FieldWriteAnalyzer.find(
                bytecode, "fixture", Fixture.class.getName(), owner, "licensed", "Z");
        List<FieldWriteAnalyzer.WriteSite> instanceWrites = FieldWriteAnalyzer.find(
                bytecode, "fixture", Fixture.class.getName(), owner, "state", "I");

        assertEquals(1, staticWrites.size());
        assertEquals("setLicensed", staticWrites.get(0).methodName);
        assertEquals("PUTSTATIC", staticWrites.get(0).opcode);
        assertTrue(staticWrites.get(0).line > 0);
        assertEquals(1, instanceWrites.size());
        assertEquals("setState", instanceWrites.get(0).methodName);
        assertEquals("PUTFIELD", instanceWrites.get(0).opcode);
    }

    private static byte[] bytes(Class<?> type) throws Exception {
        try (InputStream input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            return input.readAllBytes();
        }
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
}

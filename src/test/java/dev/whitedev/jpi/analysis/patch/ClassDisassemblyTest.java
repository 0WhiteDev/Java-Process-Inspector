package dev.whitedev.jpi.analysis.patch;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

class ClassDisassemblyTest {
    @Test
    void exposesOpcodeAndConstantChangesWithoutDebugLineNoise() {
        String before = ClassDisassembly.render(fixture(Opcodes.ICONST_0, 10));
        String after = ClassDisassembly.render(fixture(Opcodes.ICONST_1, 100));
        String diff = LineDiff.unified(before, after, "Original", "Current", true);
        assertTrue(diff.contains("-    ICONST_0"));
        assertTrue(diff.contains("+    ICONST_1"));
        assertFalse(diff.contains("LINENUMBER"));
    }

    private static byte[] fixture(int opcode, int line) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "Fixture", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        method.visitCode();
        var label = new org.objectweb.asm.Label();
        method.visitLabel(label);
        method.visitLineNumber(line, label);
        method.visitInsn(opcode);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}

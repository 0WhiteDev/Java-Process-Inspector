package dev.whitedev.jpi.analysis.patch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;

public final class ClassDisassembly {
    private ClassDisassembly() {}

    public static String render(byte[] bytecode) {
        if (bytecode.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Bytecode disassembly exceeds the 2 MiB class limit");
        StringWriter text = new StringWriter();
        new ClassReader(bytecode).accept(new TraceClassVisitor(new PrintWriter(text)),
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return text.toString();
    }
}

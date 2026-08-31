package dev.whitedev.jpi.agent.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FieldWriteAnalyzer {
    private FieldWriteAnalyzer() { }

    public static List<WriteSite> find(byte[] bytecode, String classIdentifier, String className,
                                       String owner, String fieldName, String descriptor) throws IOException {
        try {
            ClassNode type = new ClassNode(Opcodes.ASM9);
            new ClassReader(bytecode).accept(type, ClassReader.SKIP_FRAMES);
            Map<String, WriteSite> sites = new LinkedHashMap<String, WriteSite>();
            for (MethodNode method : type.methods) {
                int line = -1;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof LineNumberNode) {
                        line = ((LineNumberNode) instruction).line;
                        continue;
                    }
                    if (!(instruction instanceof FieldInsnNode)) continue;
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    if (!owner.equals(field.owner) || !fieldName.equals(field.name)
                            || !descriptor.equals(field.desc) || !isWrite(field.getOpcode())) continue;
                    String opcode = field.getOpcode() == Opcodes.PUTSTATIC ? "PUTSTATIC" : "PUTFIELD";
                    String key = method.name + '\u0000' + method.desc + '\u0000' + line + '\u0000' + opcode;
                    WriteSite current = sites.get(key);
                    if (current == null) {
                        sites.put(key, new WriteSite(classIdentifier, className, method.name,
                                method.desc, line, opcode, 1));
                    } else {
                        current.count++;
                    }
                }
            }
            return new ArrayList<WriteSite>(sites.values());
        } catch (Throwable error) {
            throw new IOException("Could not analyze field writes", error);
        }
    }

    public static int count(byte[] bytecode, String owner, String fieldName, String descriptor) throws IOException {
        int count = 0;
        for (WriteSite site : find(bytecode, "", "", owner, fieldName, descriptor)) count += site.count;
        return count;
    }

    private static boolean isWrite(int opcode) {
        return opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
    }

    public static final class WriteSite {
        public final String classIdentifier;
        public final String className;
        public final String methodName;
        public final String methodDescriptor;
        public final int line;
        public final String opcode;
        public int count;

        WriteSite(String classIdentifier, String className, String methodName, String methodDescriptor,
                  int line, String opcode, int count) {
            this.classIdentifier = classIdentifier;
            this.className = className;
            this.methodName = methodName;
            this.methodDescriptor = methodDescriptor;
            this.line = line;
            this.opcode = opcode;
            this.count = count;
        }
    }
}

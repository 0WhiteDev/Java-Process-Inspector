package dev.whitedev.jpi.agent.field;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;

final class FieldWriteInstrumenter {
    private static final String RUNTIME = Type.getInternalName(FieldWriteRuntime.class);
    private static final String RECORD_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";

    private FieldWriteInstrumenter() { }

    static Result instrument(byte[] bytecode, ClassLoader loader, FieldWriteProbe probe) throws IOException {
        try {
            ClassReader reader = new ClassReader(bytecode);
            ClassNode type = new ClassNode(Opcodes.ASM9);
            reader.accept(type, ClassReader.EXPAND_FRAMES);
            int sites = 0;
            String owner = probe.owner.replace('.', '/');
            for (MethodNode method : type.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                int line = -1;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof LineNumberNode) {
                        line = ((LineNumberNode) instruction).line;
                        continue;
                    }
                    if (!(instruction instanceof FieldInsnNode)) continue;
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    int opcode = field.getOpcode();
                    if ((opcode != Opcodes.PUTFIELD && opcode != Opcodes.PUTSTATIC)
                            || !owner.equals(field.owner) || !probe.fieldName.equals(field.name)
                            || !probe.descriptor.equals(field.desc)) continue;
                    instrument(type, method, field, line, probe);
                    sites++;
                }
            }
            if (sites == 0) return new Result(bytecode, 0);
            ClassWriter writer = new LoaderClassWriter(reader,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
            type.accept(writer);
            return new Result(writer.toByteArray(), sites);
        } catch (Throwable error) {
            throw new IOException("Could not instrument field writes: " + message(error), error);
        }
    }

    private static void instrument(ClassNode type, MethodNode method, FieldInsnNode field,
                                   int line, FieldWriteProbe probe) {
        Type valueType = Type.getType(field.desc);
        int valueLocal = method.maxLocals;
        method.maxLocals += valueType.getSize();
        int previousLocal = method.maxLocals;
        method.maxLocals += valueType.getSize();
        int receiverLocal = -1;
        if (field.getOpcode() == Opcodes.PUTFIELD) receiverLocal = method.maxLocals++;

        InsnList before = new InsnList();
        before.add(new VarInsnNode(valueType.getOpcode(Opcodes.ISTORE), valueLocal));
        if (field.getOpcode() == Opcodes.PUTFIELD) {
            before.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
            before.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
            before.add(new FieldInsnNode(Opcodes.GETFIELD, field.owner, field.name, field.desc));
        } else {
            before.add(new FieldInsnNode(Opcodes.GETSTATIC, field.owner, field.name, field.desc));
        }
        before.add(new VarInsnNode(valueType.getOpcode(Opcodes.ISTORE), previousLocal));
        if (field.getOpcode() == Opcodes.PUTFIELD) before.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        before.add(new VarInsnNode(valueType.getOpcode(Opcodes.ILOAD), valueLocal));
        method.instructions.insertBefore(field, before);

        InsnList after = new InsnList();
        after.add(new LdcInsnNode(probe.id));
        after.add(new LdcInsnNode(type.name.replace('/', '.')));
        after.add(new LdcInsnNode(method.name));
        after.add(new LdcInsnNode(method.desc));
        after.add(new LdcInsnNode(field.getOpcode() == Opcodes.PUTSTATIC ? "PUTSTATIC" : "PUTFIELD"));
        after.add(new LdcInsnNode(Integer.valueOf(line)));
        if (receiverLocal < 0) after.add(new InsnNode(Opcodes.ACONST_NULL));
        else after.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        after.add(new VarInsnNode(valueType.getOpcode(Opcodes.ILOAD), previousLocal));
        box(after, valueType);
        after.add(new VarInsnNode(valueType.getOpcode(Opcodes.ILOAD), valueLocal));
        box(after, valueType);
        after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "record", RECORD_DESCRIPTOR, false));
        method.instructions.insert(field, after);
    }

    private static void box(InsnList output, Type type) {
        String owner;
        String descriptor;
        switch (type.getSort()) {
            case Type.BOOLEAN: owner = "java/lang/Boolean"; descriptor = "(Z)Ljava/lang/Boolean;"; break;
            case Type.BYTE: owner = "java/lang/Byte"; descriptor = "(B)Ljava/lang/Byte;"; break;
            case Type.CHAR: owner = "java/lang/Character"; descriptor = "(C)Ljava/lang/Character;"; break;
            case Type.SHORT: owner = "java/lang/Short"; descriptor = "(S)Ljava/lang/Short;"; break;
            case Type.INT: owner = "java/lang/Integer"; descriptor = "(I)Ljava/lang/Integer;"; break;
            case Type.FLOAT: owner = "java/lang/Float"; descriptor = "(F)Ljava/lang/Float;"; break;
            case Type.LONG: owner = "java/lang/Long"; descriptor = "(J)Ljava/lang/Long;"; break;
            case Type.DOUBLE: owner = "java/lang/Double"; descriptor = "(D)Ljava/lang/Double;"; break;
            default: return;
        }
        output.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", descriptor, false));
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    static final class Result {
        final byte[] bytecode;
        final int sites;

        Result(byte[] bytecode, int sites) {
            this.bytecode = bytecode;
            this.sites = sites;
        }
    }

    private static final class LoaderClassWriter extends ClassWriter {
        private final ClassLoader loader;

        LoaderClassWriter(ClassReader reader, int flags, ClassLoader loader) {
            super(reader, flags);
            this.loader = loader;
        }

        @Override protected String getCommonSuperClass(String left, String right) {
            try {
                Class<?> leftType = Class.forName(left.replace('/', '.'), false, loader);
                Class<?> rightType = Class.forName(right.replace('/', '.'), false, loader);
                if (leftType.isAssignableFrom(rightType)) return left;
                if (rightType.isAssignableFrom(leftType)) return right;
                if (leftType.isInterface() || rightType.isInterface()) return "java/lang/Object";
                do {
                    leftType = leftType.getSuperclass();
                } while (leftType != null && !leftType.isAssignableFrom(rightType));
                return leftType == null ? "java/lang/Object" : Type.getInternalName(leftType);
            } catch (Throwable ignored) {
                return "java/lang/Object";
            }
        }
    }
}

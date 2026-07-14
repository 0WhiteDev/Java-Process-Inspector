package dev.whitedev.jpi.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.util.Collection;

final class TraceInstrumenter {
    private static final String RUNTIME = Type.getInternalName(TraceRuntime.class);
    private static final String ENTER_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)J";
    private static final String EXIT_DESCRIPTOR = "(JLjava/lang/Object;)V";
    private static final String FAIL_DESCRIPTOR = "(JLjava/lang/Throwable;)V";
    private static final String EDGE_DESCRIPTOR = "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V";

    private TraceInstrumenter() {}

    static byte[] instrument(byte[] bytecode, ClassLoader loader, Collection<TraceProbe> probes) throws IOException {
        try {
            ClassReader reader = new ClassReader(bytecode);
            ClassNode type = new ClassNode(Opcodes.ASM9);
            reader.accept(type, ClassReader.EXPAND_FRAMES);
            for (TraceProbe probe : probes) instrument(type, probe);
            ClassWriter writer = new LoaderClassWriter(reader,
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
            type.accept(writer);
            return writer.toByteArray();
        } catch (IOException error) {
            throw error;
        } catch (Throwable error) {
            throw new IOException("Could not instrument trace target: " + message(error), error);
        }
    }

    private static void instrument(ClassNode type, TraceProbe probe) throws IOException {
        MethodNode selected = null;
        for (MethodNode method : type.methods) {
            if (method.name.equals(probe.methodName) && method.desc.equals(probe.descriptor)) {
                selected = method;
                break;
            }
        }
        if (selected == null) throw new IOException("Method is not present in the selected class: " + probe.methodKey());
        if ("<init>".equals(selected.name) || "<clinit>".equals(selected.name)) {
            throw new IOException("Constructors and class initializers cannot be traced");
        }
        if ((selected.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new IOException("Abstract and native methods cannot be traced");
        }
        if (selected.instructions == null || selected.instructions.size() == 0) {
            throw new IOException("Selected method has no executable bytecode");
        }

        AbstractInsnNode[] original = selected.instructions.toArray();
        Type returnType = Type.getReturnType(selected.desc);
        int tokenLocal = selected.maxLocals;
        selected.maxLocals += 2;
        int returnLocal = selected.maxLocals;
        if (returnType.getSort() != Type.VOID) selected.maxLocals += returnType.getSize();
        int exceptionLocal = selected.maxLocals++;

        LabelNode start = new LabelNode();
        InsnList entry = new InsnList();
        entry.add(new LdcInsnNode(probe.id));
        if ((selected.access & Opcodes.ACC_STATIC) == 0) entry.add(new VarInsnNode(Opcodes.ALOAD, 0));
        else entry.add(new InsnNode(Opcodes.ACONST_NULL));
        appendArguments(entry, selected);
        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "enter", ENTER_DESCRIPTOR, false));
        entry.add(new VarInsnNode(Opcodes.LSTORE, tokenLocal));
        entry.add(start);
        selected.instructions.insert(entry);

        for (AbstractInsnNode instruction : original) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                selected.instructions.insertBefore(instruction, edge(tokenLocal, call.owner, call.name,
                        call.desc, reflective(call.owner)));
            } else if (instruction instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode call = (InvokeDynamicInsnNode) instruction;
                selected.instructions.insertBefore(instruction, edge(tokenLocal, "<dynamic>", call.name,
                        call.desc, false));
            }
            int opcode = instruction.getOpcode();
            if (opcode == Opcodes.RETURN) {
                InsnList exit = new InsnList();
                exit.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
                exit.add(new InsnNode(Opcodes.ACONST_NULL));
                exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit", EXIT_DESCRIPTOR, false));
                selected.instructions.insertBefore(instruction, exit);
            } else if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) {
                InsnList exit = new InsnList();
                exit.add(new VarInsnNode(returnType.getOpcode(Opcodes.ISTORE), returnLocal));
                exit.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
                exit.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), returnLocal));
                box(exit, returnType);
                exit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "exit", EXIT_DESCRIPTOR, false));
                exit.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), returnLocal));
                selected.instructions.insertBefore(instruction, exit);
            }
        }

        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        selected.instructions.add(end);
        selected.instructions.add(handler);
        selected.instructions.add(new VarInsnNode(Opcodes.ASTORE, exceptionLocal));
        selected.instructions.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
        selected.instructions.add(new VarInsnNode(Opcodes.ALOAD, exceptionLocal));
        selected.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "fail", FAIL_DESCRIPTOR, false));
        selected.instructions.add(new VarInsnNode(Opcodes.ALOAD, exceptionLocal));
        selected.instructions.add(new InsnNode(Opcodes.ATHROW));
        selected.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
    }

    private static InsnList edge(int tokenLocal, String owner, String method,
                                 String descriptor, boolean reflective) {
        InsnList output = new InsnList();
        output.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
        output.add(new LdcInsnNode(owner));
        output.add(new LdcInsnNode(method));
        output.add(new LdcInsnNode(descriptor));
        output.add(new InsnNode(reflective ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        output.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "edge", EDGE_DESCRIPTOR, false));
        return output;
    }

    private static boolean reflective(String owner) {
        return owner.startsWith("java/lang/reflect/") || owner.startsWith("jdk/internal/reflect/")
                || owner.startsWith("java/lang/invoke/");
    }

    private static void appendArguments(InsnList output, MethodNode method) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        pushInt(output, arguments.length);
        output.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int local = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int index = 0; index < arguments.length; index++) {
            Type argument = arguments[index];
            output.add(new InsnNode(Opcodes.DUP));
            pushInt(output, index);
            output.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            box(output, argument);
            output.add(new InsnNode(Opcodes.AASTORE));
            local += argument.getSize();
        }
    }

    private static void box(InsnList output, Type type) {
        String owner;
        String descriptor;
        switch (type.getSort()) {
            case Type.BOOLEAN:
                owner = "java/lang/Boolean";
                descriptor = "(Z)Ljava/lang/Boolean;";
                break;
            case Type.BYTE:
                owner = "java/lang/Byte";
                descriptor = "(B)Ljava/lang/Byte;";
                break;
            case Type.CHAR:
                owner = "java/lang/Character";
                descriptor = "(C)Ljava/lang/Character;";
                break;
            case Type.SHORT:
                owner = "java/lang/Short";
                descriptor = "(S)Ljava/lang/Short;";
                break;
            case Type.INT:
                owner = "java/lang/Integer";
                descriptor = "(I)Ljava/lang/Integer;";
                break;
            case Type.FLOAT:
                owner = "java/lang/Float";
                descriptor = "(F)Ljava/lang/Float;";
                break;
            case Type.LONG:
                owner = "java/lang/Long";
                descriptor = "(J)Ljava/lang/Long;";
                break;
            case Type.DOUBLE:
                owner = "java/lang/Double";
                descriptor = "(D)Ljava/lang/Double;";
                break;
            default:
                return;
        }
        output.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", descriptor, false));
    }

    private static void pushInt(InsnList output, int value) {
        if (value >= -1 && value <= 5) output.add(new InsnNode(Opcodes.ICONST_0 + value));
        else if (value <= Byte.MAX_VALUE) output.add(new IntInsnNode(Opcodes.BIPUSH, value));
        else if (value <= Short.MAX_VALUE) output.add(new IntInsnNode(Opcodes.SIPUSH, value));
        else output.add(new LdcInsnNode(Integer.valueOf(value)));
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
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
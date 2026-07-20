package dev.whitedev.jpi.agent.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class XrefAnalyzer {
    private XrefAnalyzer() {}

    public static List<Reference> references(byte[] bytecode, String methodName, String descriptor) throws IOException {
        ClassNode type = read(bytecode);
        MethodNode selected = find(type, methodName, descriptor);
        if (selected == null) throw new IOException("Method is not present in the selected class: " + methodName + descriptor);
        Map<String, Reference> references = new LinkedHashMap<String, Reference>();
        for (AbstractInsnNode instruction : selected.instructions.toArray()) {
            Reference reference = reference(instruction);
            if (reference != null) add(references, reference);
        }
        return new ArrayList<Reference>(references.values());
    }

    public static List<Reference> callers(byte[] bytecode, String classIdentifier, String className,
                                   String owner, String methodName, String descriptor) throws IOException {
        ClassNode type = read(bytecode);
        Map<String, Reference> references = new LinkedHashMap<String, Reference>();
        for (MethodNode method : type.methods) {
            int count = 0;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (owner.equals(call.owner) && methodName.equals(call.name) && descriptor.equals(call.desc)) count++;
                }
            }
            if (count > 0) add(references, new Reference("CALLED_BY", classIdentifier, className,
                    method.name, method.desc, "invoke", count));
        }
        return new ArrayList<Reference>(references.values());
    }

    public static List<Reference> stringUsers(byte[] bytecode, String classIdentifier, String className,
                                       String query) throws IOException {
        ClassNode type = read(bytecode);
        String needle = query.toLowerCase(Locale.ROOT);
        Map<String, Reference> references = new LinkedHashMap<String, Reference>();
        for (MethodNode method : type.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof LdcInsnNode)) continue;
                Object value = ((LdcInsnNode) instruction).cst;
                if (value instanceof String && ((String) value).toLowerCase(Locale.ROOT).contains(needle)) {
                    add(references, new Reference("STRING_USER", classIdentifier, className,
                            method.name, method.desc, (String) value, 1));
                }
            }
        }
        return new ArrayList<Reference>(references.values());
    }

    private static Reference reference(AbstractInsnNode instruction) {
        if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            String owner = call.owner.replace('/', '.');
            return new Reference("CALLS", owner, owner, call.name, call.desc, opcode(call.getOpcode()), 1);
        }
        if (instruction instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode call = (InvokeDynamicInsnNode) instruction;
            Handle target = bootstrapTarget(call);
            if (target != null) {
                String owner = target.getOwner().replace('/', '.');
                return new Reference("CALLS", owner, owner, target.getName(), target.getDesc(), "INVOKEDYNAMIC", 1);
            }
            return new Reference("CALLS", "<dynamic>", "<dynamic>", call.name, call.desc, "INVOKEDYNAMIC", 1);
        }
        if (instruction instanceof FieldInsnNode) {
            FieldInsnNode field = (FieldInsnNode) instruction;
            String owner = field.owner.replace('/', '.');
            return new Reference("FIELD", owner, owner, field.name, field.desc, opcode(field.getOpcode()), 1);
        }
        if (instruction instanceof TypeInsnNode) {
            TypeInsnNode type = (TypeInsnNode) instruction;
            String name = type.desc.replace('/', '.');
            return new Reference("TYPE", name, name, "", "", opcode(type.getOpcode()), 1);
        }
        if (instruction instanceof LdcInsnNode) {
            Object constant = ((LdcInsnNode) instruction).cst;
            if (constant instanceof String) return new Reference("CONSTANT", "", "", "", "", (String) constant, 1);
            if (constant instanceof Type) {
                String name = ((Type) constant).getClassName();
                return new Reference("TYPE", name, name, "", "", "LDC", 1);
            }
        }
        return null;
    }

    private static Handle bootstrapTarget(InvokeDynamicInsnNode call) {
        if (call.bsmArgs != null) for (Object argument : call.bsmArgs) if (argument instanceof Handle) return (Handle) argument;
        return null;
    }

    private static ClassNode read(byte[] bytecode) throws IOException {
        try {
            ClassNode type = new ClassNode(Opcodes.ASM9);
            new ClassReader(bytecode).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return type;
        } catch (Throwable error) {
            throw new IOException("Could not analyze class bytecode", error);
        }
    }

    private static MethodNode find(ClassNode type, String name, String descriptor) {
        for (MethodNode method : type.methods) if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        return null;
    }

    private static void add(Map<String, Reference> references, Reference value) {
        String key = value.relation + '\u0000' + value.targetIdentifier + '\u0000' + value.className
                + '\u0000' + value.member + '\u0000' + value.descriptor + '\u0000' + value.detail;
        Reference existing = references.get(key);
        if (existing == null) references.put(key, value);
        else existing.count += value.count;
    }

    private static String opcode(int opcode) {
        switch (opcode) {
            case Opcodes.INVOKEVIRTUAL: return "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL: return "INVOKESPECIAL";
            case Opcodes.INVOKESTATIC: return "INVOKESTATIC";
            case Opcodes.INVOKEINTERFACE: return "INVOKEINTERFACE";
            case Opcodes.GETSTATIC: return "GETSTATIC";
            case Opcodes.PUTSTATIC: return "PUTSTATIC";
            case Opcodes.GETFIELD: return "GETFIELD";
            case Opcodes.PUTFIELD: return "PUTFIELD";
            case Opcodes.NEW: return "NEW";
            case Opcodes.ANEWARRAY: return "ANEWARRAY";
            case Opcodes.CHECKCAST: return "CHECKCAST";
            case Opcodes.INSTANCEOF: return "INSTANCEOF";
            default: return String.valueOf(opcode);
        }
    }

    public static final class Reference {
        public final String relation;
        public final String targetIdentifier;
        public final String className;
        public final String member;
        public final String descriptor;
        public final String detail;
        public int count;

        Reference(String relation, String targetIdentifier, String className, String member,
                  String descriptor, String detail, int count) {
            this.relation = relation;
            this.targetIdentifier = targetIdentifier;
            this.className = className;
            this.member = member;
            this.descriptor = descriptor;
            this.detail = detail;
            this.count = count;
        }
    }
}
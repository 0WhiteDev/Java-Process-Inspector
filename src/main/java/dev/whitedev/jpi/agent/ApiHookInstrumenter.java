package dev.whitedev.jpi.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;

final class ApiHookInstrumenter {
    private static final String RUNTIME = Type.getInternalName(ApiHookRuntime.class);
    private static final String HIT_DESCRIPTOR = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";

    private ApiHookInstrumenter() {}

    static Result instrument(byte[] source, Set<ApiHookProfile> profiles) {
        ClassReader reader = new ClassReader(source);
        ClassNode type = new ClassNode();
        reader.accept(type, 0);
        int sites = 0;
        for (MethodNode method : type.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
                AbstractInsnNode next = instruction.getNext();
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    List<ApiHookProfile> matches = ApiHookProfile.matching(profiles, call.owner, call.name);
                    for (ApiHookProfile profile : matches) {
                        method.instructions.insertBefore(call, hook(profile, type.name, method, call));
                        sites++;
                    }
                }
                instruction = next;
            }
        }
        if (sites == 0) return new Result(source, 0);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return new Result(writer.toByteArray(), sites);
    }

    private static InsnList hook(ApiHookProfile profile, String caller, MethodNode method, MethodInsnNode call) {
        InsnList instructions = new InsnList();
        instructions.add(new LdcInsnNode(profile.name()));
        instructions.add(new LdcInsnNode(caller.replace('/', '.')));
        instructions.add(new LdcInsnNode(method.name));
        instructions.add(new LdcInsnNode(method.desc));
        instructions.add(new LdcInsnNode(call.owner.replace('/', '.')));
        instructions.add(new LdcInsnNode(call.name));
        instructions.add(new LdcInsnNode(call.desc));
        instructions.add(new LdcInsnNode(call.getOpcode() == Opcodes.INVOKESTATIC ? "static" : "instance"));
        instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "hit", HIT_DESCRIPTOR, false));
        return instructions;
    }

    static final class Result {
        final byte[] bytecode;
        final int sites;

        Result(byte[] bytecode, int sites) {
            this.bytecode = bytecode;
            this.sites = sites;
        }
    }
}

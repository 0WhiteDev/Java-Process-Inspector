package dev.whitedev.jpi.agent.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class DeobfuscationInventory {
    private DeobfuscationInventory() {}

    public static int append(StringBuilder output, byte[] bytecode, int remaining) {
        if (remaining <= 0) return 0;
        try {
            ClassNode type = new ClassNode(Opcodes.ASM9);
            new ClassReader(bytecode).accept(type, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            String owner = type.name.replace('/', '.');
            output.append('C').append('\t').append(encoded(owner)).append('\t')
                    .append(type.access).append('\n');
            int written = 1;
            for (FieldNode field : type.fields) {
                if (written >= remaining) break;
                output.append('F').append('\t').append(encoded(owner)).append('\t')
                        .append(encoded(field.name)).append('\t').append(encoded(field.desc)).append('\t')
                        .append(field.access).append('\n');
                written++;
            }
            for (MethodNode method : type.methods) {
                if (written >= remaining) break;
                output.append('M').append('\t').append(encoded(owner)).append('\t')
                        .append(encoded(method.name)).append('\t').append(encoded(method.desc)).append('\t')
                        .append(method.access).append('\t')
                        .append(Type.getArgumentTypes(method.desc).length).append('\n');
                written++;
            }
            return written;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
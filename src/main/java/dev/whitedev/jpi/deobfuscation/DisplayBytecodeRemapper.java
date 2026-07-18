package dev.whitedev.jpi.deobfuscation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

public final class DisplayBytecodeRemapper {
    private DisplayBytecodeRemapper() {}

    public static byte[] remap(byte[] bytecode, DeobfuscationWorkspace workspace) {
        ClassReader reader = new ClassReader(bytecode);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, new WorkspaceRemapper(workspace)), 0);
        return writer.toByteArray();
    }

    private static final class WorkspaceRemapper extends Remapper {
        private final DeobfuscationWorkspace workspace;

        WorkspaceRemapper(DeobfuscationWorkspace workspace) {
            this.workspace = workspace;
        }

        @Override public String map(String internalName) {
            if (internalName == null) return null;
            String original = internalName.replace('/', '.');
            String mapped = workspace.classAlias(original);
            return mapped.equals(original) ? internalName : mapped.replace('.', '/');
        }

        @Override public String mapPackageName(String name) {
            String original = name.replace('/', '.');
            String mapped = workspace.packageAlias(original);
            return mapped.equals(original) ? name : mapped.replace('.', '/');
        }

        @Override public String mapMethodName(String owner, String name, String descriptor) {
            return workspace.methodAlias(owner.replace('/', '.'), name, descriptor);
        }

        @Override public String mapFieldName(String owner, String name, String descriptor) {
            return workspace.fieldAlias(owner.replace('/', '.'), name, descriptor);
        }
    }
}
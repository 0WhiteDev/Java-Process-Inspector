package dev.whitedev.jpi.deobfuscation;

import dev.whitedev.jpi.decompile.DecompilerService;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayBytecodeRemapperTest {
    @Test void createsADecompilableReadOnlyCopyWithMappedSymbols() throws Exception {
        String owner = DisplayRemapFixture.class.getName();
        DeobfuscationWorkspace workspace = new DeobfuscationWorkspace();
        workspace.update(mapped(new MappingEntry(MappingKind.CLASS, "", owner, "", -1, 0),
                "ReadableType"));
        workspace.update(mapped(new MappingEntry(MappingKind.FIELD, owner, "secret",
                "Ljava/lang/String;", -1, 0), "serverPublicKey"));
        workspace.update(mapped(new MappingEntry(MappingKind.METHOD, owner, "calculate",
                "(Ljava/lang/String;)Ljava/lang/String;", -1, 0), "validateLicense"));
        workspace.update(mapped(new MappingEntry(MappingKind.METHOD, owner, "helper",
                "(Ljava/lang/String;)Ljava/lang/String;", -1, 0), "normalizeLicense"));
        workspace.update(mapped(new MappingEntry(MappingKind.METHOD, owner, "self",
                "()L" + owner.replace('.', '/') + ";", -1, 0), "currentManager"));

        byte[] remapped = DisplayBytecodeRemapper.remap(fixtureBytes(), workspace);
        ClassNode type = new ClassNode();
        new ClassReader(remapped).accept(type, 0);
        assertEquals("ReadableType", type.name);
        assertTrue(type.fields.stream().map(field -> ((FieldNode) field).name)
                .anyMatch("serverPublicKey"::equals));
        assertTrue(type.methods.stream().map(method -> ((MethodNode) method).name)
                .anyMatch("validateLicense"::equals));
        assertTrue(type.methods.stream().map(method -> ((MethodNode) method).desc)
                .anyMatch("()LReadableType;"::equals));

        String source = new DecompilerService().decompile("ReadableType", remapped);
        assertTrue(source.contains("class ReadableType"));
        assertTrue(source.contains("serverPublicKey"));
        assertTrue(source.contains("validateLicense"));
        assertTrue(source.contains("normalizeLicense"));
    }

    private static MappingEntry mapped(MappingEntry entry, String name) {
        entry.setMappedName(name);
        return entry;
    }

    private static byte[] fixtureBytes() throws Exception {
        String resource = "/" + DisplayRemapFixture.class.getName().replace('.', '/') + ".class";
        try (InputStream input = DisplayRemapFixture.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Fixture bytecode is unavailable");
            return input.readAllBytes();
        }
    }
}
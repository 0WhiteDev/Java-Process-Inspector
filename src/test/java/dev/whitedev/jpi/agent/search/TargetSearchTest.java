package dev.whitedev.jpi.agent.search;

import dev.whitedev.jpi.search.SearchCategory;
import dev.whitedev.jpi.search.TargetSearchResults;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TargetSearchTest {
    @Test void findsSymbolsAndLiteralStringsWithoutMergingClassloaderDefinitions() throws Exception {
        byte[] bytes = fixture(1);
        Class<?> left = new Loader().define(bytes), right = new Loader().define(bytes);
        var report = TargetSearchResults.parse(TargetSearch.search(new Class<?>[]{left, right}, "auth", source(bytes)));
        assertEquals(2, report.counts().get(SearchCategory.CLASSES).matches());
        assertEquals(2, report.counts().get(SearchCategory.METHODS).matches());
        assertEquals(2, report.counts().get(SearchCategory.FIELDS).matches());
        assertEquals(2, report.counts().get(SearchCategory.STRINGS).matches());
        Set<String> ids = new HashSet<>();
        report.hits().stream().filter(hit -> hit.category() == SearchCategory.CLASSES).forEach(hit -> ids.add(hit.classId()));
        assertEquals(2, ids.size());
        assertTrue(report.hits().stream().filter(hit -> hit.category() == SearchCategory.STRINGS)
                .allMatch(hit -> hit.member().equals("authenticate0") && hit.descriptor().equals("()Ljava/lang/String;")));
        assertFalse(report.counts().get(SearchCategory.METHODS).partial());
    }

    @Test void capsPerCategoryAndMarksMissingBytecodeAsPartial() throws Exception {
        byte[] bytes = fixture(240);
        Class<?> type = new Loader().define(bytes);
        var report = TargetSearchResults.parse(TargetSearch.search(new Class<?>[]{type}, "auth", source(bytes)));
        assertEquals(200, report.hits().stream().filter(hit -> hit.category() == SearchCategory.METHODS).count());
        assertEquals(200, report.hits().stream().filter(hit -> hit.category() == SearchCategory.STRINGS).count());
        assertTrue(report.counts().get(SearchCategory.METHODS).partial());
        var unavailable = TargetSearchResults.parse(TargetSearch.search(new Class<?>[]{type}, "auth", source(null)));
        assertEquals(1, unavailable.counts().get(SearchCategory.CLASSES).matches());
        assertTrue(unavailable.counts().get(SearchCategory.METHODS).partial());
        assertThrows(IOException.class, () -> TargetSearch.search(new Class<?>[]{type}, "a", source(bytes)));
        assertThrows(IOException.class, () -> TargetSearch.search(new Class<?>[]{type}, "auth\nanything", source(bytes)));
    }

    private static TargetSearch.Source source(byte[] bytes) {
        return new TargetSearch.Source() {
            @Override public String identifier(Class<?> type) { return "c:" + System.identityHashCode(type); }
            @Override public String loader(Class<?> type) { return "loader@" + System.identityHashCode(type.getClassLoader()); }
            @Override public byte[] bytes(Class<?> type) { return bytes; }
        };
    }

    private static byte[] fixture(int methods) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "demo/AuthService", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "authToken", "Ljava/lang/String;", null, null).visitEnd();
        for (int i = 0; i < methods; i++) {
            var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "authenticate" + i, "()Ljava/lang/String;", null, null);
            method.visitCode();
            method.visitLdcInsn("/api/auth/" + i);
            method.visitInsn(Opcodes.ARETURN);
            method.visitMaxs(1, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class Loader extends ClassLoader {
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
}

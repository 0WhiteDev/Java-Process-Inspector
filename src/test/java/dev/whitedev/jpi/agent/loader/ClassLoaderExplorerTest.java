package dev.whitedev.jpi.agent.loader;

import dev.whitedev.jpi.loader.ClassLoaderSnapshot;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassLoaderExplorerTest {
    @Test
    void preservesDefiningIdentityAndFindsDuplicateNamesAndSharedPackages() throws Exception {
        ClassLoaderExplorer explorer = new ClassLoaderExplorer();
        try (Loader first = new Loader(null); Loader second = new Loader(first)) {
            Class<?> a = first.define("example.Shared");
            Class<?> b = second.define("example.Shared");
            ClassLoaderSnapshot snapshot = ClassLoaderSnapshot.parse(explorer.snapshot(new Class<?>[]{String.class, a, b}, null));
            var duplicates = snapshot.duplicateDefinitions().get("example.Shared");
            assertEquals(2, duplicates.size());
            assertNotEquals(duplicates.get(0).id(), duplicates.get(1).id());
            assertNotEquals(duplicates.get(0).loaderId(), duplicates.get(1).loaderId());
            assertEquals(2, snapshot.packageCollisions().get("example").size());
            var firstLoader = snapshot.loaders().stream().filter(loader -> loader.id().equals(duplicates.get(0).loaderId())).findFirst().orElseThrow();
            var secondLoader = snapshot.loaders().stream().filter(loader -> loader.id().equals(duplicates.get(1).loaderId())).findFirst().orElseThrow();
            assertEquals(firstLoader.id(), secondLoader.parentId());
            assertTrue(snapshot.sources().get(firstLoader.id()).getFirst().startsWith("Loader URL: file:/"));
            var refreshed = ClassLoaderSnapshot.parse(explorer.snapshot(new Class<?>[]{a, b}, null));
            assertEquals(duplicates.get(0).loaderId(), refreshed.definitions().getFirst().loaderId());
        }
    }

    @Test
    void doesNotPinLoadersAndKeepsCollectedHistory() throws Exception {
        ClassLoaderExplorer explorer = new ClassLoaderExplorer();
        WeakReference<ClassLoader> reference = captureTemporary(explorer);
        for (int attempt = 0; attempt < 30 && reference.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        assertEquals(null, reference.get(), "Explorer must not retain the target loader");
        var snapshot = ClassLoaderSnapshot.parse(explorer.snapshot(new Class<?>[]{String.class}, null));
        assertTrue(snapshot.loaders().stream().anyMatch(loader -> loader.status().equals("collected") && loader.observedAt() > 0));
    }

    private static WeakReference<ClassLoader> captureTemporary(ClassLoaderExplorer explorer) throws Exception {
        Loader loader = new Loader(null);
        Class<?> type = loader.define("temporary.Disposable");
        explorer.snapshot(new Class<?>[]{type}, null);
        loader.close();
        return new WeakReference<>(loader);
    }

    private static final class Loader extends URLClassLoader {
        Loader(ClassLoader parent) throws Exception { super(new URL[]{new URL("file:/loader-fixture.jar")}, parent); }
        Class<?> define(String name) {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
            writer.visitEnd();
            byte[] bytes = writer.toByteArray();
            return defineClass(name, bytes, 0, bytes.length);
        }
        @Override public boolean equals(Object value) { return value instanceof Loader; }
        @Override public int hashCode() { return 1; }
    }
}

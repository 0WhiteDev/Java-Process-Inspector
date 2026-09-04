package dev.whitedev.jpi.agent.file;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class FileCallSiteTransformerTest {
    @AfterEach void clear() {
        FileInterceptorRuntime.clear();
    }

    @Test void observesReadsAndWritesWithoutChangingBehavior() throws Exception {
        FileInterceptorRuntime.configure(100, true, 4096);
        Class<?> fixture = transformed();
        Path file = Files.createTempFile("jpi-file-observe-", ".bin");
        byte[] content = new byte[]{1, 2, 3, 4};

        byte[] result = (byte[]) fixture.getMethod("roundTrip", Path.class, byte[].class)
                .invoke(null, file, content);

        assertArrayEquals(content, result);
        String events = FileInterceptorRuntime.events();
        assertTrue(events.contains("\tWRITE\t"));
        assertTrue(events.contains("\tREAD\t"));
        assertTrue(events.contains("\t4\t"));
    }

    @Test void redirectsConsistentlyAndBlocksWithFileSystemException() throws Exception {
        Path root = Files.createTempDirectory("jpi-file-sandbox-");
        Path original = root.resolveSibling("jpi-original-").resolve("state.bin");
        FileInterceptorRuntime.rules().put(new FileRule("redirect", Collections.singleton(FileOperation.WRITE),
                "**", "*", FileDecision.REDIRECT, root.toString()));
        FileInterceptorRuntime.rules().put(new FileRule("redirect-read", Collections.singleton(FileOperation.READ),
                "**", "*", FileDecision.REDIRECT, root.toString()));
        FileInterceptorRuntime.rules().put(new FileRule("block-delete", Collections.singleton(FileOperation.DELETE),
                "**", "*", FileDecision.BLOCK, ""));
        FileInterceptorRuntime.configure(100, false, 4096);
        Class<?> fixture = transformed();
        byte[] content = new byte[]{9, 8, 7};

        byte[] result = (byte[]) fixture.getMethod("roundTrip", Path.class, byte[].class)
                .invoke(null, original, content);

        assertArrayEquals(content, result);
        assertFalse(Files.exists(original));
        assertTrue(FileInterceptorRuntime.events().contains("\tREDIRECT\t"));
        try {
            fixture.getMethod("delete", Path.class).invoke(null, original);
            fail("Delete should be blocked");
        } catch (InvocationTargetException error) {
            assertEquals(FileSystemException.class, error.getCause().getClass());
        }
    }

    @Test void capturesCreateCopyMoveOpenTruncateAndDelete() throws Exception {
        FileInterceptorRuntime.configure(100, false, 4096);
        Class<?> fixture = transformed();
        Path directory = Files.createTempDirectory("jpi-file-operations-");
        Path created = directory.resolve("created.bin");
        Path copied = directory.resolve("copied.bin");
        Path moved = directory.resolve("moved.bin");

        fixture.getMethod("create", Path.class).invoke(null, created);
        fixture.getMethod("open", Path.class).invoke(null, created);
        fixture.getMethod("truncate", String.class).invoke(null, created.toString());
        fixture.getMethod("copy", Path.class, Path.class).invoke(null, created, copied);
        fixture.getMethod("move", Path.class, Path.class).invoke(null, copied, moved);
        fixture.getMethod("delete", Path.class).invoke(null, moved);

        String events = FileInterceptorRuntime.events();
        assertTrue(events.contains("\tCREATE\t"));
        assertTrue(events.contains("\tOPEN\t"));
        assertTrue(events.contains("\tTRUNCATE\t"));
        assertTrue(events.contains("\tCOPY\t"));
        assertTrue(events.contains("\tMOVE\t"));
        assertTrue(events.contains("\tDELETE\t"));
    }

    private static Class<?> transformed() throws Exception {
        byte[] source;
        try (InputStream input = Fixture.class.getResourceAsStream("/" + Fixture.class.getName().replace('.', '/') + ".class")) {
            source = input.readAllBytes();
        }
        FileCallSiteTransformer.Result result = FileCallSiteTransformer.transform(source);
        assertEquals(8, result.sites);
        return new FixtureLoader().define(Fixture.class.getName(), result.bytecode);
    }

    public static final class Fixture {
        public static byte[] roundTrip(Path path, byte[] content) throws Exception {
            Files.write(path, content);
            return Files.readAllBytes(path);
        }

        public static boolean delete(Path path) throws Exception {
            return Files.deleteIfExists(path);
        }

        public static void create(Path path) throws Exception {
            Files.createFile(path);
        }

        public static void copy(Path source, Path target) throws Exception {
            Files.copy(source, target);
        }

        public static void move(Path source, Path target) throws Exception {
            Files.move(source, target);
        }

        public static void open(Path path) throws Exception {
            FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE);
            channel.close();
        }

        public static void truncate(String path) throws Exception {
            FileOutputStream output = new FileOutputStream(path);
            output.close();
        }
    }

    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader() {
            super(FileCallSiteTransformerTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}

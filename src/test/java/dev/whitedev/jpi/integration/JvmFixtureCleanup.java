package dev.whitedev.jpi.integration;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class JvmFixtureCleanup {
    private JvmFixtureCleanup() {}

    static void stop(Process process) throws Exception {
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Fixture JVM did not terminate");
        }
        assertFalse(process.isAlive(), "Fixture JVM remains alive");
        process.getOutputStream().close();
        process.getInputStream().close();
        process.getErrorStream().close();
    }

    static void delete(Path... paths) throws IOException, InterruptedException {
        for (Path path : paths) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (;;) {
                try { Files.deleteIfExists(path); break; }
                catch (FileSystemException error) {
                    if (!System.getProperty("os.name", "").startsWith("Windows") || System.nanoTime() >= deadline) throw error;
                    Thread.sleep(20);
                }
            }
        }
    }
}

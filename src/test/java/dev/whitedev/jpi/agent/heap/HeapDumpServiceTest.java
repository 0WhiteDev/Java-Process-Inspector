package dev.whitedev.jpi.agent.heap;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class HeapDumpServiceTest {
    @Test
    void rejectsAmbiguousOrDestructiveDestinationsBeforeCallingTheJvm() throws Exception {
        assertThrows(IOException.class, () -> HeapDumpService.dump(""));
        assertThrows(IOException.class, () -> HeapDumpService.dump("relative.hprof"));

        Path directory = Files.createTempDirectory("jpi-heap-dump-test-");
        Path wrongExtension = directory.resolve("heap.bin");
        Path existing = Files.createFile(directory.resolve("existing.hprof"));

        assertThrows(IOException.class, () -> HeapDumpService.dump(wrongExtension.toString()));
        assertThrows(IOException.class, () -> HeapDumpService.dump(existing.toString()));
    }
}

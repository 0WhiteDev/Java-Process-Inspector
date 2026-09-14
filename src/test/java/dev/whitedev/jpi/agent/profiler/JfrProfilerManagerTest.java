package dev.whitedev.jpi.agent.profiler;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrProfilerManagerTest {
    @Test void recordsAndAggregatesRuntimeEvents() throws Exception {
        JfrProfilerManager manager = new JfrProfilerManager();
        assertTrue(manager.status().startsWith("IDLE\ttrue"));
        manager.start("durationSeconds=1;categories=CPU,ALLOCATIONS,LOCKS,EXCEPTIONS,GC,THREADS,IO");
        Path file = Files.createTempFile("jpi-jfr-test-", ".bin");
        Files.write(file, new byte[8192]);
        long deadline = System.nanoTime() + 900_000_000L;
        long value = 0L;
        while (System.nanoTime() < deadline) {
            byte[] allocation = new byte[4096];
            allocation[0] = 1;
            value += allocation[0];
        }
        assertTrue(value > 0L);
        for (int attempt = 0; attempt < 100 && !manager.status().startsWith("COMPLETE\t"); attempt++) {
            Thread.sleep(50L);
        }
        String report = manager.report();
        assertTrue(report.startsWith("R\t"));
        assertTrue(Integer.parseInt(report.split("\n", 2)[0].split("\t")[4]) > 0);
        assertTrue(report.contains("\nC\tCPU\t"));
        assertTrue(report.contains("\nF\tCPU\t"));
        assertTrue(report.contains("\nC\tALLOCATIONS\t"));
        assertTrue(report.contains("\nC\tIO\t"));
        Files.deleteIfExists(file);
        manager.close();
    }
}

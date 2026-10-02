package dev.whitedev.jpi.agent.exception;

import dev.whitedev.jpi.exceptions.ExceptionSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionAnalyzerTest {
    @Test void aggregatesWindowTotalsThreadsAndLocations() {
        ExceptionAnalyzer analyzer = new ExceptionAnalyzer();
        long now = System.currentTimeMillis();
        analyzer.record(0, "example.Failure", now - 40000, 1, "example.Service", "run", "()V", 12, "stack");
        analyzer.record(0, "example.Failure", now, 2, "example.Service", "run", "()V", 12, "stack");
        var entry = ExceptionSnapshot.parse(analyzer.snapshot(30)).entries().getFirst();
        assertEquals(1, entry.windowCount());
        assertEquals(2, entry.total());
        assertEquals(2, entry.threads());
        assertEquals(now - 40000, entry.firstSeen());
        assertEquals(now, entry.lastSeen());
        assertEquals(2, entry.locations().getFirst().count());
        assertEquals("()V", entry.locations().getFirst().descriptor());
    }

    @Test void clearDiscardsOldBufferedEventsButAcceptsNewEvents() {
        ExceptionAnalyzer analyzer = new ExceptionAnalyzer();
        long old = System.currentTimeMillis() - 1000;
        analyzer.clear();
        analyzer.record(analyzer.generation(), "old", old, 1, "A", "run", "()V", 1, "");
        assertTrue(ExceptionSnapshot.parse(analyzer.snapshot(30)).entries().isEmpty());
        analyzer.record(analyzer.generation(), "new", System.currentTimeMillis(), 1, "A", "run", "()V", 1, "");
        assertEquals("new", ExceptionSnapshot.parse(analyzer.snapshot(30)).entries().getFirst().type());
        analyzer.clear();
        assertTrue(ExceptionSnapshot.parse(analyzer.snapshot(30)).entries().isEmpty());
    }

    @Test void boundsUniqueTypesAndThreadsAndRejectsInvalidWindow() {
        ExceptionAnalyzer analyzer = new ExceptionAnalyzer();
        for (int i = 0; i < 300; i++) analyzer.record(0, "failure" + i, System.currentTimeMillis(), i,
                "A", "run", "()V", 1, "");
        var snapshot = ExceptionSnapshot.parse(analyzer.snapshot(30));
        assertEquals(256, snapshot.entries().size());
        assertEquals(44, snapshot.dropped());
        for (int i = 0; i < 200; i++) analyzer.record(0, "failure0", System.currentTimeMillis(), i,
                "A", "run", "()V", 1, "");
        var entry = ExceptionSnapshot.parse(analyzer.snapshot(30)).entries().getFirst();
        assertEquals(128, entry.threads());
        assertTrue(entry.threadOverflow());
        assertThrows(IllegalArgumentException.class, () -> analyzer.snapshot(301));
    }

    @Test void durationStopsRecordingAutomaticallyAndAllowsRestart() throws Exception {
        try (ExceptionAnalyzer analyzer = new ExceptionAnalyzer()) {
            assertThrows(java.io.IOException.class, () -> analyzer.start("durationSeconds=0"));
            analyzer.start("durationSeconds=1");
            long deadline = System.nanoTime() + 10000000000L;
            while (ExceptionSnapshot.parse(analyzer.snapshot(30)).state().equals("recording")
                    && System.nanoTime() < deadline) Thread.sleep(50);
            assertEquals("stopped", ExceptionSnapshot.parse(analyzer.snapshot(30)).state());
            assertEquals("recording", ExceptionSnapshot.parse(analyzer.start("durationSeconds=30")).state());
        }
    }

    @Test void boundsGlobalLocationsAndEncodedSnapshotSize() {
        ExceptionAnalyzer analyzer = new ExceptionAnalyzer();
        String stack = "x".repeat(16000);
        for (int type = 0; type < 32; type++) for (int line = 0; line < 32; line++) {
            analyzer.record(0, "Failure" + type, System.currentTimeMillis(), 1, "Service", "run", "()V", line, stack);
        }
        String raw = analyzer.snapshot(30);
        var snapshot = ExceptionSnapshot.parse(raw);
        assertEquals(512, snapshot.entries().stream().mapToInt(entry -> entry.locations().size()).sum());
        assertEquals(512, snapshot.dropped());
        assertTrue(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < dev.whitedev.jpi.protocol.WireProtocol.MAX_PAYLOAD);
    }

    @Test void streamsRealJfrExceptionsAndSupportsClearDuringRecording() throws Exception {
        try (ExceptionAnalyzer analyzer = new ExceptionAnalyzer()) {
            assertEquals("recording", ExceptionSnapshot.parse(analyzer.start("durationSeconds=90")).state());
            assertThrows(java.io.IOException.class, () -> analyzer.start(""));
            var entry = awaitFailure(analyzer);
            assertTrue(entry.total() >= 1);
            assertTrue(entry.locations().stream().anyMatch(location -> location.method().equals("emit")));
            analyzer.clear();
            assertTrue(awaitFailure(analyzer).total() >= 1);
            new TestError();
            var error = awaitType(analyzer, TestError.class.getName());
            assertTrue(error.total() >= 1 && error.total() <= 2);
            assertEquals("stopped", ExceptionSnapshot.parse(analyzer.stop()).state());
        }
    }

    private static ExceptionSnapshot.Entry awaitFailure(ExceptionAnalyzer analyzer) throws Exception {
        return awaitType(analyzer, TestFailure.class.getName(), ExceptionAnalyzerTest::emit);
    }

    private static ExceptionSnapshot.Entry awaitType(ExceptionAnalyzer analyzer, String type) throws Exception {
        return awaitType(analyzer, type, () -> { });
    }

    private static ExceptionSnapshot.Entry awaitType(ExceptionAnalyzer analyzer, String type, Runnable producer) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        ExceptionSnapshot snapshot;
        do {
            snapshot = ExceptionSnapshot.parse(analyzer.snapshot(30));
            var entry = snapshot.entries().stream().filter(value -> value.type().equals(type)).findFirst();
            if (entry.isPresent()) return entry.get();
            if (!"recording".equals(snapshot.state())) {
                throw new AssertionError("JFR stopped before delivering " + type + ": " + snapshot.state() + " | " + snapshot.message());
            }
            producer.run();
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("JFR event not delivered for " + type + ": state=" + snapshot.state()
                + ", dropped=" + snapshot.dropped() + ", observed types="
                + snapshot.entries().stream().map(ExceptionSnapshot.Entry::type).limit(10).toList()
                + ", message=" + snapshot.message());
    }

    private static void emit() {
        try { throw new TestFailure(); } catch (TestFailure ignored) { }
    }

    private static final class TestFailure extends RuntimeException { }
    private static final class TestError extends Error { }
}

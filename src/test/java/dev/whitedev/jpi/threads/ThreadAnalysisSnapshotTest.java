package dev.whitedev.jpi.threads;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadAnalysisSnapshotTest {
    @Test
    void parsesThreadMetricsLocksHistoryAndLifetimes() {
        String raw = "M\t1000\t500\t2\t1\t1\t0\ttrue\ttrue\ttrue\ttrue\n"
                + "T\t7\tWorker-7\tBLOCKED\tfalse\t5\t3\t40\t6\t80\t250000000\tLock@1\t8\tWorker-8\tfalse\tfalse\tfalse\ttrue\t500\n"
                + "S\t7\t0\tapp.Worker.run(Worker.java:42)\n"
                + "L\t7\tmonitor\tLock@2\t1\n"
                + "H\t7\t500\tRUNNABLE\t\t\n"
                + "H\t7\t900\tBLOCKED\tLock@1\tWorker-8\n"
                + "C\t7\tWorker-7\t500\t0\n";

        ThreadAnalysisSnapshot snapshot = ThreadAnalysisSnapshot.parse(raw);
        ThreadAnalysisSnapshot.ThreadEntry thread = snapshot.threads().get(0);

        assertEquals(1, snapshot.deadlockCount());
        assertEquals(1, snapshot.blockedCount());
        assertEquals("Worker-7", thread.name());
        assertEquals("Lock@1", thread.lock());
        assertEquals(2, thread.history().size());
        assertEquals(1, thread.heldLocks().size());
        assertEquals(50d, thread.cpuPercent(snapshot.intervalMillis()), .01d);
        assertTrue(thread.deadlocked());
        assertEquals(1, snapshot.lifetimes().size());
    }
}

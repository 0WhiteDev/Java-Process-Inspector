package dev.whitedev.jpi.agent.thread;

import dev.whitedev.jpi.threads.ThreadAnalysisSnapshot;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadAnalyzerTest {
    @Test
    void reportsDeadlockedThreadsAndLockOwners() throws Exception {
        ReentrantLock first = new ReentrantLock();
        ReentrantLock second = new ReentrantLock();
        CountDownLatch held = new CountDownLatch(2);
        Thread left = worker("jpi-deadlock-left", first, second, held);
        Thread right = worker("jpi-deadlock-right", second, first, held);
        left.start();
        right.start();
        assertTrue(held.await(2, TimeUnit.SECONDS));

        ThreadAnalyzer analyzer = new ThreadAnalyzer();
        String snapshot = "";
        try {
            for (int attempt = 0; attempt < 30 && !snapshot.contains("jpi-deadlock-left\tWAITING"); attempt++) {
                Thread.sleep(20L);
                snapshot = analyzer.snapshot();
            }
            assertTrue(snapshot.contains("jpi-deadlock-left"));
            assertTrue(snapshot.contains("jpi-deadlock-right"));
            assertTrue(snapshot.contains("synchronizer"));
            ThreadAnalysisSnapshot parsed = ThreadAnalysisSnapshot.parse(snapshot);
            assertTrue(parsed.threads().stream().anyMatch(thread ->
                    thread.name().equals("jpi-deadlock-left") && thread.deadlocked()
                            && thread.ownerName().equals("jpi-deadlock-right")));
            assertTrue(parsed.threads().stream().anyMatch(thread ->
                    thread.name().equals("jpi-deadlock-right") && thread.deadlocked()
                            && thread.ownerName().equals("jpi-deadlock-left")));
        } finally {
            left.interrupt();
            right.interrupt();
            left.join(2000L);
            right.join(2000L);
            analyzer.close();
        }
    }

    private Thread worker(String name, ReentrantLock owned, ReentrantLock requested, CountDownLatch held) {
        Thread thread = new Thread(() -> {
            owned.lock();
            try {
                held.countDown();
                held.await();
                requested.lockInterruptibly();
                requested.unlock();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                owned.unlock();
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }
}

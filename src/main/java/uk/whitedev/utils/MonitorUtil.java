package uk.whitedev.utils;

import java.lang.management.*;

public class MonitorUtil {
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    public String getPerformanceData() {
        StringBuilder sb = new StringBuilder();
        sb.append("Performance Profiling:\n");

        int threadCount = threadBean.getThreadCount();
        sb.append("  Thread Count: ").append(threadCount).append("\n");

        long cpuTime = threadBean.getCurrentThreadCpuTime() / 1_000_000;
        sb.append("  Current Thread CPU Time: ").append(cpuTime).append(" ms\n");

        long[] threadIds = threadBean.getAllThreadIds();
        long totalCpuTime = 0;
        for (long threadId : threadIds) {
            long threadCpuTime = threadBean.getThreadCpuTime(threadId) / 1_000_000;
            totalCpuTime += threadCpuTime;
        }
        sb.append("  Total CPU Time for All Threads: ").append(totalCpuTime).append(" ms\n");

        return sb.toString();
    }


    public String getResourcesData() {
        StringBuilder sb = new StringBuilder();
        sb.append("Resource Monitoring:\n");

        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        sb.append("  Heap Memory Usage:\n");
        sb.append("    Init: ").append(heapUsage.getInit() / 1_000_000).append(" MB\n");
        sb.append("    Used: ").append(heapUsage.getUsed() / 1_000_000).append(" MB\n");
        sb.append("    Committed: ").append(heapUsage.getCommitted() / 1_000_000).append(" MB\n");
        sb.append("    Max: ").append(heapUsage.getMax() / 1_000_000).append(" MB\n");

        MemoryUsage nonHeapUsage = memoryBean.getNonHeapMemoryUsage();
        sb.append("  Non-Heap Memory Usage:\n");
        sb.append("    Init: ").append(nonHeapUsage.getInit() / 1_000_000).append(" MB\n");
        sb.append("    Used: ").append(nonHeapUsage.getUsed() / 1_000_000).append(" MB\n");
        sb.append("    Committed: ").append(nonHeapUsage.getCommitted() / 1_000_000).append(" MB\n");
        sb.append("    Max: ").append(nonHeapUsage.getMax() / 1_000_000).append(" MB\n");

        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            sb.append("  Garbage Collector - ").append(gcBean.getName()).append(":\n");
            sb.append("    Collection Count: ").append(gcBean.getCollectionCount()).append("\n");
            sb.append("    Collection Time: ").append(gcBean.getCollectionTime()).append(" ms\n");
        }

        return sb.toString();
    }


    public String getDetailedInfo() {
        StringBuilder sb = new StringBuilder();

        long[] deadlockedThreads = threadBean.findDeadlockedThreads();
        sb.append("Analysis of locks and deadlocks:\n");
        if (deadlockedThreads != null) {
            sb.append("  Deadlocked threads:\n");
            for (long threadId : deadlockedThreads) {
                sb.append("    Thread ID: ").append(threadId).append("\n");
            }
        } else {
            sb.append("  No deadlocked threads found.\n");
        }
        sb.append("\n");

        sb.append("Application Data:\n");
        sb.append("  Java Version: ").append(System.getProperty("java.version")).append("\n");
        sb.append("  Java Vendor: ").append(System.getProperty("java.vendor")).append("\n");
        sb.append("  JVM Version: ").append(System.getProperty("java.vm.version")).append("\n");
        sb.append("  JVM Vendor: ").append(System.getProperty("java.vm.vendor")).append("\n");
        sb.append("  OS: ").append(System.getProperty("os.name")).append("\n");
        sb.append("  OS Version: ").append(System.getProperty("os.version")).append("\n");
        sb.append("\n");

        sb.append("Threads:\n");
        long[] threadIds = threadBean.getAllThreadIds();
        for (long threadId : threadIds) {
            StackTraceElement[] stackTrace = threadBean.getThreadInfo(threadId).getStackTrace();
            sb.append("  Thread ID ").append(threadId).append(":\n");
            for (StackTraceElement elem : stackTrace) {
                sb.append("    ").append(elem.toString()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}

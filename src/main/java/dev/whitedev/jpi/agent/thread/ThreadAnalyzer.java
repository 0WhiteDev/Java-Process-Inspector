package dev.whitedev.jpi.agent.thread;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ThreadAnalyzer {
    private static final int MAX_THREADS = 2048;
    private static final int MAX_HISTORY = 48;
    private static final int MAX_FRAMES = 24;

    private final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    private final Map<Long, Long> previousCpu = new HashMap<Long, Long>();
    private final Map<Long, Deque<StatePoint>> history = new HashMap<Long, Deque<StatePoint>>();
    private final LinkedHashMap<Long, Lifetime> lifetimes = new LinkedHashMap<Long, Lifetime>();
    private final boolean initialCpuEnabled;
    private final boolean initialContentionEnabled;
    private long previousCapture;

    public ThreadAnalyzer() {
        initialCpuEnabled = bean.isThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled();
        initialContentionEnabled = bean.isThreadContentionMonitoringSupported()
                && bean.isThreadContentionMonitoringEnabled();
    }

    public synchronized String snapshot() {
        enableMetrics();
        long now = System.currentTimeMillis();
        long interval = previousCapture == 0L ? 0L : Math.max(1L, now - previousCapture);
        previousCapture = now;
        Map<Long, Thread> runtimeThreads = runtimeThreads();
        Set<Long> deadlocked = deadlockedThreads();
        ThreadInfo[] infos = bean.dumpAllThreads(true, true);
        List<CurrentThread> current = new ArrayList<CurrentThread>();
        Set<Long> live = new HashSet<Long>();
        int created = 0;
        for (ThreadInfo info : infos) {
            if (info == null) continue;
            long id = info.getThreadId();
            live.add(Long.valueOf(id));
            if (current.size() >= MAX_THREADS) continue;
            Lifetime lifetime = lifetimes.get(Long.valueOf(id));
            if (lifetime == null) {
                lifetime = new Lifetime(id, info.getThreadName(), now);
                lifetimes.put(Long.valueOf(id), lifetime);
                created++;
            } else {
                lifetime.name = info.getThreadName();
                lifetime.endedAt = 0L;
            }
            long cpu = cpuTime(id);
            Long oldCpu = previousCpu.put(Long.valueOf(id), Long.valueOf(cpu));
            long cpuDelta = cpu < 0L || oldCpu == null || oldCpu.longValue() < 0L
                    ? 0L : Math.max(0L, cpu - oldCpu.longValue());
            Thread runtime = runtimeThreads.get(Long.valueOf(id));
            CurrentThread value = new CurrentThread(info, runtime, lifetime.firstSeen, cpuDelta,
                    deadlocked.contains(Long.valueOf(id)));
            current.add(value);
            recordState(value, now);
        }
        int ended = markEnded(live, now);
        prune();
        Collections.sort(current, new Comparator<CurrentThread>() {
            @Override public int compare(CurrentThread left, CurrentThread right) {
                int cpu = Long.compare(right.cpuDelta, left.cpuDelta);
                return cpu != 0 ? cpu : left.name.compareToIgnoreCase(right.name);
            }
        });
        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("M\t").append(now).append('\t').append(interval).append('\t').append(current.size())
                .append('\t').append(deadlocked.size()).append('\t').append(created).append('\t').append(ended)
                .append('\t').append(bean.isThreadCpuTimeSupported()).append('\t').append(bean.isThreadCpuTimeEnabled())
                .append('\t').append(bean.isThreadContentionMonitoringSupported()).append('\t')
                .append(bean.isThreadContentionMonitoringEnabled()).append('\n');
        for (CurrentThread value : current) appendThread(out, value);
        for (CurrentThread value : current) appendHistory(out, value.id);
        for (Lifetime lifetime : lifetimes.values()) appendLifetime(out, lifetime);
        return out.toString();
    }

    public synchronized String clear() {
        previousCpu.clear();
        history.clear();
        lifetimes.clear();
        previousCapture = 0L;
        return "cleared";
    }

    public synchronized void close() {
        clear();
        try {
            if (bean.isThreadCpuTimeSupported() && !initialCpuEnabled && bean.isThreadCpuTimeEnabled()) {
                bean.setThreadCpuTimeEnabled(false);
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (bean.isThreadContentionMonitoringSupported() && !initialContentionEnabled
                    && bean.isThreadContentionMonitoringEnabled()) {
                bean.setThreadContentionMonitoringEnabled(false);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void enableMetrics() {
        try {
            if (bean.isThreadCpuTimeSupported() && !bean.isThreadCpuTimeEnabled()) bean.setThreadCpuTimeEnabled(true);
        } catch (RuntimeException ignored) {
        }
        try {
            if (bean.isThreadContentionMonitoringSupported() && !bean.isThreadContentionMonitoringEnabled()) {
                bean.setThreadContentionMonitoringEnabled(true);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private Map<Long, Thread> runtimeThreads() {
        Map<Long, Thread> result = new HashMap<Long, Thread>();
        try {
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                result.put(Long.valueOf(thread.getId()), thread);
            }
        } catch (SecurityException ignored) {
        }
        return result;
    }

    private Set<Long> deadlockedThreads() {
        long[] ids = bean.findDeadlockedThreads();
        if (ids == null) ids = bean.findMonitorDeadlockedThreads();
        if (ids == null) return Collections.emptySet();
        Set<Long> result = new HashSet<Long>();
        for (long id : ids) result.add(Long.valueOf(id));
        return result;
    }

    private long cpuTime(long id) {
        if (!bean.isThreadCpuTimeSupported() || !bean.isThreadCpuTimeEnabled()) return -1L;
        try {
            return bean.getThreadCpuTime(id);
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private void recordState(CurrentThread thread, long now) {
        Long id = Long.valueOf(thread.id);
        Deque<StatePoint> points = history.get(id);
        if (points == null) {
            points = new ArrayDeque<StatePoint>();
            history.put(id, points);
        }
        StatePoint latest = points.peekLast();
        if (latest != null && latest.state.equals(thread.state) && latest.lock.equals(thread.lock)) return;
        points.addLast(new StatePoint(now, thread.state, thread.lock, thread.ownerName));
        while (points.size() > MAX_HISTORY) points.removeFirst();
    }

    private int markEnded(Set<Long> live, long now) {
        int count = 0;
        for (Lifetime lifetime : lifetimes.values()) {
            if (lifetime.endedAt == 0L && !live.contains(Long.valueOf(lifetime.id))) {
                lifetime.endedAt = now;
                previousCpu.remove(Long.valueOf(lifetime.id));
                count++;
            }
        }
        return count;
    }

    private void prune() {
        if (lifetimes.size() <= MAX_THREADS) return;
        Iterator<Map.Entry<Long, Lifetime>> iterator = lifetimes.entrySet().iterator();
        while (lifetimes.size() > MAX_THREADS && iterator.hasNext()) {
            Map.Entry<Long, Lifetime> entry = iterator.next();
            if (entry.getValue().endedAt == 0L) continue;
            history.remove(entry.getKey());
            previousCpu.remove(entry.getKey());
            iterator.remove();
        }
    }

    private void appendThread(StringBuilder out, CurrentThread thread) {
        ThreadInfo info = thread.info;
        out.append("T\t").append(thread.id).append('\t').append(text(thread.name)).append('\t')
                .append(thread.state).append('\t').append(thread.daemon).append('\t').append(thread.priority)
                .append('\t').append(info.getBlockedCount()).append('\t').append(info.getBlockedTime())
                .append('\t').append(info.getWaitedCount()).append('\t').append(info.getWaitedTime())
                .append('\t').append(thread.cpuDelta).append('\t').append(text(thread.lock)).append('\t')
                .append(info.getLockOwnerId()).append('\t').append(text(thread.ownerName)).append('\t')
                .append(thread.parked).append('\t').append(info.isInNative()).append('\t').append(info.isSuspended())
                .append('\t').append(thread.deadlocked).append('\t').append(thread.firstSeen).append('\n');
        int frameIndex = 0;
        for (StackTraceElement frame : info.getStackTrace()) {
            if (frameIndex >= MAX_FRAMES) break;
            out.append("S\t").append(thread.id).append('\t').append(frameIndex++)
                    .append('\t').append(text(frame.toString())).append('\n');
        }
        for (MonitorInfo monitor : info.getLockedMonitors()) {
            out.append("L\t").append(thread.id).append("\tmonitor\t").append(text(monitor.toString()))
                    .append('\t').append(monitor.getLockedStackDepth()).append('\n');
        }
        for (LockInfo synchronizer : info.getLockedSynchronizers()) {
            out.append("L\t").append(thread.id).append("\tsynchronizer\t")
                    .append(text(synchronizer.toString())).append("\t-1\n");
        }
    }

    private void appendHistory(StringBuilder out, long id) {
        Deque<StatePoint> points = history.get(Long.valueOf(id));
        if (points == null) return;
        for (StatePoint point : points) {
            out.append("H\t").append(id).append('\t').append(point.timestamp).append('\t').append(point.state)
                    .append('\t').append(text(point.lock)).append('\t').append(text(point.owner)).append('\n');
        }
    }

    private void appendLifetime(StringBuilder out, Lifetime lifetime) {
        out.append("C\t").append(lifetime.id).append('\t').append(text(lifetime.name)).append('\t')
                .append(lifetime.firstSeen).append('\t').append(lifetime.endedAt).append('\n');
    }

    private static String text(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static final class CurrentThread {
        final ThreadInfo info;
        final long id;
        final String name;
        final String state;
        final boolean daemon;
        final int priority;
        final long cpuDelta;
        final String lock;
        final String ownerName;
        final boolean parked;
        final boolean deadlocked;
        final long firstSeen;

        CurrentThread(ThreadInfo info, Thread runtime, long firstSeen, long cpuDelta, boolean deadlocked) {
            this.info = info;
            this.id = info.getThreadId();
            this.name = info.getThreadName();
            this.state = info.getThreadState().name();
            this.daemon = runtime != null && runtime.isDaemon();
            this.priority = runtime == null ? 0 : runtime.getPriority();
            this.cpuDelta = cpuDelta;
            LockInfo lockInfo = info.getLockInfo();
            this.lock = lockInfo == null ? "" : lockInfo.toString();
            this.ownerName = info.getLockOwnerName() == null ? "" : info.getLockOwnerName();
            this.parked = isParked(info);
            this.deadlocked = deadlocked;
            this.firstSeen = firstSeen;
        }

        private static boolean isParked(ThreadInfo info) {
            if (info.getThreadState() != Thread.State.WAITING && info.getThreadState() != Thread.State.TIMED_WAITING) {
                return false;
            }
            LockInfo lock = info.getLockInfo();
            if (lock != null && lock.getClassName().startsWith("java.util.concurrent")) return true;
            StackTraceElement[] frames = info.getStackTrace();
            return frames.length > 0 && frames[0].getMethodName().equals("park");
        }
    }

    private static final class StatePoint {
        final long timestamp;
        final String state;
        final String lock;
        final String owner;

        StatePoint(long timestamp, String state, String lock, String owner) {
            this.timestamp = timestamp;
            this.state = state;
            this.lock = lock;
            this.owner = owner;
        }
    }

    private static final class Lifetime {
        final long id;
        final long firstSeen;
        String name;
        long endedAt;

        Lifetime(long id, String name, long firstSeen) {
            this.id = id;
            this.name = name;
            this.firstSeen = firstSeen;
        }
    }
}

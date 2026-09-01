package dev.whitedev.jpi.agent.cfg;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public final class CfgRuntime {
    private static final ConcurrentHashMap<String, CounterState> STATES =
            new ConcurrentHashMap<String, CounterState>();

    private CfgRuntime() {}

    public static void hit(String probeId, int block) {
        CounterState state = STATES.get(probeId);
        if (state == null || !state.active || block < 0 || block >= state.blocks.length()) return;
        state.blocks.incrementAndGet(block);
        state.total.incrementAndGet();
        state.transition(block);
    }

    static void register(String probeId, int blockCount) {
        STATES.clear();
        STATES.put(probeId, new CounterState(blockCount));
    }

    static void deactivate(String probeId) {
        CounterState state = STATES.get(probeId);
        if (state == null) return;
        state.active = false;
        state.stoppedAt = System.currentTimeMillis();
    }

    static void discard(String probeId) {
        STATES.remove(probeId);
    }

    static String snapshot(String probeId) {
        CounterState state = STATES.get(probeId);
        if (state == null) return "S\t" + probeId + "\tfalse\t0\t0\t0\n";
        StringBuilder output = new StringBuilder();
        output.append('S').append('\t').append(probeId).append('\t').append(state.active).append('\t')
                .append(state.startedAt).append('\t').append(state.stoppedAt).append('\t')
                .append(state.total.get()).append('\n');
        for (int index = 0; index < state.blocks.length(); index++) {
            output.append('H').append('\t').append(index).append('\t')
                    .append(state.blocks.get(index)).append('\n');
        }
        for (Transition transition : state.transitions) {
            output.append('T').append('\t').append(transition.sequence).append('\t')
                    .append(transition.timestamp).append('\t').append(transition.threadId).append('\t')
                    .append(transition.from).append('\t').append(transition.to).append('\n');
        }
        output.append('D').append('\t').append(state.dropped.get()).append('\n');
        return output.toString();
    }

    static void clear() {
        STATES.clear();
    }

    private static final class CounterState {
        private static final long MAX_TRANSITIONS = 20_000L;
        final AtomicLongArray blocks;
        final AtomicLong total = new AtomicLong();
        final AtomicLong transitionSequence = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
        final ConcurrentHashMap<Long, Integer> previousByThread = new ConcurrentHashMap<Long, Integer>();
        final ConcurrentLinkedQueue<Transition> transitions = new ConcurrentLinkedQueue<Transition>();
        final long startedAt = System.currentTimeMillis();
        volatile boolean active = true;
        volatile long stoppedAt;

        CounterState(int blockCount) {
            blocks = new AtomicLongArray(blockCount);
        }

        void transition(int block) {
            long threadId = Thread.currentThread().getId();
            Integer previous = block == 0 ? previousByThread.remove(Long.valueOf(threadId))
                    : previousByThread.get(Long.valueOf(threadId));
            int from = block == 0 || previous == null ? -1 : previous.intValue();
            previousByThread.put(Long.valueOf(threadId), Integer.valueOf(block));
            long sequence = transitionSequence.incrementAndGet();
            if (sequence > MAX_TRANSITIONS) {
                dropped.incrementAndGet();
                return;
            }
            transitions.add(new Transition(sequence, System.currentTimeMillis(), threadId, from, block));
        }
    }

    private static final class Transition {
        final long sequence;
        final long timestamp;
        final long threadId;
        final int from;
        final int to;

        Transition(long sequence, long timestamp, long threadId, int from, int to) {
            this.sequence = sequence;
            this.timestamp = timestamp;
            this.threadId = threadId;
            this.from = from;
            this.to = to;
        }
    }
}


package dev.whitedev.jpi.agent.cfg;

import java.util.concurrent.ConcurrentHashMap;
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
        return output.toString();
    }

    static void clear() {
        STATES.clear();
    }

    private static final class CounterState {
        final AtomicLongArray blocks;
        final AtomicLong total = new AtomicLong();
        final long startedAt = System.currentTimeMillis();
        volatile boolean active = true;
        volatile long stoppedAt;

        CounterState(int blockCount) {
            blocks = new AtomicLongArray(blockCount);
        }
    }
}


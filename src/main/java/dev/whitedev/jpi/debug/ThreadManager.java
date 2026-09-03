package dev.whitedev.jpi.debug;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ThreadManager {
    private final VirtualMachine vm;

    ThreadManager(VirtualMachine vm) {
        this.vm = vm;
    }

    public List<ThreadView> threads() {
        List<ThreadView> values = new ArrayList<>();
        for (ThreadReference thread : vm.allThreads()) {
            values.add(new ThreadView(thread.uniqueID(), thread.name(), thread.status(),
                    thread.isSuspended(), thread.suspendCount()));
        }
        values.sort(Comparator.comparing(ThreadView::suspended).reversed().thenComparing(ThreadView::name));
        return List.copyOf(values);
    }

    ThreadReference find(long id) {
        for (ThreadReference thread : vm.allThreads()) if (thread.uniqueID() == id) return thread;
        throw new IllegalArgumentException("Debugger thread no longer exists: " + id);
    }

    public record ThreadView(long id, String name, int status, boolean suspended, int suspendCount) {
        @Override public String toString() {
            return (suspended ? "[suspended] " : "[running] ") + name + "  #" + id + "  " + state(status);
        }

        private static String state(int value) {
            return switch (value) {
                case ThreadReference.THREAD_STATUS_MONITOR -> "monitor";
                case ThreadReference.THREAD_STATUS_NOT_STARTED -> "not started";
                case ThreadReference.THREAD_STATUS_RUNNING -> "runnable";
                case ThreadReference.THREAD_STATUS_SLEEPING -> "sleeping";
                case ThreadReference.THREAD_STATUS_WAIT -> "waiting";
                case ThreadReference.THREAD_STATUS_ZOMBIE -> "terminated";
                default -> "unknown";
            };
        }
    }
}

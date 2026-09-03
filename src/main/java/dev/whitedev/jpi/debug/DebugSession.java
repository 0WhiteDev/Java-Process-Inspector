package dev.whitedev.jpi.debug;

import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.Method;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VMDisconnectedException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DebugSession implements AutoCloseable {
    private final VirtualMachine vm;
    private final Process process;
    private final DebugCapabilitySet capabilities;
    private final BreakpointManager breakpoints;
    private final StepManager steps;
    private final ThreadManager threads;
    private final StackFrameManager frames;
    private final BasicExpressionEvaluator evaluator;
    private final MethodLocationResolver locations;
    private final DebugEventLoop events;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile DebugState state;
    private volatile long stoppedThreadId = -1L;

    private DebugSession(VirtualMachine vm, Process process, boolean initiallySuspended) {
        this.vm = vm;
        this.process = process;
        capabilities = DebugCapabilitySet.from(vm);
        breakpoints = new BreakpointManager(vm);
        steps = new StepManager(vm);
        threads = new ThreadManager(vm);
        frames = new StackFrameManager(vm, threads);
        evaluator = new BasicExpressionEvaluator(vm, threads);
        locations = new MethodLocationResolver(vm);
        events = new DebugEventLoop(this, vm, initiallySuspended);
        state = initiallySuspended ? DebugState.SUSPENDED : DebugState.RUNNING;
        events.start();
    }

    public static DebugSession attach(String host, int port) throws Exception {
        VirtualMachine vm = new JdiConnector().attach(host, port);
        try {
            return new DebugSession(vm, null, false);
        } catch (Exception | Error error) {
            vm.dispose();
            throw error;
        }
    }

    public static DebugSession attach(long processId) throws Exception {
        VirtualMachine vm = new JdiConnector().attach(processId);
        try {
            return new DebugSession(vm, null, false);
        } catch (Exception | Error error) {
            vm.dispose();
            throw error;
        }
    }

    public static DebugSession launch(java.io.File jar, List<String> vmArguments,
                                      List<String> applicationArguments) throws Exception {
        JdiConnector.LaunchResult launch = new JdiConnector().launch(jar, vmArguments, applicationArguments);
        try {
            return new DebugSession(launch.virtualMachine(), launch.process(), true);
        } catch (Exception | Error error) {
            launch.virtualMachine().dispose();
            launch.process().destroy();
            throw error;
        }
    }

    public DebugCapabilitySet capabilities() {
        return capabilities;
    }

    public DebugState state() {
        return state;
    }

    public Process launchedProcess() {
        return process;
    }

    public long stoppedThreadId() {
        return stoppedThreadId;
    }

    public BreakpointManager breakpoints() {
        return breakpoints;
    }

    public StepManager steps() {
        return steps;
    }

    public ThreadManager threads() {
        return threads;
    }

    public StackFrameManager frames() {
        return frames;
    }

    public BasicExpressionEvaluator evaluator() {
        return evaluator;
    }

    public MethodLocationResolver locations() {
        return locations;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void continueExecution() {
        requireOpen();
        stoppedThreadId = -1L;
        vm.resume();
        changed(DebugState.RUNNING);
        emit(DebugEvent.Type.RESUME, null, "", "all threads");
    }

    public void pause() {
        requireOpen();
        vm.suspend();
        stoppedThreadId = firstSuspendedThread();
        changed(DebugState.SUSPENDED);
        emit(DebugEvent.Type.PAUSE, stoppedThreadId < 0 ? null : threads.find(stoppedThreadId), "", "all threads");
    }

    public void resumeAll() {
        requireOpen();
        try {
            for (ThreadReference thread : vm.allThreads()) {
                int attempts = 0;
                while (thread.isSuspended() && thread.suspendCount() > 0 && attempts++ < 64) thread.resume();
            }
        } catch (VMDisconnectedException error) {
            disconnected("Target VM disconnected");
            return;
        }
        stoppedThreadId = -1L;
        changed(DebugState.RUNNING);
        emit(DebugEvent.Type.RESUME, null, "", "all suspend counts released");
    }

    public void step(long threadId, StepManager.Depth depth, StepManager.Mode mode) {
        requireSuspended();
        stoppedThreadId = -1L;
        changed(DebugState.RUNNING);
        steps.step(threads.find(threadId), depth, mode);
    }

    public StackFrameManager.Change setValue(StackFrameManager.VariableView variable, String input) throws Exception {
        requireSuspended();
        StackFrameManager.Change change = frames.setValue(variable, input);
        emit(DebugEvent.Type.VALUE_CHANGE, stoppedThread(), change.name(), change.before() + " -> " + change.after());
        return change;
    }

    public String forceEarlyReturn(long threadId, int frameIndex, String input) throws Exception {
        requireSuspended();
        if (!capabilities.forceEarlyReturn()) throw new IllegalStateException("The target JVM does not support force early return");
        if (frameIndex != 0) throw new IllegalArgumentException("Force return is available only for the top frame");
        ThreadReference thread = threads.find(threadId);
        Method method = thread.frame(0).location().method();
        Value value = new ValueEditor().parse(vm, method.returnType(), input);
        try {
            thread.forceEarlyReturn(value);
        } catch (InvalidTypeException | IncompatibleThreadStateException error) {
            throw new IllegalArgumentException("The JVM rejected this return value: " + message(error), error);
        }
        emit(DebugEvent.Type.FORCE_RETURN, thread, method.name(), input);
        return method.declaringType().name() + "." + method.name() + method.signature();
    }

    void stopped(ThreadReference thread, DebugEvent.Type type, String location, String details) {
        stoppedThreadId = thread.uniqueID();
        changed(DebugState.SUSPENDED);
        emit(type, thread, location, details);
    }

    void emit(DebugEvent.Type type, ThreadReference thread, String location, String details) {
        long id = thread == null ? -1L : safeId(thread);
        String name = thread == null ? "" : safeName(thread);
        DebugEvent event = new DebugEvent(type, System.currentTimeMillis(), id, name,
                location == null ? "" : location, details == null ? "" : details);
        for (Listener listener : listeners) listener.onEvent(event);
    }

    void disconnected(String details) {
        if (!closed.compareAndSet(false, true)) return;
        changed(DebugState.DISCONNECTED);
        DebugEvent event = new DebugEvent(DebugEvent.Type.DISCONNECTED, System.currentTimeMillis(), -1L, "", "", details);
        for (Listener listener : listeners) listener.onEvent(event);
    }

    private ThreadReference stoppedThread() {
        return stoppedThreadId < 0 ? null : threads.find(stoppedThreadId);
    }

    private long firstSuspendedThread() {
        for (ThreadManager.ThreadView thread : threads.threads()) {
            if (thread.suspended()) return thread.id();
        }
        return -1L;
    }

    private void changed(DebugState next) {
        state = next;
        for (Listener listener : listeners) listener.onState(next);
    }

    private void requireSuspended() {
        requireOpen();
        if (state != DebugState.SUSPENDED) throw new IllegalStateException("The target must be suspended");
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("Debugger is disconnected");
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        events.close();
        try {
            breakpoints.clear();
            steps.clear(null);
            vm.dispose();
        } catch (RuntimeException ignored) {
        }
        changed(DebugState.DISCONNECTED);
    }

    private static long safeId(ThreadReference thread) {
        try {
            return thread.uniqueID();
        } catch (RuntimeException error) {
            return -1L;
        }
    }

    private static String safeName(ThreadReference thread) {
        try {
            return thread.name();
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public interface Listener {
        default void onState(DebugState state) {
        }

        default void onEvent(DebugEvent event) {
        }
    }
}

package dev.whitedev.jpi.debug;

import com.sun.jdi.Location;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.ThreadDeathEvent;
import com.sun.jdi.event.ThreadStartEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.ThreadDeathRequest;
import com.sun.jdi.request.ThreadStartRequest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class DebugEventLoop implements AutoCloseable {
    private final DebugSession session;
    private final VirtualMachine vm;
    private final boolean holdVmStart;
    private final CountDownLatch initialStart = new CountDownLatch(1);
    private volatile boolean open = true;
    private Thread worker;

    DebugEventLoop(DebugSession session, VirtualMachine vm, boolean holdVmStart) {
        this.session = session;
        this.vm = vm;
        this.holdVmStart = holdVmStart;
    }

    void start() {
        ThreadStartRequest starts = vm.eventRequestManager().createThreadStartRequest();
        starts.setSuspendPolicy(EventRequest.SUSPEND_NONE);
        starts.enable();
        ThreadDeathRequest deaths = vm.eventRequestManager().createThreadDeathRequest();
        deaths.setSuspendPolicy(EventRequest.SUSPEND_NONE);
        deaths.enable();
        worker = new Thread(this::run, "jpi-jdi-events");
        worker.setDaemon(true);
        worker.start();
        if (holdVmStart) {
            try {
                if (!initialStart.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out while waiting for the suspended JVM start event");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while opening the debugger", error);
            }
        }
    }

    private void run() {
        try {
            while (open) process(vm.eventQueue().remove());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (VMDisconnectedException ignored) {
            session.disconnected("");
        } catch (Throwable error) {
            session.disconnected(message(error));
        } finally {
            initialStart.countDown();
        }
    }

    private void process(EventSet set) {
        boolean stopped = false;
        for (Event event : set) {
            if (event instanceof VMStartEvent start && holdVmStart) {
                stopped = true;
                session.stopped(start.thread(), DebugEvent.Type.PAUSE, "VM start", "launched suspended");
                initialStart.countDown();
            } else if (event instanceof ClassPrepareEvent prepared) {
                session.breakpoints().classPrepared(prepared);
                session.emit(DebugEvent.Type.CLASS_PREPARE, prepared.thread(), prepared.referenceType().name(), "");
            } else if (event instanceof BreakpointEvent breakpoint) {
                stopped = true;
                session.stopped(breakpoint.thread(), DebugEvent.Type.BREAK, location(breakpoint.location()), "");
            } else if (event instanceof StepEvent step) {
                stopped = true;
                session.steps().completed((com.sun.jdi.request.StepRequest) step.request());
                session.stopped(step.thread(), DebugEvent.Type.STEP, location(step.location()), "");
            } else if (event instanceof ExceptionEvent exception) {
                stopped = true;
                session.stopped(exception.thread(), DebugEvent.Type.EXCEPTION, location(exception.location()),
                        exception.exception().referenceType().name());
            } else if (event instanceof ThreadStartEvent start) {
                session.emit(DebugEvent.Type.THREAD_START, start.thread(), "", "");
            } else if (event instanceof ThreadDeathEvent death) {
                session.emit(DebugEvent.Type.THREAD_DEATH, death.thread(), "", "");
            } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                open = false;
                session.disconnected("");
            }
        }
        if (!stopped && open) set.resume();
    }

    private static String location(Location location) {
        String suffix = location.lineNumber() < 0 ? "BCI " + location.codeIndex() : "line " + location.lineNumber();
        return location.declaringType().name() + "." + location.method().name()
                + location.method().signature() + " " + suffix;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override public void close() {
        open = false;
        if (worker != null) worker.interrupt();
    }
}

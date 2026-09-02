package dev.whitedev.jpi.debug;

import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;

public final class StepManager {
    private final VirtualMachine vm;

    StepManager(VirtualMachine vm) {
        this.vm = vm;
    }

    public void step(ThreadReference thread, Depth depth, Mode mode) {
        clear(thread);
        int size = mode == Mode.SOURCE ? StepRequest.STEP_LINE : StepRequest.STEP_MIN;
        int jdiDepth = switch (depth) {
            case INTO -> StepRequest.STEP_INTO;
            case OVER -> StepRequest.STEP_OVER;
            case OUT -> StepRequest.STEP_OUT;
        };
        StepRequest request = vm.eventRequestManager().createStepRequest(thread, size, jdiDepth);
        request.addCountFilter(1);
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        request.enable();
        vm.resume();
    }

    void completed(StepRequest request) {
        vm.eventRequestManager().deleteEventRequest(request);
    }

    void clear(ThreadReference thread) {
        for (StepRequest request : vm.eventRequestManager().stepRequests()) {
            if (thread == null || request.thread().equals(thread)) vm.eventRequestManager().deleteEventRequest(request);
        }
    }

    public enum Depth { INTO, OVER, OUT }
    public enum Mode { SOURCE, MINIMAL }
}

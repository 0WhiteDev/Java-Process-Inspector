package dev.whitedev.jpi.debug;

import com.sun.jdi.VirtualMachine;

public record DebugCapabilitySet(boolean forceEarlyReturn, boolean redefineClasses,
                                 boolean popFrames, boolean bytecodes) {
    static DebugCapabilitySet from(VirtualMachine vm) {
        return new DebugCapabilitySet(vm.canForceEarlyReturn(), vm.canRedefineClasses(),
                vm.canPopFrames(), vm.canGetBytecodes());
    }
}

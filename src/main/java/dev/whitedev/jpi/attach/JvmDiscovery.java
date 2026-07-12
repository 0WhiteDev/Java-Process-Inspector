package dev.whitedev.jpi.attach;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class JvmDiscovery {
    public List<JvmDescriptor> discover() {
        String ownPid = ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0];
        List<JvmDescriptor> result = new ArrayList<>();
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            if (!descriptor.id().equals(ownPid)) result.add(new JvmDescriptor(descriptor.id(), descriptor.displayName()));
        }
        result.sort(Comparator.comparing(JvmDescriptor::id));
        return result;
    }
}

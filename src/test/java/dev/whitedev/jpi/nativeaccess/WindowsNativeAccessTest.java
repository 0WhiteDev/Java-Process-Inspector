package dev.whitedev.jpi.nativeaccess;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import org.junit.jupiter.api.Test;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsNativeAccessTest {
    @Test void processSnapshotContainsTheTestJvm() throws Exception {
        WindowsNativeAccess access = new WindowsNativeAccess();
        assumeTrue(access.isSupported());
        int ownPid = Integer.parseInt(ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0]);
        List<ProcessInfo> processes = access.processes();
        assertTrue(processes.stream().anyMatch(process -> process.pid() == ownPid));
    }

    @Test void writesAndReadsBackMemoryInTheCurrentProcess() throws Exception {
        WindowsNativeAccess access = new WindowsNativeAccess();
        assumeTrue(access.isSupported());
        int ownPid = Integer.parseInt(ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0]);
        long before = 0x1020304050607080L;
        long replacement = 0x1122334455667788L;
        Memory buffer = new Memory(Long.BYTES);
        try {
            buffer.setLong(0, before);
            long address = Pointer.nativeValue(buffer);
            MemoryMatch match = new MemoryMatch(address, Long.toString(before), Long.BYTES);

            access.writeMemory(ownPid, Collections.singletonList(match),
                    Long.toString(replacement), MemoryDataType.INT64);

            assertEquals(replacement, buffer.getLong(0),
                    "WriteProcessMemory should change the allocated test buffer");
        } finally {
            buffer.close();
        }
    }
}

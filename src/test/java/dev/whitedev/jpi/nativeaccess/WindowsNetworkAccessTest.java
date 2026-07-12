package dev.whitedev.jpi.nativeaccess;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class WindowsNetworkAccessTest {
    @Test void convertsWindowsNetworkOrderPorts() {
        assertEquals(80, WindowsNetworkAccess.port(0x00005000));
        assertEquals(443, WindowsNetworkAccess.port(0x0000BB01));
    }

    @Test void readsTheCurrentWindowsProcessTable() {
        WindowsNetworkAccess access = new WindowsNetworkAccess();
        if (access.isSupported()) assertDoesNotThrow(() -> access.connections((int) ProcessHandle.current().pid()));
    }
}

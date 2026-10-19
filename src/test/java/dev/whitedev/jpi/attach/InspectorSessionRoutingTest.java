package dev.whitedev.jpi.attach;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;

class InspectorSessionRoutingTest {
    @Test void keepsTunnelOriginExplicitEvenWhenTheRemotePidLooksLocal() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             Socket peer = listener.accept();
             InspectorSession session = new InspectorSession(new JvmDescriptor("123", "Remote JVM"), socket, true)) {
            assertTrue(session.isTunneled());
            assertEquals("123", session.target().id());
            assertThrows(java.io.IOException.class, () -> new AttachService().enableNativeBackend(session, null));
            assertFalse(peer.isClosed());
        }
    }
}

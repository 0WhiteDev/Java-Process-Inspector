package dev.whitedev.jpi.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentOptionsTest {
    @Test void acceptsAuthenticatedLoopbackEndpoint() {
        AgentOptions options = AgentOptions.parse("host=127.0.0.1;port=43123;token=1234567890abcdef");
        assertEquals(43123, options.port());
        assertTrue(options.host().isLoopbackAddress());
        assertEquals(AgentOptions.Mode.CONNECT, options.mode());
        assertEquals(900_000, options.acceptTimeoutMillis());
    }

    @Test void acceptsLoopbackListenerMode() {
        AgentOptions options = AgentOptions.parse(
                "mode=listen;host=127.0.0.1;port=43123;token=1234567890abcdef;acceptTimeoutSeconds=60");
        assertEquals(AgentOptions.Mode.LISTEN, options.mode());
        assertEquals(60_000, options.acceptTimeoutMillis());
    }

    @Test void rejectsRemoteEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptions.parse("host=8.8.8.8;port=43123;token=1234567890abcdef"));
    }

    @Test void rejectsInvalidListenerSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptions.parse("mode=public;host=127.0.0.1;port=43123;token=1234567890abcdef"));
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptions.parse("mode=listen;host=127.0.0.1;port=43123;token=1234567890abcdef;acceptTimeoutSeconds=2"));
    }
}

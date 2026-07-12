package dev.whitedev.jpi.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentOptionsTest {
    @Test void acceptsAuthenticatedLoopbackEndpoint() {
        AgentOptions options = AgentOptions.parse("host=127.0.0.1;port=43123;token=1234567890abcdef");
        assertEquals(43123, options.port()); assertTrue(options.host().isLoopbackAddress());
    }

    @Test void rejectsRemoteEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentOptions.parse("host=8.8.8.8;port=43123;token=1234567890abcdef"));
    }
}

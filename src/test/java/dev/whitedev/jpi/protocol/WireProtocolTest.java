package dev.whitedev.jpi.protocol;

import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class WireProtocolTest {
    @Test void requestRoundTripPreservesOperationAndUnicodePayload() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WireProtocol.writeRequest(new DataOutputStream(bytes), Operation.EXECUTE, WireProtocol.utf8("zażółć"));
        WireProtocol.Request request = WireProtocol.readRequest(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(Operation.EXECUTE, request.operation());
        assertEquals("zażółć", WireProtocol.text(request.payload()));
    }

    @Test void responseRoundTripPreservesFailure() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WireProtocol.writeResponse(new DataOutputStream(bytes), false, WireProtocol.utf8("failure"));
        WireProtocol.Response response = WireProtocol.readResponse(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertFalse(response.success()); assertEquals("failure", response.text());
    }
}

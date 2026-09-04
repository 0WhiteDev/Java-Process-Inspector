package dev.whitedev.jpi.file;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FileProtocolCodecTest {
    @Test void parsesBoundedFileEventBatch() {
        String event = "E\t7\t123\tWRITE\t" + encoded("input.txt") + "\t" + encoded("C:\\input.txt")
                + "\t" + encoded("app.Main") + "\t" + encoded("save") + "\t" + encoded("()V")
                + "\t" + encoded("Worker") + "\tREDIRECT\t3\t" + encoded("sandbox/input.txt")
                + "\t\t" + encoded("app.Main.save") + "\t" + encodedBytes(new byte[]{1, 2, 3}) + "\t42\n";

        FileProtocolCodec.Batch batch = FileProtocolCodec.parseEvents("M\t" + encoded("active") + "\nS\t2\t1\n" + event);

        assertEquals("active", batch.summary());
        assertEquals(2, batch.dropped());
        assertEquals(FileDecision.REDIRECT, batch.events().getFirst().decision());
        assertEquals(42, batch.events().getFirst().callId());
        assertArrayEquals(new byte[]{1, 2, 3}, batch.events().getFirst().payloadPreview());
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodedBytes(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}

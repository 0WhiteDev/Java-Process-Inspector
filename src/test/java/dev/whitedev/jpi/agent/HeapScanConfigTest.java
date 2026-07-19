package dev.whitedev.jpi.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeapScanConfigTest {
    @Test
    void parsesBoundedSearchSettings() throws Exception {
        HeapScanConfig config = HeapScanConfig.parse(
                "root=com.example\nclass=Session\nfield=token\nvalue=abc\nmaxDepth=6\nmaxObjects=2500");

        assertEquals(6, config.maxDepth);
        assertEquals(2500, config.maxObjects);
        assertTrue(config.matchesRoot("com.example.Registry"));
        assertTrue(config.matchesClass("com.example.Session"));
        assertTrue(config.matchesField("accessToken"));
        assertTrue(config.matchesValue("ABC-123"));
    }

    @Test
    void requiresAnExplicitRootAndRejectsUnsafeLimits() {
        assertThrows(IOException.class, () -> HeapScanConfig.parse("class=Session"));
        assertThrows(IOException.class, () -> HeapScanConfig.parse("root=com.example\nmaxDepth=100"));
        assertThrows(IOException.class, () -> HeapScanConfig.parse("root=com.example\ntimeoutMillis=60000"));
    }
}

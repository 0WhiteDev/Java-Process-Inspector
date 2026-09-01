package dev.whitedev.jpi.agent.cfg;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CfgRuntimeTest {
    @AfterEach
    void clear() {
        CfgRuntime.clear();
    }

    @Test
    void preservesOrderedBlockTransitionsAlongsideCounters() {
        CfgRuntime.register("probe", 4);

        CfgRuntime.hit("probe", 0);
        CfgRuntime.hit("probe", 1);
        CfgRuntime.hit("probe", 3);

        String snapshot = CfgRuntime.snapshot("probe");
        assertTrue(snapshot.contains("H\t0\t1"));
        assertTrue(snapshot.matches("(?s).*T\t1\t[0-9]+\t[0-9]+\t-1\t0.*"));
        assertTrue(snapshot.matches("(?s).*T\t2\t[0-9]+\t[0-9]+\t0\t1.*"));
        assertTrue(snapshot.matches("(?s).*T\t3\t[0-9]+\t[0-9]+\t1\t3.*"));
        assertTrue(snapshot.contains("D\t0"));
    }
}

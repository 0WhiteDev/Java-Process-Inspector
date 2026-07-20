package dev.whitedev.jpi.ui.analysis;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CfgGraphModelTest {
    @Test void parsesGraphAndAppliesRuntimeCounts() {
        String graph = "G\t" + encoded("sample.Target") + "\t" + encoded("verify") + "\t"
                + encoded("()Z") + "\t2\t3\t2\t0\n"
                + "B\tB0\t0\t2\t10\t10\ttrue\t\t" + encoded("B0") + "\t"
                + encoded("0000  ALOAD 0\n0001  IFNULL -> B1") + "\n"
                + "B\tB1\t3\t3\t11\t11\ttrue\tB0\t" + encoded("B0,B1") + "\t"
                + encoded("0003  IRETURN") + "\n"
                + "E\tENTRY\tB0\tENTRY\t\n"
                + "E\tB0\tB1\tBRANCH\t" + encoded("true") + "\n"
                + "E\tB1\tEXIT\tRETURN\t\n";

        CfgGraphModel model = CfgGraphModel.parse(graph);
        CfgGraphModel.Snapshot snapshot = model.applySnapshot(
                "S\tcfg-1\tfalse\t100\t200\t7\nH\t0\t7\nH\t1\t0\n");

        assertEquals(2, model.blocks.size());
        assertEquals(3, model.edges.size());
        assertEquals(7L, model.blocks.get(0).executions);
        assertEquals(0L, model.blocks.get(1).executions);
        assertEquals("B0", model.blocks.get(1).immediateDominator);
        assertTrue(model.details(model.blocks.get(1)).contains("Predecessors: B0 [true]"));
        assertFalse(snapshot.active);
        assertEquals(7L, snapshot.total);
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}


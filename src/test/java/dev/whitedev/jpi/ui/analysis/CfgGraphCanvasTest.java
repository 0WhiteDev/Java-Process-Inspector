package dev.whitedev.jpi.ui.analysis;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CfgGraphCanvasTest {
    @Test void countRefreshDoesNotRepublishBlockSelection() throws Exception {
        CfgGraphModel model = CfgGraphModel.parse(
                "G\t" + encoded("sample.Target") + "\t" + encoded("run") + "\t"
                        + encoded("()V") + "\t1\t1\t1\t0\n"
                        + "B\tB0\t0\t0\t1\t1\ttrue\t\t" + encoded("B0") + "\t"
                        + encoded("0000  RETURN") + "\n"
                        + "E\tENTRY\tB0\tENTRY\t\n"
                        + "E\tB0\tEXIT\tRETURN\t\n");
        AtomicInteger selections = new AtomicInteger();

        SwingUtilities.invokeAndWait(() -> {
            CfgGraphCanvas graph = new CfgGraphCanvas();
            graph.setSelectionListener(block -> selections.incrementAndGet());
            graph.setModel(model);
            graph.refreshCounts();
            graph.refreshCounts();
        });

        assertEquals(1, selections.get());
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
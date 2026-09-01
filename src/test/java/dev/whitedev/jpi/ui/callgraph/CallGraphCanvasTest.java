package dev.whitedev.jpi.ui.callgraph;

import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CallGraphCanvasTest {
    @Test
    void laysOutAndRendersWeightedGraph() throws Exception {
        String raw = "G\t1\t2\t1\n"
                + node("sample.Main", "run", "()V", 10, 1_000_000)
                + node("sample.Worker", "tick", "()V", 10_000, 20_000_000)
                + "E\t" + encoded("sample.Main") + "\t" + encoded("run") + "\t" + encoded("()V")
                + "\t" + encoded("sample.Worker") + "\t" + encoded("tick") + "\t" + encoded("()V")
                + "\t10000\t2\tfalse\n";
        CallGraphModel model = CallGraphModel.parse(raw);
        AtomicReference<Dimension> preferred = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            CallGraphCanvas canvas = new CallGraphCanvas(new DeobfuscationWorkspace());
            canvas.setView(model.view("", 0L, 100, CallGraphModel.HeatMetric.CALLS));
            Dimension size = canvas.getPreferredSize();
            canvas.setSize(size);
            BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            canvas.paint(graphics);
            graphics.dispose();
            preferred.set(size);
        });

        assertTrue(preferred.get().width > 500);
        assertTrue(preferred.get().height > 100);
    }

    private static String node(String owner, String method, String descriptor, long calls, long nanos) {
        return "N\t" + encoded(owner) + "\t" + encoded(method) + "\t" + encoded(descriptor)
                + "\t" + calls + "\t" + calls + "\t" + nanos + "\t0\t1\ttrue\n";
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}

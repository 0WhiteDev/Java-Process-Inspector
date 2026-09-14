package dev.whitedev.jpi.ui.profiler;

import dev.whitedev.jpi.profiler.ProfilerReport;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FlameGraphCanvasTest {
    @Test void laysOutAndRendersWeightedStacks() throws Exception {
        ProfilerReport report = ProfilerReport.parse("R\t1\t1001\t1000\t20\tfalse\n"
                + flame(12, "main\nexample.Game.run\nexample.Renderer.render")
                + flame(8, "main\nexample.Game.run\nexample.World.tick"));
        AtomicReference<Dimension> preferred = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            FlameGraphCanvas canvas = new FlameGraphCanvas();
            canvas.setMetrics(report.category(dev.whitedev.jpi.profiler.ProfilerCategory.CPU).flames());
            Dimension size = canvas.getPreferredSize();
            canvas.setSize(size);
            BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            canvas.paint(graphics);
            graphics.dispose();
            preferred.set(size);
        });

        assertTrue(preferred.get().width >= 1200);
        assertTrue(preferred.get().height >= 220);
    }

    private static String flame(long value, String path) {
        return "F\tCPU\t" + value + "\t" + value + "\t"
                + Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8)) + "\n";
    }
}

package dev.whitedev.jpi.ui.threads;

import dev.whitedev.jpi.threads.ThreadAnalysisSnapshot;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadLockGraphCanvasTest {
    @Test
    void rendersLockDependencyGraph() {
        ThreadAnalysisSnapshot snapshot = ThreadAnalysisSnapshot.parse(
                "M\t1000\t500\t1\t1\t1\t0\ttrue\ttrue\ttrue\ttrue\n"
                        + "T\t7\tWorker\tBLOCKED\tfalse\t5\t1\t10\t1\t10\t0\tLock@1\t8\tOwner\tfalse\tfalse\tfalse\ttrue\t500\n"
                        + "L\t7\tmonitor\tLock@2\t0\n");
        ThreadLockGraphCanvas canvas = new ThreadLockGraphCanvas();
        canvas.setSnapshot(snapshot);
        canvas.setSize(canvas.getPreferredSize());
        BufferedImage image = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        canvas.paint(graphics);
        graphics.dispose();
        assertTrue(canvas.getPreferredSize().height > 0);
    }
}

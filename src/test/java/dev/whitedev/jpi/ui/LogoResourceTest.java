package dev.whitedev.jpi.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class LogoResourceTest {
    @Test void logoRendersFromThePackagedSvgResource() {
        FlatSVGIcon icon = new FlatSVGIcon("assets/logo.svg", 128, 128);
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        int opaquePixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) opaquePixels++;
            }
        }
        assertTrue(opaquePixels > 5_000, "logo should render a visible mark");
    }
}

package dev.whitedev.jpi.ui;

import dev.whitedev.jpi.plugin.runtime.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class WorkspaceDesignIT {
    @Test
    void everyDestinationRendersAtMinimumAndDesktopSize(@TempDir Path directory) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        List<BufferedImage> sheets = new ArrayList<>();
        List<BufferedImage> previews = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            Ui.install();
            MainFrame frame = new MainFrame(new PluginManager(directory));
            try {
                frame.addNotify();
                List<Component> components = new ArrayList<>();
                collect(frame.getContentPane(), components);
                List<JButton> navigation = components.stream().filter(JButton.class::isInstance)
                        .map(JButton.class::cast)
                        .filter(button -> "toolBarButton".equals(button.getClientProperty("JButton.buttonType")))
                        .filter(button -> !"Command Palette".equals(button.getText()))
                        .filter(button -> button.getParent().getLayout() instanceof BoxLayout)
                        .toList();
                JPanel cards = components.stream().filter(JPanel.class::isInstance).map(JPanel.class::cast)
                        .filter(panel -> panel.getLayout() instanceof CardLayout && panel.getComponentCount() >= 30)
                        .findFirst().orElseThrow();
                assertEquals(cards.getComponentCount(), navigation.size());
                assertEquals(Ui.TEXT, UIManager.getColor("TitlePane.foreground"));
                for (int width : List.of(1024, 1440)) {
                    frame.setSize(width, width == 1024 ? 700 : 900);
                    BufferedImage sheet = new BufferedImage(4 * 320, ((navigation.size() + 3) / 4) * 224,
                            BufferedImage.TYPE_INT_RGB);
                    Graphics2D overview = sheet.createGraphics();
                    Set<Component> visited = new HashSet<>();
                    try {
                        int index = 0;
                        for (JButton button : navigation) {
                            button.doClick(0);
                            frame.validate();
                            assertNotNull(button.getToolTipText(), button.getText());
                            Component visible = List.of(cards.getComponents()).stream().filter(Component::isVisible)
                                    .findFirst().orElseThrow();
                            assertTrue(visited.add(visible), button.getText());
                            assertTrue(visible.getWidth() > 0 && visible.getHeight() > 0, button.getText());
                            if (width == 1440 && button.getText().equals("Overview")) {
                                BufferedImage preview = new BufferedImage(frame.getRootPane().getWidth(),
                                        frame.getRootPane().getHeight(), BufferedImage.TYPE_INT_RGB);
                                Graphics2D graphics = preview.createGraphics();
                                try {
                                    frame.getRootPane().printAll(graphics);
                                } finally {
                                    graphics.dispose();
                                }
                                previews.add(preview);
                            }
                            Graphics2D tile = (Graphics2D) overview.create(index % 4 * 320, index / 4 * 224, 320, 224);
                            try {
                                tile.scale(320.0 / frame.getContentPane().getWidth(),
                                        224.0 / frame.getContentPane().getHeight());
                                frame.getContentPane().printAll(tile);
                            } finally {
                                tile.dispose();
                            }
                            index++;
                        }
                    } finally {
                        overview.dispose();
                    }
                    sheets.add(sheet);
                }
            } finally {
                frame.dispose();
            }
        });
        for (int i = 0; i < sheets.size(); i++) {
            ImageIO.write(sheets.get(i), "png", Path.of("target", "workspace-layout-" + i + ".png").toFile());
        }
        ImageIO.write(previews.getFirst(), "png", Path.of("target", "workspace-overview.png").toFile());
    }

    private static void collect(Container parent, List<Component> result) {
        for (Component child : parent.getComponents()) {
            result.add(child);
            if (child instanceof Container container) collect(container, result);
        }
    }
}

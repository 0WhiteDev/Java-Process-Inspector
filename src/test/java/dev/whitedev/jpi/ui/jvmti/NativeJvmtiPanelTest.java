package dev.whitedev.jpi.ui.jvmti;

import dev.whitedev.jpi.attach.ImprovedAttachService;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeJvmtiPanelTest {
    @Test void disablesTargetActionsWhileDisconnectedAndKeepsToolbarButtonsVisible() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            NativeJvmtiPanel panel = new NativeJvmtiPanel(new ImprovedAttachService(), () -> {});
            panel.setSession(null);
            for (int width : new int[]{1200, 700, 500, 1200}) {
                panel.setSize(width, 900);
                for (int pass = 0; pass < 4; pass++) layout(panel);
                for (String label : List.of("Load through JVM / Bind", "Load with native injection", "Bind startup agent", "Refresh capabilities", "Browse with JVMTI", "Inspect class heap", "Start interception", "Stop interception", "Refresh capture events", "Save captured class...")) {
                    JButton button = button(panel, label);
                    assertNotNull(button);
                    assertFalse(button.isEnabled());
                    assertTrue(button.getX() + button.getWidth() <= button.getParent().getWidth(), label);
                    assertTrue(button.getY() + button.getHeight() <= button.getParent().getHeight(), label);
                }
            }
        });
    }

    private static JButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container parent) {
                JButton result = button(parent, text);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container parent) layout(parent);
    }
}

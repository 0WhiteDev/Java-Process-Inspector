package dev.whitedev.jpi.ui.fingerprint;

import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TargetFingerprintPanelTest {
    @Test void disablesTargetActionsWhenDisconnectedAndKeepsButtonsInsideTheToolbar() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TargetFingerprintPanel panel = new TargetFingerprintPanel(new DeobfuscationWorkspace(), List::of, message -> {});
            panel.setSession(null);
            for (int width : new int[]{1200, 650, 500, 1200}) {
                panel.setSize(width, 800);
                for (int pass = 0; pass < 4; pass++) layout(panel);
                for (String label : List.of("Capture / Compare", "Use as workspace baseline", "Export fingerprint...")) {
                    JButton button = button(panel, label);
                    assertFalse(button.isEnabled());
                    assertTrue(button.getX() + button.getWidth() <= button.getParent().getWidth(), label);
                    assertTrue(button.getY() + button.getHeight() <= button.getParent().getHeight(), label);
                }
            }
        });
    }

    private static JButton button(Container root, String label) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && label.equals(button.getText())) return button;
            if (child instanceof Container parent) {
                JButton found = button(parent, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container parent) layout(parent);
    }
}

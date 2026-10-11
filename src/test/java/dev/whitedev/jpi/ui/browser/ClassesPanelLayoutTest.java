package dev.whitedev.jpi.ui.browser;

import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClassesPanelLayoutTest {
    @Test
    void keepsClassAndMethodActionsInsideTheirToolbarsAfterResizing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ClassesPanel panel = new ClassesPanel(new DeobfuscationWorkspace(), null, null, null,
                    () -> {}, () -> {}, () -> {});
            for (int width : new int[]{1600, 1100, 900, 1600}) {
                panel.setSize(width, 1000);
                for (int pass = 0; pass < 5; pass++) layout(panel);
                for (String label : List.of("Class diff / History", "Apply source", "Rollback original", "Dump all")) {
                    assertContained(button(panel, label));
                }
                for (JTabbedPane tabs : tabs(panel)) {
                    for (int index = 0; index < tabs.getTabCount(); index++) {
                        tabs.setSelectedIndex(index);
                        for (int pass = 0; pass < 5; pass++) layout(panel);
                        for (JButton button : buttons(tabs.getSelectedComponent())) {
                            if (button.isVisible() && button.getText() != null && !button.getText().isEmpty()) assertContained(button);
                        }
                    }
                }
            }
        });
    }

    private static void assertContained(JButton button) {
        Container parent = button.getParent();
        assertTrue(button.getX() >= 0 && button.getY() >= 0, button.getText());
        assertTrue(button.getX() + button.getWidth() <= parent.getWidth(), button.getText());
        assertTrue(button.getY() + button.getHeight() <= parent.getHeight(), button.getText());
        assertTrue(button.getWidth() > 0 && button.getHeight() > 0, button.getText());
    }

    private static JButton button(Container parent, String label) {
        return buttons(parent).stream().filter(button -> label.equals(button.getText())).findFirst().orElseThrow();
    }

    private static List<JButton> buttons(Component root) {
        List<JButton> result = new ArrayList<>();
        if (root instanceof JButton button) result.add(button);
        if (root instanceof Container parent) for (Component child : parent.getComponents()) result.addAll(buttons(child));
        return result;
    }

    private static List<JTabbedPane> tabs(Container root) {
        List<JTabbedPane> result = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (child instanceof JTabbedPane tabs) result.add(tabs);
            else if (child instanceof Container parent) result.addAll(tabs(parent));
        }
        return result;
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container parent) layout(parent);
    }
}

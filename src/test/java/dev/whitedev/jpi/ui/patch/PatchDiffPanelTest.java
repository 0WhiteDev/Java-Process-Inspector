package dev.whitedev.jpi.ui.patch;

import dev.whitedev.jpi.analysis.patch.PatchEntry;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PatchDiffPanelTest {
    @Test
    void keepsDiffActionsVisibleWhenTheDialogIsNarrow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PatchDiffPanel panel = new PatchDiffPanel(null, "A@1", "A", null, "", "", "", () -> false, () -> {});
            try {
                for (int width : new int[]{1200, 650, 450, 1200}) {
                    panel.setSize(width, 800);
                    for (int pass = 0; pass < 3; pass++) layout(panel);
                    Container controls = (Container) panel.getComponent(0);
                    for (Component control : controls.getComponents()) {
                        assertTrue(control.getX() >= 0);
                        assertTrue(control.getY() >= 0);
                        assertTrue(control.getX() + control.getWidth() <= controls.getWidth());
                        assertTrue(control.getY() + control.getHeight() <= controls.getHeight());
                    }
                }
            } finally {
                panel.close();
            }
        });
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) {
            if (child instanceof Container parent) layout(parent);
        }
    }

    @Test
    void requiresMatchingPreviewBeforeEnablingRollbackAndDisablesOnClose() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                AtomicBoolean connected = new AtomicBoolean(false);
                PatchDiffPanel panel = new PatchDiffPanel(null, "A@1", "A", null, "", "", "", connected::get, () -> {});
                JButton rollback = (JButton) field(panel, "rollback").get(panel);
                assertFalse(rollback.isEnabled());
                field(panel, "entries").set(panel, List.of(new PatchEntry(1, 1, "B@2", "B", "source", 1, 1, "", "")));
                field(panel, "busy").set(panel, true);
                DefaultTableModel model = (DefaultTableModel) field(panel, "historyModel").get(panel);
                model.addRow(new Object[]{1L, "00:00", "B", "source", 1, 1});
                ((JTable) field(panel, "history").get(panel)).setRowSelectionInterval(0, 0);
                field(panel, "busy").set(panel, false);
                connected.set(true);
                field(panel, "rollbackCurrent").set(panel, new byte[]{1});
                field(panel, "rollbackEntry").set(panel, 2L);
                var controls = PatchDiffPanel.class.getDeclaredMethod("controls");
                controls.setAccessible(true);
                controls.invoke(panel);
                assertFalse(rollback.isEnabled());
                field(panel, "rollbackEntry").set(panel, 1L);
                controls.invoke(panel);
                assertTrue(rollback.isEnabled());
                panel.close();
                assertFalse(rollback.isEnabled());
                assertFalse(((JButton) field(panel, "compile").get(panel)).isEnabled());
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
    }

    private static Field field(PatchDiffPanel panel, String name) throws Exception {
        Field field = panel.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}

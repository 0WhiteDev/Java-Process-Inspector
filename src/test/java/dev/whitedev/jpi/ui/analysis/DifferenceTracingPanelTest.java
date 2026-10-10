package dev.whitedev.jpi.ui.analysis;

import dev.whitedev.jpi.analysis.difference.DifferenceReport;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DifferenceTracingPanelTest {
    @Test
    void hidesCommonRowsWithoutRemovingChangedMethods() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                DifferenceTracingPanel panel = new DifferenceTracingPanel(new DeobfuscationWorkspace(), new RuntimeTimelineStore());
                assertEquals("Baseline", ((JTextField) field(panel, "baselineName").get(panel)).getText());
                assertEquals("Action", ((JTextField) field(panel, "actionName").get(panel)).getText());
                assertFalse(((JButton) field(panel, "startA").get(panel)).isEnabled());
                JSlider slider = (JSlider) field(panel, "hideCommon").get(panel);
                assertEquals(95, slider.getValue());
                field(panel, "report").set(panel, new DifferenceReport(2, List.of(), List.of(), 0, 1, 0, null,
                        List.of(), List.of(new DifferenceReport.MethodCalls("Noise.tick()V", 100, 100, true),
                        new DifferenceReport.MethodCalls("Check.test()Z", 1, 1, false))));
                slider.setValue(100);
                DefaultTableModel model = (DefaultTableModel) field(panel, "methodModel").get(panel);
                assertEquals(1, model.getRowCount());
                assertEquals("Check.test()Z", model.getValueAt(0, 0));
                assertEquals(Long.class, model.getColumnClass(1));
                slider.setValue(0);
                assertEquals(2, model.getRowCount());
                panel.setSession(null);
                assertEquals(0, model.getRowCount());
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
    }

    private static Field field(DifferenceTracingPanel panel, String name) throws Exception {
        Field field = panel.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}

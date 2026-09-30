package dev.whitedev.jpi.ui.exceptions;

import dev.whitedev.jpi.exceptions.ExceptionSnapshot;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionAnalyzerPanelTest {
    @Test void refreshPreservesSelectionAndStackScrollPosition() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var panel = new ExceptionAnalyzerPanel((action, selection) -> { });
                render(panel, snapshot(1));
                JTable types = field(panel, "types", JTable.class);
                types.setRowSelectionInterval(0, 0);
                JTextArea stack = field(panel, "stack", JTextArea.class);
                stack.setCaretPosition(5);
                render(panel, snapshot(2));
                assertEquals(0, types.getSelectedRow());
                assertEquals(5, stack.getCaretPosition());
                assertEquals(2L, types.getValueAt(0, 1));
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    @Test void filterUsesLiteralTextInsteadOfRegularExpressions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                var panel = new ExceptionAnalyzerPanel((action, selection) -> { });
                render(panel, snapshot(1));
                JTextField filter = field(panel, "filter", JTextField.class);
                JTable types = field(panel, "types", JTable.class);
                filter.setText("EXAMPLE");
                assertEquals(1, types.getRowCount());
                filter.setText("[");
                assertEquals(0, types.getRowCount());
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    private static ExceptionSnapshot snapshot(long count) {
        var location = new ExceptionSnapshot.Location(count, "example.Service", "run", "()V", 42, "example.Service.run:42\ncaller.run:10");
        return new ExceptionSnapshot(1000, "recording", 30, 0, "recording", List.of(
                new ExceptionSnapshot.Entry("example.Failure", count, count, 1000, 1000, 1, false, List.of(location))));
    }

    private static void render(ExceptionAnalyzerPanel panel, ExceptionSnapshot snapshot) throws Exception {
        Method method = ExceptionAnalyzerPanel.class.getDeclaredMethod("render", ExceptionSnapshot.class);
        method.setAccessible(true);
        method.invoke(panel, snapshot);
    }

    private static <T> T field(ExceptionAnalyzerPanel panel, String name, Class<T> type) throws Exception {
        Field field = ExceptionAnalyzerPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(panel));
    }
}

package dev.whitedev.jpi.ui.debug;

import dev.whitedev.jpi.debug.watch.WatchExpressionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WatchesPanelTest {
    @TempDir Path directory;

    @Test void keepsExpressionsButClearsValuesWithoutSuspendedFrame() throws Exception {
        WatchExpressionStore store = new WatchExpressionStore(directory.resolve("watches.txt"));
        store.save(List.of("player.health", "session.token"));
        SwingUtilities.invokeAndWait(() -> {
            try {
                WatchesPanel panel = new WatchesPanel(store);
                JTable table = field(panel, "watches", JTable.class);
                assertEquals(2, table.getRowCount());
                table.setValueAt("sensitive value", 1, 1);
                panel.setContext(null, null);
                assertEquals(2, table.getRowCount());
                assertEquals("", table.getValueAt(1, 1));
                assertEquals("Not suspended / no frame", table.getValueAt(1, 2));
                table.setRowSelectionInterval(0, 0);
                assertFalse(field(panel, "arm", JButton.class).isEnabled());
                assertTrue(field(panel, "edit", JButton.class).isEnabled());
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    private static <T> T field(WatchesPanel panel, String name, Class<T> type) throws Exception {
        Field field = WatchesPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(panel));
    }
}

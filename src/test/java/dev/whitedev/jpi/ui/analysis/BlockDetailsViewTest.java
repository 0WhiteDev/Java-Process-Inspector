package dev.whitedev.jpi.ui.analysis;

import org.junit.jupiter.api.Test;

import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockDetailsViewTest {
    @Test void executionUpdateKeepsTheScrollPosition() throws Exception {
        AtomicInteger before = new AtomicInteger();
        AtomicInteger after = new AtomicInteger();

        SwingUtilities.invokeAndWait(() -> {
            BlockDetailsView details = new BlockDetailsView();
            StringBuilder content = new StringBuilder("B10\nExecutions: 0\n");
            for (int index = 0; index < 80; index++) content.append(index).append(" ALOAD 0\n");
            details.setContent(content.toString());
            JScrollPane scroll = new JScrollPane(details);
            scroll.setSize(360, 180);
            scroll.doLayout();
            details.setSize(details.getPreferredSize());
            scroll.getVerticalScrollBar().setValue(240);
            before.set(scroll.getVerticalScrollBar().getValue());
            details.updateExecution(9L);
            after.set(scroll.getVerticalScrollBar().getValue());
            assertEquals("Executions: 9", details.getModel().getElementAt(1));
        });

        assertEquals(before.get(), after.get());
    }
}
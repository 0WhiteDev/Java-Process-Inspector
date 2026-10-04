package dev.whitedev.jpi.ui.context;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ContextActionsTest {
    @Test void exposesOnlyActionsSupportedByTheSelectedSymbol() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ContextActions actions = new ContextActions(() -> true, (action, target) -> { });
            List<String> method = labels(actions.menu(AnalysisTarget.method("c:1", "a.b", "run", "()V")));
            assertTrue(method.containsAll(List.of("Find static usages", "Find runtime usages", "Trace callers",
                    "Trace callees", "Open CFG", "Add breakpoint", "Investigate")));
            assertFalse(method.contains("Watch writes"));
            assertEquals(List.of("Find static usages", "Watch writes", "Investigate"),
                    labels(actions.menu(AnalysisTarget.field("c:1", "a.b", "value", "I"))));
            assertEquals(List.of("Find static usages", "Find runtime usages", "Investigate"),
                    labels(actions.menu(AnalysisTarget.type("c:1", "a.b"))));
            assertEquals(List.of("Find static usages", "Investigate"),
                    labels(actions.menu(AnalysisTarget.constant("https://example.test"))));
            assertEquals(0, actions.menu(null).getComponentCount());
            assertEquals(List.of("Investigate"),
                    labels(actions.menu(AnalysisTarget.method("a.b", "a.b", "run", ""))));
        });
    }

    @Test void preservesOriginalDefinitionAndDescriptorWhenDispatching() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<AnalysisTarget> received = new AtomicReference<>();
            AnalysisTarget target = AnalysisTarget.method("c:71", "obfuscated.a", "a", "(Ljava/lang/String;)Z");
            ContextActions actions = new ContextActions(() -> true, (action, selected) -> received.set(selected));
            ((JMenuItem) actions.menu(target).getComponent(0)).doClick();
            assertSame(target, received.get());
            assertEquals("c:71", received.get().identifier());
            assertEquals("(Ljava/lang/String;)Z", received.get().descriptor());
        });
    }

    @Test void disablesDetachedActionsAndRechecksConnectionAtDispatch() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicBoolean attached = new AtomicBoolean(true);
            AtomicBoolean called = new AtomicBoolean();
            ContextActions actions = new ContextActions(attached::get, (action, target) -> called.set(true));
            AnalysisTarget target = AnalysisTarget.type("c:1", "a.b");
            JMenuItem item = (JMenuItem) actions.menu(target).getComponent(0);
            attached.set(false);
            item.doClick();
            assertFalse(called.get());
            for (var component : actions.menu(target).getComponents()) assertFalse(component.isEnabled());
        });
    }

    @Test void selectsClickedViewRowBeforeResolvingItsModelTarget() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTable table = new JTable(new DefaultTableModel(new Object[][]{{"z"}, {"a"}}, new Object[]{"Class"}));
            table.setSize(240, 120);
            table.setAutoCreateRowSorter(true);
            table.getRowSorter().toggleSortOrder(0);
            assertTrue(ContextActions.selectAt(table, new Point(10, 5)));
            assertEquals(1, table.convertRowIndexToModel(table.getSelectedRow()));
            assertFalse(ContextActions.selectAt(table, new Point(10, 100)));
            assertEquals(0, table.getSelectedRow());
        });
    }

    @Test void ignoresEmptyListAndTreeSpaceAndProvidesKeyboardAccess() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JList<String> list = new JList<>(new String[]{"a"});
            list.setFixedCellHeight(20);
            list.setSize(200, 120);
            assertFalse(ContextActions.selectAt(list, new Point(10, 100)));
            assertEquals(-1, list.getSelectedIndex());
            assertTrue(ContextActions.selectAt(list, new Point(10, 5)));
            assertEquals(0, list.getSelectedIndex());
            JTree tree = new JTree();
            tree.setSize(200, 120);
            assertFalse(ContextActions.selectAt(tree, new Point(199, 119)));
            ContextActions actions = new ContextActions(() -> true, (action, target) -> { });
            actions.install(list, () -> AnalysisTarget.type("c:1", "a.b"));
            assertNotNull(list.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_F10, KeyEvent.SHIFT_DOWN_MASK)));
        });
    }

    private static List<String> labels(JPopupMenu menu) {
        return Arrays.stream(menu.getComponents()).map(component -> ((JMenuItem) component).getText()).toList();
    }
}

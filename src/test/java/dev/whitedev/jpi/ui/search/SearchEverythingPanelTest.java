package dev.whitedev.jpi.ui.search;

import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.deobfuscation.MappingEntry;
import dev.whitedev.jpi.deobfuscation.MappingKind;
import dev.whitedev.jpi.search.SearchCategory;
import dev.whitedev.jpi.search.SearchHit;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SearchEverythingPanelTest {
    @Test
    void searchesOfflineMappingsAndOpensTheSelectedResultWithoutAttaching() throws Exception {
        CountDownLatch rendered = new CountDownLatch(1);
        AtomicReference<SearchEverythingPanel> panel = new AtomicReference<>();
        AtomicReference<SearchHit> opened = new AtomicReference<>();
        MappingEntry entry = new MappingEntry(MappingKind.CLASS, "", "a.b.Auth", "", -1, 0);
        entry.setMappedName("AuthenticationManager");
        SwingUtilities.invokeAndWait(() -> {
            DeobfuscationWorkspace workspace = new DeobfuscationWorkspace();
            workspace.update(entry);
            SearchEverythingPanel view = new SearchEverythingPanel(workspace, new RuntimeTimelineStore(), () -> null, opened::set);
            panel.set(view);
            view.setSession(null);
            JTable table = component(view, JTable.class);
            table.getModel().addTableModelListener(event -> {
                if (table.getRowCount() > 0) rendered.countDown();
            });
            JTextField query = component(view, JTextField.class);
            query.setText("authentication");
            query.postActionEvent();
        });
        try {
            assertTrue(rendered.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                var view = panel.get();
                assertEquals(10, component(view, JList.class).getModel().getSize());
                JTable table = component(view, JTable.class);
                assertEquals(1, table.getRowCount());
                JButton open = components(view).stream().filter(c -> c instanceof JButton b && b.getText().equals("Open result"))
                        .map(c -> (JButton) c).findFirst().orElseThrow();
                open.doClick();
                assertEquals(SearchCategory.MAPPINGS, opened.get().category());
                assertEquals(entry.key(), opened.get().value());
                for (int width : new int[]{1200, 900}) {
                    view.setSize(width, 700);
                    layout(view);
                    assertTrue(component(view, JTextField.class).getWidth() > 100);
                }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> panel.get().close());
        }
    }

    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container container) layout(container);
    }

    private static <T> T component(Container parent, Class<T> type) {
        return components(parent).stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    private static List<Component> components(Container parent) {
        List<Component> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            result.add(child);
            if (child instanceof Container container) result.addAll(components(container));
        }
        return result;
    }
}

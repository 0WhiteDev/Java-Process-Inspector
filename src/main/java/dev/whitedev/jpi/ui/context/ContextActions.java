package dev.whitedev.jpi.ui.context;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.Point;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class ContextActions {
    private final BooleanSupplier connected;
    private final BiConsumer<ContextAction, AnalysisTarget> handler;

    public ContextActions(BooleanSupplier connected, BiConsumer<ContextAction, AnalysisTarget> handler) {
        this.connected = connected;
        this.handler = handler;
    }

    public JPopupMenu menu(AnalysisTarget target) {
        JPopupMenu menu = new JPopupMenu();
        for (ContextAction action : ContextAction.values()) {
            if (!action.supports(target)) continue;
            JMenuItem item = new JMenuItem(action.label());
            item.setEnabled(connected.getAsBoolean());
            item.addActionListener(event -> {
                if (connected.getAsBoolean()) handler.accept(action, target);
            });
            menu.add(item);
        }
        return menu;
    }

    public void install(JComponent source, Supplier<AnalysisTarget> selection) {
        install(source, selection, ignored -> { });
    }

    public void install(JComponent source, Supplier<AnalysisTarget> selection,
                        java.util.function.Consumer<Point> selectPoint) {
        source.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { show(event); }
            @Override public void mouseReleased(MouseEvent event) { show(event); }

            private void show(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                if (!selectAt(source, event.getPoint())) return;
                selectPoint.accept(event.getPoint());
                JPopupMenu popup = menu(selection.get());
                if (popup.getComponentCount() > 0) popup.show(source, event.getX(), event.getY());
            }
        });
        source.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F10, KeyEvent.SHIFT_DOWN_MASK), "analysis-context");
        source.getActionMap().put("analysis-context", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                JPopupMenu popup = menu(selection.get());
                if (popup.getComponentCount() > 0) popup.show(source, 12, 12);
            }
        });
    }

    static boolean selectAt(JComponent source, Point point) {
        if (source instanceof JTable table) {
            int row = table.rowAtPoint(point);
            if (row < 0) return false;
            table.setRowSelectionInterval(row, row);
        } else if (source instanceof JList<?> list) {
            int row = list.locationToIndex(point);
            if (row < 0 || !list.getCellBounds(row, row).contains(point)) return false;
            list.setSelectedIndex(row);
        } else if (source instanceof JTree tree) {
            var path = tree.getPathForLocation(point.x, point.y);
            if (path == null) return false;
            tree.setSelectionPath(path);
        }
        return true;
    }
}

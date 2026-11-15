package dev.whitedev.jpi.ui.context;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.Point;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import dev.whitedev.jpi.ui.Ui;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Consumer;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

public final class ContextActions {
    private static final String SELECTION = "jpi.analysis.selection";
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
            item.setEnabled(action == ContextAction.COPY_JVM_IDENTIFIER || connected.getAsBoolean());
            item.addActionListener(event -> execute(action, target));
            menu.add(item);
        }
        return menu;
    }

    public void execute(ContextAction action, AnalysisTarget target) {
        if (!action.supports(target)) return;
        if (action == ContextAction.COPY_JVM_IDENTIFIER) {
            try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(target.qualifiedJvmIdentifier()), null); }
            catch (RuntimeException error) { Ui.error(null, error); }
        } else if (connected.getAsBoolean()) handler.accept(action, target);
    }

    public static void bindSelection(JComponent component, Supplier<AnalysisTarget> selection) { component.putClientProperty(SELECTION, selection); }

    public void appendToPopup(JPopupMenu popup, Supplier<AnalysisTarget> selection) {
        JMenu actions = new JMenu("JPI actions");
        popup.addSeparator();
        popup.add(actions);
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
                actions.removeAll();
                JPopupMenu available = menu(selection.get());
                while (available.getComponentCount() > 0) actions.add(available.getComponent(0));
                actions.setEnabled(actions.getMenuComponentCount() > 0);
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {}
            @Override public void popupMenuCanceled(PopupMenuEvent event) {}
        });
    }

    public static AnalysisTarget selection(Component focused, JComponent activeView) {
        if (focused != null && SwingUtilities.isDescendingFrom(focused, activeView)) {
            for (Component current = focused; current != null; current = current.getParent()) {
                if (current instanceof JComponent component && component.getClientProperty(SELECTION) instanceof Supplier<?> supplier)
                    return supplier.get() instanceof AnalysisTarget target ? target : null;
                if (current == activeView) break;
            }
        }
        return selectedIn(activeView);
    }

    private static AnalysisTarget selectedIn(JComponent component) {
        if (component.getClientProperty(SELECTION) instanceof Supplier<?> supplier)
            return supplier.get() instanceof AnalysisTarget target ? target : null;
        for (Component child : component.getComponents()) if (child instanceof JComponent candidate && child.isVisible()) {
            AnalysisTarget target = selectedIn(candidate);
            if (target != null) return target;
        }
        return null;
    }

    public void install(JComponent source, Supplier<AnalysisTarget> selection) {
        install(source, selection, ignored -> { });
    }

    public void install(JComponent source, Supplier<AnalysisTarget> selection,
                        Consumer<Point> selectPoint) {
        bindSelection(source, selection);
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

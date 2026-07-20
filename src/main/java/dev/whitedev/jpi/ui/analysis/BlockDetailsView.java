package dev.whitedev.jpi.ui.analysis;

import dev.whitedev.jpi.ui.Ui;

import javax.swing.AbstractAction;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;
import java.awt.Component;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;

final class BlockDetailsView extends JList<String> {
    private static final String EXECUTION_PREFIX = "Executions: ";

    private final DefaultListModel<String> lines = new DefaultListModel<String>();

    BlockDetailsView() {
        setModel(lines);
        setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        setBackground(Ui.BACKGROUND);
        setForeground(Ui.TEXT);
        setSelectionBackground(Ui.SURFACE_LIGHT);
        setSelectionForeground(Ui.TEXT);
        setFixedCellHeight(19);
        setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                     boolean selected, boolean focused) {
                JComponent component = (JComponent) super.getListCellRendererComponent(
                        list, value, index, selected, focused);
                component.setBorder(new EmptyBorder(0, 12, 0, 12));
                return component;
            }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(
                KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "copy-lines");
        getActionMap().put("copy-lines", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                copyLines();
            }
        });
    }

    void setContent(String value) {
        String normalized = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
        lines.clear();
        for (String line : normalized.split("\n", -1)) lines.addElement(line);
        clearSelection();
        if (!lines.isEmpty()) ensureIndexIsVisible(0);
    }

    void updateExecution(long executions) {
        String replacement = EXECUTION_PREFIX + executions;
        for (int index = 0; index < lines.size(); index++) {
            if (!lines.get(index).startsWith(EXECUTION_PREFIX)) continue;
            if (!replacement.equals(lines.get(index))) lines.set(index, replacement);
            return;
        }
    }

    @Override public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport && getPreferredSize().width <= getParent().getWidth();
    }

    private void copyLines() {
        List<String> selected = getSelectedValuesList();
        StringBuilder output = new StringBuilder();
        if (selected.isEmpty()) {
            for (int index = 0; index < lines.size(); index++) append(output, lines.get(index));
        } else {
            for (String line : selected) append(output, line);
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(output.toString()), null);
    }

    private static void append(StringBuilder output, String line) {
        if (output.length() > 0) output.append(System.lineSeparator());
        output.append(line);
    }
}
package dev.whitedev.jpi.ui.inspection;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;

public final class ConstantSearchPanel extends JPanel implements SessionAware {
    private final DeobfuscationWorkspace workspace;
    private final Consumer<String> investigation;
    private final JTextField query = new JTextField();
    private final JButton search = Ui.primaryButton("Search constants");
    private final JButton investigate = Ui.secondaryButton("Investigate");
    private final JLabel resultCount = new JLabel("Not attached");
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Class", "Class loader", "Matching constant or symbol"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable results = new JTable(model);
    private InspectorSession session;

    public ConstantSearchPanel(DeobfuscationWorkspace workspace) {
        this(workspace, null);
    }

    public ConstantSearchPanel(DeobfuscationWorkspace workspace, Consumer<String> investigation) {
        super(new BorderLayout(0, 16));
        this.workspace = workspace;
        this.investigation = investigation;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        query.setPreferredSize(new Dimension(330, 34));
        query.putClientProperty("JTextField.placeholderText", "URL, package, method, field, marker...");
        query.addActionListener(e -> search());
        search.addActionListener(e -> search());
        investigate.addActionListener(e -> investigate());
        JButton copy = Ui.secondaryButton("Copy results");
        copy.addActionListener(e -> copyResults());
        actions.add(query); actions.add(copy);
        if (investigation != null) actions.add(investigate);
        actions.add(search);
        add(Ui.sectionHeader("Constant search",
                "Find strings, descriptors, class names, methods, and field symbols across available bytecode",
                actions), BorderLayout.NORTH);

        results.setFillsViewportHeight(true);
        results.setAutoCreateRowSorter(true);
        results.getColumnModel().getColumn(0).setPreferredWidth(280);
        results.getColumnModel().getColumn(1).setPreferredWidth(220);
        results.getColumnModel().getColumn(2).setPreferredWidth(520);
        JPanel card = Ui.card(new BorderLayout(0, 10));
        resultCount.setForeground(Ui.MUTED);
        card.add(resultCount, BorderLayout.NORTH);
        card.add(Ui.scroll(results), BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);
        setSession(null);
    }

    @Override public void setSession(InspectorSession session) {
        this.session = session;
        search.setEnabled(session != null);
        investigate.setEnabled(session != null && investigation != null);
        query.setEnabled(session != null);
        model.setRowCount(0);
        resultCount.setText(session == null ? "Not attached" : "Enter at least two characters");
    }

    private void investigate() {
        if (investigation == null) return;
        String value = query.getText().trim();
        int row = results.getSelectedRow();
        if (value.isEmpty() && row >= 0) {
            value = String.valueOf(model.getValueAt(results.convertRowIndexToModel(row), 2));
        }
        if (value.length() < 2) {
            resultCount.setText("Enter or select at least two characters");
            return;
        }
        investigation.accept(value);
    }

    private void search() {
        InspectorSession current = session;
        String value = workspace.translateSource(query.getText().trim()).source();
        if (current == null) return;
        if (value.length() < 2) {
            resultCount.setText("Enter at least two characters");
            return;
        }
        search.setEnabled(false);
        model.setRowCount(0);
        resultCount.setText("Scanning captured and resource-backed class definitions...");
        Async.run(() -> current.requestText(Operation.CONSTANT_SEARCH, value), raw -> {
            render(raw);
            search.setEnabled(true);
        }, error -> {
            search.setEnabled(true);
            resultCount.setText("Search failed");
            Ui.error(this, error);
        });
    }

    private void render(String raw) {
        model.setRowCount(0);
        for (String line : raw.split("\\n")) {
            if (line.isBlank()) continue;
            String[] columns = line.split("\\t", 3);
            if (columns.length == 3) {
                String mapped = workspace.classAlias(columns[0]);
                if (!mapped.equals(columns[0])) columns[0] = mapped + " [" + columns[0] + "]";
                model.addRow(columns);
            }
        }
        resultCount.setText(model.getRowCount() + " matching constants");
    }

    private void copyResults() {
        StringBuilder text = new StringBuilder("Class\tClass loader\tConstant\n");
        for (int row = 0; row < model.getRowCount(); row++) {
            text.append(model.getValueAt(row, 0)).append('\t')
                    .append(model.getValueAt(row, 1)).append('\t')
                    .append(model.getValueAt(row, 2)).append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.toString()), null);
    }
}

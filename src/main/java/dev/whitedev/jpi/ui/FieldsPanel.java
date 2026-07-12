package dev.whitedev.jpi.ui;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.protocol.Operation;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

final class FieldsPanel extends JPanel implements SessionAware {
    private final JTextField filter = new JTextField();
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Class", "Field", "Type", "Value"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JButton inspect = Ui.primaryButton("Inspect fields");
    private final JLabel resultCount = new JLabel("No results");
    private InspectorSession session;

    FieldsPanel() {
        super(new BorderLayout(0, 16));
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);
        add(Ui.sectionHeader("Static fields",
                "Inspect existing static state without constructing target application classes", null),
                BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);
        JPanel toolbar = Ui.card(new BorderLayout(12, 0));
        JPanel query = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        query.setOpaque(false);
        JLabel label = new JLabel("Class filter");
        filter.setPreferredSize(new Dimension(380, 36));
        filter.putClientProperty("JTextField.placeholderText", "e.g. com.example.service");
        filter.putClientProperty("JTextField.showClearButton", true);
        filter.addActionListener(e -> inspect());
        query.add(label);
        query.add(filter);
        query.add(inspect);
        resultCount.setForeground(Ui.MUTED);
        toolbar.add(query, BorderLayout.WEST);
        toolbar.add(resultCount, BorderLayout.EAST);
        inspect.addActionListener(e -> inspect());
        body.add(toolbar, BorderLayout.NORTH);

        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(280);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(420);
        JPanel tableCard = Ui.card(new BorderLayout());
        tableCard.add(Ui.scroll(table), BorderLayout.CENTER);
        body.add(tableCard, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);
    }

    @Override public void setSession(InspectorSession session) {
        this.session = session;
        inspect.setEnabled(session != null);
        if (session == null) {
            model.setRowCount(0);
            resultCount.setText("Attach required");
        } else {
            resultCount.setText("Ready");
        }
    }

    private void inspect() {
        final InspectorSession current = session;
        if (current == null) return;
        final String query = filter.getText();
        inspect.setEnabled(false);
        resultCount.setText("Inspecting...");
        model.setRowCount(0);
        Async.run(() -> current.requestText(Operation.FIELDS, query), value -> {
            for (String line : value.split("\\n")) {
                String[] columns = line.split("\\t", 4);
                if (columns.length == 4) model.addRow(columns);
            }
            resultCount.setText(model.getRowCount() + " fields");
            inspect.setEnabled(true);
        }, error -> {
            resultCount.setText("Failed");
            inspect.setEnabled(true);
            Ui.error(this, error);
        });
    }
}

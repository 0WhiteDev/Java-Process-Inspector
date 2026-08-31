package dev.whitedev.jpi.ui.inspection;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FieldsPanel extends JPanel implements SessionAware {
    private final DeobfuscationWorkspace workspace;
    private final RuntimeTimelineStore timeline;
    private final FieldWritesPanel fieldWrites;
    private final Runnable openFieldWrites;
    private final JTextField filter = new JTextField();
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Class", "Field", "Type", "Value"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JButton inspect = Ui.primaryButton("Inspect fields");
    private final JButton traceWrites = Ui.secondaryButton("Trace writes");
    private final JCheckBox watch = new JCheckBox("Watch changes");
    private final JLabel resultCount = new JLabel("No results");
    private final Map<String, FieldValue> previousValues = new LinkedHashMap<>();
    private final List<FieldValue> fieldRows = new ArrayList<>();
    private final JTable table = new JTable(model);
    private final Timer watchTimer = new Timer(1500, event -> {
        if (watch.isSelected()) inspect();
    });
    private InspectorSession session;
    private boolean loading;

    public FieldsPanel(DeobfuscationWorkspace workspace) {
        this(workspace, new RuntimeTimelineStore(), null, () -> { });
    }

    public FieldsPanel(DeobfuscationWorkspace workspace, RuntimeTimelineStore timeline) {
        this(workspace, timeline, null, () -> { });
    }

    public FieldsPanel(DeobfuscationWorkspace workspace, RuntimeTimelineStore timeline,
                       FieldWritesPanel fieldWrites, Runnable openFieldWrites) {
        super(new BorderLayout(0, 16));
        this.workspace = workspace;
        this.timeline = timeline;
        this.fieldWrites = fieldWrites;
        this.openFieldWrites = openFieldWrites;
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
        watch.addActionListener(event -> toggleWatch());
        traceWrites.addActionListener(event -> traceSelectedField());
        query.add(label);
        query.add(filter);
        query.add(watch);
        query.add(traceWrites);
        query.add(inspect);
        resultCount.setForeground(Ui.MUTED);
        toolbar.add(query, BorderLayout.WEST);
        toolbar.add(resultCount, BorderLayout.EAST);
        inspect.addActionListener(e -> inspect());
        body.add(toolbar, BorderLayout.NORTH);

        table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setPreferredWidth(280);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(420);
        table.getSelectionModel().addListSelectionListener(event -> traceWrites.setEnabled(
                session != null && table.getSelectedRow() >= 0 && fieldWrites != null));
        JPanel tableCard = Ui.card(new BorderLayout());
        tableCard.add(Ui.scroll(table), BorderLayout.CENTER);
        body.add(tableCard, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);
        watchTimer.start();
    }

    @Override public void setSession(InspectorSession session) {
        this.session = session;
        loading = false;
        previousValues.clear();
        watch.setSelected(false);
        watch.setEnabled(session != null);
        inspect.setEnabled(session != null);
        traceWrites.setEnabled(false);
        if (session == null) {
            model.setRowCount(0);
            fieldRows.clear();
            resultCount.setText("Attach required");
        } else {
            resultCount.setText("Ready");
        }
    }

    private void inspect() {
        final InspectorSession current = session;
        if (current == null || loading) return;
        final String query = workspace.translateSource(filter.getText()).source();
        loading = true;
        inspect.setEnabled(false);
        resultCount.setText("Inspecting...");
        model.setRowCount(0);
        fieldRows.clear();
        Async.run(() -> current.requestText(Operation.FIELDS, query), value -> {
            if (session != current) return;
            Map<String, FieldValue> currentValues = new LinkedHashMap<>();
            for (String line : value.split("\\n")) {
                String[] columns = line.split("\\t", 4);
                if (columns.length == 4) {
                    FieldValue field = new FieldValue(columns[0], columns[1], columns[2], columns[3]);
                    currentValues.put(field.key(), field);
                    fieldRows.add(field);
                    String mappedClass = workspace.classAlias(columns[0]);
                    String mappedField = workspace.fieldAlias(columns[0], columns[1]);
                    if (!mappedClass.equals(columns[0])) columns[0] = mappedClass + " [" + columns[0] + "]";
                    if (!mappedField.equals(columns[1])) columns[1] = mappedField + " [" + columns[1] + "]";
                    model.addRow(columns);
                }
            }
            publishChanges(currentValues);
            previousValues.clear();
            previousValues.putAll(currentValues);
            resultCount.setText(model.getRowCount() + " fields");
            loading = false;
            inspect.setEnabled(true);
        }, error -> {
            if (session != current) return;
            resultCount.setText("Failed");
            loading = false;
            inspect.setEnabled(true);
            Ui.error(this, error);
        });
    }

    private void traceSelectedField() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0 || fieldWrites == null) return;
        int row = table.convertRowIndexToModel(viewRow);
        if (row < 0 || row >= fieldRows.size()) return;
        FieldValue selected = fieldRows.get(row);
        fieldWrites.selectField(selected.owner, selected.name);
        openFieldWrites.run();
    }

    private void toggleWatch() {
        if (!watch.isSelected()) return;
        if (session == null || filter.getText().isBlank()) {
            watch.setSelected(false);
            Ui.error(this, new IllegalStateException("Enter a class filter before watching static field changes"));
            return;
        }
        previousValues.clear();
        inspect();
    }

    private void publishChanges(Map<String, FieldValue> currentValues) {
        if (previousValues.isEmpty()) return;
        long timestamp = System.currentTimeMillis();
        for (Map.Entry<String, FieldValue> entry : currentValues.entrySet()) {
            FieldValue previous = previousValues.get(entry.getKey());
            FieldValue current = entry.getValue();
            if (previous == null || previous.value.equals(current.value)) continue;
            String owner = workspace.classAlias(current.owner);
            String field = workspace.fieldAlias(current.owner, current.name);
            timeline.publish(new TimelineEvent("field:" + timestamp + ":" + entry.getKey(), timestamp,
                    TimelineSource.FIELD, "", "", "", owner + "." + field + " changed",
                    "Original field: " + current.owner + "." + current.name + "\nType: " + current.type
                            + "\nPrevious: " + previous.value + "\nCurrent: " + current.value));
        }
    }

    private record FieldValue(String owner, String name, String type, String value) {
        String key() {
            return owner + "#" + name + ":" + type;
        }
    }
}

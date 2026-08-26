package dev.whitedev.jpi.ui.timeline;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RuntimeTimelinePanel extends JPanel implements SessionAware {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private final RuntimeTimelineStore store;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Time", "Source", "Thread", "Correlation", "Event"}, 0) {
        @Override public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JTextArea details = Ui.outputArea();
    private final JTextField filter = new JTextField();
    private final JComboBox<Object> source = new JComboBox<>();
    private final JCheckBox correlatedOnly = new JCheckBox("Correlated only");
    private final JCheckBox live = new JCheckBox("Live", true);
    private final JLabel status = new JLabel("Attach to a JVM to build a runtime timeline");
    private final JButton marker = Ui.primaryButton("Add action marker...");
    private final List<RuntimeTimelineStore.CorrelatedEvent> visible = new ArrayList<>();
    private final Timer refreshTimer = new Timer(200, event -> refresh());
    private InspectorSession session;

    public RuntimeTimelinePanel(RuntimeTimelineStore store) {
        super(new BorderLayout(0, 16));
        this.store = store;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton copy = Ui.secondaryButton("Copy event");
        JButton clear = Ui.secondaryButton("Clear timeline");
        copy.addActionListener(event -> copySelected());
        clear.addActionListener(event -> store.clear());
        marker.addActionListener(event -> addMarker());
        live.addActionListener(event -> {
            if (live.isSelected()) refresh();
            else updateStatus(store.snapshot().size(), visible.size());
        });
        actions.add(copy);
        actions.add(clear);
        actions.add(live);
        actions.add(marker);
        add(Ui.sectionHeader("Runtime timeline",
                "Correlate existing trace, API, network, class, field, and snapshot events by call, thread, and time",
                actions), BorderLayout.NORTH);

        JPanel toolbar = Ui.card(new BorderLayout(12, 0));
        filter.putClientProperty("JTextField.placeholderText", "Filter method, class, endpoint, thread, or call ID");
        filter.putClientProperty("JTextField.showClearButton", true);
        filter.setPreferredSize(new Dimension(390, 34));
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refresh(); }
            @Override public void removeUpdate(DocumentEvent event) { refresh(); }
            @Override public void changedUpdate(DocumentEvent event) { refresh(); }
        });
        source.addItem("All sources");
        for (TimelineSource value : TimelineSource.values()) source.addItem(value);
        source.setPreferredSize(new Dimension(145, 34));
        source.addActionListener(event -> refresh());
        correlatedOnly.addActionListener(event -> refresh());
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filters.setOpaque(false);
        filters.add(filter);
        filters.add(source);
        filters.add(correlatedOnly);
        status.setForeground(Ui.MUTED);
        toolbar.add(filters, BorderLayout.WEST);
        toolbar.add(status, BorderLayout.EAST);

        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(105);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(190);
        table.getColumnModel().getColumn(3).setPreferredWidth(105);
        table.getColumnModel().getColumn(4).setPreferredWidth(650);
        table.getColumnModel().getColumn(1).setCellRenderer(new SourceRenderer());
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelected();
        });
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.scroll(table), Ui.scroll(details));
        split.setResizeWeight(.72);
        split.setDividerLocation(850);
        split.setBorder(null);
        JPanel card = Ui.card(new BorderLayout(0, 10));
        card.add(toolbar, BorderLayout.NORTH);
        card.add(split, BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);
        details.setText("Select an event to inspect its correlation and source details.");
        marker.setEnabled(false);
        refreshTimer.setRepeats(false);
        store.addListener(this::queueRefresh);
    }

    @Override public void setSession(InspectorSession session) {
        this.session = session;
        marker.setEnabled(session != null);
        refresh();
    }

    private void refresh() {
        String query = filter.getText().trim().toLowerCase(Locale.ROOT);
        Object selectedSource = source.getSelectedItem();
        List<RuntimeTimelineStore.CorrelatedEvent> snapshot = store.snapshot();
        visible.clear();
        model.setRowCount(0);
        for (RuntimeTimelineStore.CorrelatedEvent correlated : snapshot) {
            TimelineEvent event = correlated.event();
            if (selectedSource instanceof TimelineSource value && event.source() != value) continue;
            if (correlatedOnly.isSelected() && correlated.correlation().isEmpty()) continue;
            String searchable = event.source() + " " + event.thread() + " " + correlated.correlation() + " "
                    + event.summary() + " " + event.details();
            if (!query.isEmpty() && !searchable.toLowerCase(Locale.ROOT).contains(query)) continue;
            visible.add(correlated);
            model.addRow(new Object[]{TIME.format(Instant.ofEpochMilli(event.timestamp())), event.source(),
                    event.thread().isEmpty() ? "-" : event.thread(),
                    correlated.correlation().isEmpty() ? "-" : correlated.correlation(), event.summary()});
        }
        updateStatus(snapshot.size(), visible.size());
        if (live.isSelected() && model.getRowCount() > 0) {
            Rectangle cell = table.getCellRect(model.getRowCount() - 1, 0, true);
            table.scrollRectToVisible(cell);
        }
    }

    private void queueRefresh() {
        Runnable update = () -> {
            if (live.isSelected()) refreshTimer.restart();
            else updateStatus(store.snapshot().size(), visible.size());
        };
        if (SwingUtilities.isEventDispatchThread()) update.run();
        else SwingUtilities.invokeLater(update);
    }

    private void showSelected() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= visible.size()) return;
        RuntimeTimelineStore.CorrelatedEvent correlated = visible.get(row);
        TimelineEvent event = correlated.event();
        details.setText("Time: " + TIME.format(Instant.ofEpochMilli(event.timestamp()))
                + "\nSource: " + event.source()
                + "\nThread: " + value(event.thread())
                + "\nCall ID: " + value(event.callId())
                + "\nParent call: " + value(event.parentCallId())
                + "\nCorrelation: " + value(correlated.correlation())
                + "\nCorrelation basis: " + value(correlated.correlationBasis())
                + "\n\n" + event.summary() + "\n\n" + event.details());
        details.setCaretPosition(0);
    }

    private void addMarker() {
        if (session == null) return;
        String text = JOptionPane.showInputDialog(this,
                "Describe the action you are about to perform in the target application:",
                "Add runtime action marker", JOptionPane.PLAIN_MESSAGE);
        if (text == null || text.isBlank()) return;
        long now = System.currentTimeMillis();
        store.publish(new TimelineEvent("marker:" + now + ":" + text.hashCode(), now, TimelineSource.MARKER,
                "", "", "", text.trim(), "Manual analysis marker for target PID " + session.target().id()));
    }

    private void copySelected() {
        if (details.getText().isBlank()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(details.getText()), null);
    }

    private void updateStatus(int total, int shown) {
        status.setText(session == null ? "Attach to a JVM to build a runtime timeline"
                : shown + " shown / " + total + " events" + (live.isSelected() ? "" : "  |  paused"));
    }

    private static String value(String value) {
        return value == null || value.isEmpty() ? "none" : value;
    }

    private static final class SourceRenderer extends DefaultTableCellRenderer {
        @Override protected void setValue(Object value) {
            super.setValue(value);
            setForeground(value == TimelineSource.TRACE ? Ui.SUCCESS
                    : value == TimelineSource.NETWORK ? new Color(102, 178, 255)
                    : value == TimelineSource.CLASS_LOAD ? Ui.WARNING : Ui.TEXT);
        }
    }
}

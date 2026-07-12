package dev.whitedev.jpi.ui;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.nativeaccess.NetworkConnection;
import dev.whitedev.jpi.nativeaccess.WindowsNetworkAccess;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class NetworkPanel extends JPanel implements SessionAware {
    private final WindowsNetworkAccess network;
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Protocol", "Local endpoint", "Remote endpoint", "State"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
    private final JTextField filter = new JTextField();
    private final JTextArea events = Ui.outputArea();
    private final JButton refresh = Ui.primaryButton("Refresh");
    private final JCheckBox live = new JCheckBox("Live", true);
    private final Set<NetworkConnection> previous = new HashSet<>();
    private InspectorSession session;
    private boolean loading;

    NetworkPanel(WindowsNetworkAccess network) {
        super(new BorderLayout(0, 16));
        this.network = network;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        filter.putClientProperty("JTextField.placeholderText", "Filter address, port, protocol, or state");
        filter.setPreferredSize(new Dimension(290, 34));
        filter.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        refresh.addActionListener(e -> refresh());
        JButton copy = Ui.secondaryButton("Copy snapshot");
        copy.addActionListener(e -> copySnapshot());
        actions.add(filter); actions.add(live); actions.add(copy); actions.add(refresh);
        add(Ui.sectionHeader("Network activity", "Process-owned TCP/UDP endpoints and a live connection timeline", actions), BorderLayout.NORTH);

        table.setRowSorter(sorter);
        table.setFillsViewportHeight(true);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(table), Ui.scroll(events));
        split.setResizeWeight(0.67);
        split.setBorder(null);
        JPanel card = Ui.card(new BorderLayout());
        card.add(split, BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);
        events.setText("Attach to a JVM to observe its network endpoints. Packet contents are not captured.\n");
        new Timer(2000, e -> { if (live.isSelected()) refresh(); }).start();
    }

    @Override public void setSession(InspectorSession session) {
        this.session = session;
        previous.clear();
        model.setRowCount(0);
        refresh.setEnabled(session != null && network.isSupported());
        if (session == null) events.setText("Attach to a JVM to observe its network endpoints. Packet contents are not captured.\n");
        else if (!network.isSupported()) events.setText("Network ownership inspection is currently available on Windows only.\n");
        else { events.setText("Network observation started for PID " + session.target().id() + ".\n"); refresh(); }
    }

    private void refresh() {
        InspectorSession current = session;
        if (current == null || loading || !network.isSupported()) return;
        final int pid;
        try { pid = Integer.parseInt(current.target().id()); }
        catch (NumberFormatException error) { events.append("Target PID is unavailable for this session.\n"); return; }
        loading = true;
        refresh.setEnabled(false);
        Async.run(() -> network.connections(pid), value -> {
            render(value); loading = false; refresh.setEnabled(true);
        }, error -> {
            loading = false; refresh.setEnabled(true);
            events.append("Network refresh failed: " + error.getMessage() + "\n");
        });
    }

    private void render(List<NetworkConnection> connections) {
        Set<NetworkConnection> current = new HashSet<>(connections);
        if (!previous.isEmpty()) {
            for (NetworkConnection connection : current) if (!previous.contains(connection)) appendEvent("OPEN", connection);
            for (NetworkConnection connection : previous) if (!current.contains(connection)) appendEvent("CLOSE", connection);
        }
        previous.clear(); previous.addAll(current);
        model.setRowCount(0);
        for (NetworkConnection connection : connections) model.addRow(new Object[]{connection.protocol(),
                connection.localEndpoint(), connection.remoteEndpoint(), connection.state()});
    }

    private void appendEvent(String type, NetworkConnection connection) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        events.append(time + "  " + type + "  " + connection.protocol() + "  "
                + connection.localEndpoint() + " -> " + connection.remoteEndpoint() + "  " + connection.state() + "\n");
        events.setCaretPosition(events.getDocument().getLength());
    }

    private void applyFilter() {
        String text = filter.getText().trim();
        sorter.setRowFilter(text.isEmpty() ? null : RowFilter.regexFilter("(?i)" + Pattern.quote(text)));
    }

    private void copySnapshot() {
        StringBuilder text = new StringBuilder("Protocol\tLocal endpoint\tRemote endpoint\tState\n");
        for (int row = 0; row < model.getRowCount(); row++) {
            for (int column = 0; column < model.getColumnCount(); column++) {
                if (column > 0) text.append('\t');
                text.append(model.getValueAt(row, column));
            }
            text.append('\n');
        }
        text.append("\nTimeline\n").append(events.getText());
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.toString()), null);
    }
}

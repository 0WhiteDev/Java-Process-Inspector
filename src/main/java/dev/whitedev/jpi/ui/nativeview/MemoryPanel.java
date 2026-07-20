package dev.whitedev.jpi.ui.nativeview;

import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.nativeaccess.MemoryDataType;
import dev.whitedev.jpi.nativeaccess.MemoryMatch;
import dev.whitedev.jpi.nativeaccess.ProcessInfo;
import dev.whitedev.jpi.nativeaccess.WindowsNativeAccess;
import dev.whitedev.jpi.protocol.Operation;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MemoryPanel extends JPanel implements SessionAware {
    private final WindowsNativeAccess windows;
    private final JTextField pid = new JTextField();
    private final JTextField value = new JTextField();
    private final JComboBox<MemoryDataType> type = new JComboBox<>(MemoryDataType.values());
    private final JComboBox<ProcessInfo> processes = new JComboBox<>();
    private final JCheckBox visibleOnly = new JCheckBox("Visible windows only");
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Address", "Current value", "Bytes"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JButton newScan = Ui.primaryButton("New scan");
    private final JButton refine = Ui.secondaryButton("Refine results");
    private final JButton write = Ui.secondaryButton("Write selected");
    private final JLabel resultCount = new JLabel("No scan");
    private List<MemoryMatch> matches = new ArrayList<>();

    public MemoryPanel(WindowsNativeAccess windows) {
        super(new BorderLayout(0, 16));
        this.windows = windows;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);
        add(Ui.sectionHeader("Memory scanner",
                "Find typed values, refine their addresses, and explicitly write selected results", null),
                BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);
        JPanel controls = Ui.card(new GridLayout(2, 1, 0, 10));
        controls.add(targetControls());
        controls.add(scanControls());
        body.add(controls, BorderLayout.NORTH);

        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(360);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);
        JPanel results = Ui.card(new BorderLayout(0, 10));
        JPanel resultHeader = new JPanel(new BorderLayout());
        resultHeader.setOpaque(false);
        JLabel title = new JLabel("Scan results");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        resultCount.setForeground(Ui.MUTED);
        resultHeader.add(title, BorderLayout.WEST);
        resultHeader.add(resultCount, BorderLayout.EAST);
        results.add(resultHeader, BorderLayout.NORTH);
        results.add(Ui.scroll(table), BorderLayout.CENTER);
        body.add(results, BorderLayout.CENTER);

        JPanel warning = Ui.card(new BorderLayout(10, 0));
        JLabel warningTitle = new JLabel("Memory writes are immediate");
        warningTitle.setForeground(Ui.WARNING);
        warningTitle.setFont(warningTitle.getFont().deriveFont(Font.BOLD));
        JLabel warningText = new JLabel("Only selected rows are written. Invalid values or addresses can still crash the target.");
        warningText.setForeground(Ui.MUTED);
        warning.add(warningTitle, BorderLayout.WEST);
        warning.add(warningText, BorderLayout.CENTER);
        body.add(warning, BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);

        newScan.addActionListener(e -> scan(false));
        refine.addActionListener(e -> scan(true));
        write.addActionListener(e -> writeSelected());
        visibleOnly.addActionListener(e -> loadProcesses());
        write.setEnabled(false);
        refine.setEnabled(false);
        loadProcesses();
    }

    private JPanel targetControls() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        pid.setPreferredSize(new Dimension(105, 36));
        pid.putClientProperty("JTextField.placeholderText", "PID");
        processes.setPreferredSize(new Dimension(350, 36));
        processes.addActionListener(e -> {
            ProcessInfo selected = (ProcessInfo) processes.getSelectedItem();
            if (selected != null) pid.setText(String.valueOf(selected.pid()));
        });
        JButton refresh = Ui.secondaryButton("Refresh processes");
        refresh.addActionListener(e -> loadProcesses());
        row.add(new JLabel("Target"));
        row.add(processes);
        row.add(new JLabel("PID"));
        row.add(pid);
        row.add(visibleOnly);
        row.add(refresh);
        return row;
    }

    private JPanel scanControls() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        value.setPreferredSize(new Dimension(260, 36));
        value.putClientProperty("JTextField.placeholderText", "Value to find");
        value.addActionListener(e -> scan(false));
        type.setPreferredSize(new Dimension(120, 36));
        refine.setToolTipText("Search again and keep only addresses present in the previous scan");
        write.setToolTipText("Write a replacement only to rows selected in the results table");
        row.add(new JLabel("Value"));
        row.add(value);
        row.add(type);
        row.add(newScan);
        row.add(refine);
        row.add(write);
        return row;
    }

    @Override public void setSession(final InspectorSession session) {
        if (session == null) return;
        Async.run(() -> session.requestText(Operation.METRICS, ""), raw -> {
            for (String line : raw.split("\\n")) {
                if (line.startsWith("pid=")) pid.setText(line.substring(4));
            }
        }, error -> { });
    }

    private void loadProcesses() {
        if (!windows.isSupported()) {
            resultCount.setText("Windows only");
            return;
        }
        final boolean windowsOnly = visibleOnly.isSelected();
        processes.setEnabled(false);
        Async.run(() -> windowsOnly ? windows.visibleWindows() : windows.processes(), found -> {
            processes.removeAllItems();
            for (ProcessInfo process : found) processes.addItem(process);
            processes.setEnabled(true);
        }, error -> {
            processes.setEnabled(true);
            Ui.error(this, error);
        });
    }

    private void scan(final boolean refineExisting) {
        final int processId;
        try {
            processId = Integer.parseInt(pid.getText().trim());
        } catch (NumberFormatException error) {
            Ui.error(this, new IllegalArgumentException("Enter a valid PID"));
            return;
        }
        final String query = value.getText();
        if (query.isBlank()) {
            Ui.error(this, new IllegalArgumentException("Enter a value to scan"));
            return;
        }
        final MemoryDataType selectedType = (MemoryDataType) type.getSelectedItem();
        final List<MemoryMatch> previous = new ArrayList<>(matches);
        setScanning(true);
        model.setRowCount(0);
        resultCount.setText("Scanning process " + processId + "...");
        Async.run(() -> {
            List<MemoryMatch> found = windows.scanMemory(processId, query, selectedType);
            if (!refineExisting) return found;
            Set<Long> previousAddresses = new HashSet<>();
            for (MemoryMatch old : previous) previousAddresses.add(old.address());
            List<MemoryMatch> filtered = new ArrayList<>();
            for (MemoryMatch match : found) {
                if (previousAddresses.contains(match.address())) filtered.add(match);
            }
            return filtered;
        }, found -> {
            matches = found;
            render();
            setScanning(false);
        }, error -> {
            matches = new ArrayList<>();
            model.setRowCount(0);
            resultCount.setText("Scan failed");
            setScanning(false);
            Ui.error(this, error);
        });
    }

    private void setScanning(boolean scanning) {
        newScan.setEnabled(!scanning);
        refine.setEnabled(!scanning && !matches.isEmpty());
        write.setEnabled(!scanning && !matches.isEmpty());
    }

    private void render() {
        model.setRowCount(0);
        for (MemoryMatch match : matches) {
            model.addRow(new Object[]{
                    String.format("0x%016X", match.address()),
                    match.value(),
                    match.byteSize()
            });
        }
        resultCount.setText(matches.isEmpty() ? "No matches" : matches.size() + " matches");
        refine.setEnabled(!matches.isEmpty());
        write.setEnabled(!matches.isEmpty());
    }

    private void writeSelected() {
        int[] selectedRows = table.getSelectedRows();
        if (selectedRows.length == 0) {
            Ui.error(this, new IllegalStateException("Select one or more result rows first"));
            return;
        }
        final List<Integer> modelRows = new ArrayList<>();
        final List<MemoryMatch> selectedMatches = new ArrayList<>();
        for (int selectedRow : selectedRows) {
            int modelRow = table.convertRowIndexToModel(selectedRow);
            modelRows.add(modelRow);
            selectedMatches.add(matches.get(modelRow));
        }
        final String replacement = JOptionPane.showInputDialog(this, "Replacement value:");
        if (replacement == null) return;
        int confirmation = JOptionPane.showConfirmDialog(this,
                "Write " + selectedMatches.size() + " selected memory location(s)?\n"
                        + "This operation cannot be undone by JPI.",
                "Confirm memory write", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirmation != JOptionPane.OK_OPTION) return;
        final int processId;
        try {
            processId = Integer.parseInt(pid.getText().trim());
        } catch (NumberFormatException error) {
            Ui.error(this, new IllegalArgumentException("Enter a valid PID"));
            return;
        }
        final MemoryDataType selectedType = (MemoryDataType) type.getSelectedItem();
        write.setEnabled(false);
        resultCount.setText("Writing " + selectedMatches.size() + " locations...");
        Async.run(() -> {
            windows.writeMemory(processId, selectedMatches, replacement, selectedType);
            return true;
        }, ignored -> {
            for (Integer modelRow : modelRows) {
                MemoryMatch old = matches.get(modelRow);
                matches.set(modelRow, new MemoryMatch(old.address(), replacement, old.byteSize()));
            }
            render();
            JOptionPane.showMessageDialog(this, "Memory write completed and verified by the operating system.");
        }, error -> {
            write.setEnabled(true);
            resultCount.setText("Write failed");
            Ui.error(this, error);
        });
    }
}

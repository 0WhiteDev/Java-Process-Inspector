package dev.whitedev.jpi.ui.threads;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.threads.ThreadAnalysisSnapshot;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ThreadAnalyzerPanel extends JPanel implements SessionAware {
    private final RuntimeTimelineStore timeline;
    private final DefaultTableModel threadsModel = readOnly("Thread", "ID", "State", "CPU", "Blocked", "Waiting", "Owner", "Kind");
    private final DefaultTableModel historyModel = readOnly("Time", "State", "Waiting for", "Owner");
    private final DefaultTableModel lifetimeModel = readOnly("First seen", "Last seen", "Thread", "ID", "Observed for");
    private final JTable threads = threadTable();
    private final JTable history = new JTable(historyModel);
    private final JTextArea details = Ui.outputArea();
    private final ThreadLockGraphCanvas graph = new ThreadLockGraphCanvas();
    private final JButton refresh = Ui.primaryButton("Capture snapshot");
    private final JButton clear = Ui.secondaryButton("Clear history");
    private final JCheckBox autoRefresh = new JCheckBox("Auto refresh", true);
    private final JLabel status = new JLabel("Not attached");
    private final JLabel alert = new JLabel("No deadlock detected", SwingConstants.CENTER);
    private final Map<String, JLabel> metrics = new LinkedHashMap<>();
    private final Timer timer = new Timer(1500, event -> {
        if (isShowing()) capture();
    });
    private final Set<Long> activeDeadlocks = new HashSet<>();
    private InspectorSession session;
    private ThreadAnalysisSnapshot snapshot;
    private long previousTimestamp;
    private boolean loading;

    public ThreadAnalyzerPanel(RuntimeTimelineStore timeline) {
        super(new BorderLayout(0, 12));
        this.timeline = timeline;
        setOpaque(false);
        setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        autoRefresh.setOpaque(false);
        actions.add(autoRefresh);
        actions.add(clear);
        actions.add(refresh);
        add(Ui.sectionHeader("Thread Analyzer", "Deadlocks, contention, hot threads, lock ownership, and state history", actions), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(summary(), BorderLayout.NORTH);
        body.add(content(), BorderLayout.CENTER);
        body.add(status, BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);

        threads.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelectedThread();
        });
        refresh.addActionListener(event -> capture());
        clear.addActionListener(event -> clearHistory());
        autoRefresh.addActionListener(event -> updateTimer());
        timer.setRepeats(true);
        setSession(null);
    }

    @Override public void setSession(InspectorSession value) {
        timer.stop();
        session = value;
        snapshot = null;
        previousTimestamp = 0L;
        activeDeadlocks.clear();
        loading = false;
        resetView();
        status.setText(value == null ? "Not attached" : "Capturing initial thread snapshot...");
        updateControls();
        if (value != null) {
            updateTimer();
            if (isShowing()) capture();
        }
    }

    private JPanel summary() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        alert.setOpaque(true);
        alert.setBackground(new Color(41, 72, 57));
        alert.setForeground(Ui.SUCCESS);
        alert.setBorder(new EmptyBorder(7, 10, 7, 10));
        panel.add(alert, BorderLayout.NORTH);
        JPanel cards = new JPanel(new GridLayout(1, 6, 8, 0));
        cards.setOpaque(false);
        cards.add(metric("live", "Live"));
        cards.add(metric("blocked", "Blocked"));
        cards.add(metric("parked", "Parked"));
        cards.add(metric("deadlocked", "Deadlocked"));
        cards.add(metric("hot", "Hottest thread"));
        cards.add(metric("contention", "Contention data"));
        panel.add(cards, BorderLayout.CENTER);
        return panel;
    }

    private JPanel metric(String key, String title) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(Ui.SURFACE_LIGHT);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.BORDER),
                new EmptyBorder(8, 10, 8, 10)));
        JLabel name = new JLabel(title);
        name.setForeground(Ui.MUTED);
        JLabel value = new JLabel("--");
        value.setFont(value.getFont().deriveFont(Font.BOLD, 16f));
        metrics.put(key, value);
        panel.add(name, BorderLayout.NORTH);
        panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    private Component content() {
        threads.setAutoCreateRowSorter(true);
        threads.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        threads.setFillsViewportHeight(true);
        history.setFillsViewportHeight(true);
        JTable lifetimes = new JTable(lifetimeModel);
        lifetimes.setAutoCreateRowSorter(true);
        lifetimes.setFillsViewportHeight(true);

        JTabbedPane lower = new JTabbedPane();
        lower.addTab("Thread details", Ui.scroll(details));
        lower.addTab("State history", Ui.scroll(history));
        lower.addTab("Creation timeline", Ui.scroll(lifetimes));
        JScrollPane graphScroll = Ui.scroll(graph);
        graphScroll.getVerticalScrollBar().setUnitIncrement(20);
        graphScroll.getHorizontalScrollBar().setUnitIncrement(20);
        lower.addTab("Lock graph", graphScroll);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(threads), lower);
        split.setResizeWeight(.52);
        split.setDividerLocation(330);
        split.setBorder(null);
        return split;
    }

    private JTable threadTable() {
        return new JTable(threadsModel) {
            @Override public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer, int row, int column) {
                Component component = super.prepareRenderer(renderer, row, column);
                int modelRow = convertRowIndexToModel(row);
                String state = String.valueOf(getModel().getValueAt(modelRow, 2));
                if (!isRowSelected(row)) {
                    component.setBackground("DEADLOCKED".equals(state) ? new Color(78, 39, 42)
                            : "BLOCKED".equals(state) ? new Color(66, 55, 38) : Ui.SURFACE);
                }
                return component;
            }
        };
    }

    private void capture() {
        InspectorSession current = session;
        if (current == null || loading) return;
        loading = true;
        updateControls();
        Async.run(() -> ThreadAnalysisSnapshot.parse(current.requestText(Operation.THREAD_ANALYZE, "")), value -> {
            if (session != current) return;
            loading = false;
            render(value);
            updateControls();
            updateTimer();
        }, error -> {
            if (session != current) return;
            loading = false;
            timer.stop();
            status.setForeground(Ui.WARNING);
            status.setText("Thread analysis failed: " + message(error));
            updateControls();
        });
    }

    private void clearHistory() {
        InspectorSession current = session;
        if (current == null || loading) return;
        loading = true;
        updateControls();
        Async.run(() -> current.requestText(Operation.THREAD_ANALYZER_CLEAR, ""), ignored -> {
            if (session != current) return;
            loading = false;
            snapshot = null;
            previousTimestamp = 0L;
            activeDeadlocks.clear();
            resetView();
            status.setText("Thread history cleared");
            capture();
        }, error -> {
            if (session != current) return;
            loading = false;
            Ui.error(this, error);
            updateControls();
        });
    }

    private void render(ThreadAnalysisSnapshot value) {
        long selectedId = selectedThreadId();
        publishTimeline(value);
        snapshot = value;
        threadsModel.setRowCount(0);
        for (ThreadAnalysisSnapshot.ThreadEntry thread : value.threads()) {
            String state = thread.deadlocked() ? "DEADLOCKED" : thread.state();
            threadsModel.addRow(new Object[]{thread.name(), thread.id(), state,
                    String.format("%.1f%%", thread.cpuPercent(value.intervalMillis())),
                    duration(thread.blockedTime()), duration(thread.waitedTime()),
                    thread.ownerName().isBlank() ? "" : thread.ownerName() + " #" + thread.ownerId(),
                    thread.daemon() ? "daemon" : "non-daemon"});
        }
        renderLifetimes(value);
        graph.setSnapshot(value);
        setMetric("live", Integer.toString(value.threads().size()));
        setMetric("blocked", Integer.toString(value.blockedCount()));
        setMetric("parked", Integer.toString(value.parkedCount()));
        setMetric("deadlocked", Integer.toString(value.deadlockCount()));
        ThreadAnalysisSnapshot.ThreadEntry hottest = value.threads().isEmpty() ? null : value.threads().get(0);
        setMetric("hot", hottest == null ? "--" : String.format("%.1f%%", hottest.cpuPercent(value.intervalMillis())));
        setMetric("contention", value.contentionEnabled() ? "Enabled" : value.contentionSupported() ? "Unavailable" : "Unsupported");
        renderAlert(value);
        restoreSelection(selectedId);
        status.setForeground(Ui.MUTED);
        status.setText("Snapshot " + time(value.timestamp()) + "  |  CPU "
                + (value.cpuEnabled() ? "enabled" : "unavailable") + "  |  " + value.threads().size() + " live threads");
        previousTimestamp = value.timestamp();
    }

    private void renderAlert(ThreadAnalysisSnapshot value) {
        int deadlocks = value.deadlockCount();
        if (deadlocks == 0) {
            alert.setText("No deadlock detected");
            alert.setBackground(new Color(41, 72, 57));
            alert.setForeground(Ui.SUCCESS);
        } else {
            alert.setText("Deadlock detected across " + deadlocks + " threads. Open Lock graph for the dependency cycle.");
            alert.setBackground(new Color(86, 39, 43));
            alert.setForeground(new Color(255, 139, 139));
        }
    }

    private void renderLifetimes(ThreadAnalysisSnapshot value) {
        lifetimeModel.setRowCount(0);
        for (ThreadAnalysisSnapshot.Lifetime lifetime : value.lifetimes()) {
            String ended = lifetime.endedAt() == 0L ? "Running" : time(lifetime.endedAt());
            long until = lifetime.endedAt() == 0L ? value.timestamp() : lifetime.endedAt();
            lifetimeModel.addRow(new Object[]{time(lifetime.firstSeen()), ended, lifetime.name(), lifetime.id(),
                    duration(Math.max(0L, until - lifetime.firstSeen()))});
        }
    }

    private void showSelectedThread() {
        ThreadAnalysisSnapshot.ThreadEntry thread = selectedThread();
        historyModel.setRowCount(0);
        if (thread == null) {
            details.setText("Select a thread to inspect its stack, locks, and metrics.");
            return;
        }
        for (ThreadAnalysisSnapshot.StateChange change : thread.history()) {
            historyModel.addRow(new Object[]{time(change.timestamp()), change.state(), change.lock(), change.owner()});
        }
        StringBuilder text = new StringBuilder();
        text.append(thread.name()).append(" #").append(thread.id()).append('\n')
                .append("State: ").append(thread.state()).append('\n')
                .append("Type: ").append(thread.daemon() ? "daemon" : "non-daemon").append('\n')
                .append("Priority: ").append(thread.priority()).append('\n')
                .append("CPU in sample: ").append(String.format("%.2f%%", thread.cpuPercent(snapshot.intervalMillis()))).append('\n')
                .append("Blocked: ").append(thread.blockedCount()).append(" times, ").append(duration(thread.blockedTime())).append('\n')
                .append("Waiting: ").append(thread.waitedCount()).append(" times, ").append(duration(thread.waitedTime())).append('\n')
                .append("Parking: ").append(thread.parked() ? "yes" : "no").append('\n')
                .append("Native: ").append(thread.nativeThread() ? "yes" : "no").append('\n')
                .append("Suspended: ").append(thread.suspended() ? "yes" : "no").append('\n')
                .append("Deadlocked: ").append(thread.deadlocked() ? "yes" : "no").append('\n');
        if (!thread.lock().isBlank()) text.append("Waiting for: ").append(thread.lock()).append('\n');
        if (!thread.ownerName().isBlank()) text.append("Lock owner: ").append(thread.ownerName())
                .append(" #").append(thread.ownerId()).append('\n');
        if (!thread.heldLocks().isEmpty()) {
            text.append("\nHeld locks:\n");
            for (ThreadAnalysisSnapshot.HeldLock lock : thread.heldLocks()) {
                text.append("  ").append(lock.kind()).append(' ').append(lock.identity());
                if (lock.stackDepth() >= 0) text.append(" at stack depth ").append(lock.stackDepth());
                text.append('\n');
            }
        }
        text.append("\nStack:\n");
        for (String frame : thread.frames()) text.append("  at ").append(frame).append('\n');
        details.setText(text.toString());
        details.setCaretPosition(0);
    }

    private void publishTimeline(ThreadAnalysisSnapshot value) {
        Set<Long> nowDeadlocked = new HashSet<>();
        for (ThreadAnalysisSnapshot.ThreadEntry thread : value.threads()) {
            if (thread.deadlocked()) {
                nowDeadlocked.add(thread.id());
                if (!activeDeadlocks.contains(thread.id())) publish("deadlock", value.timestamp(), thread.name(),
                        "Deadlock detected", "Waiting for " + thread.lock() + " held by " + thread.ownerName());
            }
        }
        if (previousTimestamp > 0L) {
            for (ThreadAnalysisSnapshot.Lifetime lifetime : value.lifetimes()) {
                if (lifetime.firstSeen() > previousTimestamp) publish("start", lifetime.firstSeen(), lifetime.name(),
                        "Thread observed", "Thread #" + lifetime.id());
                if (lifetime.endedAt() > previousTimestamp) publish("end", lifetime.endedAt(), lifetime.name(),
                        "Thread ended", "Thread #" + lifetime.id());
            }
        }
        activeDeadlocks.clear();
        activeDeadlocks.addAll(nowDeadlocked);
    }

    private void publish(String kind, long timestamp, String thread, String title, String detail) {
        timeline.publish(new TimelineEvent("thread:" + kind + ':' + timestamp + ':' + thread, timestamp,
                TimelineSource.THREAD, thread, "", "", title, detail));
    }

    private long selectedThreadId() {
        int row = threads.getSelectedRow();
        if (row < 0) return -1L;
        Object value = threadsModel.getValueAt(threads.convertRowIndexToModel(row), 1);
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private ThreadAnalysisSnapshot.ThreadEntry selectedThread() {
        long id = selectedThreadId();
        if (snapshot == null || id < 0L) return null;
        return snapshot.threads().stream().filter(value -> value.id() == id).findFirst().orElse(null);
    }

    private void restoreSelection(long id) {
        if (threadsModel.getRowCount() == 0) {
            showSelectedThread();
            return;
        }
        int modelRow = 0;
        if (id >= 0L) {
            for (int row = 0; row < threadsModel.getRowCount(); row++) {
                if (((Number) threadsModel.getValueAt(row, 1)).longValue() == id) {
                    modelRow = row;
                    break;
                }
            }
        }
        int viewRow = threads.convertRowIndexToView(modelRow);
        if (viewRow >= 0) threads.setRowSelectionInterval(viewRow, viewRow);
        showSelectedThread();
    }

    private void resetView() {
        threadsModel.setRowCount(0);
        historyModel.setRowCount(0);
        lifetimeModel.setRowCount(0);
        graph.setSnapshot(null);
        details.setText("Select a thread to inspect its stack, locks, and metrics.");
        alert.setText("No deadlock detected");
        alert.setBackground(new Color(41, 72, 57));
        alert.setForeground(Ui.SUCCESS);
        for (JLabel value : metrics.values()) value.setText("--");
    }

    private void updateTimer() {
        if (session != null && autoRefresh.isSelected() && !timer.isRunning()) timer.start();
        if ((session == null || !autoRefresh.isSelected()) && timer.isRunning()) timer.stop();
    }

    private void updateControls() {
        boolean attached = session != null;
        refresh.setEnabled(attached && !loading);
        clear.setEnabled(attached && !loading);
        autoRefresh.setEnabled(attached);
    }

    private void setMetric(String key, String value) {
        JLabel label = metrics.get(key);
        if (label != null) label.setText(value);
    }

    private static String duration(long millis) {
        if (millis < 0L) return "n/a";
        if (millis >= 60_000L) return String.format("%.1f min", millis / 60_000d);
        if (millis >= 1000L) return String.format("%.2f s", millis / 1000d);
        return millis + " ms";
    }

    private static String time(long timestamp) {
        return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(timestamp));
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static DefaultTableModel readOnly(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }
}

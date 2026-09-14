package dev.whitedev.jpi.ui.profiler;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.profiler.ProfilerCategory;
import dev.whitedev.jpi.profiler.ProfilerReport;
import dev.whitedev.jpi.profiler.ProfilerStatus;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.EnumMap;
import java.util.Map;
import java.util.StringJoiner;

public final class ProfilerPanel extends JPanel implements SessionAware {
    private final RuntimeTimelineStore timeline;
    private final JComboBox<String> duration = new JComboBox<>(new String[]{"15 seconds", "30 seconds", "60 seconds", "Custom"});
    private final JSpinner customSeconds = new JSpinner(new SpinnerNumberModel(15, 1, 3600, 1));
    private final Map<ProfilerCategory, JCheckBox> categoryChecks = new EnumMap<>(ProfilerCategory.class);
    private final Map<ProfilerCategory, CategoryView> categoryViews = new EnumMap<>(ProfilerCategory.class);
    private final JButton start = Ui.primaryButton("Start recording");
    private final JButton stop = Ui.secondaryButton("Stop recording");
    private final JButton refresh = Ui.secondaryButton("Refresh");
    private final JLabel status = new JLabel("Not attached");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final DefaultTableModel overview = readOnly("Category", "Events", "Total", "Unit");
    private final JLabel recordingTime = new JLabel("No recording");
    private final JLabel processedEvents = new JLabel("0 events");
    private final JLabel recordingState = new JLabel("Idle");
    private final Timer timer = new Timer(750, event -> refreshStatus());
    private InspectorSession session;
    private ProfilerStatus currentStatus;
    private long loadedReportStart;
    private boolean loading;

    public ProfilerPanel(RuntimeTimelineStore timeline) {
        super(new BorderLayout(0, 12));
        this.timeline = timeline;
        setOpaque(false);
        setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        duration.setPreferredSize(new Dimension(120, 32));
        customSeconds.setPreferredSize(new Dimension(74, 32));
        actions.add(new JLabel("Duration"));
        actions.add(duration);
        actions.add(customSeconds);
        actions.add(stop);
        actions.add(refresh);
        actions.add(start);
        add(Ui.sectionHeader("JFR Profiler", "Low-overhead runtime profiling with Java Flight Recorder", actions), BorderLayout.NORTH);

        JPanel categories = Ui.card(new FlowLayout(FlowLayout.LEFT, 12, 4));
        categories.add(new JLabel("Capture"));
        for (ProfilerCategory category : ProfilerCategory.values()) {
            JCheckBox check = new JCheckBox(category.toString(), true);
            check.setOpaque(false);
            categoryChecks.put(category, check);
            categories.add(check);
        }

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Overview", overviewPanel());
        for (ProfilerCategory category : ProfilerCategory.values()) {
            CategoryView view = new CategoryView(category);
            categoryViews.put(category, view);
            tabs.addTab(category.toString(), view.panel);
        }

        JPanel state = new JPanel(new BorderLayout(12, 0));
        state.setOpaque(false);
        status.setForeground(Ui.MUTED);
        progress.setStringPainted(true);
        progress.setPreferredSize(new Dimension(260, 20));
        state.add(status, BorderLayout.CENTER);
        state.add(progress, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(categories, BorderLayout.NORTH);
        body.add(tabs, BorderLayout.CENTER);
        body.add(state, BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);

        duration.addActionListener(event -> updateControls());
        start.addActionListener(event -> startRecording());
        stop.addActionListener(event -> stopRecording());
        refresh.addActionListener(event -> refreshStatus());
        timer.setRepeats(true);
        setSession(null);
    }

    @Override public void setSession(InspectorSession value) {
        timer.stop();
        session = value;
        currentStatus = null;
        loadedReportStart = 0L;
        loading = false;
        clearReport();
        status.setText(value == null ? "Not attached" : "Checking Java Flight Recorder availability...");
        updateProgress();
        updateControls();
        if (value != null) refreshStatus();
    }

    private JPanel overviewPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);
        JPanel metrics = new JPanel(new GridLayout(1, 3, 8, 0));
        metrics.setOpaque(false);
        metrics.add(metricCard("Recording", recordingState));
        metrics.add(metricCard("Duration", recordingTime));
        metrics.add(metricCard("Processed", processedEvents));
        panel.add(metrics, BorderLayout.NORTH);
        JTable table = new JTable(overview);
        table.setFillsViewportHeight(true);
        panel.add(Ui.scroll(table), BorderLayout.CENTER);
        return panel;
    }

    private void startRecording() {
        InspectorSession current = session;
        if (current == null || loading) return;
        StringJoiner selected = new StringJoiner(",");
        for (Map.Entry<ProfilerCategory, JCheckBox> entry : categoryChecks.entrySet()) {
            if (entry.getValue().isSelected()) selected.add(entry.getKey().name());
        }
        if (selected.length() == 0) {
            Ui.error(this, new IllegalArgumentException("Select at least one profiler category"));
            return;
        }
        int seconds = selectedDuration();
        String settings = "durationSeconds=" + seconds + ";categories=" + selected;
        loading = true;
        clearReport();
        updateControls();
        Async.run(() -> ProfilerStatus.parse(current.requestText(Operation.JFR_PROFILE_START, settings)), value -> {
            if (session != current) return;
            loading = false;
            currentStatus = value;
            loadedReportStart = 0L;
            publish("JFR recording started", selected + " | " + seconds + " seconds", value.startedAt());
            timer.start();
            applyStatus();
        }, error -> {
            if (session != current) return;
            loading = false;
            status.setForeground(Ui.WARNING);
            status.setText("Could not start JFR: " + message(error));
            updateControls();
        });
    }

    private void stopRecording() {
        InspectorSession current = session;
        if (current == null || loading) return;
        loading = true;
        updateControls();
        Async.run(() -> ProfilerStatus.parse(current.requestText(Operation.JFR_PROFILE_STOP, "")), value -> {
            if (session != current) return;
            loading = false;
            currentStatus = value;
            applyStatus();
        }, error -> {
            if (session != current) return;
            loading = false;
            status.setForeground(Ui.WARNING);
            status.setText("Could not stop JFR: " + message(error));
            updateControls();
        });
    }

    private void refreshStatus() {
        InspectorSession current = session;
        if (current == null || loading) return;
        loading = true;
        updateControls();
        Async.run(() -> ProfilerStatus.parse(current.requestText(Operation.JFR_PROFILE_STATUS, "")), value -> {
            if (session != current) return;
            loading = false;
            currentStatus = value;
            applyStatus();
        }, error -> {
            if (session != current) return;
            loading = false;
            timer.stop();
            status.setForeground(Ui.WARNING);
            status.setText("JFR status failed: " + message(error));
            updateControls();
        });
    }

    private void applyStatus() {
        ProfilerStatus value = currentStatus;
        if (value == null) return;
        status.setForeground("FAILED".equals(value.state()) || "UNSUPPORTED".equals(value.state()) ? Ui.WARNING : Ui.MUTED);
        status.setText(value.message());
        recordingState.setText(value.state());
        updateProgress();
        if ("COMPLETE".equals(value.state()) && loadedReportStart != value.startedAt()) loadReport(value.startedAt());
        if (!value.active() && !"COMPLETE".equals(value.state())) timer.stop();
        updateControls();
    }

    private void loadReport(long reportStart) {
        InspectorSession current = session;
        if (current == null || loading) return;
        loading = true;
        status.setText("Loading aggregated JFR report...");
        updateControls();
        Async.run(() -> ProfilerReport.parse(current.requestText(Operation.JFR_PROFILE_REPORT, "")), report -> {
            if (session != current) return;
            loading = false;
            loadedReportStart = reportStart;
            render(report);
            timer.stop();
            status.setForeground(report.truncated() ? Ui.WARNING : Ui.SUCCESS);
            status.setText(report.truncated() ? "Report ready. Event processing reached the safety limit." : "JFR report ready");
            publish("JFR recording completed", report.processedEvents() + " events", report.finishedAt());
            updateControls();
        }, error -> {
            if (session != current) return;
            loading = false;
            status.setForeground(Ui.WARNING);
            status.setText("Could not load JFR report: " + message(error));
            updateControls();
        });
    }

    private void render(ProfilerReport report) {
        overview.setRowCount(0);
        for (ProfilerCategory category : ProfilerCategory.values()) {
            ProfilerReport.CategoryData data = report.category(category);
            overview.addRow(new Object[]{category, data.events(), format(category, data.total()), category.unit()});
            categoryViews.get(category).setData(data);
        }
        recordingTime.setText(formatDuration(report.durationMillis()));
        processedEvents.setText(compact(report.processedEvents()) + " events");
        recordingState.setText(report.truncated() ? "Complete, limited" : "Complete");
    }

    private void clearReport() {
        overview.setRowCount(0);
        for (CategoryView view : categoryViews.values()) view.setData(new ProfilerReport.CategoryData(0L, 0L, java.util.List.of(), java.util.List.of()));
        recordingTime.setText("No recording");
        processedEvents.setText("0 events");
        recordingState.setText("Idle");
    }

    private void updateProgress() {
        ProfilerStatus value = currentStatus;
        if (value == null || value.startedAt() <= 0L || value.durationMillis() <= 0L) {
            progress.setValue(0);
            progress.setString("Idle");
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - value.startedAt());
        int amount = "COMPLETE".equals(value.state()) ? 1000
                : (int) Math.min(1000L, elapsed * 1000L / value.durationMillis());
        progress.setValue(amount);
        progress.setString("ANALYZING".equals(value.state()) ? "Analyzing" : amount / 10 + "%");
    }

    private void updateControls() {
        boolean attached = session != null;
        boolean available = currentStatus == null || currentStatus.available();
        boolean active = currentStatus != null && currentStatus.active();
        start.setEnabled(attached && available && !active && !loading);
        stop.setEnabled(attached && active && !loading);
        refresh.setEnabled(attached && !loading);
        duration.setEnabled(attached && !active && !loading);
        customSeconds.setEnabled(attached && !active && !loading && duration.getSelectedIndex() == 3);
        for (JCheckBox check : categoryChecks.values()) check.setEnabled(attached && !active && !loading);
    }

    private int selectedDuration() {
        if (duration.getSelectedIndex() == 0) return 15;
        if (duration.getSelectedIndex() == 1) return 30;
        if (duration.getSelectedIndex() == 2) return 60;
        return ((Number) customSeconds.getValue()).intValue();
    }

    private void publish(String title, String details, long timestamp) {
        timeline.publish(new TimelineEvent("jfr:" + timestamp + ":" + title, timestamp, TimelineSource.PROFILER,
                "JFR", "", "", title, details));
    }

    private static JPanel metricCard(String title, JLabel value) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(Ui.SURFACE_LIGHT);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.BORDER),
                new EmptyBorder(8, 10, 8, 10)));
        JLabel label = new JLabel(title);
        label.setForeground(Ui.MUTED);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 17f));
        panel.add(label, BorderLayout.NORTH);
        panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    private static String format(ProfilerCategory category, long value) {
        if (category == ProfilerCategory.ALLOCATIONS || category == ProfilerCategory.IO) return bytes(value);
        if (category == ProfilerCategory.LOCKS || category == ProfilerCategory.GC) return nanos(value);
        return compact(value);
    }

    private static String bytes(long value) {
        if (value >= 1024L * 1024L * 1024L) return String.format("%.2f GB", value / (1024d * 1024d * 1024d));
        if (value >= 1024L * 1024L) return String.format("%.2f MB", value / (1024d * 1024d));
        if (value >= 1024L) return String.format("%.2f KB", value / 1024d);
        return value + " B";
    }

    private static String nanos(long value) {
        if (value >= 1_000_000_000L) return String.format("%.2f s", value / 1_000_000_000d);
        if (value >= 1_000_000L) return String.format("%.2f ms", value / 1_000_000d);
        if (value >= 1_000L) return String.format("%.2f us", value / 1_000d);
        return value + " ns";
    }

    private static String compact(long value) {
        if (value >= 1_000_000_000L) return String.format("%.1fB", value / 1_000_000_000d);
        if (value >= 1_000_000L) return String.format("%.1fM", value / 1_000_000d);
        if (value >= 1_000L) return String.format("%.1fK", value / 1_000d);
        return Long.toString(value);
    }

    private static String formatDuration(long millis) {
        return millis >= 60_000L ? String.format("%.1f min", millis / 60_000d) : String.format("%.1f s", millis / 1000d);
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

    private final class CategoryView {
        final ProfilerCategory category;
        final JPanel panel = new JPanel(new BorderLayout(0, 8));
        final FlameGraphCanvas graph = new FlameGraphCanvas();
        final DefaultTableModel items = readOnly("Value", "Events", "Name");
        final JTextArea details = Ui.outputArea();

        CategoryView(ProfilerCategory category) {
            this.category = category;
            panel.setOpaque(false);
            JScrollPane graphScroll = Ui.scroll(graph);
            graphScroll.getHorizontalScrollBar().setUnitIncrement(24);
            graphScroll.getVerticalScrollBar().setUnitIncrement(24);
            JTable table = new JTable(items);
            table.setFillsViewportHeight(true);
            JSplitPane lower = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.scroll(table), Ui.scroll(details));
            lower.setResizeWeight(.68);
            lower.setDividerLocation(700);
            lower.setBorder(null);
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, graphScroll, lower);
            split.setResizeWeight(.68);
            split.setDividerLocation(470);
            split.setBorder(null);
            panel.add(split, BorderLayout.CENTER);
            details.setText("Select a flame block to inspect its contribution.");
            graph.setSelectionListener(this::selected);
        }

        void setData(ProfilerReport.CategoryData data) {
            graph.setMetrics(data.flames());
            items.setRowCount(0);
            for (ProfilerReport.Metric item : data.items()) {
                items.addRow(new Object[]{format(category, item.value()), item.count(), item.label()});
            }
            details.setText(data.events() == 0L ? "No events captured for this category."
                    : data.events() + " events\nTotal: " + format(category, data.total()));
        }

        void selected(FlameGraphCanvas.Node node) {
            if (node == null) {
                details.setText("Select a flame block to inspect its contribution.");
                return;
            }
            details.setText(node.name + "\n\nValue: " + format(category, node.value)
                    + "\nEvents: " + node.count);
            details.setCaretPosition(0);
        }
    }
}

package dev.whitedev.jpi.ui;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.protocol.Operation;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

final class OverviewPanel extends JPanel implements SessionAware {
    private final Map<String, JLabel> values = new LinkedHashMap<>();
    private final JTextArea threads = Ui.outputArea();
    private final JLabel targetInfo = new JLabel("Attach to a JVM to begin");
    private final JButton threadDump = Ui.primaryButton("Capture thread dump");
    private InspectorSession session;
    private boolean loading;

    OverviewPanel() {
        super(new BorderLayout(0, 16));
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton refresh = Ui.secondaryButton("Refresh now");
        refresh.addActionListener(e -> refresh());
        threadDump.addActionListener(e -> loadThreads());
        actions.add(refresh);
        actions.add(threadDump);
        add(Ui.sectionHeader("Overview", "Live health and runtime information from the attached JVM", actions), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 16));
        body.setOpaque(false);
        JPanel summary = new JPanel(new BorderLayout(0, 12));
        summary.setOpaque(false);
        targetInfo.setForeground(Ui.MUTED);
        summary.add(targetInfo, BorderLayout.NORTH);
        JPanel metricGrid = new JPanel(new GridLayout(2, 4, 12, 12));
        metricGrid.setOpaque(false);
        metricGrid.add(metricCard("heap.used", "Heap used"));
        metricGrid.add(metricCard("heap.max", "Heap max"));
        metricGrid.add(metricCard("threads.live", "Live threads"));
        metricGrid.add(metricCard("classes.loaded", "Loaded classes"));
        metricGrid.add(metricCard("uptime.ms", "Uptime"));
        metricGrid.add(metricCard("gc.count", "GC runs"));
        metricGrid.add(metricCard("gc.time.ms", "GC time"));
        metricGrid.add(metricCard("processors", "Processors"));
        summary.add(metricGrid, BorderLayout.CENTER);
        body.add(summary, BorderLayout.NORTH);

        JPanel threadCard = Ui.card(new BorderLayout(0, 10));
        JPanel threadHeader = new JPanel(new BorderLayout());
        threadHeader.setOpaque(false);
        JLabel threadTitle = new JLabel("Thread dump");
        threadTitle.setFont(threadTitle.getFont().deriveFont(Font.BOLD, 15f));
        JLabel threadHint = new JLabel("Stacks, locks, monitors, and deadlock information");
        threadHint.setForeground(Ui.MUTED);
        threadHeader.add(threadTitle, BorderLayout.WEST);
        threadHeader.add(threadHint, BorderLayout.EAST);
        threads.setText("No thread dump captured.");
        threadCard.add(threadHeader, BorderLayout.NORTH);
        threadCard.add(Ui.scroll(threads), BorderLayout.CENTER);
        body.add(threadCard, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        new Timer(2000, e -> refresh()).start();
        resetValues();
    }

    private JPanel metricCard(String key, String label) {
        JPanel card = Ui.card(new BorderLayout(0, 8));
        JLabel name = new JLabel(label);
        name.setForeground(Ui.MUTED);
        JLabel value = new JLabel("--");
        value.setFont(value.getFont().deriveFont(Font.BOLD, 21f));
        values.put(key, value);
        card.add(name, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        return card;
    }

    @Override public void setSession(InspectorSession session) {
        this.session = session;
        threadDump.setEnabled(session != null);
        if (session == null) {
            targetInfo.setText("Attach to a JVM to begin");
            threads.setText("No thread dump captured.");
            resetValues();
        } else {
            refresh();
        }
    }

    private void refresh() {
        final InspectorSession current = session;
        if (current == null || loading) return;
        loading = true;
        Async.run(() -> current.requestText(Operation.METRICS, ""), raw -> {
            renderMetrics(parse(raw));
            loading = false;
        }, error -> {
            loading = false;
            targetInfo.setText("Metrics unavailable: " + error.getMessage());
        });
    }

    private void loadThreads() {
        final InspectorSession current = session;
        if (current == null) return;
        threadDump.setEnabled(false);
        threads.setText("Capturing thread dump...");
        Async.run(() -> current.requestText(Operation.THREAD_DUMP, ""), value -> {
            threads.setText(value);
            threads.setCaretPosition(0);
            threadDump.setEnabled(true);
        }, error -> {
            threadDump.setEnabled(true);
            Ui.error(this, error);
        });
    }

    private Map<String, String> parse(String raw) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String line : raw.split("\\n")) {
            int separator = line.indexOf('=');
            if (separator > 0) parsed.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return parsed;
    }

    private void renderMetrics(Map<String, String> metrics) {
        targetInfo.setText(metrics.get("jvm") + "   |   PID " + metrics.get("pid"));
        set("heap.used", bytes(metrics.get("heap.used")));
        set("heap.max", bytes(metrics.get("heap.max")));
        set("threads.live", metrics.get("threads.live"));
        set("classes.loaded", metrics.get("classes.loaded"));
        set("uptime.ms", duration(metrics.get("uptime.ms")));
        set("gc.count", metrics.get("gc.count"));
        set("gc.time.ms", metrics.get("gc.time.ms") + " ms");
        set("processors", metrics.get("processors"));
    }

    private void set(String key, String value) {
        JLabel label = values.get(key);
        if (label != null) label.setText(value == null ? "--" : value);
    }

    private void resetValues() {
        for (JLabel label : values.values()) label.setText("--");
    }

    private String bytes(String value) {
        try { return String.format("%.1f MiB", Long.parseLong(value) / 1048576d); }
        catch (Exception ignored) { return "--"; }
    }

    private String duration(String value) {
        try {
            long seconds = Long.parseLong(value) / 1000;
            return String.format("%dh %02dm", seconds / 3600, seconds / 60 % 60);
        } catch (Exception ignored) { return "--"; }
    }
}

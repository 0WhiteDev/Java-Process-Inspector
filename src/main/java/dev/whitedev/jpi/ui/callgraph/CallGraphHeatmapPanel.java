package dev.whitedev.jpi.ui.callgraph;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CallGraphHeatmapPanel extends JPanel implements SessionAware {
    private final DeobfuscationWorkspace workspace;
    private final CallGraphCanvas graph;
    private final JTextField search = new JTextField();
    private final JSpinner minimumCalls = new JSpinner(new SpinnerNumberModel(1L, 0L, 1_000_000_000L, 1L));
    private final JSpinner maximumNodes = new JSpinner(new SpinnerNumberModel(100, 10, 500, 10));
    private final JComboBox<CallGraphModel.HeatMetric> heat = new JComboBox<>(CallGraphModel.HeatMetric.values());
    private final JCheckBox paused = new JCheckBox("Pause refresh");
    private final JButton refresh = Ui.secondaryButton("Refresh");
    private final JButton reset = Ui.secondaryButton("Reset data");
    private final JButton copy = Ui.secondaryButton("Copy details");
    private final JLabel nodes = metric("0");
    private final JLabel edges = metric("0");
    private final JLabel calls = metric("0");
    private final JLabel time = metric("0 ns");
    private final JLabel failures = metric("0");
    private final JLabel status = new JLabel("Attach and start Live Tracer probes");
    private final JTextArea details = Ui.outputArea();
    private final Timer pollTimer = new Timer(1_000, event -> refresh());
    private InspectorSession session;
    private CallGraphModel model = CallGraphModel.parse("");
    private CallGraphModel.Node selected;
    private boolean loading;

    public CallGraphHeatmapPanel(DeobfuscationWorkspace workspace) {
        super(new BorderLayout(0, 16));
        this.workspace = workspace;
        graph = new CallGraphCanvas(workspace);
        setOpaque(false);
        setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerActions.setOpaque(false);
        headerActions.add(paused);
        headerActions.add(reset);
        headerActions.add(refresh);
        add(Ui.sectionHeader("Call graph heatmap",
                "Separate hot execution paths from low-frequency behavior using live method metrics",
                headerActions), BorderLayout.NORTH);

        JPanel filters = Ui.card(new BorderLayout(10, 0));
        search.putClientProperty("JTextField.placeholderText", "Class, method, or descriptor");
        search.putClientProperty("JTextField.showClearButton", true);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        options.setOpaque(false);
        minimumCalls.setPreferredSize(new Dimension(94, 32));
        maximumNodes.setPreferredSize(new Dimension(78, 32));
        heat.setPreferredSize(new Dimension(130, 32));
        options.add(new JLabel("Minimum calls"));
        options.add(minimumCalls);
        options.add(new JLabel("Maximum nodes"));
        options.add(maximumNodes);
        options.add(new JLabel("Heat by"));
        options.add(heat);
        filters.add(search, BorderLayout.CENTER);
        filters.add(options, BorderLayout.EAST);

        JPanel metrics = new JPanel(new GridLayout(1, 5, 8, 0));
        metrics.setOpaque(false);
        metrics.add(metricCard("Visible nodes", nodes));
        metrics.add(metricCard("Visible edges", edges));
        metrics.add(metricCard("Measured calls", calls));
        metrics.add(metricCard("Total time", time));
        metrics.add(metricCard("Exceptions", failures));

        JPanel summary = new JPanel(new BorderLayout(0, 10));
        summary.setOpaque(false);
        summary.add(filters, BorderLayout.NORTH);
        summary.add(metrics, BorderLayout.SOUTH);

        JScrollPane graphScroll = Ui.scroll(graph);
        graphScroll.getHorizontalScrollBar().setUnitIncrement(24);
        graphScroll.getVerticalScrollBar().setUnitIncrement(24);
        JPanel detailCard = Ui.card(new BorderLayout(0, 8));
        JPanel detailHeader = new JPanel(new BorderLayout());
        detailHeader.setOpaque(false);
        JLabel detailTitle = new JLabel("Method details");
        detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 13f));
        copy.setEnabled(false);
        detailHeader.add(detailTitle, BorderLayout.WEST);
        detailHeader.add(copy, BorderLayout.EAST);
        details.setText("Select a node to inspect callers, callees, timing, and exceptions.");
        detailCard.add(detailHeader, BorderLayout.NORTH);
        detailCard.add(Ui.scroll(details), BorderLayout.CENTER);
        detailCard.setMinimumSize(new Dimension(320, 200));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphScroll, detailCard);
        split.setResizeWeight(.76);
        split.setDividerLocation(850);
        split.setDividerSize(8);
        split.setBorder(null);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(summary, BorderLayout.NORTH);
        body.add(split, BorderLayout.CENTER);
        status.setForeground(Ui.MUTED);
        body.add(status, BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);

        graph.setSelectionListener(this::select);
        refresh.addActionListener(event -> refresh());
        reset.addActionListener(event -> reset());
        copy.addActionListener(event -> copy());
        paused.addActionListener(event -> updateButtons());
        minimumCalls.addChangeListener(event -> render());
        maximumNodes.addChangeListener(event -> render());
        heat.addActionListener(event -> render());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { render(); }
            @Override public void removeUpdate(DocumentEvent event) { render(); }
            @Override public void changedUpdate(DocumentEvent event) { render(); }
        });
        workspace.addListener(() -> javax.swing.SwingUtilities.invokeLater(() -> {
            graph.repaint();
            select(selected);
        }));
        pollTimer.start();
        setSession(null);
    }

    @Override public void setSession(InspectorSession value) {
        session = value;
        loading = false;
        paused.setSelected(false);
        model = CallGraphModel.parse("");
        selected = null;
        graph.setView(model.view("", 0L, 100, CallGraphModel.HeatMetric.CALLS));
        details.setText("Select a node to inspect callers, callees, timing, and exceptions.");
        status.setText(value == null ? "Not attached" : "Start Live Tracer probes to collect call graph data");
        render();
        updateButtons();
        if (value != null) refresh();
    }

    private void refresh() {
        InspectorSession current = session;
        if (current == null || loading || paused.isSelected()) return;
        loading = true;
        updateButtons();
        Async.run(() -> current.requestText(Operation.TRACE_GRAPH, ""), raw -> {
            if (session != current) return;
            loading = false;
            model = CallGraphModel.parse(raw);
            render();
            status.setForeground(Ui.MUTED);
            status.setText(model.nodes.isEmpty()
                    ? "No runtime graph yet. Add probes in Live tracer and exercise the target application."
                    : "Live graph refreshed. Edge width uses observed call count.");
            updateButtons();
        }, error -> {
            if (session != current) return;
            loading = false;
            status.setForeground(Ui.WARNING);
            status.setText("Call graph refresh failed: " + error.getMessage());
            updateButtons();
        });
    }

    private void render() {
        CallGraphModel.HeatMetric metric = (CallGraphModel.HeatMetric) heat.getSelectedItem();
        if (metric == null) metric = CallGraphModel.HeatMetric.CALLS;
        long minimum = ((Number) minimumCalls.getValue()).longValue();
        int maximum = ((Number) maximumNodes.getValue()).intValue();
        CallGraphModel.View view = model.view(search.getText(), minimum, maximum, metric, this::display);
        graph.setView(view);
        nodes.setText(Integer.toString(view.nodes().size()));
        edges.setText(Integer.toString(view.edges().size()));
        calls.setText(compact(model.totalCalls()));
        time.setText(formatNanos(model.totalNanos()));
        failures.setText(compact(model.exceptions()));
    }

    private void select(CallGraphModel.Node node) {
        selected = node;
        copy.setEnabled(node != null);
        if (node == null) {
            details.setText("Select a node to inspect callers, callees, timing, and exceptions.");
            return;
        }
        StringBuilder output = new StringBuilder();
        output.append(display(node)).append("\n\n")
                .append("Calls: ").append(node.calls).append('\n')
                .append("Completed: ").append(node.completed).append('\n')
                .append("Total time: ").append(formatNanos(node.totalNanos)).append('\n')
                .append("Average time: ").append(formatNanos(node.averageNanos())).append('\n')
                .append("Exceptions: ").append(node.exceptions).append('\n')
                .append("Unique callers: ").append(node.uniqueCallers).append('\n')
                .append("Probe active: ").append(node.active ? "yes" : "no").append("\n\n")
                .append("Called by\n");
        appendEdges(output, node.incoming, true);
        output.append("\nCalls\n");
        appendEdges(output, node.outgoing, false);
        if (!node.measured()) {
            output.append("\nTiming is unavailable because this method was observed as a callee but did not have its own Live Tracer probe.\n");
        }
        details.setText(output.toString());
        details.setCaretPosition(0);
    }

    private void appendEdges(StringBuilder output, List<CallGraphModel.Edge> source, boolean incoming) {
        List<CallGraphModel.Edge> ordered = new ArrayList<>(source);
        ordered.sort(Comparator.comparingLong((CallGraphModel.Edge edge) -> edge.calls).reversed());
        if (ordered.isEmpty()) {
            output.append("  None\n");
            return;
        }
        int limit = Math.min(50, ordered.size());
        for (int index = 0; index < limit; index++) {
            CallGraphModel.Edge edge = ordered.get(index);
            String owner = incoming ? edge.callerClass : edge.calleeClass;
            String method = incoming ? edge.callerMethod : edge.calleeMethod;
            String descriptor = incoming ? edge.callerDescriptor : edge.calleeDescriptor;
            output.append("  ").append(compact(edge.calls)).append("x  ")
                    .append(workspace.classAlias(owner)).append('.')
                    .append(workspace.methodAlias(owner, method, descriptor)).append(descriptor);
            if (edge.failures > 0L) output.append("  ").append(edge.failures).append(" failed");
            if (edge.reflective) output.append("  reflective");
            output.append('\n');
        }
        if (ordered.size() > limit) output.append("  ... ").append(ordered.size() - limit).append(" more\n");
    }

    private void reset() {
        InspectorSession current = session;
        if (current == null || loading) return;
        int choice = JOptionPane.showConfirmDialog(this,
                "Clear all accumulated call graph counts and timings for this session?",
                "Reset call graph", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        loading = true;
        updateButtons();
        Async.run(() -> current.requestText(Operation.TRACE_GRAPH_CLEAR, ""), response -> {
            if (session != current) return;
            loading = false;
            status.setForeground(Ui.SUCCESS);
            status.setText(response);
            refresh();
        }, error -> {
            if (session != current) return;
            loading = false;
            status.setForeground(Ui.WARNING);
            status.setText("Could not reset call graph: " + error.getMessage());
            updateButtons();
        });
    }

    private void copy() {
        if (selected == null) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(details.getText()), null);
        status.setText("Method metrics copied");
    }

    private void updateButtons() {
        boolean attached = session != null;
        refresh.setEnabled(attached && !loading && !paused.isSelected());
        reset.setEnabled(attached && !loading);
        paused.setEnabled(attached);
    }

    private String display(CallGraphModel.Node node) {
        return workspace.classAlias(node.className) + "."
                + workspace.methodAlias(node.className, node.methodName, node.descriptor) + node.descriptor;
    }

    private static JPanel metricCard(String title, JLabel value) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Ui.SURFACE_LIGHT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Ui.BORDER), new EmptyBorder(8, 10, 8, 10)));
        JLabel label = new JLabel(title);
        label.setForeground(Ui.MUTED);
        panel.add(label);
        panel.add(value);
        return panel;
    }

    private static JLabel metric(String value) {
        JLabel label = new JLabel(value);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 18f));
        return label;
    }

    private static String formatNanos(long nanos) {
        if (nanos < 0L) return "n/a";
        if (nanos >= 1_000_000_000L) return String.format("%.2f s", nanos / 1_000_000_000d);
        if (nanos >= 1_000_000L) return String.format("%.2f ms", nanos / 1_000_000d);
        if (nanos >= 1_000L) return String.format("%.2f us", nanos / 1_000d);
        return nanos + " ns";
    }

    private static String compact(long value) {
        if (value >= 1_000_000_000L) return String.format("%.1fB", value / 1_000_000_000d);
        if (value >= 1_000_000L) return String.format("%.1fM", value / 1_000_000d);
        if (value >= 1_000L) return String.format("%.1fK", value / 1_000d);
        return Long.toString(value);
    }
}

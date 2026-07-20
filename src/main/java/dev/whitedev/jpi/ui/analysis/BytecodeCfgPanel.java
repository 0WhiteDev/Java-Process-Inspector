package dev.whitedev.jpi.ui.analysis;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;

public final class BytecodeCfgPanel extends JPanel implements SessionAware {
    private static final Color DEAD = new Color(220, 88, 96);
    private static final Color NEVER_EXECUTED = new Color(238, 174, 72);

    private final DeobfuscationWorkspace workspace;
    private final JTextField className = targetField();
    private final JTextField methodName = targetField();
    private final JTextField descriptor = targetField();
    private final JButton analyze = Ui.primaryButton("Analyze CFG");
    private final JButton startTrace = Ui.primaryButton("Start block trace");
    private final JButton stopTrace = Ui.secondaryButton("Stop trace");
    private final JSpinner duration = new JSpinner(new SpinnerNumberModel(30, 1, 600, 5));
    private final JLabel status = new JLabel("Select a method from Loaded classes");
    private final JLabel blocks = metric("0");
    private final JLabel edges = metric("0");
    private final JLabel complexity = metric("0");
    private final JLabel dead = metric("0");
    private final JLabel executions = metric("0");
    private final CfgGraphCanvas graph = new CfgGraphCanvas();
    private final BlockDetailsView details = new BlockDetailsView();
    private final Timer pollTimer = new Timer(750, event -> poll());

    private InspectorSession session;
    private CfgGraphModel model;
    private String classIdentifier = "";
    private String actualClassName = "";
    private String actualMethodName = "";
    private String actualDescriptor = "";
    private String probeId = "";
    private boolean traceActive;
    private boolean analyzing;
    private boolean polling;
    private long generation;

    public BytecodeCfgPanel(DeobfuscationWorkspace workspace) {
        super(new BorderLayout(0, 16));
        this.workspace = workspace;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        duration.setPreferredSize(new Dimension(72, 34));
        actions.add(new JLabel("Trace seconds"));
        actions.add(duration);
        actions.add(stopTrace);
        actions.add(startTrace);
        actions.add(analyze);
        add(Ui.sectionHeader("Bytecode CFG",
                "Inspect basic blocks, control-flow edges, dominators, dead code, and live block execution counts",
                actions), BorderLayout.NORTH);

        JPanel target = Ui.card(new BorderLayout(0, 10));
        JPanel fields = new JPanel(new GridLayout(2, 3, 8, 5));
        fields.setOpaque(false);
        fields.add(label("Class"));
        fields.add(label("Method"));
        fields.add(label("Descriptor"));
        fields.add(className);
        fields.add(methodName);
        fields.add(descriptor);
        target.add(fields, BorderLayout.NORTH);
        target.add(metrics(), BorderLayout.SOUTH);

        JScrollPane graphScroll = Ui.scroll(graph);
        graphScroll.getVerticalScrollBar().setUnitIncrement(24);
        graphScroll.getHorizontalScrollBar().setUnitIncrement(24);
        details.setContent("Select a basic block to inspect its instructions and control-flow relations.");
        JPanel detailCard = Ui.card(new BorderLayout(0, 8));
        JLabel detailTitle = new JLabel("Basic block details");
        detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 13f));
        JScrollPane detailScroll = Ui.scroll(details);
        detailScroll.setMinimumSize(new Dimension(300, 200));
        detailScroll.setPreferredSize(new Dimension(360, 200));
        detailCard.add(detailTitle, BorderLayout.NORTH);
        detailCard.add(detailScroll, BorderLayout.CENTER);
        detailCard.setMinimumSize(new Dimension(300, 200));
        detailCard.setPreferredSize(new Dimension(360, 200));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, graphScroll, detailCard);
        split.setResizeWeight(.76);
        split.setDividerLocation(820);
        split.setDividerSize(8);
        split.setBorder(null);
        split.setContinuousLayout(true);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(target, BorderLayout.NORTH);
        body.add(split, BorderLayout.CENTER);
        body.add(footer(), BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);

        graph.setSelectionListener(this::showBlockDetails);
        analyze.addActionListener(event -> analyze());
        startTrace.addActionListener(event -> startTrace());
        stopTrace.addActionListener(event -> stopTrace(null));
        pollTimer.start();
        setSession(null);
    }

    public void selectTarget(String identifier, String owner, String method, String methodDescriptor) {
        Runnable select = () -> {
            classIdentifier = identifier;
            actualClassName = owner;
            actualMethodName = method;
            actualDescriptor = methodDescriptor;
            String classAlias = workspace.classAlias(owner);
            String methodAlias = workspace.methodAlias(owner, method, methodDescriptor);
            className.setText(classAlias.equals(owner) ? owner : classAlias + " [" + owner + "]");
            methodName.setText(methodAlias.equals(method) ? method : methodAlias + " [" + method + "]");
            descriptor.setText(methodDescriptor);
            probeId = "";
            traceActive = false;
            model = null;
            graph.setModel(null);
            clearMetrics();
            status.setForeground(Ui.MUTED);
            status.setText("Ready to analyze " + owner + "." + method + methodDescriptor);
            updateButtons();
            analyze();
        };
        if (traceActive && session != null && !probeId.isEmpty()) stopTrace(select);
        else select.run();
    }

    @Override public void setSession(InspectorSession value) {
        session = value;
        generation++;
        analyzing = false;
        polling = false;
        probeId = "";
        traceActive = false;
        model = null;
        classIdentifier = "";
        actualClassName = "";
        actualMethodName = "";
        actualDescriptor = "";
        className.setText("");
        methodName.setText("");
        descriptor.setText("");
        graph.setModel(null);
        clearMetrics();
        status.setForeground(Ui.MUTED);
        status.setText(value == null ? "Not attached" : "Select a method from Loaded classes");
        updateButtons();
    }

    private void analyze() {
        InspectorSession current = session;
        if (current == null || classIdentifier.isEmpty() || analyzing) return;
        analyzing = true;
        long requestGeneration = ++generation;
        analyze.setEnabled(false);
        status.setForeground(Ui.MUTED);
        status.setText("Building basic blocks and control-flow edges...");
        String payload = classIdentifier + "\n" + actualMethodName + "\n" + actualDescriptor;
        Async.run(() -> current.requestText(Operation.CFG_ANALYZE, payload), raw -> {
            if (session != current || requestGeneration != generation) return;
            try {
                model = CfgGraphModel.parse(raw);
                graph.setModel(model);
                renderMetrics();
                status.setForeground(Ui.SUCCESS);
                status.setText(model.blocks.size() + " basic blocks, " + model.edges.size()
                        + " edges, cyclomatic complexity " + model.complexity);
            } catch (RuntimeException error) {
                model = null;
                graph.setModel(null);
                status.setForeground(Ui.WARNING);
                status.setText("The target returned an invalid CFG");
                Ui.error(this, error);
            }
            analyzing = false;
            updateButtons();
        }, error -> {
            if (session != current || requestGeneration != generation) return;
            analyzing = false;
            model = null;
            graph.setModel(null);
            status.setForeground(Ui.WARNING);
            status.setText("CFG analysis failed");
            updateButtons();
            Ui.error(this, error);
        });
    }

    private void startTrace() {
        InspectorSession current = session;
        if (current == null || model == null || traceActive) return;
        startTrace.setEnabled(false);
        status.setForeground(Ui.MUTED);
        status.setText("Installing block counters...");
        long durationMillis = ((Number) duration.getValue()).longValue() * 1000L;
        String payload = classIdentifier + "\n" + actualMethodName + "\n"
                + actualDescriptor + "\n" + durationMillis;
        Async.run(() -> current.requestText(Operation.CFG_TRACE_START, payload), raw -> {
            if (session != current) return;
            String[] values = raw.split("\t", -1);
            if (values.length != 4 || !"P".equals(values[0])) {
                Ui.error(this, new IllegalStateException("The target returned an invalid CFG probe response"));
                updateButtons();
                return;
            }
            probeId = values[1];
            traceActive = true;
            status.setForeground(Ui.SUCCESS);
            status.setText("Block trace active. Perform the action in the target application.");
            updateButtons();
            poll();
        }, error -> {
            if (session != current) return;
            traceActive = false;
            status.setForeground(Ui.WARNING);
            status.setText("Block trace installation failed");
            updateButtons();
            Ui.error(this, error);
        });
    }

    private void stopTrace(Runnable completion) {
        InspectorSession current = session;
        String currentProbe = probeId;
        if (current == null || currentProbe.isEmpty()) {
            traceActive = false;
            updateButtons();
            if (completion != null) completion.run();
            return;
        }
        stopTrace.setEnabled(false);
        Async.run(() -> current.requestText(Operation.CFG_TRACE_STOP, currentProbe), response -> {
            if (session != current) return;
            traceActive = false;
            status.setForeground(Ui.MUTED);
            status.setText(response);
            updateButtons();
            poll();
            if (completion != null) completion.run();
        }, error -> {
            if (session != current) return;
            traceActive = true;
            status.setForeground(Ui.WARNING);
            status.setText("Could not stop CFG block trace");
            updateButtons();
            Ui.error(this, error);
        });
    }

    private void poll() {
        InspectorSession current = session;
        String currentProbe = probeId;
        if (current == null || currentProbe.isEmpty() || model == null || polling) return;
        polling = true;
        Async.run(() -> current.requestText(Operation.CFG_SNAPSHOT, currentProbe), raw -> {
            polling = false;
            if (session != current || !currentProbe.equals(probeId) || model == null) return;
            try {
                CfgGraphModel.Snapshot snapshot = model.applySnapshot(raw);
                traceActive = snapshot.active;
                graph.refreshCounts();
                refreshSelectedExecutionCount();
                renderMetrics();
                if (!snapshot.active && snapshot.stoppedAt > 0L) {
                    status.setForeground(Ui.MUTED);
                    status.setText("Block trace finished. Zero-hit reachable blocks are highlighted.");
                }
                updateButtons();
            } catch (RuntimeException error) {
                status.setForeground(Ui.WARNING);
                status.setText("Could not parse block counters");
            }
        }, error -> {
            polling = false;
            if (session == current) {
                status.setForeground(Ui.WARNING);
                status.setText("CFG polling failed: " + error.getMessage());
            }
        });
    }

    private JPanel metrics() {
        JPanel panel = new JPanel(new GridLayout(1, 5, 8, 0));
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(12, 0, 0, 0));
        panel.add(metricCard("Basic blocks", blocks));
        panel.add(metricCard("Edges", edges));
        panel.add(metricCard("Complexity", complexity));
        panel.add(metricCard("Static dead", dead));
        panel.add(metricCard("Block hits", executions));
        return panel;
    }

    private JPanel footer() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setOpaque(false);
        status.setForeground(Ui.MUTED);
        panel.add(status, BorderLayout.CENTER);
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        legend.setOpaque(false);
        legend.add(key("Static", Ui.ACCENT));
        legend.add(key("Executed", Ui.SUCCESS));
        legend.add(key("Not executed", NEVER_EXECUTED));
        legend.add(key("Dead", DEAD));
        legend.add(key("Exception edge", new Color(220, 120, 132)));
        panel.add(legend, BorderLayout.EAST);
        return panel;
    }

    private void showBlockDetails(CfgGraphModel.Block block) {
        details.setContent(model == null ? "" : model.details(block));
    }

    private void refreshSelectedExecutionCount() {
        CfgGraphModel.Block block = graph.selectedBlock();
        if (block != null) details.updateExecution(block.executions);
    }

    private void renderMetrics() {
        if (model == null) {
            clearMetrics();
            return;
        }
        blocks.setText(String.valueOf(model.blocks.size()));
        edges.setText(String.valueOf(model.edges.size()));
        complexity.setText(String.valueOf(model.complexity));
        dead.setText(String.valueOf(model.deadBlocks));
        executions.setText(String.valueOf(model.totalExecutions));
    }

    private void clearMetrics() {
        blocks.setText("0");
        edges.setText("0");
        complexity.setText("0");
        dead.setText("0");
        executions.setText("0");
        details.setContent("Select a basic block to inspect its instructions and control-flow relations.");
    }

    private void updateButtons() {
        boolean attached = session != null;
        boolean target = attached && !classIdentifier.isEmpty();
        analyze.setEnabled(target && !analyzing);
        startTrace.setEnabled(target && model != null && !traceActive && !analyzing);
        stopTrace.setEnabled(attached && traceActive && !probeId.isEmpty());
        duration.setEnabled(attached && !traceActive);
    }

    private static JTextField targetField() {
        JTextField field = new JTextField();
        field.setEditable(false);
        return field;
    }

    private static JLabel label(String value) {
        JLabel label = new JLabel(value);
        label.setForeground(Ui.MUTED);
        return label;
    }

    private static JLabel metric(String value) {
        JLabel label = new JLabel(value);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 18f));
        return label;
    }

    private static JComponent metricCard(String name, JLabel value) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Ui.SURFACE_LIGHT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Ui.BORDER), new EmptyBorder(8, 10, 8, 10)));
        JLabel title = new JLabel(name);
        title.setForeground(Ui.MUTED);
        panel.add(title);
        panel.add(value);
        return panel;
    }

    private static JLabel key(String text, Color color) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        return label;
    }
}


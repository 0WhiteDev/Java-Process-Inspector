package dev.whitedev.jpi.ui.analysis;

import dev.whitedev.jpi.analysis.difference.CfgTransition;
import dev.whitedev.jpi.analysis.difference.DifferenceAnalyzer;
import dev.whitedev.jpi.analysis.difference.DifferenceReport;
import dev.whitedev.jpi.analysis.difference.DifferenceRun;
import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DifferenceTracingPanel extends JPanel implements SessionAware {
    private final DeobfuscationWorkspace workspace;
    private final RuntimeTimelineStore timeline;
    private final DifferenceAnalyzer analyzer = new DifferenceAnalyzer();
    private final JTextField className = targetField();
    private final JTextField methodName = targetField();
    private final JTextField descriptor = targetField();
    private final JCheckBox branches = new JCheckBox("Capture ordered CFG branches", true);
    private final JSpinner duration = new JSpinner(new SpinnerNumberModel(120, 5, 600, 5));
    private final JButton startA = Ui.primaryButton("Record Run A");
    private final JButton startB = Ui.primaryButton("Record Run B");
    private final JButton stop = Ui.secondaryButton("Stop recording");
    private final JButton compare = Ui.primaryButton("Compare runs");
    private final JButton reset = Ui.secondaryButton("Reset");
    private final JButton copy = Ui.secondaryButton("Copy report");
    private final JLabel stateA = new JLabel("Not recorded");
    private final JLabel stateB = new JLabel("Not recorded");
    private final JLabel common = metric("0");
    private final JLabel onlyA = metric("0");
    private final JLabel onlyB = metric("0");
    private final JLabel branchChanges = metric("0");
    private final JLabel returnChanges = metric("0");
    private final JLabel apiChanges = metric("0");
    private final JLabel status = new JLabel("Attach and record two executions");
    private final JTextArea divergence = Ui.outputArea();
    private final DefaultTableModel changeModel = readOnlyModel("Kind", "Location", "Run A", "Run B");
    private final JTable changes = new JTable(changeModel);

    private InspectorSession session;
    private DifferenceRun runA;
    private DifferenceRun runB;
    private Capture capture;
    private DifferenceReport report;
    private String classIdentifier = "";
    private String actualClass = "";
    private String actualMethod = "";
    private String actualDescriptor = "";

    public DifferenceTracingPanel(DeobfuscationWorkspace workspace, RuntimeTimelineStore timeline) {
        super(new BorderLayout(0, 16));
        this.workspace = workspace;
        this.timeline = timeline;
        setOpaque(false);
        setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(copy);
        actions.add(reset);
        actions.add(compare);
        add(Ui.sectionHeader("Difference tracing",
                "Record the same action twice and find the first behavioral divergence", actions), BorderLayout.NORTH);

        JPanel target = Ui.card(new BorderLayout(0, 10));
        JPanel fields = new JPanel(new GridLayout(2, 3, 8, 5));
        fields.setOpaque(false);
        fields.add(label("CFG class"));
        fields.add(label("CFG method"));
        fields.add(label("Descriptor"));
        fields.add(className);
        fields.add(methodName);
        fields.add(descriptor);
        target.add(fields, BorderLayout.CENTER);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        options.setOpaque(false);
        duration.setPreferredSize(new Dimension(76, 32));
        options.add(branches);
        options.add(new JLabel("Maximum seconds"));
        options.add(duration);
        target.add(options, BorderLayout.SOUTH);

        JPanel runControls = new JPanel(new GridLayout(1, 2, 8, 0));
        runControls.setOpaque(false);
        runControls.add(runCard("Run A", "Perform the baseline action", stateA, startA));
        runControls.add(runCard("Run B", "Perform the changed action", stateB, startB));

        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.setOpaque(false);
        top.add(target, BorderLayout.NORTH);
        top.add(runControls, BorderLayout.CENTER);
        JPanel stopRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        stopRow.setOpaque(false);
        stopRow.add(stop);
        top.add(stopRow, BorderLayout.SOUTH);

        JPanel metrics = new JPanel(new GridLayout(1, 6, 8, 0));
        metrics.setOpaque(false);
        metrics.add(metricCard("Common methods", common));
        metrics.add(metricCard("Only A", onlyA));
        metrics.add(metricCard("Only B", onlyB));
        metrics.add(metricCard("Branches", branchChanges));
        metrics.add(metricCard("Returns", returnChanges));
        metrics.add(metricCard("API calls", apiChanges));

        divergence.setEditable(false);
        divergence.setLineWrap(true);
        divergence.setWrapStyleWord(true);
        divergence.setText("First divergence will appear after both runs are compared.");
        JPanel first = Ui.card(new BorderLayout(0, 8));
        JLabel title = new JLabel("First divergence");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        first.add(title, BorderLayout.NORTH);
        first.add(new JScrollPane(divergence), BorderLayout.CENTER);
        changes.setAutoCreateRowSorter(true);
        changes.setFillsViewportHeight(true);
        JSplitPane results = new JSplitPane(JSplitPane.VERTICAL_SPLIT, first, new JScrollPane(changes));
        results.setResizeWeight(.32);
        results.setDividerLocation(150);
        results.setBorder(null);

        JPanel upper = new JPanel(new BorderLayout(0, 10));
        upper.setOpaque(false);
        upper.add(top, BorderLayout.CENTER);
        upper.add(metrics, BorderLayout.SOUTH);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(upper, BorderLayout.NORTH);
        JPanel lower = new JPanel(new BorderLayout(0, 8));
        lower.setOpaque(false);
        lower.add(results, BorderLayout.CENTER);
        status.setForeground(Ui.MUTED);
        lower.add(status, BorderLayout.SOUTH);
        body.add(lower, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        startA.addActionListener(event -> start(true));
        startB.addActionListener(event -> start(false));
        stop.addActionListener(event -> stop());
        compare.addActionListener(event -> compare());
        reset.addActionListener(event -> reset());
        copy.addActionListener(event -> copy());
        branches.addActionListener(event -> updateButtons());
        setSession(null);
    }

    public void selectTarget(String identifier, String owner, String method, String methodDescriptor) {
        if (capture != null) {
            status.setText("Stop the current recording before selecting another CFG target");
            return;
        }
        String previous = subject();
        classIdentifier = clean(identifier);
        actualClass = clean(owner);
        actualMethod = clean(method);
        actualDescriptor = clean(methodDescriptor);
        if (!previous.equals(subject()) && (runA != null || runB != null)) reset();
        String ownerAlias = workspace.classAlias(actualClass);
        String methodAlias = workspace.methodAlias(actualClass, actualMethod, actualDescriptor);
        className.setText(ownerAlias.equals(actualClass) ? actualClass : ownerAlias + " [" + actualClass + "]");
        methodName.setText(methodAlias.equals(actualMethod) ? actualMethod : methodAlias + " [" + actualMethod + "]");
        descriptor.setText(actualDescriptor);
        status.setText("CFG comparison target selected: " + subject());
        updateButtons();
    }

    @Override public void setSession(InspectorSession value) {
        session = value;
        capture = null;
        runA = null;
        runB = null;
        report = null;
        clearResults();
        stateA.setText("Not recorded");
        stateB.setText("Not recorded");
        status.setText(value == null ? "Not attached" : "Record Run A, then Run B");
        updateButtons();
    }

    private void start(boolean first) {
        InspectorSession current = session;
        if (current == null || capture != null) return;
        report = null;
        clearResults();
        (first ? stateA : stateB).setText("Preparing...");
        Capture next = new Capture(first, System.currentTimeMillis(), keys(), "");
        if (!branches.isSelected() || classIdentifier.isEmpty()) {
            begin(next);
            return;
        }
        status.setText("Installing ordered CFG trace for Run " + (first ? "A" : "B") + "...");
        String payload = classIdentifier + "\n" + actualMethod + "\n" + actualDescriptor + "\n"
                + ((Number) duration.getValue()).longValue() * 1000L;
        disableActions();
        Async.run(() -> current.requestText(Operation.CFG_TRACE_START, payload), raw -> {
            if (session != current) return;
            String[] values = raw.split("\t", -1);
            if (values.length != 4 || !"P".equals(values[0])) {
                status.setText("The target returned an invalid CFG probe response");
                restoreRunState(first);
                updateButtons();
                return;
            }
            begin(new Capture(first, System.currentTimeMillis(), keys(), values[1]));
        }, error -> {
            if (session != current) return;
            status.setText("Could not start CFG trace");
            restoreRunState(first);
            updateButtons();
            Ui.error(this, error);
        });
    }

    private void begin(Capture value) {
        capture = value;
        status.setForeground(Ui.SUCCESS);
        status.setText("Recording Run " + (value.first ? "A" : "B") + ". Perform the action, then click Stop recording.");
        (value.first ? stateA : stateB).setText("Recording...");
        updateButtons();
    }

    private void stop() {
        InspectorSession current = session;
        Capture active = capture;
        if (current == null || active == null) return;
        disableActions();
        status.setForeground(Ui.MUTED);
        status.setText("Stopping Run " + (active.first ? "A" : "B") + " and collecting events...");
        if (active.probeId.isEmpty()) {
            finish(active, "");
            return;
        }
        Async.run(() -> {
            current.requestText(Operation.CFG_TRACE_STOP, active.probeId);
            return current.requestText(Operation.CFG_SNAPSHOT, active.probeId);
        }, snapshot -> {
            if (session == current && capture == active) finish(active, snapshot);
        }, error -> {
            if (session != current) return;
            capture = null;
            status.setText("Could not finish the CFG trace");
            updateButtons();
            Ui.error(this, error);
        });
    }

    private void finish(Capture active, String snapshot) {
        long stoppedAt = System.currentTimeMillis();
        List<TimelineEvent> events = new ArrayList<>();
        for (TimelineEvent event : timeline.events()) {
            if (!active.baseline.contains(event.key())) events.add(event);
        }
        List<CfgTransition> transitions = CfgTransition.parse(snapshot, subject());
        DifferenceRun completed = new DifferenceRun(active.first ? "Run A" : "Run B",
                active.startedAt, stoppedAt, events, transitions);
        if (active.first) runA = completed;
        else runB = completed;
        publishTransitions(active.probeId, transitions);
        JLabel label = active.first ? stateA : stateB;
        label.setText(events.size() + " events, " + transitions.size() + " CFG transitions");
        capture = null;
        status.setForeground(Ui.SUCCESS);
        status.setText("Run " + (active.first ? "A" : "B") + " captured");
        updateButtons();
        if (runA != null && runB != null) compare();
    }

    private void publishTransitions(String probeId, List<CfgTransition> transitions) {
        for (CfgTransition transition : transitions) {
            String key = "cfg:" + probeId + ":" + transition.sequence();
            timeline.publish(new TimelineEvent(key, transition.timestamp(), TimelineSource.CFG,
                    "thread-" + transition.threadId(), "", "", transition.route(),
                    "Method: " + transition.subject() + "\nTransition: " + transition.route(),
                    transition.subject(), "BRANCH", transition.route()));
        }
    }

    private void compare() {
        if (runA == null || runB == null) return;
        report = analyzer.compare(runA, runB);
        common.setText(String.valueOf(report.commonMethods()));
        onlyA.setText(String.valueOf(report.onlyA().size()));
        onlyB.setText(String.valueOf(report.onlyB().size()));
        branchChanges.setText(String.valueOf(report.differentBranches()));
        returnChanges.setText(String.valueOf(report.differentReturns()));
        apiChanges.setText(String.valueOf(report.differentApiCalls()));
        changeModel.setRowCount(0);
        for (DifferenceReport.Change change : report.changes()) {
            changeModel.addRow(new Object[]{change.category(), change.subject(), change.runA(), change.runB()});
        }
        DifferenceReport.Divergence first = report.firstDivergence();
        if (first == null) {
            divergence.setText("No divergence was observed in the captured methods, branches, returns, or API calls.");
        } else {
            divergence.setText(first.category() + "\n" + first.subject() + "\n\nRun A:\n"
                    + first.runA() + "\n\nRun B:\n" + first.runB());
        }
        divergence.setCaretPosition(0);
        status.setText(report.changes().size() + " behavioral differences found");
        updateButtons();
    }

    private void reset() {
        runA = null;
        runB = null;
        report = null;
        stateA.setText("Not recorded");
        stateB.setText("Not recorded");
        clearResults();
        status.setText(session == null ? "Not attached" : "Record Run A, then Run B");
        updateButtons();
    }

    private void copy() {
        if (report == null) return;
        StringBuilder value = new StringBuilder();
        value.append("Difference tracing\n\nCommon methods: ").append(report.commonMethods())
                .append("\nOnly A: ").append(report.onlyA().size())
                .append("\nOnly B: ").append(report.onlyB().size())
                .append("\nDifferent branches: ").append(report.differentBranches())
                .append("\nDifferent returns: ").append(report.differentReturns())
                .append("\nDifferent API calls: ").append(report.differentApiCalls()).append("\n\n")
                .append(divergence.getText()).append("\n\nChanges\n");
        for (DifferenceReport.Change change : report.changes()) {
            value.append(change.category()).append(" | ").append(change.subject()).append(" | ")
                    .append(change.runA()).append(" | ").append(change.runB()).append('\n');
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value.toString()), null);
        status.setText("Difference report copied");
    }

    private Set<String> keys() {
        Set<String> keys = new HashSet<>();
        for (TimelineEvent event : timeline.events()) keys.add(event.key());
        return keys;
    }

    private String subject() {
        return actualClass + "." + actualMethod + actualDescriptor;
    }

    private void clearResults() {
        common.setText("0");
        onlyA.setText("0");
        onlyB.setText("0");
        branchChanges.setText("0");
        returnChanges.setText("0");
        apiChanges.setText("0");
        changeModel.setRowCount(0);
        divergence.setText("First divergence will appear after both runs are compared.");
    }

    private void restoreRunState(boolean first) {
        DifferenceRun run = first ? runA : runB;
        (first ? stateA : stateB).setText(run == null ? "Not recorded"
                : run.events().size() + " events, " + run.transitions().size() + " CFG transitions");
    }

    private void disableActions() {
        startA.setEnabled(false);
        startB.setEnabled(false);
        stop.setEnabled(false);
        compare.setEnabled(false);
        reset.setEnabled(false);
    }

    private void updateButtons() {
        boolean attached = session != null;
        boolean recording = capture != null;
        startA.setEnabled(attached && !recording);
        startB.setEnabled(attached && !recording);
        stop.setEnabled(attached && recording);
        compare.setEnabled(!recording && runA != null && runB != null);
        reset.setEnabled(!recording && (runA != null || runB != null));
        copy.setEnabled(report != null);
        branches.setEnabled(attached && !recording);
        duration.setEnabled(attached && !recording && branches.isSelected());
    }

    private JPanel runCard(String title, String hint, JLabel state, JButton button) {
        JPanel panel = Ui.card(new BorderLayout(8, 0));
        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 14f));
        JLabel description = new JLabel(hint);
        description.setForeground(Ui.MUTED);
        state.setForeground(Ui.MUTED);
        labels.add(heading);
        labels.add(description);
        labels.add(state);
        panel.add(labels, BorderLayout.CENTER);
        panel.add(button, BorderLayout.EAST);
        return panel;
    }

    private static JComponent metricCard(String title, JLabel value) {
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

    private static JLabel label(String value) {
        JLabel label = new JLabel(value);
        label.setForeground(Ui.MUTED);
        return label;
    }

    private static JTextField targetField() {
        JTextField field = new JTextField();
        field.setEditable(false);
        return field;
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }

    private record Capture(boolean first, long startedAt, Set<String> baseline, String probeId) {
    }
}

package dev.whitedev.jpi.ui.analysis;

import dev.whitedev.jpi.ui.context.AnalysisTarget;
import dev.whitedev.jpi.ui.context.ContextActions;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.investigation.InvestigationAnalyzer;
import dev.whitedev.jpi.investigation.InvestigationReport;
import dev.whitedev.jpi.investigation.InvestigationTarget;
import dev.whitedev.jpi.investigation.assistant.AssistantReport;
import dev.whitedev.jpi.investigation.assistant.InvestigationAssistant;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.debug.DebuggerPanel;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;

public final class InvestigationPanel extends JPanel implements SessionAware {
    private final DeobfuscationWorkspace workspace;

    private final Consumer<InvestigationTarget> openXrefs;

    private final Consumer<InvestigationTarget> openTracer;

    private final Consumer<InvestigationTarget> openCfg;

    private final JTextField query = new JTextField();

    private final JButton investigate = Ui.primaryButton("Investigate");
    private final JButton cancelAssistant = Ui.secondaryButton("Cancel analysis");
    private final JButton exportAssistant = Ui.secondaryButton("Export report...");
    private final JTextArea assistantDetails = output();
    private final JLabel[] assistantSteps = new JLabel[7];
    private final JProgressBar assistantProgress = new JProgressBar(0, 7);
    private final DefaultTableModel probeModel = new DefaultTableModel(
            new Object[]{"Suggested method", "Definition"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable suggestedProbes = new JTable(probeModel);
    private final JTabbedPane workspaceTabs = new JTabbedPane();
    private final JButton prepareSuggestion = Ui.secondaryButton("Prepare selected probe");
    private AssistantReport assistantReport;
    private AtomicBoolean assistantCancelled = new AtomicBoolean();
    private boolean assistantRunning;
    private List<InvestigationTarget> probeTargets = List.of();

    private final JButton refreshRuntime = Ui.secondaryButton("Refresh runtime");

    private final JButton analyzeSelected = Ui.secondaryButton("Analyze selected");

    private final JButton traceBranches = Ui.secondaryButton("Trace branches 30s");

    private final JButton stopBranchTrace = Ui.secondaryButton("Stop branch trace");

    private final JButton xrefs = Ui.secondaryButton("Open Xrefs");

    private final JButton tracer = Ui.secondaryButton("Prepare tracer");

    private final JButton cfg = Ui.secondaryButton("Open CFG");

    private final JButton debuggerButton = Ui.secondaryButton("Debug breakpoint");

    private final JLabel status = new JLabel("Attach to a JVM to begin an investigation");

    private final JLabel classes = metric("0");

    private final JLabel methods = metric("0");

    private final JLabel observed = metric("0");

    private final JLabel constants = metric("0");

    private final DefaultTableModel entryModel = new DefaultTableModel(
            new Object[]{"Confidence", "Method", "Runtime hits", "Matched constant"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {return false;}
    };

    private final JTable entries = new JTable(entryModel);

    private final JTextArea overview = output();

    private final JTextArea callerTree = output();

    private final JTextArea xrefDetails = output();

    private final JTextArea cfgDetails = output();

    private final Timer pollTimer = new Timer(750, event -> pollBranchTrace());

    private InspectorSession session;

    private InvestigationReport report;
    private InvestigationTarget explicitTarget;

    private List<InvestigationTarget> targets = List.of();

    private String constantsRaw = "";

    private String xrefsRaw = "";

    private String cfgRaw = "";

    private String cfgProbeId = "";

    private boolean loading;

    private boolean polling;

    private long generation;

    private DebuggerPanel debugger;

    private Runnable openDebugger;

    public InvestigationPanel(DeobfuscationWorkspace workspace,
                              Consumer<InvestigationTarget> openXrefs,
                              Consumer<InvestigationTarget> openTracer,
                              Consumer<InvestigationTarget> openCfg) {
        super(new BorderLayout(0, 16));
        this.workspace = workspace;
        this.openXrefs = openXrefs;
        this.openTracer = openTracer;
        this.openCfg = openCfg;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);

        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        headerActions.setOpaque(false);
        query.setPreferredSize(new Dimension(280, 34));
        query.putClientProperty("JTextField.placeholderText", "URL, endpoint, error text, token...");
        query.addActionListener(event -> investigate());
        investigate.addActionListener(event -> investigate());
        refreshRuntime.addActionListener(event -> refreshRuntime());
        headerActions.add(query);
        headerActions.add(refreshRuntime);
        cancelAssistant.addActionListener(event -> {
            assistantCancelled.set(true);
            cancelAssistant.setEnabled(false);
            status.setText("Cancellation requested. Waiting for the current bounded request to finish...");
        });
        exportAssistant.addActionListener(event -> exportAssistant());
        headerActions.add(cancelAssistant);
        headerActions.add(exportAssistant);
        headerActions.add(investigate);
        JPanel heading = new JPanel(new BorderLayout(0, 8));
        heading.setOpaque(false);
        heading.add(Ui.sectionHeader("Investigation Assistant",
                "Deterministic constant-to-code analysis, connected call paths, API evidence, and suggested probes",
                null), BorderLayout.NORTH);
        heading.add(headerActions, BorderLayout.CENTER);
        add(heading, BorderLayout.NORTH);

        entries.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        entries.setFillsViewportHeight(true);
        entries.setAutoCreateRowSorter(true);
        entries.getColumnModel().getColumn(0).setPreferredWidth(90);
        entries.getColumnModel().getColumn(1).setPreferredWidth(400);
        entries.getColumnModel().getColumn(2).setPreferredWidth(100);
        entries.getColumnModel().getColumn(3).setPreferredWidth(360);
        entries.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) selectedChanged();
        });

        JPanel entryCard = Ui.card(new BorderLayout(0, 8));
        JLabel entryTitle = new JLabel("Ranked entry points and suggested probes");
        entryTitle.setFont(entryTitle.getFont().deriveFont(Font.BOLD, 13f));
        entryCard.add(entryTitle, BorderLayout.NORTH);
        entryCard.add(Ui.scroll(entries), BorderLayout.CENTER);
        entryCard.add(entryActions(), BorderLayout.SOUTH);

        JTabbedPane details = new JTabbedPane();
        details.addTab("Summary", Ui.scroll(overview));
        details.addTab("Runtime path", Ui.scroll(callerTree));
        details.addTab("Selected Xrefs", Ui.scroll(xrefDetails));
        details.addTab("Interesting branch", Ui.scroll(cfgDetails));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, entryCard, details);
        split.setResizeWeight(.48);
        split.setDividerLocation(310);
        split.setBorder(null);
        workspaceTabs.addTab("Assistant", assistantView());
        workspaceTabs.addTab("Entry points and CFG", split);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(metrics(), BorderLayout.NORTH);
        body.add(workspaceTabs, BorderLayout.CENTER);
        status.setForeground(Ui.MUTED);
        body.add(status, BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);
        pollTimer.start();
        setSession(null);
    }

    public void investigate(String value) {
        if (loading || !cfgProbeId.isEmpty()) {
            status.setText("Finish the current analysis or stop branch tracing before opening another investigation.");
            return;
        }
        explicitTarget = null;
        query.setText(value == null ? "" : value.trim());
        investigate();
    }

    public void investigateMethod(InvestigationTarget target) {
        if (session == null || loading || !cfgProbeId.isEmpty()) {
            status.setText("Finish the current analysis or stop branch tracing before opening another target.");
            return;
        }
        explicitTarget = target;
        workspaceTabs.setSelectedIndex(1);
        clearAssistant();
        query.setText(target.displayName());
        constantsRaw = "";
        xrefsRaw = "";
        render(InvestigationAnalyzer.analyzeTarget(target, ""));
        overview.setText("User-selected method: " + target.displayName()
                + "\n\nThis entry point was selected manually, not ranked from constant matches.");
        analyzeSelected();
    }

    public void setDebuggerIntegration(DebuggerPanel debugger, Runnable openDebugger) {
        this.debugger = debugger;
        this.openDebugger = openDebugger;
        updateButtons();
    }

    @Override
    public void setSession(InspectorSession value) {
        session = value;
        assistantRunning = false;
        assistantCancelled.set(true);
        clearAssistant();
        generation++;
        loading = false;
        polling = false;
        report = null;
        explicitTarget = null;
        targets = List.of();
        constantsRaw = "";
        xrefsRaw = "";
        cfgRaw = "";
        cfgProbeId = "";
        entryModel.setRowCount(0);
        overview.setText("Enter a constant or text marker to start a correlated analysis session.");
        callerTree.setText("Runtime paths appear after recommended methods have been traced.");
        xrefDetails.setText("Select an entry point and click Analyze selected.");
        cfgDetails.setText("Selected method CFG and branch observations appear here.");
        clearMetrics();
        status.setText(value == null ? "Not attached" : "Ready to investigate");
        updateButtons();
    }

    private void investigate() {
        InspectorSession current = session;
        String value = workspace.translateSource(query.getText().trim()).source();
        if (current == null || loading || !cfgProbeId.isEmpty()) return;
        explicitTarget = null;
        if (value.length() < 2) {
            status.setText("Enter at least two characters");
            return;
        }
        loading = true;
        workspaceTabs.setSelectedIndex(0);
        clearAssistant();
        cfgRaw = "";
        report = null;
        targets = List.of();
        constantsRaw = "";
        xrefsRaw = "";
        clearMetrics();
        overview.setText("Automatic investigation in progress...");
        callerTree.setText("Runtime evidence pending.");
        xrefDetails.setText("Select a candidate entry point to load its Xrefs.");
        cfgDetails.setText("Select a candidate entry point to analyze its CFG.");
        AtomicBoolean cancelled = new AtomicBoolean();
        assistantCancelled = cancelled;
        assistantRunning = true;
        long requestGeneration = ++generation;
        status.setForeground(Ui.MUTED);
        status.setText("Correlating constants, method users, and captured runtime calls...");
        entryModel.setRowCount(0);
        updateButtons();
        Async.run(() -> new InvestigationAssistant(current::requestText, InvestigationAssistant.Limits.defaults())
                .run(value, progress -> SwingUtilities.invokeLater(() -> {
                    if (session != current || generation != requestGeneration || cancelled.get()) return;
                    assistantSteps[progress.step() - 1].setText(progress.step() + ". " + progress.detail());
                    assistantProgress.setValue(progress.step());
                    status.setText(progress.detail());
                }), cancelled::get), data -> {
            if (session != current || generation != requestGeneration) return;
            if (cancelled.get()) {
                loading = false;
                assistantRunning = false;
                status.setText("Investigation cancelled; no probes installed");
                updateButtons();
                return;
            }
            constantsRaw = data.constantsRaw();
            assistantRunning = false;
            xrefsRaw = data.usersRaw();
            assistantReport = data;
            render(data.investigation());
            renderAssistant(data);
            loading = false;
            status.setForeground(Ui.SUCCESS);
            status.setText(targets.isEmpty()
                    ? "Constants were found, but no method-level string users are available"
                    : (data.partial() ? "Bounded partial report" : "Investigation complete")
                    + ": " + data.edges().size() + " edges, " + data.probes().size() + " suggested probes.");
            updateButtons();
        }, error -> {
            if (session != current || generation != requestGeneration) return;
            loading = false;
            assistantRunning = false;
            status.setForeground(Ui.WARNING);
            status.setText(error instanceof CancellationException ? "Investigation cancelled; no probes installed"
                    : "Investigation failed");
            updateButtons();
            if (!(error instanceof CancellationException)) Ui.error(this, error);
        });
    }

    private void refreshRuntime() {
        if (assistantReport != null) {
            investigate();
            return;
        }
        InspectorSession current = session;
        if (current == null || report == null || loading) return;
        loading = true;
        long requestGeneration = ++generation;
        status.setText("Refreshing observed calls and caller paths...");
        updateButtons();
        Async.run(() -> current.requestText(Operation.TRACE_EVENTS, ""), raw -> {
            if (session != current || generation != requestGeneration) return;
            render(explicitTarget == null ? InvestigationAnalyzer.analyze(report.query(), constantsRaw, xrefsRaw, raw)
                    : InvestigationAnalyzer.analyzeTarget(explicitTarget, raw));
            loading = false;
            status.setForeground(Ui.SUCCESS);
            status.setText("Runtime evidence refreshed and confidence scores updated");
            updateButtons();
        }, error -> {
            if (session != current || generation != requestGeneration) return;
            loading = false;
            status.setForeground(Ui.WARNING);
            status.setText("Could not refresh runtime evidence");
            updateButtons();
            Ui.error(this, error);
        });
    }

    private void analyzeSelected() {
        InspectorSession current = session;
        InvestigationTarget target = selectedTarget();
        if (current == null || target == null || loading) return;
        loading = true;
        long requestGeneration = ++generation;
        status.setText("Loading reverse callers, outgoing calls, and CFG for " + target.displayName() + "...");
        updateButtons();
        String payload = target.classIdentifier() + "\n" + target.methodName() + "\n" + target.descriptor();
        Async.run(() -> new SelectedData(current.requestText(Operation.METHOD_XREFS, payload),
                current.requestText(Operation.CFG_ANALYZE, payload)), data -> {
            if (session != current || generation != requestGeneration) return;
            xrefDetails.setText(formatXrefs(target, data.xrefs));
            xrefDetails.setCaretPosition(0);
            cfgRaw = data.cfg;
            cfgDetails.setText(formatCfg(data.cfg, ""));
            cfgDetails.setCaretPosition(0);
            loading = false;
            status.setForeground(Ui.SUCCESS);
            status.setText("Selected entry point correlated with Xrefs and CFG");
            updateButtons();
        }, error -> {
            if (session != current || generation != requestGeneration) return;
            loading = false;
            status.setForeground(Ui.WARNING);
            status.setText("Selected entry-point analysis failed");
            updateButtons();
            Ui.error(this, error);
        });
    }

    private void startBranchTrace() {
        InspectorSession current = session;
        InvestigationTarget target = selectedTarget();
        if (current == null || target == null || cfgRaw.isEmpty() || loading) return;
        int choice = JOptionPane.showConfirmDialog(this,
                "Starting CFG counters can stop active method probes for this class. Continue?",
                "Trace selected branches", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        loading = true;
        String payload = target.classIdentifier() + "\n" + target.methodName() + "\n"
                + target.descriptor() + "\n30000";
        status.setText("Installing bounded CFG counters for 30 seconds...");
        updateButtons();
        Async.run(() -> current.requestText(Operation.CFG_TRACE_START, payload), raw -> {
            if (session != current) return;
            String[] values = raw.split("\t", -1);
            if (values.length < 2 || !"P".equals(values[0])) {
                loading = false;
                status.setForeground(Ui.WARNING);
                status.setText("The target returned an invalid CFG probe response");
                updateButtons();
                return;
            }
            cfgProbeId = values[1];
            loading = false;
            status.setForeground(Ui.SUCCESS);
            status.setText("Branch trace active. Perform the investigated action in the target application.");
            updateButtons();
            pollBranchTrace();
        }, error -> {
            loading = false;
            status.setForeground(Ui.WARNING);
            status.setText("Could not start branch trace");
            updateButtons();
            Ui.error(this, error);
        });
    }

    private void stopBranchTrace() {
        InspectorSession current = session;
        String probe = cfgProbeId;
        if (current == null || probe.isEmpty() || loading) return;
        loading = true;
        status.setText("Stopping branch trace and collecting final counters...");
        updateButtons();
        Async.run(() -> current.requestText(Operation.CFG_TRACE_STOP, probe), response -> {
            if (session != current || !probe.equals(cfgProbeId)) return;
            loading = false;
            status.setForeground(Ui.MUTED);
            status.setText(response);
            updateButtons();
            pollBranchTrace();
        }, error -> {
            if (session != current) return;
            loading = false;
            status.setForeground(Ui.WARNING);
            status.setText("Could not stop branch trace");
            updateButtons();
            Ui.error(this, error);
        });
    }

    private void pollBranchTrace() {
        InspectorSession current = session;
        String probe = cfgProbeId;
        if (current == null || probe.isEmpty() || cfgRaw.isEmpty() || polling) return;
        polling = true;
        Async.run(() -> current.requestText(Operation.CFG_SNAPSHOT, probe), raw -> {
            polling = false;
            if (session != current || !probe.equals(cfgProbeId)) return;
            cfgDetails.setText(formatCfg(cfgRaw, raw));
            cfgDetails.setCaretPosition(0);
            String[] header = firstSnapshotHeader(raw);
            if (header != null && !Boolean.parseBoolean(header[2])) {
                cfgProbeId = "";
                status.setForeground(Ui.SUCCESS);
                status.setText("Branch trace finished. CFG target-block hits are now correlated.");
                updateButtons();
            }
        }, error -> {
            polling = false;
            if (session == current) status.setText("Branch trace polling failed: " + error.getMessage());
        });
    }

    private void render(InvestigationReport value) {
        report = value;
        targets = value.entryPoints();
        entryModel.setRowCount(0);
        for (InvestigationTarget target : targets) {
            entryModel.addRow(new Object[]{target.confidence() + "%", display(target), target.runtimeHits(),
                    target.matchedConstant()});
        }
        classes.setText(String.valueOf(value.matchingClasses()));
        methods.setText(String.valueOf(targets.size()));
        constants.setText(String.valueOf(value.constants().size()));
        observed.setText(String.valueOf(targets.stream().filter(target -> target.runtimeHits() > 0).count()));
        overview.setText(summary(value));
        overview.setCaretPosition(0);
        callerTree.setText(value.runtimePaths().isEmpty()
                ? "No captured path matches this investigation yet.\n\nSelect a suggested entry point, click Prepare tracer, perform the action, then return and click Refresh runtime."
                : String.join("\n\n", value.runtimePaths()));
        callerTree.setCaretPosition(0);
        if (!targets.isEmpty()) entries.setRowSelectionInterval(0, 0);
    }

    private void selectedChanged() {
        InvestigationTarget target = selectedTarget();
        if (target == null) return;
        cfgRaw = "";
        cfgProbeId = "";
        xrefDetails.setText("Selected: " + target.displayName() + "\n\nClick Analyze selected to load callers and outgoing references.");
        cfgDetails.setText("Selected: " + target.displayName() + "\n\nClick Analyze selected to build its CFG.");
        updateButtons();
    }

    public void setContextActions(ContextActions actions) {
        actions.install(suggestedProbes, () -> {
            InvestigationTarget target = selectedSuggestion();
            return target == null ? null : AnalysisTarget.method(
                    target.classIdentifier(), target.className(), target.methodName(), target.descriptor());
        });
        actions.install(entries, () -> {
            InvestigationTarget target = selectedTarget();
            return target == null ? null : AnalysisTarget.method(
                    target.classIdentifier(), target.className(), target.methodName(), target.descriptor());
        });
    }

    private InvestigationTarget selectedTarget() {
        int row = entries.getSelectedRow();
        if (row < 0) return null;
        int modelRow = entries.convertRowIndexToModel(row);
        return modelRow >= 0 && modelRow < targets.size() ? targets.get(modelRow) : null;
    }

    private String summary(InvestigationReport value) {
        StringBuilder text = new StringBuilder("Investigation: \"").append(value.query()).append("\"\n\n");
        text.append("Entry points\n");
        if (value.entryPoints().isEmpty()) text.append("  No method-level users found\n");
        for (int index = 0; index < Math.min(12, value.entryPoints().size()); index++) {
            InvestigationTarget target = value.entryPoints().get(index);
            text.append("  ").append(display(target)).append("  confidence ")
                    .append(target.confidence()).append('%');
            if (target.runtimeHits() > 0) text.append("  observed ").append(target.runtimeHits()).append('x');
            text.append('\n');
        }
        text.append("\nInteresting constants\n");
        if (value.constants().isEmpty()) text.append("  No matching constants available\n");
        for (int index = 0; index < Math.min(20, value.constants().size()); index++) {
            text.append("  \"").append(value.constants().get(index)).append("\"\n");
        }
        text.append("\nConfidence is a heuristic based on the match, semantic markers, method names, and runtime evidence.");
        return text.toString();
    }

    private String formatXrefs(InvestigationTarget selected, String raw) {
        StringBuilder calledBy = new StringBuilder();
        StringBuilder calls = new StringBuilder();
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\n")) {
            String[] values = line.split("\t", -1);
            if (values.length != 9 || !"R".equals(values[0])) continue;
            try {
                String relation = values[2];
                String owner = decoded(values[5]);
                String member = decoded(values[6]);
                String descriptor = decoded(values[7]);
                String detail = decoded(values[8]);
                String rendered = "  " + values[1] + "  " + owner + (member.isEmpty() ? "" : "." + member)
                        + descriptor + (detail.isEmpty() ? "" : "  [" + detail + "]")
                        + ("1".equals(values[3]) ? "" : "  x" + values[3]) + "\n";
                if ("CALLED_BY".equals(relation)) calledBy.append(rendered);
                else if ("CALLS".equals(relation)) calls.append(rendered);
                else data.append(rendered);
            } catch (RuntimeException ignored) {
            }
        }
        return selected.displayName() + "\n\nCalled by\n" + empty(calledBy)
                + "\nCalls\n" + empty(calls) + "\nFields, types, and constants\n" + empty(data);
    }

    private String formatCfg(String graphRaw, String snapshotRaw) {
        CfgSummary graph = CfgSummary.parse(graphRaw, snapshotRaw);
        StringBuilder text = new StringBuilder();
        text.append(graph.owner).append('.').append(graph.method).append(graph.descriptor).append("\n\n")
                .append("Basic blocks: ").append(graph.blocks.size()).append('\n')
                .append("Edges: ").append(graph.edges.size()).append('\n')
                .append("Complexity: ").append(graph.complexity).append('\n')
                .append("Static dead blocks: ").append(graph.dead).append('\n');
        if (snapshotRaw.isEmpty()) {
            text.append("\nRuntime branch counts are not captured yet. Click Trace branches 30s and perform the action.\n");
        }
        List<CfgSummary.Block> branches = graph.branches();
        if (branches.isEmpty()) return text.append("\nNo conditional branch block was found.").toString();
        text.append("\nInteresting branches\n");
        for (int index = 0; index < Math.min(10, branches.size()); index++) {
            CfgSummary.Block block = branches.get(index);
            text.append("\n").append(block.id).append("  lines ").append(block.lines())
                    .append("  executions ").append(block.hits).append('\n');
            for (CfgSummary.Edge edge : graph.outgoing(block.id)) {
                CfgSummary.Block target = graph.byId.get(edge.to);
                text.append("  ").append(edge.label.isEmpty() ? edge.kind.toLowerCase() : edge.label)
                        .append(" -> ").append(edge.to);
                if (target != null) text.append("  target hits ").append(target.hits);
                text.append('\n');
            }
            if (!block.instructions.isEmpty()) text.append("  ").append(block.instructions).append('\n');
        }
        return text.toString();
    }

    private JPanel entryActions() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        panel.setOpaque(false);
        analyzeSelected.addActionListener(event -> analyzeSelected());
        traceBranches.addActionListener(event -> startBranchTrace());
        stopBranchTrace.addActionListener(event -> stopBranchTrace());
        xrefs.addActionListener(event -> use(openXrefs));
        tracer.addActionListener(event -> use(openTracer));
        cfg.addActionListener(event -> use(openCfg));
        debuggerButton.addActionListener(event -> debugSelected());
        panel.add(analyzeSelected);
        panel.add(traceBranches);
        panel.add(stopBranchTrace);
        panel.add(xrefs);
        panel.add(tracer);
        panel.add(cfg);
        panel.add(debuggerButton);
        return panel;
    }

    private void debugSelected() {
        InvestigationTarget target = selectedTarget();
        if (target == null || debugger == null) return;
        debugger.prepareMethodBreakpoint(target.className(), target.methodName(), target.descriptor());
        if (openDebugger != null) openDebugger.run();
    }

    private void use(Consumer<InvestigationTarget> action) {
        InvestigationTarget target = selectedTarget();
        if (target != null) action.accept(target);
    }

    private JPanel metrics() {
        JPanel panel = new JPanel(new GridLayout(1, 4, 8, 0));
        panel.setOpaque(false);
        panel.add(metricCard("Matching classes", classes));
        panel.add(metricCard("Candidate methods", methods));
        panel.add(metricCard("Interesting constants", constants));
        panel.add(metricCard("Observed candidates", observed));
        return panel;
    }

    private void updateButtons() {
        boolean attached = session != null;
        boolean selected = selectedTarget() != null;
        boolean branchActive = !cfgProbeId.isEmpty();
        entries.setEnabled(attached && !loading && !branchActive);
        investigate.setEnabled(attached && !loading && !branchActive);
        query.setEnabled(attached && !loading && !branchActive);
        refreshRuntime.setEnabled(attached && report != null && !loading && !branchActive);
        analyzeSelected.setEnabled(attached && selected && !loading);
        traceBranches.setEnabled(attached && selected && !cfgRaw.isEmpty() && !branchActive && !loading);
        stopBranchTrace.setEnabled(attached && branchActive && !loading);
        xrefs.setEnabled(attached && selected);
        tracer.setEnabled(attached && selected);
        cfg.setEnabled(attached && selected);
        debuggerButton.setEnabled(debugger != null && selected);
        cancelAssistant.setEnabled(assistantRunning && !assistantCancelled.get());
        exportAssistant.setEnabled(assistantReport != null && !loading);
        prepareSuggestion.setEnabled(attached && !loading && selectedSuggestion() != null);
    }

    private void clearMetrics() {
        classes.setText("0");
        methods.setText("0");
        constants.setText("0");
        observed.setText("0");
    }

    private String display(InvestigationTarget target) {
        String owner = workspace.classAlias(target.className());
        String method = workspace.methodAlias(target.className(), target.methodName(), target.descriptor());
        return owner + "." + method + target.descriptor();
    }

    private static String decoded(String value) {
        return value.isEmpty() ? "" : new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String empty(StringBuilder value) {
        return value.length() == 0 ? "  None\n" : value.toString();
    }

    private static String[] firstSnapshotHeader(String raw) {
        for (String line : raw.split("\n")) {
            String[] values = line.split("\t", -1);
            if (values.length == 6 && "S".equals(values[0])) return values;
        }
        return null;
    }

    private static JTextArea output() {
        JTextArea area = Ui.outputArea();
        area.setLineWrap(false);
        return area;
    }

    private static JLabel metric(String value) {
        JLabel label = new JLabel(value);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 18f));
        return label;
    }

    private static Component metricCard(String title, JLabel value) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Ui.SURFACE_LIGHT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Ui.BORDER), new EmptyBorder(8, 10, 8, 10)));
        JLabel name = new JLabel(title);
        name.setForeground(Ui.MUTED);
        panel.add(name);
        panel.add(value);
        return panel;
    }

    private JPanel assistantView() {
        JPanel steps = new JPanel(new GridLayout(8, 1, 0, 3));
        steps.setOpaque(false);
        for (int i = 0; i < assistantSteps.length; i++) {
            assistantSteps[i] = new JLabel((i + 1) + ". Pending");
            steps.add(assistantSteps[i]);
        }
        assistantProgress.setStringPainted(true);
        steps.add(assistantProgress);
        suggestedProbes.setAutoCreateRowSorter(true);
        suggestedProbes.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        suggestedProbes.getSelectionModel().addListSelectionListener(event -> updateButtons());
        prepareSuggestion.addActionListener(event -> {
            InvestigationTarget selected = selectedSuggestion();
            if (selected != null) openTracer.accept(selected);
        });
        JPanel probes = new JPanel(new BorderLayout(0, 6));
        probes.setOpaque(false);
        probes.add(new JLabel("Suggested trace points (review capture limits before starting)"), BorderLayout.NORTH);
        probes.add(Ui.scroll(suggestedProbes), BorderLayout.CENTER);
        probes.add(prepareSuggestion, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(assistantDetails), probes);
        split.setResizeWeight(.72);
        split.setBorder(null);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        panel.setOpaque(false);
        panel.add(steps, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private void clearAssistant() {
        assistantReport = null;
        probeTargets = List.of();
        probeModel.setRowCount(0);
        assistantProgress.setValue(0);
        for (int i = 0; i < assistantSteps.length; i++) if (assistantSteps[i] != null)
            assistantSteps[i].setText((i + 1) + ". Pending");
        assistantDetails.setText("Enter a marker and click Investigate to run the bounded deterministic assistant.\n"
                + "No probes are installed automatically. Static call graph paths do not prove execution order.");
    }

    private void renderAssistant(AssistantReport data) {
        assistantDetails.setText(data.text());
        assistantDetails.setCaretPosition(0);
        probeTargets = data.probes();
        probeModel.setRowCount(0);
        for (InvestigationTarget target : probeTargets) probeModel.addRow(new Object[]{display(target), target.classIdentifier()});
        if (!probeTargets.isEmpty()) suggestedProbes.setRowSelectionInterval(0, 0);
    }

    private InvestigationTarget selectedSuggestion() {
        int row = suggestedProbes.getSelectedRow();
        if (row < 0) return null;
        int model = suggestedProbes.convertRowIndexToModel(row);
        return model < probeTargets.size() ? probeTargets.get(model) : null;
    }

    private void exportAssistant() {
        AssistantReport current = assistantReport;
        if (current == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("jpi-investigation.md"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        var path = chooser.getSelectedFile().toPath();
        if (Files.exists(path) && JOptionPane.showConfirmDialog(this, "Replace the existing report?",
                "Export investigation", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        Async.run(() -> { Files.writeString(path, current.markdown(), StandardCharsets.UTF_8); return path; },
                saved -> status.setText("Report exported to " + saved), error -> Ui.error(this, error));
    }

    private record SelectedData(String xrefs, String cfg) {
    }

    private static final class CfgSummary {
        final String owner;

        final String method;

        final String descriptor;

        final int complexity;

        final int dead;

        final List<Block> blocks;

        final List<Edge> edges;

        final Map<String, Block> byId;

        private CfgSummary(String owner, String method, String descriptor, int complexity, int dead,
                           List<Block> blocks, List<Edge> edges) {
            this.owner = owner;
            this.method = method;
            this.descriptor = descriptor;
            this.complexity = complexity;
            this.dead = dead;
            this.blocks = blocks;
            this.edges = edges;
            this.byId = new LinkedHashMap<>();
            for (Block block : blocks) byId.put(block.id, block);
        }

        static CfgSummary parse(String graphRaw, String snapshotRaw) {
            String owner = "";
            String method = "";
            String descriptor = "";
            int complexity = 0;
            int dead = 0;
            List<Block> blocks = new ArrayList<>();
            List<Edge> edges = new ArrayList<>();
            Map<Integer, Long> hits = new LinkedHashMap<>();
            for (String line : snapshotRaw.split("\n")) {
                String[] values = line.split("\t", -1);
                if (values.length == 3 && "H".equals(values[0])) {
                    try {hits.put(Integer.parseInt(values[1]), Long.parseLong(values[2]));} catch (
                            NumberFormatException ignored) {}
                }
            }
            for (String line : graphRaw.split("\n")) {
                String[] values = line.split("\t", -1);
                try {
                    if (values.length == 8 && "G".equals(values[0])) {
                        owner = decoded(values[1]);
                        method = decoded(values[2]);
                        descriptor = decoded(values[3]);
                        complexity = Integer.parseInt(values[6]);
                        dead = Integer.parseInt(values[7]);
                    } else if (values.length == 10 && "B".equals(values[0])) {
                        int index = blocks.size();
                        blocks.add(new Block(values[1], Integer.parseInt(values[4]), Integer.parseInt(values[5]),
                                decoded(values[9]), hits.getOrDefault(index, 0L)));
                    } else if (values.length == 5 && "E".equals(values[0])) {
                        edges.add(new Edge(values[1], values[2], values[3], decoded(values[4])));
                    }
                } catch (RuntimeException ignored) {
                }
            }
            return new CfgSummary(owner, method, descriptor, complexity, dead, blocks, edges);
        }

        List<Block> branches() {
            List<Block> result = new ArrayList<>();
            for (Block block : blocks) if (outgoing(block.id).size() > 1) result.add(block);
            result.sort((left, right) -> Long.compare(right.hits, left.hits));
            return result;
        }

        List<Edge> outgoing(String block) {
            List<Edge> result = new ArrayList<>();
            for (Edge edge : edges) if (block.equals(edge.from)) result.add(edge);
            return result;
        }

        private record Block(String id, int startLine, int endLine, String instructions, long hits) {
            String lines() {
                if (startLine < 0) return "unknown";
                return startLine == endLine ? String.valueOf(startLine) : startLine + " to " + endLine;
            }
        }

        private record Edge(String from, String to, String kind, String label) {
        }
    }
}

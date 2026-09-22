package dev.whitedev.jpi.ui.provenance;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.provenance.ValueTraceAnalyzer;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public final class ValueTracePanel extends JPanel implements SessionAware {
    private final DeobfuscationWorkspace workspace;
    private final RuntimeTimelineStore timeline;
    private final JTextField value = new JTextField();
    private final JButton analyze = Ui.primaryButton("Trace value");
    private final JButton clear = Ui.secondaryButton("Clear");
    private final JLabel status = new JLabel("Capture field writes or live trace events, then investigate a value");
    private final JLabel evidence = new JLabel("No analysis");
    private final JLabel fieldCount = new JLabel("--");
    private final JLabel traceCount = new JLabel("--");
    private final JLabel staticCount = new JLabel("--");
    private final DefaultTableModel linksModel = model("From", "To", "Evidence", "Time", "Thread", "Call");
    private final DefaultTableModel refsModel = model("Layer", "Relation", "Class", "Member", "Descriptor", "Hits");
    private final JTable links = new JTable(linksModel);
    private final JTable refs = new JTable(refsModel);
    private final JTextArea details = Ui.outputArea();
    private InspectorSession session;
    private boolean loading;

    public ValueTracePanel(DeobfuscationWorkspace workspace, RuntimeTimelineStore timeline) {
        super(new BorderLayout(0, 16));
        this.workspace = workspace;
        this.timeline = timeline;
        setOpaque(false);
        setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        value.setPreferredSize(new java.awt.Dimension(280, 32));
        value.putClientProperty("JTextField.placeholderText", "abc123, \"token\", class@identity...");
        value.addActionListener(event -> analyze());
        analyze.addActionListener(event -> analyze());
        clear.addActionListener(event -> reset());
        actions.add(new JLabel("Value"));
        actions.add(value);
        actions.add(clear);
        actions.add(analyze);
        add(Ui.sectionHeader("Trace this value", "Correlate field provenance, runtime call paths, and static value users", actions), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(summary(), BorderLayout.NORTH);
        body.add(content(), BorderLayout.CENTER);
        body.add(status, BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);
        setSession(null);
    }

    public void selectValue(String selected) {
        if (selected == null || selected.isBlank()) return;
        value.setText(selected);
        analyze();
    }

    @Override public void setSession(InspectorSession value) {
        session = value;
        loading = false;
        reset();
        analyze.setEnabled(value != null);
        clear.setEnabled(value != null);
        if (value == null) {
            status.setText("Attach to a JVM to trace value provenance");
        }
    }

    private JPanel summary() {
        JPanel panel = new JPanel(new GridLayout(1, 4, 8, 0));
        panel.setOpaque(false);
        panel.add(metric("Runtime links", evidence));
        panel.add(metric("Field writes", fieldCount));
        panel.add(metric("Trace events", traceCount));
        panel.add(metric("Static refs", staticCount));
        return panel;
    }

    private JPanel metric(String title, JLabel value) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(Ui.SURFACE_LIGHT);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.BORDER),
                new EmptyBorder(8, 10, 8, 10)));
        JLabel label = new JLabel(title);
        label.setForeground(Ui.MUTED);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 16f));
        panel.add(label, BorderLayout.NORTH);
        panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    private JTabbedPane content() {
        links.setAutoCreateRowSorter(true);
        links.setFillsViewportHeight(true);
        refs.setAutoCreateRowSorter(true);
        refs.setFillsViewportHeight(true);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Provenance chain", Ui.scroll(links));
        tabs.addTab("Static value users", Ui.scroll(refs));
        tabs.addTab("Explanation", Ui.scroll(details));
        details.setText("Select a value or use Trace value on a captured field write.\n\nThe analysis uses bounded field and tracer histories, call IDs, thread and timestamp correlation, and static Xrefs.");
        return tabs;
    }

    private void analyze() {
        InspectorSession current = session;
        String query = value.getText().trim();
        if (current == null || loading || query.isEmpty()) return;
        loading = true;
        updateControls();
        status.setText("Reading bounded field and trace histories...");
        Async.run(() -> {
            String fields = current.requestText(Operation.FIELD_TRACE_HISTORY, "");
            String traces = current.requestText(Operation.TRACE_HISTORY, "");
            String xrefs = current.requestText(Operation.XREF_SEARCH, query.replace("\"", ""));
            return ValueTraceAnalyzer.analyze(query, fields, traces, xrefs);
        }, report -> {
            if (session != current) return;
            loading = false;
            render(report);
            updateControls();
        }, error -> {
            if (session != current) return;
            loading = false;
            status.setForeground(Ui.WARNING);
            status.setText("Value trace failed: " + message(error));
            updateControls();
        });
    }

    private void render(ValueTraceAnalyzer.Report report) {
        linksModel.setRowCount(0);
        refsModel.setRowCount(0);
        Map<String, ValueTraceAnalyzer.Node> nodes = new HashMap<>();
        for (ValueTraceAnalyzer.Node node : report.nodes()) nodes.put(node.id(), node);
        StringBuilder chain = new StringBuilder();
        chain.append("Value: ").append(report.query()).append("\n\n");
        if (report.links().isEmpty()) chain.append("No runtime provenance was captured for this value.\n");
        for (ValueTraceAnalyzer.Link link : report.links()) {
            ValueTraceAnalyzer.Node from = nodes.get(link.from());
            ValueTraceAnalyzer.Node to = nodes.get(link.to());
            String fromLabel = from == null ? link.from() : from.label();
            String toLabel = to == null ? link.to() : to.label();
            linksModel.addRow(new Object[]{fromLabel, toLabel, link.kind(), time(link.timestamp()),
                    link.thread(), link.callId() == 0L ? "" : "#" + link.callId()});
            chain.append(fromLabel).append("\n  | ").append(link.kind()).append("\n  v\n")
                    .append(toLabel).append("  ").append(link.detail()).append("\n\n");
            if (link.callId() > 0L) chain.append("Call #").append(link.callId()).append(" on ")
                    .append(link.thread()).append("\n\n");
        }
        chain.append("Static references:\n");
        for (ValueTraceAnalyzer.StaticReference reference : report.references()) {
            refsModel.addRow(new Object[]{reference.layer(), reference.relation(), workspace.classAlias(reference.owner()),
                    reference.member(), reference.descriptor(), reference.count()});
            chain.append("  ").append(reference.owner()).append('.').append(reference.member()).append('\n');
        }
        details.setText(chain.toString());
        details.setCaretPosition(0);
        evidence.setText(Integer.toString(report.links().size()));
        fieldCount.setText(Integer.toString(report.fieldMatches()));
        traceCount.setText(Integer.toString(report.traceEvents()));
        staticCount.setText(Integer.toString(report.references().size()));
        status.setForeground(Ui.SUCCESS);
        status.setText("Value analysis complete: " + report.links().size() + " runtime links and "
                + report.references().size() + " static references");
        timeline.publish(new TimelineEvent("value-trace:" + System.currentTimeMillis(), System.currentTimeMillis(),
                TimelineSource.VALUE_TRACE, "", "", "", "Value trace: " + report.query(),
                report.links().size() + " runtime links, " + report.references().size() + " static references"));
    }

    private void reset() {
        linksModel.setRowCount(0);
        refsModel.setRowCount(0);
        evidence.setText("No analysis");
        fieldCount.setText("--");
        traceCount.setText("--");
        staticCount.setText("--");
        details.setText("Select a value or use Trace value on a captured field write.\n\nThe analysis uses bounded field and tracer histories, call IDs, thread and timestamp correlation, and static Xrefs.");
    }

    private void updateControls() {
        boolean attached = session != null;
        analyze.setEnabled(attached && !loading);
        clear.setEnabled(attached && !loading);
    }

    private static String time(long timestamp) {
        return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(timestamp));
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }
}

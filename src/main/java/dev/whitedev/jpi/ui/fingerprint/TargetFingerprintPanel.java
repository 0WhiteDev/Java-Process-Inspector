package dev.whitedev.jpi.ui.fingerprint;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.debug.BreakpointSpec;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.deobfuscation.MappingKind;
import dev.whitedev.jpi.deobfuscation.io.MappingFormats;
import dev.whitedev.jpi.fingerprint.FingerprintComparison;
import dev.whitedev.jpi.fingerprint.TargetFingerprint;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.layout.WrappingFlowLayout;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class TargetFingerprintPanel extends JPanel implements SessionAware {
    private final DeobfuscationWorkspace workspace;
    private final Supplier<List<BreakpointSpec>> breakpointSource;
    private final Consumer<String> warning;
    private final JTextArea selection = Ui.outputArea();
    private final JTextArea summary = Ui.outputArea();
    private final DefaultTableModel hashes = model("Class", "Loader hierarchy", "SHA-256", "Status");
    private final DefaultTableModel issues = model("Kind", "Location", "Status", "Reason");
    private final JButton capture = Ui.primaryButton("Capture / Compare");
    private final JButton bind = Ui.secondaryButton("Use as workspace baseline");
    private final JButton export = Ui.secondaryButton("Export fingerprint...");
    private final JLabel status = new JLabel("Attach to capture a target fingerprint.");
    private InspectorSession session;
    private String currentWire = "";
    private TargetFingerprint current;
    private boolean busy;
    private long generation;
    private String observedBaseline = "";
    private record Capture(String wire, TargetFingerprint fingerprint) {}

    public TargetFingerprintPanel(DeobfuscationWorkspace workspace, Supplier<List<BreakpointSpec>> breakpointSource,
                                  Consumer<String> warning) {
        super(new BorderLayout(0, 12));
        this.workspace = workspace;
        this.breakpointSource = breakpointSource;
        this.warning = warning;
        setOpaque(false);
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(Ui.sectionHeader("Target fingerprint", "Compare target builds, selected bytecode, mappings, and saved breakpoint locations", new JPanel()), BorderLayout.NORTH);
        JPanel actions = new JPanel(new WrappingFlowLayout(FlowLayout.LEFT, 8, 3));
        JButton load = Ui.secondaryButton("Import baseline...");
        JButton forget = Ui.secondaryButton("Forget baseline");
        JButton copy = Ui.secondaryButton("Copy report");
        for (JButton button : List.of(capture, bind, load, export, forget, copy)) actions.add(button);
        capture.addActionListener(event -> capture());
        bind.addActionListener(event -> bind());
        load.addActionListener(event -> load());
        export.addActionListener(event -> export());
        forget.addActionListener(event -> {
            if (JOptionPane.showConfirmDialog(this, "Forget the saved target baseline? Mappings remain unchanged.",
                    "Target fingerprint", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) workspace.setTargetFingerprint("");
        });
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(summary.getText()), null));
        selection.setEditable(true);
        selection.setRows(3);
        selection.setToolTipText("Optional exact binary class names, one per line or comma-separated. Maximum 128 hashed definitions.");
        JPanel inputs = new JPanel(new BorderLayout(0, 5));
        inputs.add(new JLabel("Additional classes to hash. Baseline, breakpoint, and workspace owners are included up to the 128-definition limit."), BorderLayout.NORTH);
        inputs.add(Ui.scroll(selection), BorderLayout.CENTER);
        inputs.add(actions, BorderLayout.SOUTH);
        top.add(inputs, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        summary.setEditable(false);
        tabs.addTab("Target / Comparison", Ui.scroll(summary));
        tabs.addTab("Selected bytecode hashes", table(hashes));
        tabs.addTab("Mappings / Breakpoints to review", table(issues));
        add(tabs, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        observedBaseline = workspace.targetFingerprint();
        workspace.addListener(() -> SwingUtilities.invokeLater(() -> {
            boolean changed = !observedBaseline.equals(workspace.targetFingerprint());
            observedBaseline = workspace.targetFingerprint();
            render();
            if (changed && session != null && !observedBaseline.isEmpty()) capture();
        }));
        controls();
        render();
    }

    @Override public void setSession(InspectorSession value) {
        session = value;
        generation++;
        busy = false;
        current = null;
        currentWire = "";
        hashes.setRowCount(0);
        issues.setRowCount(0);
        controls();
        render();
        if (value != null && !workspace.targetFingerprint().isEmpty()) capture();
    }

    private void capture() {
        if (session == null || busy) return;
        InspectorSession expected = session;
        long epoch = ++generation;
        Set<String> names = new LinkedHashSet<>();
        if (!workspace.targetFingerprint().isEmpty()) {
            var baseline = TargetFingerprint.parse(workspace.targetFingerprint());
            for (var definition : baseline.definitions()) if (definition.inspected()) names.add(definition.name());
            for (var breakpoint : baseline.breakpoints()) names.add(breakpoint.className());
        }
        for (String name : selection.getText().split("[\\n,]")) if (!name.trim().isEmpty()) names.add(name.trim());
        List<BreakpointSpec> breakpoints = List.copyOf(breakpointSource.get());
        for (var breakpoint : breakpoints) names.add(breakpoint.className());
        if (names.size() > 128) {
            Ui.error(this, new IllegalArgumentException("Select at most 128 baseline, additional, and breakpoint class names."));
            return;
        }
        for (var mapping : workspace.entries()) {
            if (names.size() >= 128) break;
            if (mapping.enabled() && (!mapping.mappedName().isEmpty() || !mapping.comment().isEmpty() || !mapping.tags().isEmpty()) && mapping.kind() != MappingKind.PACKAGE) {
                names.add(mapping.kind() == MappingKind.CLASS ? mapping.originalName() : mapping.owner());
            }
        }
        busy = true;
        status.setText("Capturing the class catalog and selected bytecode. The target continues running.");
        controls();
        String payload = String.join("\n", names);
        Async.run(() -> {
            String wire = expected.requestText(Operation.TARGET_FINGERPRINT, payload);
            String withBreakpoints = TargetFingerprint.withBreakpoints(wire, breakpoints);
            return new Capture(withBreakpoints, TargetFingerprint.parse(withBreakpoints));
        }, result -> {
            if (session != expected || epoch != generation) return;
            currentWire = result.wire();
            current = result.fingerprint();
            busy = false;
            controls();
            render();
        }, error -> {
            if (session != expected || epoch != generation) return;
            busy = false;
            controls();
            status.setText("Capture failed: " + error.getMessage());
            Ui.error(this, error);
        });
    }

    private void bind() {
        if (current == null || busy) return;
        if (!workspace.targetFingerprint().isEmpty() && JOptionPane.showConfirmDialog(this,
                "Replace the workspace baseline with this capture?", "Target fingerprint", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        workspace.setTargetFingerprint(currentWire);
        observedBaseline = currentWire;
        render();
    }

    private void load() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            String wire = MappingFormats.readFingerprint(chooser.getSelectedFile().toPath());
            if (wire.isEmpty()) throw new IllegalArgumentException("File does not contain a target fingerprint");
            workspace.setTargetFingerprint(wire);
            render();
            if (session != null) capture();
        } catch (Exception error) { Ui.error(this, error); }
    }

    private void export() {
        if (current == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("jpi-target-fingerprint.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        if (chooser.getSelectedFile().exists() && JOptionPane.showConfirmDialog(this, "Overwrite this file?",
                "Export fingerprint", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            MappingFormats.write(chooser.getSelectedFile().toPath(), MappingFormats.Format.JSON, List.of(), currentWire);
        } catch (Exception error) { Ui.error(this, error); }
    }

    private void render() {
        issues.setRowCount(0);
        hashes.setRowCount(0);
        String baselineWire = workspace.targetFingerprint();
        TargetFingerprint baseline = baselineWire.isEmpty() ? null : TargetFingerprint.parse(baselineWire);
        TargetFingerprint displayed = current == null ? baseline : current;
        if (displayed == null) {
            summary.setText("No fingerprint captured. Select important application classes and click Capture / Compare.\n"
                    + "Use as workspace baseline saves the capture with the deobfuscation workspace.\n"
                    + "Unselected classes contribute loader-aware catalog identities, not verified bytecode hashes.");
            warning.accept("");
            return;
        }
        StringBuilder text = new StringBuilder("Main class: ").append(displayed.mainClass()).append("\nJava: ")
                .append(displayed.javaVersion()).append("\nLoaded classes: ").append(displayed.loadedCount())
                .append("\nCatalog definitions: ").append(displayed.definitions().size())
                .append("\nCaptured: ").append(java.time.Instant.ofEpochMilli(displayed.capturedAt()))
                .append("\nFingerprint: ").append(displayed.id()).append('\n');
        for (var definition : displayed.definitions()) {
            if (!definition.status().equals("not selected")) hashes.addRow(new Object[]{definition.name(), definition.loader(), definition.hash(), definition.status()});
        }
        if (current != null && baseline != null) {
            Set<BreakpointSpec> breakpoints = new LinkedHashSet<>(baseline.breakpoints());
            breakpoints.addAll(current.breakpoints());
            var comparison = FingerprintComparison.compare(baseline, current, workspace.entries(), List.copyOf(breakpoints));
            long review = comparison.issues().stream().filter(issue -> issue.category().equals("Mapping") && issue.status().equals("Review")).count();
            long unresolved = comparison.issues().stream().filter(issue -> issue.category().equals("Breakpoint") && issue.status().equals("Unresolved")).count();
            long unverified = comparison.issues().stream().filter(issue -> issue.status().equals("Unverified")).count();
            text.append("\nWorkspace baseline: ").append(baseline.id()).append("\n")
                    .append(String.format(Locale.ROOT, "Catalog definitions matched: %.1f%% (%d/%d)%n", comparison.classMatchPercent(), comparison.matchingDefinitions(), comparison.baselineDefinitions()))
                    .append("Selected bytecode hashes matched: ").append(comparison.matchingHashes()).append('/').append(comparison.checkedHashes())
                    .append("\nMappings requiring review: ").append(review).append("\nUnresolved breakpoint locations: ").append(unresolved)
                    .append("\nUnverified checks: ").append(unverified).append('\n');
            for (var issue : comparison.issues().stream().limit(5000).toList()) {
                issues.addRow(new Object[]{issue.category(), issue.location(), issue.status(), issue.reason()});
            }
            if (comparison.issues().size() > 5000) text.append("Review table is limited to the first 5,000 issues; summary counts include all checks.\n");
            String message = comparison.issues().isEmpty() ? "No differences in checked definitions." : "Workspace target differs or needs verification. " + review + " mappings to review; " + unresolved + " unresolved breakpoint locations; " + unverified + " unverified checks.";
            status.setText(message);
            warning.accept(comparison.issues().isEmpty() ? "" : message + " Open Target fingerprint for details.");
        } else {
            text.append(current == null ? "\nSaved baseline only. Attach to compare against the current target.\n" : "\nNo workspace baseline. Use as workspace baseline to enable future comparisons.\n");
            status.setText(current == null ? "Saved workspace baseline loaded." : "Capture complete.");
            warning.accept("");
        }
        text.append("\nA loaded-class catalog is not a build signature. Dynamic loading and instrumentation can change results.\n")
                .append("Missing classes may simply not be loaded yet. Identical loader hierarchies can be ambiguous.\n")
                .append("Line checks require debug symbols; bytecode breakpoint indices require debugger validation.\n")
                .append("Selected hashes describe captured bytes, which can include runtime patches or probes.\n");
        summary.setText(text.toString());
        summary.setCaretPosition(0);
    }

    private void controls() {
        capture.setEnabled(session != null && !busy);
        bind.setEnabled(current != null && !busy);
        export.setEnabled(current != null && !busy);
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    private static JScrollPane table(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        return Ui.scroll(table);
    }
}

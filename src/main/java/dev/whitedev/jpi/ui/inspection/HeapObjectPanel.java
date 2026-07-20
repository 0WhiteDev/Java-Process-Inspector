package dev.whitedev.jpi.ui.inspection;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class HeapObjectPanel extends JPanel implements SessionAware {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DeobfuscationWorkspace workspace;
    private final JTextField rootFilter = new JTextField();
    private final JTextField classFilter = new JTextField();
    private final JTextField fieldFilter = new JTextField();
    private final JTextField valueFilter = new JTextField();
    private final JSpinner maxDepth = spinner(4, 0, 16, 1);
    private final JSpinner maxObjects = spinner(10000, 1, 100000, 1000);
    private final JSpinner maxResults = spinner(250, 1, 5000, 50);
    private final JSpinner timeout = spinner(5, 1, 30, 1);
    private final JButton scan = Ui.primaryButton("Scan reachable objects");
    private final JButton inspect = Ui.secondaryButton("Inspect selected");
    private final JButton dump = Ui.secondaryButton("Export full HPROF...");
    private final JLabel status = new JLabel("Attach to a JVM to inspect objects");
    private final DefaultTableModel instanceModel = model(
            "ID", "Class", "Classloader", "Shallow size", "Depth", "Known static root", "Path", "Preview");
    private final DefaultTableModel countModel = model("Class", "Classloader", "Reachable instances", "Shallow bytes");
    private final DefaultTableModel memberModel = model("Kind", "Declaring class", "Member", "Type", "Object ID", "Value");
    private final JTable instances = new JTable(instanceModel);
    private final JTable counts = new JTable(countModel);
    private final JTable members = new JTable(memberModel);
    private final JTextArea objectHeader = Ui.outputArea();
    private InspectorSession session;

    public HeapObjectPanel(DeobfuscationWorkspace workspace) {
        super(new BorderLayout(0, 16));
        this.workspace = workspace;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(dump);
        actions.add(inspect);
        actions.add(scan);
        add(Ui.sectionHeader("Heap / Object Inspector",
                "Explore a bounded object graph from explicit static roots or export a complete HPROF snapshot",
                actions), BorderLayout.NORTH);

        JPanel configuration = configuration();
        instances.setFillsViewportHeight(true);
        instances.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        instances.setAutoCreateRowSorter(true);
        counts.setFillsViewportHeight(true);
        counts.setAutoCreateRowSorter(true);
        members.setFillsViewportHeight(true);
        members.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        instances.getSelectionModel().addListSelectionListener(event -> inspect.setEnabled(
                session != null && instances.getSelectedRow() >= 0));
        instances.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) inspectSelected();
            }
        });
        members.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) inspectReferenced();
            }
        });

        javax.swing.JTabbedPane results = new javax.swing.JTabbedPane();
        results.addTab("Sample instances", Ui.scroll(instances));
        results.addTab("Reachable class counts", Ui.scroll(counts));
        JSplitPane objectDetails = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(objectHeader), Ui.scroll(members));
        objectDetails.setResizeWeight(.3);
        objectDetails.setDividerLocation(150);
        objectDetails.setBorder(null);
        results.addTab("Object details", objectDetails);

        JPanel activity = Ui.card(new BorderLayout(0, 10));
        JPanel activityHeader = new JPanel(new BorderLayout());
        activityHeader.setOpaque(false);
        JLabel title = new JLabel("Reachable object graph");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        status.setForeground(Ui.MUTED);
        activityHeader.add(title, BorderLayout.WEST);
        activityHeader.add(status, BorderLayout.EAST);
        activity.add(activityHeader, BorderLayout.NORTH);
        activity.add(results, BorderLayout.CENTER);

        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, configuration, activity);
        main.setResizeWeight(.34);
        main.setDividerLocation(250);
        main.setBorder(null);
        add(main, BorderLayout.CENTER);

        rootFilter.addActionListener(event -> scan());
        classFilter.addActionListener(event -> scan());
        fieldFilter.addActionListener(event -> scan());
        valueFilter.addActionListener(event -> scan());
        scan.addActionListener(event -> scan());
        inspect.addActionListener(event -> inspectSelected());
        dump.addActionListener(event -> dumpHeap());
        objectHeader.setText("Select a sampled object. Handles are weak and may expire after the target releases an object.");
        setSession(null);
    }

    @Override public void setSession(InspectorSession session) {
        this.session = session;
        boolean connected = session != null;
        scan.setEnabled(connected);
        dump.setEnabled(connected);
        inspect.setEnabled(false);
        if (connected) {
            status.setText("Enter an explicit static root class or package");
        } else {
            clearResults();
            status.setText("Attach to a JVM to inspect objects");
        }
    }

    private JPanel configuration() {
        JPanel card = Ui.card(new BorderLayout(0, 12));
        JPanel fields = new JPanel(new GridBagLayout());
        fields.setOpaque(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(0, 0, 8, 10);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;

        addField(fields, constraints, 0, "Static root class / package", rootFilter,
                "Required, e.g. com.example.Registry or com.example");
        addField(fields, constraints, 1, "Instance class", classFilter,
                "Optional class-name fragment, e.g. Session");
        addField(fields, constraints, 2, "Field name", fieldFilter,
                "Optional field-name fragment, e.g. token");
        addField(fields, constraints, 3, "String / scalar value", valueFilter,
                "Optional value fragment, e.g. user@example.com");
        card.add(fields, BorderLayout.CENTER);

        JPanel limits = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        limits.setOpaque(false);
        limits.add(new JLabel("Depth"));
        limits.add(maxDepth);
        limits.add(new JLabel("Objects"));
        limits.add(maxObjects);
        limits.add(new JLabel("Samples"));
        limits.add(maxResults);
        limits.add(new JLabel("Timeout (s)"));
        limits.add(timeout);
        card.add(limits, BorderLayout.SOUTH);

        JLabel warning = new JLabel("Selected static fields may initialize their classes. Counts cover only known roots; getters and toString are never called.");
        warning.setForeground(Ui.WARNING);
        card.add(warning, BorderLayout.NORTH);
        return card;
    }

    private void addField(JPanel panel, GridBagConstraints constraints, int row, String label,
                          JTextField field, String placeholder) {
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.weightx = 0;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        field.putClientProperty("JTextField.placeholderText", placeholder);
        field.putClientProperty("JTextField.showClearButton", true);
        field.setPreferredSize(new Dimension(520, 33));
        panel.add(field, constraints);
    }

    private void scan() {
        InspectorSession current = session;
        if (current == null) return;
        String root = translated(rootFilter.getText());
        if (root.isBlank()) {
            status.setForeground(Ui.WARNING);
            status.setText("Static root class or package is required");
            return;
        }
        String payload = "root=" + root
                + "\nclass=" + translated(classFilter.getText())
                + "\nfield=" + translated(fieldFilter.getText())
                + "\nvalue=" + valueFilter.getText().trim()
                + "\nmaxDepth=" + maxDepth.getValue()
                + "\nmaxObjects=" + maxObjects.getValue()
                + "\nmaxResults=" + maxResults.getValue()
                + "\nmaxArrayElements=64\nmaxValueLength=512\nmaxRootFields=2000"
                + "\ntimeoutMillis=" + ((Number) timeout.getValue()).intValue() * 1000;
        scan.setEnabled(false);
        inspect.setEnabled(false);
        clearResults();
        status.setForeground(Ui.MUTED);
        status.setText("Traversing bounded static-root graph...");
        Async.run(() -> current.requestText(Operation.HEAP_SCAN, payload), raw -> {
            scan.setEnabled(session == current);
            if (session == current) renderScan(raw);
        }, error -> {
            scan.setEnabled(session == current);
            status.setForeground(Ui.WARNING);
            status.setText("Object graph scan failed");
            Ui.error(this, error);
        });
    }

    private void renderScan(String raw) {
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] values = line.split("\t", -1);
            if ("S".equals(values[0]) && values.length >= 8) {
                status.setForeground(Boolean.parseBoolean(values[6]) ? Ui.WARNING : Ui.SUCCESS);
                status.setText(values[1] + " objects | " + values[3] + " matches | "
                        + formatBytes(number(values[4])) + " shallow | " + values[5] + " ms"
                        + (Boolean.parseBoolean(values[6]) ? " | limited" : ""));
            } else if ("C".equals(values[0]) && values.length >= 5) {
                String originalClass = decoded(values[1]);
                countModel.addRow(new Object[]{displayClass(originalClass), decoded(values[2]),
                        number(values[3]), number(values[4])});
            } else if ("O".equals(values[0]) && values.length >= 9) {
                Sample sample = Sample.parse(values);
                if (sample == null) continue;
                instanceModel.addRow(new Object[]{sample.id, displayClass(sample.className), sample.loader,
                        sample.size, sample.depth, sample.root, sample.path, sample.preview});
            }
        }
    }

    private void inspectSelected() {
        int viewRow = instances.getSelectedRow();
        if (viewRow < 0) return;
        int row = instances.convertRowIndexToModel(viewRow);
        Object value = instanceModel.getValueAt(row, 0);
        if (value instanceof Number) inspectObject(((Number) value).longValue());
    }

    private void inspectReferenced() {
        int row = members.getSelectedRow();
        if (row < 0) return;
        Object value = memberModel.getValueAt(row, 4);
        if (value instanceof Number && ((Number) value).longValue() > 0L) {
            inspectObject(((Number) value).longValue());
        }
    }

    private void inspectObject(long id) {
        InspectorSession current = session;
        if (current == null) return;
        inspect.setEnabled(false);
        status.setForeground(Ui.MUTED);
        status.setText("Reading bounded instance fields...");
        Async.run(() -> current.requestText(Operation.HEAP_OBJECT, String.valueOf(id)), raw -> {
            inspect.setEnabled(session == current && instances.getSelectedRow() >= 0);
            if (session == current) renderObject(raw);
        }, error -> {
            inspect.setEnabled(session == current && instances.getSelectedRow() >= 0);
            status.setForeground(Ui.WARNING);
            status.setText("Object handle expired or inspection failed");
            Ui.error(this, error);
        });
    }

    private void renderObject(String raw) {
        memberModel.setRowCount(0);
        List<String> notices = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] values = line.split("\t", -1);
            if ("H".equals(values[0]) && values.length >= 9) {
                objectHeader.setText("Object #" + values[1] + "\nClass: " + displayClass(decoded(values[2]))
                        + "\nClassloader: " + decoded(values[3])
                        + "\nShallow size: " + values[4] + " bytes\nDepth: " + values[5]
                        + "\nKnown static root: " + decoded(values[6]) + "\nPath to known root: "
                        + decoded(values[7]) + "\nPreview: " + decoded(values[8]));
                objectHeader.setCaretPosition(0);
            } else if (("F".equals(values[0]) || "A".equals(values[0])) && values.length >= 6) {
                String declaring = decoded(values[1]);
                String member = decoded(values[2]);
                if ("F".equals(values[0])) member = workspace.fieldAlias(declaring, member);
                memberModel.addRow(new Object[]{"F".equals(values[0]) ? "Field" : "Array element",
                        displayClass(declaring), member, decoded(values[3]), number(values[4]), decoded(values[5])});
            } else if ("X".equals(values[0]) && values.length >= 2) {
                notices.add(decoded(values[1]));
            }
        }
        status.setForeground(notices.isEmpty() ? Ui.SUCCESS : Ui.WARNING);
        status.setText(notices.isEmpty() ? "Object fields loaded. Double-click an object reference to follow it."
                : String.join(" | ", notices));
    }

    private void dumpHeap() {
        InspectorSession current = session;
        if (current == null) return;
        int confirmation = JOptionPane.showConfirmDialog(this,
                "A full live heap dump can pause the target and consume disk space comparable to its heap. Continue?",
                "Export full HPROF", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirmation != JOptionPane.YES_OPTION) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a new HPROF destination visible to the target JVM");
        chooser.setFileFilter(new FileNameExtensionFilter("Java heap profile (*.hprof)", "hprof"));
        chooser.setSelectedFile(new File("jpi-heap-" + FILE_TIME.format(LocalDateTime.now()) + ".hprof"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File destination = chooser.getSelectedFile();
        if (!destination.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hprof")) {
            destination = new File(destination.getParentFile(), destination.getName() + ".hprof");
        }
        if (destination.exists()) {
            Ui.error(this, new IllegalArgumentException("Choose a new file. Existing HPROF files are never overwritten."));
            return;
        }
        final File selected = destination;
        dump.setEnabled(false);
        status.setForeground(Ui.WARNING);
        status.setText("Target JVM is writing a live HPROF snapshot...");
        Async.run(() -> current.requestText(Operation.HEAP_DUMP, selected.getAbsolutePath()), response -> {
            dump.setEnabled(session == current);
            String[] values = response.split("\t", 2);
            status.setForeground(Ui.SUCCESS);
            status.setText("Heap dump saved: " + values[0]
                    + (values.length > 1 ? " | " + formatBytes(number(values[1])) : ""));
        }, error -> {
            dump.setEnabled(session == current);
            status.setForeground(Ui.WARNING);
            status.setText("Heap dump failed");
            Ui.error(this, error);
        });
    }

    private void clearResults() {
        instanceModel.setRowCount(0);
        countModel.setRowCount(0);
        memberModel.setRowCount(0);
        objectHeader.setText("Select a sampled object. Handles are weak and may expire after the target releases an object.");
    }

    private String translated(String value) {
        return workspace.translateSource(value.trim()).source();
    }

    private String displayClass(String original) {
        String mapped = workspace.classAlias(original);
        return mapped.equals(original) ? original : mapped + " [" + original + "]";
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private static String decoded(String value) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            return "<invalid object data>";
        }
    }

    private static String formatBytes(long value) {
        if (value < 1024L) return value + " B";
        if (value < 1024L * 1024L) return String.format("%.1f KiB", value / 1024d);
        return String.format("%.1f MiB", value / (1024d * 1024d));
    }

    private static JSpinner spinner(int value, int minimum, int maximum, int step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
        spinner.setPreferredSize(new Dimension(88, 30));
        return spinner;
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private record Sample(long id, String className, String loader, long size, int depth, String root, String path, String preview) {
        static Sample parse(String[] values) {
            try {
                return new Sample(Long.parseLong(values[1]), decoded(values[2]), decoded(values[3]),
                        Long.parseLong(values[4]), Integer.parseInt(values[5]), decoded(values[6]),
                        decoded(values[7]), decoded(values[8]));
            } catch (RuntimeException error) {
                return null;
            }
        }
    }
}

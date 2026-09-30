package dev.whitedev.jpi.ui.heap;

import dev.whitedev.jpi.heap.HprofSnapshot;
import dev.whitedev.jpi.heap.HprofAnalysis;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public final class HprofPanel extends JPanel {
    private final JButton open = Ui.primaryButton("Open HPROF...");
    private final JButton close = Ui.secondaryButton("Close snapshot");
    private final JButton search = Ui.secondaryButton("Find objects");
    private final JButton biggest = Ui.secondaryButton("Largest retained objects");
    private final JButton inspect = Ui.secondaryButton("Who references me?");
    private final JTextField filter = new JTextField(20);
    private final JTextField objectId = new JTextField(16);
    private final JCheckBox retained = new JCheckBox("Compute retained size");
    private final JCheckBox excludeWeak = new JCheckBox("Exclude weak / soft / phantom referents", true);
    private final JSpinner memory = new JSpinner(new SpinnerNumberModel(2048, 128, 32768, 256));
    private final JLabel status = new JLabel("Open a local HPROF snapshot. No attached session is required.");
    private final DefaultTableModel objectsModel = model("Object ID", "Object", "Shallow bytes", "Retained bytes");
    private final DefaultTableModel incomingModel = model("Object ID", "Referenced by", "Field / array index", "Weak / soft");
    private final DefaultTableModel outgoingModel = model("Object ID", "References", "Field / array index", "Weak / soft");
    private final DefaultTableModel loaderModel = model("Class ID", "Defined class", "Instances", "Shallow bytes");
    private final JTable objects = new JTable(objectsModel);
    private final JTable incoming = new JTable(incomingModel);
    private final JTable outgoing = new JTable(outgoingModel);
    private final JTable loaderClasses = new JTable(loaderModel);
    private final JTextArea paths = Ui.outputArea();
    private final JTextArea summary = Ui.outputArea();
    private HprofAnalysis snapshot;
    private boolean busy;
    private long generation;

    public HprofPanel() {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        JPanel controls = new JPanel(new java.awt.GridLayout(0, 1, 0, 6));
        controls.setOpaque(false);
        JPanel files = row();
        files.add(open);
        files.add(close);
        files.add(new JLabel("Analyzer MiB"));
        files.add(memory);
        files.add(new JLabel("Class contains"));
        files.add(filter);
        files.add(search);
        files.add(biggest);
        JPanel selection = row();
        selection.add(new JLabel("Snapshot object ID (hex)"));
        selection.add(objectId);
        selection.add(inspect);
        selection.add(retained);
        selection.add(excludeWeak);
        controls.add(files);
        controls.add(selection);
        add(controls, BorderLayout.NORTH);
        configure(objects);
        configure(incoming);
        configure(outgoing);
        configure(loaderClasses);
        objects.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || objects.getSelectedRow() < 0) return;
            objectId.setText(objectsModel.getValueAt(objects.convertRowIndexToModel(objects.getSelectedRow()), 0).toString());
        });
        follow(objects);
        follow(incoming);
        follow(outgoing);
        loaderClasses.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2 || busy || loaderClasses.getSelectedRow() < 0) return;
                filter.setText(loaderClasses.getValueAt(loaderClasses.getSelectedRow(), 1).toString());
                search();
            }
        });
        JTabbedPane details = new JTabbedPane();
        details.addTab("Paths to GC roots", Ui.scroll(paths));
        details.addTab("Incoming references", Ui.scroll(incoming));
        details.addTab("Outgoing references", Ui.scroll(outgoing));
        details.addTab("Loader-defined classes", Ui.scroll(loaderClasses));
        details.addTab("Object summary", Ui.scroll(summary));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(objects), details);
        split.setResizeWeight(.4);
        split.setDividerLocation(200);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        open.addActionListener(event -> chooseFile());
        close.addActionListener(event -> closeSnapshot());
        search.addActionListener(event -> search());
        filter.addActionListener(event -> search());
        biggest.addActionListener(event -> biggest());
        inspect.addActionListener(event -> inspect());
        objectId.addActionListener(event -> inspect());
        retained.setOpaque(false);
        excludeWeak.setOpaque(false);
        updateControls();
    }

    public void openFile(Path file) {
        if (busy) return;
        int memoryMiB = ((Number) memory.getValue()).intValue();
        HprofAnalysis previous = snapshot;
        run("Opening HPROF snapshot...", () -> HprofAnalysis.open(file, memoryMiB), loaded -> {
            if (previous != null) Async.run(() -> { previous.close(); return null; }, ignored -> {}, ignored -> {});
            snapshot = loaded;
            clear();
            status.setText("Loaded " + loaded.file() + ". Search a class or inspect a snapshot object ID.");
            if (!filter.getText().isBlank()) search();
        });
    }

    public void focusClass(String className) {
        filter.setText(className);
        retained.setSelected(true);
        if (snapshot != null && !busy) search();
        else status.setText("Open HPROF to find loader instances. Choose the matching snapshot object; live and HPROF IDs differ.");
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Java heap profile (*.hprof)", "hprof"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) openFile(chooser.getSelectedFile().toPath());
    }

    private void closeSnapshot() {
        generation++;
        busy = false;
        HprofAnalysis current = snapshot;
        snapshot = null;
        clear();
        status.setText("Closing heap analyzer...");
        long closedGeneration = generation;
        if (current != null) Async.run(() -> { current.close(); return null; }, ignored -> {
            if (generation == closedGeneration) status.setText("Snapshot closed. Analyzer process and mapped files have been released.");
        }, ignored -> {});
        updateControls();
    }

    private void search() {
        if (snapshot == null || busy) return;
        HprofAnalysis current = snapshot;
        String query = filter.getText();
        run("Searching snapshot objects...", () -> current.search(query, 500), rows -> {
            renderObjects(rows);
            status.setText(rows.size() + " objects shown (limit 500). Narrow the class filter for more specific results.");
        });
    }

    private void biggest() {
        if (snapshot == null || busy) return;
        HprofAnalysis current = snapshot;
        run("Computing retained sizes across the heap. This may take several minutes...", () -> current.biggest(50), rows -> {
            renderObjects(rows);
            status.setText("Largest 50 retained objects. Retained size is overlapping and must not be summed.");
        });
    }

    private void inspect() {
        if (snapshot == null || busy) return;
        long id;
        try {
            String raw = objectId.getText().trim();
            if (raw.startsWith("0x") || raw.startsWith("0X")) raw = raw.substring(2);
            id = Long.parseUnsignedLong(raw, 16);
        } catch (NumberFormatException error) {
            status.setText("Enter a snapshot object ID in hexadecimal, for example 0x23fa");
            return;
        }
        HprofAnalysis current = snapshot;
        boolean compute = retained.isSelected();
        boolean exclude = excludeWeak.isSelected();
        run("Indexing references and finding GC root paths...", () -> current.inspect(id, compute, exclude), this::renderReport);
    }

    private void renderObjects(List<HprofSnapshot.ObjectSummary> rows) {
        objectsModel.setRowCount(0);
        for (var row : rows) objectsModel.addRow(new Object[]{hex(row.id()), row.label(), row.shallowSize(),
                row.retainedSize() < 0 ? "not computed" : row.retainedSize()});
    }

    private void renderReport(HprofSnapshot.ObjectReport report) {
        renderReferences(incomingModel, report.incoming());
        renderReferences(outgoingModel, report.outgoing());
        loaderModel.setRowCount(0);
        for (var defined : report.loaderClasses()) loaderModel.addRow(new Object[]{hex(defined.classId()),
                defined.name(), defined.instances(), defined.shallowBytes()});
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < report.paths().size(); index++) {
            var path = report.paths().get(index);
            text.append("Path ").append(index + 1).append(" | GC ROOT: ").append(path.kind()).append('\n');
            for (var step : path.steps()) {
                text.append(step.object()).append('\n');
                if (!"selected object".equals(step.edgeToChild())) text.append("  | ").append(step.edgeToChild()).append("\n  v\n");
            }
            text.append('\n');
        }
        if (report.paths().isEmpty()) text.append("No GC root path found under the selected reference policy and search limits.\n");
        text.append("Search visited ").append(report.visitedObjects()).append(" objects.\n")
                .append("Up to 8 distinct roots, 50,000 visited objects and depth 128.\n")
                .append("Weak / soft / phantom referents: ").append(excludeWeak.isSelected() ? "excluded" : "included").append('\n');
        if (report.pathsLimited()) text.append("Path results are limited; explore incoming references for additional paths.\n");
        paths.setText(text.toString());
        paths.setCaretPosition(0);
        summary.setText(report.object().label() + "\nSnapshot: " + snapshot.file()
                + "\nShallow size: " + report.object().shallowSize() + " bytes"
                + "\nRetained size: " + (report.object().retainedSize() < 0 ? "not computed" : report.object().retainedSize() + " bytes")
                + "\nIncoming references: " + report.incomingCount() + "\nOutgoing references: " + report.outgoingCount()
                + "\nReference tables show up to 1,000 edges. Double-click to inspect the referring or referenced object."
                + "\nSnapshot IDs are HPROF identifiers, not live inspector handles or identityHashCode values."
                + "\nRetained size uses the heap analyzer's retention model; root path filtering does not alter this calculation."
                + (report.loaderClassCount() == 0 ? "" : "\n\nLoader-defined classes: " + report.loaderClassCount()
                + "\nInstances of those classes: " + report.loaderInstances() + "\nTheir shallow bytes: " + report.loaderShallowBytes()
                + "\nClass association is not exclusive retention ownership. Double-click a defined class to find its instances."));
        summary.setCaretPosition(0);
        status.setText(report.incomingCount() + " incoming | " + report.outgoingCount() + " outgoing | "
                + report.paths().size() + " GC root paths" + (report.pathsLimited() ? " | paths limited" : ""));
    }

    private void renderReferences(DefaultTableModel model, List<HprofSnapshot.Reference> references) {
        model.setRowCount(0);
        for (var reference : references) model.addRow(new Object[]{hex(reference.id()), reference.object(),
                reference.edge(), reference.weak()});
    }

    private <T> void run(String message, java.util.concurrent.Callable<T> task, Consumer<T> success) {
        long currentGeneration = ++generation;
        busy = true;
        status.setText(message);
        updateControls();
        Async.run(task, result -> {
            if (currentGeneration != generation) {
                if (result instanceof HprofAnalysis unused) Async.run(() -> { unused.close(); return null; }, ignored -> {}, ignored -> {});
                return;
            }
            busy = false;
            success.accept(result);
            updateControls();
        }, error -> {
            if (currentGeneration != generation) return;
            busy = false;
            updateControls();
            status.setText("Heap analysis failed: " + error.getMessage());
            Ui.error(this, error);
        });
    }

    private void follow(JTable table) {
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2 || busy || table.getSelectedRow() < 0) return;
                objectId.setText(table.getValueAt(table.getSelectedRow(), 0).toString());
                inspect();
            }
        });
    }

    private void clear() {
        objectsModel.setRowCount(0);
        incomingModel.setRowCount(0);
        outgoingModel.setRowCount(0);
        loaderModel.setRowCount(0);
        paths.setText("");
        summary.setText("");
        objectId.setText("");
    }

    private void updateControls() {
        open.setEnabled(!busy);
        memory.setEnabled(!busy);
        close.setEnabled(snapshot != null);
        close.setText(busy ? "Stop and close snapshot" : "Close snapshot");
        search.setEnabled(!busy && snapshot != null);
        biggest.setEnabled(!busy && snapshot != null);
        inspect.setEnabled(!busy && snapshot != null);
        retained.setEnabled(!busy);
        excludeWeak.setEnabled(!busy);
        filter.setEnabled(!busy);
        objectId.setEnabled(!busy);
    }

    private static JPanel row() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        panel.setOpaque(false);
        return panel;
    }

    private static void configure(JTable table) {
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    }

    private static String hex(long id) {
        return "0x" + Long.toHexString(id);
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }
}

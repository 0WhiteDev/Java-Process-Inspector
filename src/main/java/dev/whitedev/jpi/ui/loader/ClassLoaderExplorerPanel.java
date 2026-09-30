package dev.whitedev.jpi.ui.loader;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.loader.ClassLoaderSnapshot;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class ClassLoaderExplorerPanel extends JPanel implements SessionAware {
    private static final int PAGE_SIZE = 500;
    private final JButton refresh = Ui.primaryButton("Refresh loaders");
    private final JButton heap = Ui.secondaryButton("Inspect retained memory...");
    private final JButton previous = Ui.secondaryButton("Previous");
    private final JButton next = Ui.secondaryButton("Next");
    private final JTextField filter = new JTextField(22);
    private final JLabel pageLabel = new JLabel("No classes");
    private final JLabel status = new JLabel("Attach to inspect classloaders");
    private final JTree tree = new JTree(new DefaultMutableTreeNode("ClassLoader tree"));
    private final JTextArea details = Ui.outputArea();
    private final DefaultTableModel classesModel = model("Definition ID", "Class", "Code source", "Kind");
    private final DefaultTableModel duplicatesModel = model("Class", "Loader", "Definition ID", "Code source");
    private final DefaultTableModel collisionsModel = model("Package", "Loaders", "Loader identities");
    private final JTable classes = table(classesModel);
    private final JTable duplicates = table(duplicatesModel);
    private final JTable collisions = table(collisionsModel);
    private final Timer filterTimer = new Timer(300, event -> { page = 0; render(); });
    private final BiConsumer<String, String> openClass;
    private final Consumer<String> openHeap;
    private InspectorSession session;
    private ClassLoaderSnapshot snapshot;
    private ClassLoaderSnapshot.Loader selected;
    private Map<String, List<ClassLoaderSnapshot.Definition>> duplicateGroups = Map.of();
    private Map<String, java.util.Set<String>> packageGroups = Map.of();
    private List<ClassLoaderSnapshot.Definition> matchingClasses = List.of();
    private boolean loading;
    private int page;

    public ClassLoaderExplorerPanel(BiConsumer<String, String> openClass, Consumer<String> openHeap) {
        super(new BorderLayout(0, 12));
        this.openClass = openClass;
        this.openHeap = openHeap;
        setOpaque(false);
        setBorder(new EmptyBorder(4, 0, 0, 0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(heap);
        actions.add(refresh);
        add(Ui.sectionHeader("ClassLoader Explorer", "Defining loaders, parent hierarchy, class identity, and namespace overlaps", actions), BorderLayout.NORTH);
        JPanel right = new JPanel(new BorderLayout(0, 8));
        right.setOpaque(false);
        JPanel search = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        search.setOpaque(false);
        search.add(new JLabel("Class / package filter"));
        search.add(filter);
        search.add(previous);
        search.add(next);
        search.add(pageLabel);
        right.add(search, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Defined classes", Ui.scroll(classes));
        tabs.addTab("Loader details / URLs", Ui.scroll(details));
        tabs.addTab("Duplicate definitions (global)", Ui.scroll(duplicates));
        tabs.addTab("Package collisions (global)", Ui.scroll(collisions));
        right.add(tabs, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.scroll(tree), right);
        split.setResizeWeight(.28);
        split.setDividerLocation(340);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        tree.addTreeSelectionListener(event -> {
            Object node = tree.getLastSelectedPathComponent();
            selected = node instanceof DefaultMutableTreeNode branch && branch.getUserObject() instanceof ClassLoaderSnapshot.Loader loader ? loader : null;
            page = 0;
            render();
        });
        refresh.addActionListener(event -> refresh());
        heap.addActionListener(event -> {
            if (selected != null && !selected.type().isEmpty()) openHeap.accept(selected.type());
        });
        previous.addActionListener(event -> { page--; renderClassPage(); });
        next.addActionListener(event -> { page++; renderClassPage(); });
        filterTimer.setRepeats(false);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filterTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent event) { filterTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent event) { filterTimer.restart(); }
        });
        follow(classes, 0, 1);
        follow(duplicates, 2, 0);
        setSession(null);
    }

    @Override public void setSession(InspectorSession value) {
        session = value;
        loading = false;
        snapshot = null;
        duplicateGroups = Map.of();
        packageGroups = Map.of();
        selected = null;
        matchingClasses = List.of();
        page = 0;
        filterTimer.stop();
        tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("ClassLoader tree")));
        classesModel.setRowCount(0);
        duplicatesModel.setRowCount(0);
        collisionsModel.setRowCount(0);
        details.setText("");
        renderClassPage();
        updateControls();
        status.setText(value == null ? "Attach to inspect classloaders" : "Refresh to inspect the current loader graph");
        if (value != null) refresh();
    }

    private void refresh() {
        InspectorSession current = session;
        if (current == null || loading) return;
        loading = true;
        updateControls();
        status.setText("Collecting defining loaders and classes...");
        Async.run(() -> {
            ClassLoaderSnapshot parsed = ClassLoaderSnapshot.parse(current.requestText(Operation.CLASSLOADER_SNAPSHOT, ""));
            return new Analysis(parsed, parsed.duplicateDefinitions(), parsed.packageCollisions());
        }, result -> {
            if (session != current) return;
            loading = false;
            snapshot = result.snapshot;
            duplicateGroups = result.duplicates;
            packageGroups = result.packages;
            rebuildTree();
            render();
            status.setText(snapshot.loaders().size() + " loaders | " + snapshot.definitions().size()
                    + " classes | " + duplicateGroups.size() + " duplicate names | " + packageGroups.size()
                    + " shared packages | " + Instant.ofEpochMilli(snapshot.timestamp()));
            updateControls();
        }, error -> {
            if (session != current) return;
            loading = false;
            updateControls();
            status.setText("Loader snapshot failed: " + error.getMessage());
            Ui.error(this, error);
        });
    }

    private void rebuildTree() {
        String selectedId = selected == null ? "l:0" : selected.id();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("ClassLoader tree");
        Map<String, DefaultMutableTreeNode> nodes = new LinkedHashMap<>();
        for (var loader : snapshot.loaders()) nodes.put(loader.id(), new DefaultMutableTreeNode(loader));
        for (var loader : snapshot.loaders()) {
            DefaultMutableTreeNode node = nodes.get(loader.id());
            DefaultMutableTreeNode parent = nodes.get(loader.parentId());
            if (parent == null || parent == node || node.isNodeDescendant(parent)) root.add(node);
            else parent.add(node);
        }
        tree.setModel(new DefaultTreeModel(root));
        tree.expandRow(0);
        DefaultMutableTreeNode active = nodes.getOrDefault(selectedId, nodes.get("l:0"));
        if (active != null) {
            TreePath path = new TreePath(active.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
    }

    private void render() {
        if (snapshot == null) return;
        String query = filter.getText().trim().toLowerCase(Locale.ROOT);
        matchingClasses = selected == null ? List.of() : snapshot.definitions().stream()
                .filter(value -> value.loaderId().equals(selected.id()) && value.name().toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(ClassLoaderSnapshot.Definition::name)).toList();
        renderClassPage();
        details.setText(selected == null ? "Select a loader" : selected.label()
                + "\nStatus: " + selected.status() + "\nParent: " + snapshot.loaderLabel(selected.parentId())
                + "\nDefined classes (including array types): " + selected.classes()
                + "\nFirst observed: " + time(selected.firstSeen()) + "\n"
                + ("collected".equals(selected.status()) ? "Collection observed: " : "Last observed: ") + time(selected.observedAt())
                + "\nLoader shallow size: " + (selected.shallowSize() < 0 ? "unavailable" : selected.shallowSize() + " bytes")
                + "\n" + selected.notice() + "\n\nURLs / loaded-class code sources:\n"
                + String.join("\n", snapshot.sources().getOrDefault(selected.id(), List.of()))
                + "\n\nRetained memory: use Inspect retained memory to locate loader instances in an offline HPROF."
                + "\nLive loader IDs and identity hashes are not HPROF object IDs. Select the correct snapshot instance."
                + "\nCollected means the weak loader reference was cleared. Active with zero classes does not mean unloaded.");
        details.setCaretPosition(0);
        duplicatesModel.setRowCount(0);
        duplicateGroups.entrySet().stream().filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).contains(query))
                .sorted(Map.Entry.comparingByKey()).limit(1000).forEach(entry -> {
                    for (var definition : entry.getValue()) {
                        if (duplicatesModel.getRowCount() == 2000) break;
                        duplicatesModel.addRow(new Object[]{definition.name(), snapshot.loaderLabel(definition.loaderId()), definition.id(), definition.source()});
                    }
                });
        collisionsModel.setRowCount(0);
        packageGroups.entrySet().stream().filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).contains(query))
                .sorted(Map.Entry.comparingByKey()).limit(1000).forEach(entry -> collisionsModel.addRow(new Object[]{entry.getKey(),
                        entry.getValue().size(), entry.getValue().stream().sorted().map(snapshot::loaderLabel).collect(java.util.stream.Collectors.joining("\n"))}));
        updateControls();
    }

    private void renderClassPage() {
        int pages = Math.max(1, (matchingClasses.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        classesModel.setRowCount(0);
        for (var definition : matchingClasses.subList(page * PAGE_SIZE, Math.min(matchingClasses.size(), (page + 1) * PAGE_SIZE))) {
            classesModel.addRow(new Object[]{definition.id(), definition.name(), definition.source(), definition.kind()});
        }
        pageLabel.setText("Page " + (page + 1) + "/" + pages + " | " + matchingClasses.size() + " classes");
        previous.setEnabled(page > 0);
        next.setEnabled(page + 1 < pages);
    }

    private void follow(JTable table, int idColumn, int nameColumn) {
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                int row = table.getSelectedRow();
                if (event.getClickCount() == 2 && row >= 0 && session != null && openClass != null) {
                    openClass.accept(table.getValueAt(row, idColumn).toString(), table.getValueAt(row, nameColumn).toString());
                }
            }
        });
    }

    private void updateControls() {
        refresh.setEnabled(session != null && !loading);
        heap.setEnabled(selected != null && !selected.type().isEmpty() && openHeap != null && !loading);
    }

    private static String time(long value) { return value == 0 ? "session baseline" : Instant.ofEpochMilli(value).toString(); }
    private static JTable table(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        return table;
    }
    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
    private record Analysis(ClassLoaderSnapshot snapshot, Map<String, List<ClassLoaderSnapshot.Definition>> duplicates,
                            Map<String, java.util.Set<String>> packages) {}
}

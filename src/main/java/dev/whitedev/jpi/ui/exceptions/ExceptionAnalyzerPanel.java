package dev.whitedev.jpi.ui.exceptions;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.exceptions.ExceptionSnapshot;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.BiConsumer;

public final class ExceptionAnalyzerPanel extends JPanel implements SessionAware {
    private final DefaultTableModel typesModel = model("Exception", "Window events", "Session total", "First seen", "Last seen", "Threads");
    private final DefaultTableModel locationsModel = model("Events", "Class", "Method", "Descriptor", "Line");
    private final JTable types = new JTable(typesModel);
    private final JTable locations = new JTable(locationsModel);
    private final JTextArea stack = Ui.outputArea();
    private final JTextField filter = new JTextField(18);
    private final JSpinner duration = new JSpinner(new SpinnerNumberModel(60, 1, 3600, 15));
    private final JSpinner window = new JSpinner(new SpinnerNumberModel(30, 1, 300, 5));
    private final JButton start = Ui.primaryButton("Start recording");
    private final JButton stop = Ui.secondaryButton("Stop");
    private final JButton clear = Ui.secondaryButton("Clear");
    private final JButton trace = Ui.primaryButton("Trace this throw");
    private final JButton cfg = Ui.secondaryButton("Open CFG");
    private final JButton debug = Ui.secondaryButton("Prepare exception breakpoint");
    private final JLabel status = new JLabel("Not attached");
    private final BiConsumer<String, Selection> navigation;
    private final Timer timer = new Timer(1500, event -> { if (isShowing()) request(Operation.EXCEPTION_SNAPSHOT); });
    private InspectorSession session;
    private ExceptionSnapshot snapshot;
    private List<ExceptionSnapshot.Location> selectedLocations = List.of();
    private boolean loading;
    private boolean rendering;

    public ExceptionAnalyzerPanel(BiConsumer<String, Selection> navigation) {
        super(new BorderLayout(0, 10));
        this.navigation = navigation;
        setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(new JLabel("Duration (s)")); actions.add(duration);
        actions.add(new JLabel("Window (s)")); actions.add(window);
        actions.add(start); actions.add(stop); actions.add(clear);
        add(Ui.sectionHeader("Exception Analyzer", "Live JFR exception events, frequency, threads, and observed creation sites", actions), BorderLayout.NORTH);

        JPanel upper = new JPanel(new BorderLayout(0, 6));
        upper.setOpaque(false);
        JPanel search = new JPanel(new BorderLayout(8, 0));
        search.setOpaque(false);
        search.add(new JLabel("Filter exception type"), BorderLayout.WEST);
        search.add(filter, BorderLayout.CENTER);
        upper.add(search, BorderLayout.NORTH);
        upper.add(Ui.scroll(types), BorderLayout.CENTER);
        JPanel lower = new JPanel(new BorderLayout(0, 6));
        lower.setOpaque(false);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tools.setOpaque(false);
        tools.add(trace); tools.add(cfg); tools.add(debug);
        lower.add(tools, BorderLayout.NORTH);
        JSplitPane detailSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.scroll(locations), Ui.scroll(stack));
        detailSplit.setResizeWeight(.6);
        detailSplit.setBorder(null);
        lower.add(detailSplit, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, upper, lower);
        split.setResizeWeight(.45);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        types.setAutoCreateRowSorter(true);
        locations.setAutoCreateRowSorter(true);
        types.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(1, SortOrder.DESCENDING)));
        locations.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
        types.setFillsViewportHeight(true);
        locations.setFillsViewportHeight(true);
        types.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        locations.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        types.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !rendering) showType(null);
        });
        locations.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !rendering) showLocation();
        });
        start.addActionListener(event -> request(Operation.EXCEPTION_START));
        stop.addActionListener(event -> request(Operation.EXCEPTION_STOP));
        clear.addActionListener(event -> request(Operation.EXCEPTION_CLEAR));
        window.addChangeListener(event -> request(Operation.EXCEPTION_SNAPSHOT));
        trace.addActionListener(event -> navigate("trace"));
        cfg.addActionListener(event -> navigate("cfg"));
        debug.addActionListener(event -> navigate("debug"));
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        setSession(null);
    }

    @Override public void setSession(InspectorSession value) {
        timer.stop();
        session = value;
        snapshot = null;
        loading = false;
        typesModel.setRowCount(0);
        locationsModel.setRowCount(0);
        selectedLocations = List.of();
        stack.setText("JFR observes exception creation, not every rethrow.\nSelect an observed location for its stack.\nMessages and exception objects are not sent to the desktop.\nNative JFR buffers can contain exception messages.");
        status.setText(value == null ? "Not attached" : "Target Java 14+ with JFR required. Click Start recording.");
        controls();
        if (value != null) timer.start();
    }

    private void request(Operation operation) {
        InspectorSession current = session;
        if (current == null || loading) return;
        loading = true;
        controls();
        String payload = operation == Operation.EXCEPTION_START ? "durationSeconds=" + duration.getValue()
                : operation == Operation.EXCEPTION_SNAPSHOT ? window.getValue().toString() : "";
        String selectedWindow = window.getValue().toString();
        Async.run(() -> {
            String raw = current.requestText(operation, payload);
            if (operation != Operation.EXCEPTION_SNAPSHOT) raw = current.requestText(Operation.EXCEPTION_SNAPSHOT, selectedWindow);
            return ExceptionSnapshot.parse(raw);
        }, value -> {
            if (current != session) return;
            loading = false;
            render(value);
            controls();
        }, error -> {
            if (current != session) return;
            loading = false;
            status.setText("Exception analysis failed: " + error.getMessage());
            controls();
            if (operation != Operation.EXCEPTION_SNAPSHOT) Ui.error(this, error);
        });
    }

    private void render(ExceptionSnapshot value) {
        String selectedType = selectedType();
        ExceptionSnapshot.Location selected = selectedLocation();
        snapshot = value;
        rendering = true;
        try {
            typesModel.setRowCount(0);
            for (ExceptionSnapshot.Entry entry : value.entries()) {
                typesModel.addRow(new Object[]{entry.type(), entry.windowCount(), entry.total(), time(entry.firstSeen()),
                        time(entry.lastSeen()), entry.threads() + (entry.threadOverflow() ? "+" : "")});
            }
            restore(types, 0, selectedType);
            showType(selected);
        } finally { rendering = false; }
        status.setText(value.state() + " | Window " + value.windowSeconds() + "s | Dropped/evicted details: "
                + value.dropped() + " | " + value.message());
    }

    private void showType(ExceptionSnapshot.Location previous) {
        String type = selectedType();
        selectedLocations = snapshot == null ? List.of() : snapshot.entries().stream().filter(entry -> entry.type().equals(type))
                .findFirst().map(ExceptionSnapshot.Entry::locations).orElse(List.of());
        locationsModel.setRowCount(0);
        for (ExceptionSnapshot.Location location : selectedLocations) locationsModel.addRow(new Object[]{location.count(),
                location.owner(), location.method(), location.descriptor(), location.line()});
        int selected = -1;
        if (previous != null) for (int i = 0; i < selectedLocations.size(); i++) {
            ExceptionSnapshot.Location candidate = selectedLocations.get(i);
            if (candidate.toString().equals(previous.toString())) selected = i;
        }
        if (!selectedLocations.isEmpty()) {
            int row = selected < 0 ? 0 : locations.convertRowIndexToView(selected);
            locations.setRowSelectionInterval(row, row);
        }
        showLocation();
    }

    private void showLocation() {
        ExceptionSnapshot.Location location = selectedLocation();
        String text = location == null ? "Select an exception and observed location."
                : location + "\n\nObserved creation stack:\n" + location.stack();
        if (!stack.getText().equals(text)) { stack.setText(text); stack.setCaretPosition(0); }
        controls();
    }

    private String selectedType() {
        int row = types.getSelectedRow();
        return row < 0 ? "" : String.valueOf(typesModel.getValueAt(types.convertRowIndexToModel(row), 0));
    }

    private ExceptionSnapshot.Location selectedLocation() {
        int row = locations.getSelectedRow();
        if (row < 0) return null;
        int index = locations.convertRowIndexToModel(row);
        return index < selectedLocations.size() ? selectedLocations.get(index) : null;
    }

    private void navigate(String action) {
        ExceptionSnapshot.Location location = selectedLocation();
        if (location != null) navigation.accept(action, new Selection(selectedType(), location));
    }

    private void controls() {
        boolean connected = session != null && !loading;
        boolean running = snapshot != null && snapshot.state().startsWith("recording");
        start.setEnabled(connected && !running);
        stop.setEnabled(connected && running);
        clear.setEnabled(connected);
        ExceptionSnapshot.Location location = selectedLocation();
        trace.setEnabled(connected && location != null && !location.owner().isBlank());
        cfg.setEnabled(trace.isEnabled());
        debug.setEnabled(connected && !selectedType().isBlank());
    }

    @SuppressWarnings("unchecked")
    private void applyFilter() {
        TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) types.getRowSorter();
        String query = filter.getText().toLowerCase(java.util.Locale.ROOT);
        sorter.setRowFilter(new RowFilter<>() {
            @Override public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                return entry.getStringValue(0).toLowerCase(java.util.Locale.ROOT).contains(query);
            }
        });
    }

    private static void restore(JTable table, int column, String value) {
        for (int row = 0; row < table.getRowCount(); row++) if (value.equals(table.getValueAt(row, column))) {
            table.setRowSelectionInterval(row, row); return;
        }
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
            @Override public Class<?> getColumnClass(int column) {
                return "Events".equals(getColumnName(column)) || "Window events".equals(getColumnName(column))
                        || "Session total".equals(getColumnName(column)) ? Long.class : Object.class;
            }
        };
    }

    private static String time(long timestamp) { return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(timestamp)); }

    public record Selection(String exceptionType, ExceptionSnapshot.Location location) {}
}

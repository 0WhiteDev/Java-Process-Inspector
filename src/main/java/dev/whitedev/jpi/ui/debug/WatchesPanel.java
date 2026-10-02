package dev.whitedev.jpi.ui.debug;

import dev.whitedev.jpi.debug.BreakpointSpec;
import dev.whitedev.jpi.debug.DebugSession;
import dev.whitedev.jpi.debug.DebugState;
import dev.whitedev.jpi.debug.StackFrameManager;
import dev.whitedev.jpi.debug.watch.WatchExpressionStore;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

public final class WatchesPanel extends JPanel {
    private final WatchExpressionStore store;
    private final DefaultTableModel watchesModel = model("Expression", "Value", "Status");
    private final DefaultTableModel dataModel = model("ID", "Enabled", "Expression", "Field", "Object", "Break when", "Suspend", "Hits", "Pending write", "Status");
    private final JTable watches = new JTable(watchesModel);
    private final JTable data = new JTable(dataModel);
    private final JButton add = Ui.secondaryButton("Add watch...");
    private final JButton edit = Ui.secondaryButton("Edit...");
    private final JButton remove = Ui.secondaryButton("Remove");
    private final JButton refresh = Ui.secondaryButton("Refresh");
    private final JButton arm = Ui.primaryButton("Break when...");
    private final JButton toggle = Ui.secondaryButton("Enable / disable");
    private final JButton removeData = Ui.secondaryButton("Remove data breakpoint");
    private final JLabel status = new JLabel("Watches are evaluated in the selected suspended frame, without method calls.");
    private List<String> expressions = List.of();
    private DebugSession session;
    private StackFrameManager.FrameView frame;
    private long revision;
    private boolean saving;
    private boolean evaluating;

    public WatchesPanel() { this(WatchExpressionStore.defaultStore()); }

    public WatchesPanel(WatchExpressionStore store) {
        super(new BorderLayout(0, 8));
        this.store = store;
        setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actions.setOpaque(false);
        actions.add(add); actions.add(edit); actions.add(remove); actions.add(refresh); actions.add(arm);
        add(actions, BorderLayout.NORTH);
        watches.setFillsViewportHeight(true);
        watches.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        data.setFillsViewportHeight(true);
        data.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel breakpoints = new JPanel(new BorderLayout(0, 6));
        breakpoints.setOpaque(false);
        JPanel dataActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        dataActions.setOpaque(false);
        dataActions.add(new JLabel("Active data breakpoints (session only)"));
        dataActions.add(toggle); dataActions.add(removeData);
        breakpoints.add(dataActions, BorderLayout.NORTH);
        breakpoints.add(Ui.scroll(data), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(watches), breakpoints);
        split.setResizeWeight(.6);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        status.setToolTipText("Only expression text is saved to " + store.path());
        add(status, BorderLayout.SOUTH);
        add.addActionListener(event -> addExpression(JOptionPane.showInputDialog(this, "Expression in selected frame", "this.field")));
        edit.addActionListener(event -> edit());
        remove.addActionListener(event -> remove());
        refresh.addActionListener(event -> refresh());
        arm.addActionListener(event -> arm());
        toggle.addActionListener(event -> changeData(false));
        removeData.addActionListener(event -> changeData(true));
        watches.getSelectionModel().addListSelectionListener(event -> controls());
        data.getSelectionModel().addListSelectionListener(event -> controls());
        try { expressions = store.load(); }
        catch (Exception error) { status.setText("Could not load watches: " + error.getMessage()); }
        resetValues();
        controls();
    }

    public void setContext(DebugSession value, StackFrameManager.FrameView selected) {
        session = value;
        frame = selected;
        revision++;
        evaluating = false;
        resetValues();
        refreshData();
        status.setText(suspended() ? "Evaluating watches in selected frame..."
                : "Not suspended / no frame. Expressions are retained; values are cleared.");
        controls();
        if (suspended()) refresh();
    }

    public void addExpression(String expression) {
        if (expression == null || saving) return;
        List<String> updated = new ArrayList<>(expressions);
        updated.add(expression);
        save(updated);
    }

    private void edit() {
        int row = watches.getSelectedRow();
        if (row < 0) return;
        String updated = JOptionPane.showInputDialog(this, "Watch expression", expressions.get(row));
        if (updated == null) return;
        List<String> values = new ArrayList<>(expressions);
        values.set(row, updated);
        save(values);
    }

    private void remove() {
        int row = watches.getSelectedRow();
        if (row < 0) return;
        List<String> values = new ArrayList<>(expressions);
        values.remove(row);
        save(values);
    }

    private void save(List<String> updated) {
        final List<String> validated;
        try { validated = WatchExpressionStore.validate(updated); }
        catch (Exception error) { Ui.error(this, error); return; }
        saving = true;
        controls();
        Async.run(() -> { store.save(validated); return validated; }, values -> {
            expressions = values;
            saving = false;
            setContext(session, frame);
        }, error -> {
            saving = false;
            controls();
            Ui.error(this, error);
        });
    }

    private void resetValues() {
        int selected = watches.getSelectedRow();
        watchesModel.setRowCount(0);
        for (String expression : expressions) watchesModel.addRow(new Object[]{expression, "", suspended() ? "Waiting for evaluation" : "Not suspended / no frame"});
        if (selected >= 0 && selected < watches.getRowCount()) watches.setRowSelectionInterval(selected, selected);
    }

    private boolean suspended() {
        return session != null && session.state() == DebugState.SUSPENDED && frame != null;
    }

    private void refresh() {
        if (!suspended() || evaluating) return;
        DebugSession current = session;
        StackFrameManager.FrameView selected = frame;
        List<String> requested = expressions;
        long generation = revision;
        evaluating = true;
        controls();
        Async.run(() -> {
            List<Result> results = new ArrayList<>();
            for (String expression : requested) {
                if (current.state() != DebugState.SUSPENDED) break;
                try { results.add(new Result(current.evaluator().evaluate(selected.threadId(), selected.index(), expression), "OK")); }
                catch (Exception error) { results.add(new Result("", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())); }
            }
            return results;
        }, results -> {
            if (generation != revision || session != current || !suspended()) return;
            evaluating = false;
            for (int i = 0; i < results.size(); i++) {
                watchesModel.setValueAt(results.get(i).value(), i, 1);
                watchesModel.setValueAt(results.get(i).status(), i, 2);
            }
            status.setText("Thread #" + selected.threadId() + " | frame " + selected.index() + " | " + selected.className() + '.' + selected.methodName());
            controls();
        }, error -> {
            if (generation != revision) return;
            evaluating = false;
            controls();
            status.setText("Watch evaluation failed: " + error.getMessage());
        });
    }

    private void arm() {
        int row = watches.getSelectedRow();
        if (!suspended() || row < 0) return;
        String expression = expressions.get(row);
        JComboBox<String> mode = new JComboBox<>(new String[]{"Value changes", "Comparison"});
        JTextField condition = new JTextField("< 5");
        JComboBox<BreakpointSpec.SuspendPolicy> policy = new JComboBox<>(BreakpointSpec.SuspendPolicy.values());
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 5));
        form.add(new JLabel(expression + " (resolved once to the current field/object)"));
        form.add(mode);
        form.add(new JLabel("Comparison against proposed new value, e.g. < 5 or == false"));
        form.add(condition);
        form.add(new JLabel("Suspend policy")); form.add(policy);
        form.add(new JLabel("Stops before the write. Hot fields can cause significant debugger overhead."));
        if (JOptionPane.showConfirmDialog(this, form, "Arm data breakpoint", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        DebugSession current = session;
        StackFrameManager.FrameView selected = frame;
        String predicate = mode.getSelectedIndex() == 0 ? "" : condition.getText();
        BreakpointSpec.SuspendPolicy suspend = (BreakpointSpec.SuspendPolicy) policy.getSelectedItem();
        Async.run(() -> current.watchField(selected.threadId(), selected.index(), expression, predicate, suspend), view -> {
            if (session != current) return;
            status.setText("Data breakpoint armed on " + view.field() + (view.objectId() < 0 ? " (static)" : " @" + view.objectId()));
            refreshData();
        }, error -> Ui.error(this, error));
    }

    private void changeData(boolean remove) {
        int row = data.getSelectedRow();
        DebugSession current = session;
        if (current == null || row < 0) return;
        long id = ((Number) dataModel.getValueAt(row, 0)).longValue();
        boolean enabled = Boolean.TRUE.equals(dataModel.getValueAt(row, 1));
        Async.run(() -> {
            if (remove) current.dataBreakpoints().remove(id);
            else current.dataBreakpoints().setEnabled(id, !enabled);
            return true;
        }, ignored -> { if (session == current) refreshData(); }, error -> Ui.error(this, error));
    }

    private void refreshData() {
        int selected = data.getSelectedRow();
        dataModel.setRowCount(0);
        if (session == null || session.state() == DebugState.DISCONNECTED) return;
        for (var view : session.dataBreakpoints().snapshot()) dataModel.addRow(new Object[]{view.id(), view.enabled(),
                view.expression(), view.field(), view.objectId() < 0 ? "static" : "@" + view.objectId(), view.condition(),
                view.suspendPolicy(), view.hits(), view.lastWrite(), view.error()});
        if (selected >= 0 && selected < data.getRowCount()) data.setRowSelectionInterval(selected, selected);
        controls();
    }

    private void controls() {
        add.setEnabled(!saving);
        edit.setEnabled(!saving && watches.getSelectedRow() >= 0);
        remove.setEnabled(edit.isEnabled());
        refresh.setEnabled(suspended() && !evaluating);
        arm.setEnabled(suspended() && watches.getSelectedRow() >= 0 && !saving);
        boolean connected = session != null && session.state() != DebugState.DISCONNECTED;
        toggle.setEnabled(connected && data.getSelectedRow() >= 0);
        removeData.setEnabled(toggle.isEnabled());
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    private record Result(String value, String status) {}
}

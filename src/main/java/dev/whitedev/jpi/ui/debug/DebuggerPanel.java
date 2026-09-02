package dev.whitedev.jpi.ui.debug;

import dev.whitedev.jpi.attach.CommandLineTokenizer;
import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.debug.BreakpointManager;
import dev.whitedev.jpi.debug.BreakpointSpec;
import dev.whitedev.jpi.debug.DebugEvent;
import dev.whitedev.jpi.debug.DebugSession;
import dev.whitedev.jpi.debug.DebugState;
import dev.whitedev.jpi.debug.StackFrameManager;
import dev.whitedev.jpi.debug.StepManager;
import dev.whitedev.jpi.debug.ThreadManager;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class DebuggerPanel extends JPanel implements SessionAware {
    private final RuntimeTimelineStore timeline;
    private final AtomicLong eventIds = new AtomicLong();
    private final JButton launch = Ui.primaryButton("Launch with debugger...");
    private final JButton connect = Ui.secondaryButton("Attach JDWP...");
    private final JButton disconnect = Ui.secondaryButton("Disconnect debugger");
    private final JButton pause = Ui.secondaryButton("Pause");
    private final JButton resume = Ui.primaryButton("Continue");
    private final JButton stepInto = Ui.secondaryButton("Step into");
    private final JButton stepOver = Ui.secondaryButton("Step over");
    private final JButton stepOut = Ui.secondaryButton("Step out");
    private final JButton forceReturn = Ui.secondaryButton("Force return...");
    private final JComboBox<StepManager.Mode> stepMode = new JComboBox<>(StepManager.Mode.values());
    private final JLabel state = new JLabel("Debugger disconnected");
    private final JLabel instrumentation = new JLabel("Instrumentation not attached");
    private final JList<ThreadManager.ThreadView> threads = new JList<>();
    private final JList<StackFrameManager.FrameView> frames = new JList<>();
    private final DefaultMutableTreeNode variableRoot = new DefaultMutableTreeNode("Variables");
    private final DefaultTreeModel variableModel = new DefaultTreeModel(variableRoot);
    private final JTree variables = new JTree(variableModel);
    private final JTextArea location = Ui.outputArea();
    private final JTextField expression = new JTextField();
    private final JLabel evaluation = new JLabel(" ");
    private final JTextField breakpointClass = new JTextField();
    private final JTextField breakpointMethod = new JTextField();
    private final JTextField breakpointDescriptor = new JTextField();
    private final JTextField breakpointLocation = new JTextField();
    private final JComboBox<BreakpointSpec.Type> breakpointType = new JComboBox<>(BreakpointSpec.Type.values());
    private final JComboBox<BreakpointSpec.SuspendPolicy> suspendPolicy = new JComboBox<>(BreakpointSpec.SuspendPolicy.values());
    private final DefaultTableModel breakpointModel = readOnly("ID", "Enabled", "Type", "Location", "Installed", "Status");
    private final JTable breakpointTable = new JTable(breakpointModel);
    private DebugSession debugger;

    public DebuggerPanel(RuntimeTimelineStore timeline) {
        super(new BorderLayout(0, 12));
        this.timeline = timeline;
        setOpaque(false);
        setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerActions.setOpaque(false);
        headerActions.add(connect);
        headerActions.add(launch);
        headerActions.add(disconnect);
        add(Ui.sectionHeader("Interactive debugger",
                "Control a JVM through JDWP with breakpoints, stepping, frames, and live values", headerActions), BorderLayout.NORTH);

        JPanel control = Ui.card(new BorderLayout(12, 0));
        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        state.setFont(state.getFont().deriveFont(java.awt.Font.BOLD, 13f));
        instrumentation.setForeground(Ui.MUTED);
        labels.add(state);
        labels.add(instrumentation);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(pause);
        actions.add(resume);
        actions.add(new JLabel("Step mode"));
        actions.add(stepMode);
        actions.add(stepInto);
        actions.add(stepOver);
        actions.add(stepOut);
        actions.add(forceReturn);
        control.add(labels, BorderLayout.WEST);
        control.add(actions, BorderLayout.EAST);

        threads.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        frames.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        variables.setRootVisible(false);
        variables.setShowsRootHandles(true);
        location.setText("Suspend the target and select a frame to inspect its exact JVM location.");
        JPanel stack = new JPanel(new GridLayout(1, 3, 8, 0));
        stack.setOpaque(false);
        stack.add(titled("Threads", new JScrollPane(threads)));
        stack.add(titled("Call stack", new JScrollPane(frames)));
        stack.add(titled("Variables", new JScrollPane(variables)));

        JPanel evaluate = new JPanel(new BorderLayout(8, 0));
        evaluate.setOpaque(false);
        JButton evaluateButton = Ui.secondaryButton("Evaluate");
        expression.putClientProperty("JTextField.placeholderText", "this.field, local, literal");
        evaluate.add(expression, BorderLayout.CENTER);
        evaluate.add(evaluateButton, BorderLayout.EAST);
        evaluate.add(evaluation, BorderLayout.SOUTH);
        JPanel source = titled("JVM location and bytecode", new JScrollPane(location));
        source.add(evaluate, BorderLayout.SOUTH);

        JPanel breakpointEditor = breakpointEditor();
        JPanel breakpointPanel = titled("Breakpoints", new JScrollPane(breakpointTable));
        breakpointPanel.add(breakpointEditor, BorderLayout.NORTH);
        JSplitPane lower = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, source, breakpointPanel);
        lower.setResizeWeight(.48);
        lower.setDividerLocation(500);
        lower.setBorder(null);
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stack, lower);
        center.setResizeWeight(.52);
        center.setDividerLocation(300);
        center.setBorder(null);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(control, BorderLayout.NORTH);
        body.add(center, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        launch.addActionListener(event -> launch());
        connect.addActionListener(event -> connect());
        disconnect.addActionListener(event -> closeDebugger());
        pause.addActionListener(event -> execute(() -> debugger.pause()));
        resume.addActionListener(event -> execute(() -> debugger.continueExecution()));
        stepInto.addActionListener(event -> step(StepManager.Depth.INTO));
        stepOver.addActionListener(event -> step(StepManager.Depth.OVER));
        stepOut.addActionListener(event -> step(StepManager.Depth.OUT));
        forceReturn.addActionListener(event -> forceReturn());
        evaluateButton.addActionListener(event -> evaluate());
        expression.addActionListener(event -> evaluate());
        threads.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) loadFrames();
        });
        frames.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) loadVariables();
        });
        variables.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                loadChildren((DefaultMutableTreeNode) event.getPath().getLastPathComponent());
            }

            @Override public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        variables.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) editSelectedValue();
            }
        });
        breakpointTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) toggleBreakpoint();
            }
        });
        updateControls();
    }

    public void selectTarget(String owner, String method, String descriptor) {
        breakpointClass.setText(clean(owner));
        breakpointMethod.setText(clean(method));
        breakpointDescriptor.setText(clean(descriptor));
        breakpointType.setSelectedItem(BreakpointSpec.Type.METHOD);
        breakpointLocation.setText("");
    }

    public void addMethodBreakpoint(String owner, String method, String descriptor) {
        selectTarget(owner, method, descriptor);
        addBreakpoint();
    }

    public void close() {
        closeDebugger();
    }

    @Override public void setSession(InspectorSession session) {
        instrumentation.setText(session == null
                ? "Instrumentation not attached. Existing processes require JDWP for debugging."
                : "Instrumentation attached. Debug control still requires a separate JDWP connection.");
    }

    private JPanel breakpointEditor() {
        JPanel panel = new JPanel(new BorderLayout(8, 6));
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(0, 0, 8, 0));
        JPanel fields = new JPanel(new GridLayout(2, 4, 6, 4));
        fields.setOpaque(false);
        fields.add(breakpointClass);
        fields.add(breakpointMethod);
        fields.add(breakpointDescriptor);
        fields.add(breakpointLocation);
        fields.add(label("Class"));
        fields.add(label("Method"));
        fields.add(label("Descriptor"));
        fields.add(label("Line or BCI"));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        JButton add = Ui.primaryButton("Add");
        JButton remove = Ui.secondaryButton("Remove");
        actions.add(breakpointType);
        actions.add(suspendPolicy);
        actions.add(add);
        actions.add(remove);
        add.addActionListener(event -> addBreakpoint());
        remove.addActionListener(event -> removeBreakpoint());
        breakpointType.addActionListener(event -> breakpointLocation.setEnabled(
                breakpointType.getSelectedItem() != BreakpointSpec.Type.METHOD));
        breakpointLocation.setEnabled(false);
        panel.add(fields, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void connect() {
        JTextField host = new JTextField("127.0.0.1");
        JTextField port = new JTextField("5005");
        JPanel form = new JPanel(new GridLayout(4, 1, 0, 5));
        form.add(new JLabel("JDWP host"));
        form.add(host);
        form.add(new JLabel("JDWP port"));
        form.add(port);
        if (JOptionPane.showConfirmDialog(this, form, "Attach JDWP", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        final int number;
        try {
            number = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException error) {
            Ui.error(this, new IllegalArgumentException("JDWP port must be a number"));
            return;
        }
        opening();
        Async.run(() -> DebugSession.attach(host.getText(), number), this::opened, this::failed);
    }

    private void launch() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select an executable JAR to launch under JDI");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        JTextField vmArguments = new JTextField();
        JTextField applicationArguments = new JTextField();
        JPanel form = new JPanel(new GridLayout(4, 1, 0, 5));
        form.add(new JLabel("Optional JVM arguments"));
        form.add(vmArguments);
        form.add(new JLabel("Optional application arguments"));
        form.add(applicationArguments);
        if (JOptionPane.showConfirmDialog(this, form, "Launch with debugger", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        final List<String> vmArgs;
        final List<String> appArgs;
        try {
            vmArgs = CommandLineTokenizer.parse(vmArguments.getText());
            appArgs = CommandLineTokenizer.parse(applicationArguments.getText());
        } catch (IllegalArgumentException error) {
            Ui.error(this, error);
            return;
        }
        File jar = chooser.getSelectedFile();
        opening();
        Async.run(() -> DebugSession.launch(jar, vmArgs, appArgs), this::opened, this::failed);
    }

    private void opening() {
        closeDebugger();
        state.setText("Connecting debugger...");
        launch.setEnabled(false);
        connect.setEnabled(false);
    }

    private void opened(DebugSession session) {
        debugger = session;
        session.addListener(new DebugSession.Listener() {
            @Override public void onState(DebugState value) {
                SwingUtilities.invokeLater(() -> stateChanged(value));
            }

            @Override public void onEvent(DebugEvent event) {
                publish(event);
                SwingUtilities.invokeLater(() -> eventReceived(event));
            }
        });
        stateChanged(session.state());
        publish(new DebugEvent(DebugEvent.Type.CONNECTED, System.currentTimeMillis(), -1L, "", "",
                session.capabilities().toString()));
        refreshSuspendedState();
    }

    private void failed(Throwable error) {
        debugger = null;
        state.setText("Debugger connection failed");
        updateControls();
        Ui.error(this, error);
    }

    private void stateChanged(DebugState value) {
        state.setText(switch (value) {
            case CONNECTING -> "Connecting debugger...";
            case RUNNING -> "Target running";
            case SUSPENDED -> "Target suspended";
            case DISCONNECTED -> "Debugger disconnected";
        });
        updateControls();
        if (value == DebugState.SUSPENDED) refreshSuspendedState();
        if (value == DebugState.RUNNING) clearInspection();
    }

    private void eventReceived(DebugEvent event) {
        if (event.type() == DebugEvent.Type.DISCONNECTED) {
            debugger = null;
            stateChanged(DebugState.DISCONNECTED);
        } else if (event.type() == DebugEvent.Type.BREAK || event.type() == DebugEvent.Type.STEP
                || event.type() == DebugEvent.Type.EXCEPTION || event.type() == DebugEvent.Type.PAUSE) {
            refreshSuspendedState();
        } else if (event.type() == DebugEvent.Type.CLASS_PREPARE) {
            refreshBreakpoints();
        }
    }

    private void refreshSuspendedState() {
        DebugSession current = debugger;
        if (current == null || current.state() != DebugState.SUSPENDED) return;
        Async.run(() -> current.threads().threads(), values -> {
            if (debugger != current) return;
            threads.setListData(values.toArray(ThreadManager.ThreadView[]::new));
            long stopped = current.stoppedThreadId();
            int selected = 0;
            for (int index = 0; index < values.size(); index++) {
                if (values.get(index).id() == stopped) selected = index;
            }
            if (!values.isEmpty()) threads.setSelectedIndex(selected);
            refreshBreakpoints();
        }, error -> state.setText("Suspended, but threads are unavailable: " + message(error)));
    }

    private void loadFrames() {
        DebugSession current = debugger;
        ThreadManager.ThreadView thread = threads.getSelectedValue();
        if (current == null || thread == null || !thread.suspended()) {
            frames.setListData(new StackFrameManager.FrameView[0]);
            return;
        }
        Async.run(() -> current.frames().frames(thread.id()), values -> {
            if (debugger != current || threads.getSelectedValue() != thread) return;
            frames.setListData(values.toArray(StackFrameManager.FrameView[]::new));
            if (!values.isEmpty()) frames.setSelectedIndex(0);
        }, error -> state.setText("Stack unavailable: " + message(error)));
    }

    private void loadVariables() {
        DebugSession current = debugger;
        StackFrameManager.FrameView frame = frames.getSelectedValue();
        if (current == null || frame == null) return;
        Async.run(() -> new FrameData(current.frames().variables(frame.threadId(), frame.index()),
                        current.frames().locationDetails(frame.threadId(), frame.index())), data -> {
            if (debugger != current || frames.getSelectedValue() != frame) return;
            variableRoot.removeAllChildren();
            for (StackFrameManager.VariableView variable : data.variables()) variableRoot.add(node(variable));
            variableModel.reload();
            location.setText(data.location());
            location.setCaretPosition(0);
        }, error -> state.setText("Frame values unavailable: " + message(error)));
    }

    private DefaultMutableTreeNode node(StackFrameManager.VariableView value) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(value);
        if (value.expandable()) node.add(new DefaultMutableTreeNode(Loading.INSTANCE));
        return node;
    }

    private void loadChildren(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof StackFrameManager.VariableView value) || node.getChildCount() != 1
                || ((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject() != Loading.INSTANCE) return;
        DebugSession current = debugger;
        if (current == null) return;
        Async.run(() -> current.frames().children(value), children -> {
            if (debugger != current) return;
            node.removeAllChildren();
            for (StackFrameManager.VariableView child : children) node.add(node(child));
            variableModel.reload(node);
        }, error -> state.setText("Object fields unavailable: " + message(error)));
    }

    private void editSelectedValue() {
        TreePath path = variables.getSelectionPath();
        if (path == null || debugger == null) return;
        Object value = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (!(value instanceof StackFrameManager.VariableView variable) || !variable.editable()) return;
        String replacement = JOptionPane.showInputDialog(this, "New value for " + variable.name(), variable.displayValue());
        if (replacement == null) return;
        DebugSession current = debugger;
        Async.run(() -> current.setValue(variable, replacement), change -> {
            state.setText(change.name() + " changed: " + change.before() + " -> " + change.after());
            loadVariables();
        }, error -> Ui.error(this, error));
    }

    private void evaluate() {
        DebugSession current = debugger;
        StackFrameManager.FrameView frame = frames.getSelectedValue();
        if (current == null || frame == null) return;
        Async.run(() -> current.evaluator().evaluate(frame.threadId(), frame.index(), expression.getText()),
                value -> evaluation.setText("= " + value), error -> evaluation.setText("Error: " + message(error)));
    }

    private void step(StepManager.Depth depth) {
        ThreadManager.ThreadView thread = threads.getSelectedValue();
        if (debugger == null || thread == null) return;
        execute(() -> debugger.step(thread.id(), depth, (StepManager.Mode) stepMode.getSelectedItem()));
    }

    private void forceReturn() {
        StackFrameManager.FrameView frame = frames.getSelectedValue();
        DebugSession current = debugger;
        if (current == null || frame == null) return;
        String value = JOptionPane.showInputDialog(this,
                "Force the top frame to return immediately. This changes target behavior.\nReturn value:", "null");
        if (value == null) return;
        Async.run(() -> current.forceEarlyReturn(frame.threadId(), frame.index(), value), method -> {
            state.setText("Forced return from " + method);
            refreshSuspendedState();
        }, error -> Ui.error(this, error));
    }

    private void addBreakpoint() {
        DebugSession current = debugger;
        if (current == null) {
            Ui.error(this, new IllegalStateException("Connect the debugger first"));
            return;
        }
        final BreakpointSpec spec;
        try {
            BreakpointSpec.Type type = (BreakpointSpec.Type) breakpointType.getSelectedItem();
            Integer line = type == BreakpointSpec.Type.LINE ? Integer.valueOf(breakpointLocation.getText().trim()) : null;
            Long bci = type == BreakpointSpec.Type.BYTECODE ? Long.valueOf(breakpointLocation.getText().trim()) : null;
            spec = new BreakpointSpec(breakpointClass.getText(), breakpointMethod.getText(),
                    breakpointDescriptor.getText(), line, bci, type,
                    (BreakpointSpec.SuspendPolicy) suspendPolicy.getSelectedItem(), true);
        } catch (RuntimeException error) {
            Ui.error(this, error);
            return;
        }
        Async.run(() -> current.breakpoints().add(spec), ignored -> refreshBreakpoints(), error -> Ui.error(this, error));
    }

    private void toggleBreakpoint() {
        DebugSession current = debugger;
        int row = breakpointTable.getSelectedRow();
        if (current == null || row < 0) return;
        int modelRow = breakpointTable.convertRowIndexToModel(row);
        long id = ((Number) breakpointModel.getValueAt(modelRow, 0)).longValue();
        boolean enabled = Boolean.TRUE.equals(breakpointModel.getValueAt(modelRow, 1));
        execute(() -> current.breakpoints().setEnabled(id, !enabled));
        refreshBreakpoints();
    }

    private void removeBreakpoint() {
        DebugSession current = debugger;
        int row = breakpointTable.getSelectedRow();
        if (current == null || row < 0) return;
        long id = ((Number) breakpointModel.getValueAt(breakpointTable.convertRowIndexToModel(row), 0)).longValue();
        current.breakpoints().remove(id);
        refreshBreakpoints();
    }

    private void refreshBreakpoints() {
        breakpointModel.setRowCount(0);
        if (debugger == null) return;
        for (BreakpointManager.BreakpointView view : debugger.breakpoints().snapshot()) {
            breakpointModel.addRow(new Object[]{view.id(), view.spec().enabled(), view.spec().type(),
                    view.spec().location(), view.installedLocations(), view.error().isEmpty()
                    ? view.installedLocations() == 0 ? "Pending class load" : "Ready" : view.error()});
        }
    }

    private void execute(Action action) {
        try {
            action.run();
            refreshBreakpoints();
            updateControls();
        } catch (Exception error) {
            Ui.error(this, error);
        }
    }

    private void closeDebugger() {
        DebugSession current = debugger;
        debugger = null;
        if (current != null) current.close();
        state.setText("Debugger disconnected");
        clearInspection();
        refreshBreakpoints();
        updateControls();
    }

    private void clearInspection() {
        threads.setListData(new ThreadManager.ThreadView[0]);
        frames.setListData(new StackFrameManager.FrameView[0]);
        variableRoot.removeAllChildren();
        variableModel.reload();
        location.setText("Suspend the target and select a frame to inspect its exact JVM location.");
    }

    private void updateControls() {
        boolean connected = debugger != null && debugger.state() != DebugState.DISCONNECTED;
        boolean suspended = connected && debugger.state() == DebugState.SUSPENDED;
        launch.setEnabled(!connected);
        connect.setEnabled(!connected);
        disconnect.setEnabled(connected);
        pause.setEnabled(connected && !suspended);
        resume.setEnabled(suspended);
        stepInto.setEnabled(suspended);
        stepOver.setEnabled(suspended);
        stepOut.setEnabled(suspended);
        stepMode.setEnabled(suspended);
        forceReturn.setEnabled(suspended && debugger.capabilities().forceEarlyReturn());
    }

    private void publish(DebugEvent event) {
        String subject = event.location().isEmpty() ? event.details() : event.location();
        timeline.publish(new TimelineEvent("debug:" + event.timestamp() + ":" + eventIds.incrementAndGet(),
                event.timestamp(), TimelineSource.DEBUG, event.threadName(), "", "",
                event.type().toString(), event.details(), subject, event.type().toString(), event.details()));
    }

    private static JPanel titled(String title, java.awt.Component content) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createLineBorder(Ui.BORDER));
        JLabel label = new JLabel(title);
        label.setBorder(new EmptyBorder(7, 9, 0, 9));
        panel.add(label, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Ui.MUTED);
        return label;
    }

    private static DefaultTableModel readOnly(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private interface Action {
        void run() throws Exception;
    }

    private enum Loading { INSTANCE }
    private record FrameData(List<StackFrameManager.VariableView> variables, String location) {
    }
}

package dev.whitedev.jpi.ui.tracing;

import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LiveTracerPanel extends JPanel implements SessionAware {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private static final int MAX_LOCAL_EVENTS = 10_000;

    private final DeobfuscationWorkspace workspace;
    private final RuntimeTimelineStore timeline;
    private final JTextField classIdentifier = new JTextField();
    private final JLabel classLabel = new JLabel("No class selected");
    private final JComboBox<TraceMethod> methods = new JComboBox<>();
    private final JTextField condition = new JTextField();
    private final JCheckBox captureArguments = new JCheckBox("Arguments", true);
    private final JCheckBox captureReturn = new JCheckBox("Return value", true);
    private final JCheckBox captureException = new JCheckBox("Exception", true);
    private final JCheckBox captureDuration = new JCheckBox("Duration", true);
    private final JCheckBox captureThread = new JCheckBox("Thread", true);
    private final JCheckBox captureStack = new JCheckBox("Caller stack", true);
    private final JCheckBox captureIdentity = new JCheckBox("Object identity", true);
    private final JSpinner sampleEvery = spinner(1, 1, 1_000_000, 1);
    private final JSpinner rateLimit = spinner(100, 1, 100_000, 10);
    private final JSpinner maxEvents = spinner(500, 1, 10_000, 100);
    private final JSpinner stopAfterSeconds = spinner(60, 1, 3600, 10);
    private final JSpinner maxValueLength = spinner(2048, 64, 65_536, 256);
    private final JSpinner maxArrayElements = spinner(32, 1, 1024, 8);
    private final JSpinner maxStackDepth = spinner(24, 1, 256, 8);
    private final JButton loadMethods = Ui.secondaryButton("Load methods");
    private final JButton start = Ui.primaryButton("Start probe");
    private final JButton stop = Ui.secondaryButton("Stop selected");
    private final JButton stopAll = Ui.secondaryButton("Stop all");
    private final JButton clear = Ui.secondaryButton("Clear tunnel");
    private final JLabel status = new JLabel("Attach to a JVM and select a method");
    private final DefaultTableModel probeModel = readOnlyModel(
            "Probe", "Method", "Calls", "Captured", "Dropped", "Expires");
    private final DefaultTableModel eventModel = readOnlyModel(
            "#", "Time", "Thread", "Method", "Outcome", "Duration");
    private final JTable probes = new JTable(probeModel);
    private final JTable events = new JTable(eventModel);
    private final JTextArea details = Ui.outputArea();
    private final DefaultMutableTreeNode callRoot = new DefaultMutableTreeNode("Captured calls");
    private final DefaultTreeModel callTreeModel = new DefaultTreeModel(callRoot);
    private final JTree callTree = new JTree(callTreeModel);
    private final Map<Long, TraceEvent> captured = new LinkedHashMap<>();
    private final Map<Long, DefaultMutableTreeNode> callNodes = new HashMap<>();
    private final Map<Long, List<DefaultMutableTreeNode>> waitingChildren = new HashMap<>();
    private final Timer pollTimer = new Timer(750, event -> poll());
    private InspectorSession session;
    private boolean loadingMethods;
    private boolean polling;
    private String currentClassName = "";

    public LiveTracerPanel(DeobfuscationWorkspace workspace) {
        this(workspace, new RuntimeTimelineStore());
    }

    public LiveTracerPanel(DeobfuscationWorkspace workspace, RuntimeTimelineStore timeline) {
        super(new BorderLayout(0, 16));
        this.workspace = workspace;
        this.timeline = timeline;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);

        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerActions.setOpaque(false);
        stop.addActionListener(event -> stopSelected());
        stopAll.addActionListener(event -> stopAll());
        clear.addActionListener(event -> clearTunnel());
        JButton copy = Ui.secondaryButton("Copy event");
        copy.addActionListener(event -> copyEvent());
        headerActions.add(copy);
        headerActions.add(clear);
        headerActions.add(stop);
        headerActions.add(stopAll);
        add(Ui.sectionHeader("Live behavior tracer",
                "Observe real method calls, values, exceptions, timing, threads, and caller paths", headerActions),
                BorderLayout.NORTH);

        JPanel configuration = configurationPanel();
        probes.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        probes.setFillsViewportHeight(true);
        events.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        events.setFillsViewportHeight(true);
        events.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelectedEvent();
        });
        events.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) useSelectedAsProbe();
            }
        });
        callTree.setRootVisible(true);
        callTree.addTreeSelectionListener(event -> {
            Object selected = selectedTreeValue();
            if (selected instanceof TraceEvent) showEvent((TraceEvent) selected);
        });
        callTree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    Object selected = selectedTreeValue();
                    if (selected instanceof TraceEvent) useAsProbe((TraceEvent) selected);
                }
            }
        });

        JTabbedPane tunnelTabs = new JTabbedPane();
        tunnelTabs.addTab("Time Tunnel", Ui.scroll(events));
        tunnelTabs.addTab("Call tree", Ui.scroll(callTree));
        JSplitPane eventSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tunnelTabs, Ui.scroll(details));
        eventSplit.setResizeWeight(.62);
        eventSplit.setDividerLocation(680);
        eventSplit.setBorder(null);

        JPanel activity = Ui.card(new BorderLayout(0, 10));
        JLabel probeTitle = new JLabel("Active probes");
        probeTitle.setFont(probeTitle.getFont().deriveFont(Font.BOLD, 13f));
        activity.add(probeTitle, BorderLayout.NORTH);
        JSplitPane activitySplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(probes), eventSplit);
        activitySplit.setResizeWeight(.23);
        activitySplit.setDividerLocation(150);
        activitySplit.setBorder(null);
        activity.add(activitySplit, BorderLayout.CENTER);

        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, configuration, activity);
        main.setResizeWeight(.38);
        main.setDividerLocation(280);
        main.setBorder(null);
        add(main, BorderLayout.CENTER);

        loadMethods.addActionListener(event -> loadMethods());
        start.addActionListener(event -> startProbe());
        stop.setEnabled(false);
        stopAll.setEnabled(false);
        start.setEnabled(false);
        loadMethods.setEnabled(false);
        classLabel.setForeground(Ui.MUTED);
        status.setForeground(Ui.MUTED);
        details.setText("Select a captured invocation to inspect it.");
        probes.getSelectionModel().addListSelectionListener(event -> stop.setEnabled(
                session != null && probes.getSelectedRow() >= 0));
        pollTimer.start();
    }

    public void selectTarget(String identifier, String className, String methodName, String descriptor) {
        currentClassName = className;
        classIdentifier.setText(identifier);
        String mappedClass = workspace.classAlias(className);
        classLabel.setText((mappedClass.equals(className) ? className : mappedClass + " [" + className + "]")
                + "  |  " + identifier);
        methods.removeAllItems();
        String mappedMethod = workspace.methodAlias(className, methodName, descriptor);
        TraceMethod method = new TraceMethod(methodName, mappedMethod, descriptor, "", true);
        methods.addItem(method);
        methods.setSelectedItem(method);
        status.setText("Ready to trace " + mappedClass + "." + mappedMethod + descriptor);
        start.setEnabled(session != null);
    }

    @Override public void setSession(InspectorSession session) {
        this.session = session;
        loadingMethods = false;
        polling = false;
        boolean connected = session != null;
        loadMethods.setEnabled(connected);
        start.setEnabled(connected && methods.getSelectedItem() != null);
        stop.setEnabled(false);
        stopAll.setEnabled(connected);
        if (connected) {
            status.setText("Select a class and method, then configure a bounded probe");
            poll();
        } else {
            probeModel.setRowCount(0);
            clearTunnel();
            status.setText("Attach to a JVM to use the live tracer");
        }
    }

    private JPanel configurationPanel() {
        JPanel panel = Ui.card(new BorderLayout(0, 12));

        JPanel target = new JPanel(new GridBagLayout());
        target.setOpaque(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(0, 0, 7, 8);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;

        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 0;
        target.add(new JLabel("Class"), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        classIdentifier.putClientProperty("JTextField.placeholderText", "Binary class name or exact class ID");
        target.add(classIdentifier, constraints);
        constraints.gridx = 2;
        constraints.weightx = 0;
        target.add(loadMethods, constraints);

        constraints.gridx = 1;
        constraints.gridy = 1;
        constraints.gridwidth = 2;
        target.add(classLabel, constraints);
        constraints.gridwidth = 1;
        constraints.gridx = 0;
        constraints.gridy = 2;
        target.add(new JLabel("Method"), constraints);
        constraints.gridx = 1;
        constraints.gridwidth = 2;
        methods.setPreferredSize(new Dimension(500, 34));
        target.add(methods, constraints);

        constraints.gridx = 0;
        constraints.gridy = 3;
        constraints.gridwidth = 1;
        target.add(new JLabel("Condition"), constraints);
        constraints.gridx = 1;
        constraints.gridwidth = 2;
        condition.putClientProperty("JTextField.placeholderText",
                "$1 != null && $1.contains(\"token\") && duration > 10ms");
        condition.setToolTipText("Supported: $N null/contains, returnValue comparison, exception null, thread.name.contains, duration");
        target.add(condition, constraints);

        JPanel capture = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        capture.setOpaque(false);
        capture.add(new JLabel("Capture"));
        capture.add(captureArguments);
        capture.add(captureReturn);
        capture.add(captureException);
        capture.add(captureDuration);
        capture.add(captureThread);
        capture.add(captureStack);
        capture.add(captureIdentity);

        JPanel limits = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        limits.setOpaque(false);
        limits.add(new JLabel("Sample 1 /"));
        limits.add(sampleEvery);
        limits.add(new JLabel("Rate / sec"));
        limits.add(rateLimit);
        limits.add(new JLabel("Events"));
        limits.add(maxEvents);
        limits.add(new JLabel("Stop after sec"));
        limits.add(stopAfterSeconds);
        limits.add(new JLabel("Value chars"));
        limits.add(maxValueLength);
        limits.add(new JLabel("Array items"));
        limits.add(maxArrayElements);
        limits.add(new JLabel("Stack depth"));
        limits.add(maxStackDepth);

        JPanel footer = new JPanel(new BorderLayout(10, 0));
        footer.setOpaque(false);
        footer.add(status, BorderLayout.CENTER);
        footer.add(start, BorderLayout.EAST);

        JPanel options = new JPanel();
        options.setOpaque(false);
        options.setLayout(new BoxLayout(options, BoxLayout.Y_AXIS));
        options.add(capture);
        options.add(Box.createVerticalStrut(8));
        options.add(limits);
        panel.add(target, BorderLayout.NORTH);
        panel.add(options, BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private void loadMethods() {
        InspectorSession current = session;
        String identifier = classIdentifier.getText().trim();
        if (current == null || loadingMethods) return;
        if (identifier.isEmpty()) {
            Ui.error(this, new IllegalArgumentException("Enter a binary class name or select a class in Loaded classes"));
            return;
        }
        loadingMethods = true;
        loadMethods.setEnabled(false);
        status.setText("Loading methods for " + identifier + "...");
        Async.run(() -> current.requestText(Operation.CLASS_METHODS, identifier), raw -> {
            if (session != current) return;
            methods.removeAllItems();
            for (String line : raw.split("\n")) {
                TraceMethod method = TraceMethod.parse(line, workspace, currentClassName);
                if (method != null && method.traceable()) methods.addItem(method);
            }
            String mappedClass = workspace.classAlias(currentClassName.isEmpty() ? identifier : currentClassName);
            classLabel.setText(mappedClass);
            loadingMethods = false;
            loadMethods.setEnabled(true);
            start.setEnabled(methods.getItemCount() > 0);
            status.setText(methods.getItemCount() + " traceable methods loaded");
        }, error -> {
            loadingMethods = false;
            loadMethods.setEnabled(true);
            start.setEnabled(false);
            status.setText("Could not load methods");
            Ui.error(this, error);
        });
    }

    private void startProbe() {
        InspectorSession current = session;
        TraceMethod method = (TraceMethod) methods.getSelectedItem();
        String identifier = classIdentifier.getText().trim();
        if (current == null || method == null || identifier.isEmpty()) {
            Ui.error(this, new IllegalStateException("Select a traceable class and method first"));
            return;
        }
        start.setEnabled(false);
        status.setText("Installing probe...");
        String payload = identifier + "\n" + method.name + "\n" + method.descriptor + "\n" + settings();
        Async.run(() -> current.requestText(Operation.TRACE_START, payload), response -> {
            if (session != current) return;
            status.setForeground(Ui.SUCCESS);
            status.setText(response.contains("\t") ? response.substring(response.indexOf('\t') + 1) : response);
            start.setEnabled(true);
            poll();
        }, error -> {
            status.setForeground(Ui.WARNING);
            status.setText("Probe installation failed");
            start.setEnabled(true);
            Ui.error(this, error);
        });
    }

    private String settings() {
        String encodedCondition = Base64.getEncoder().encodeToString(
                condition.getText().trim().getBytes(StandardCharsets.UTF_8));
        return "captureArguments=" + captureArguments.isSelected()
                + "\ncaptureReturn=" + captureReturn.isSelected()
                + "\ncaptureException=" + captureException.isSelected()
                + "\ncaptureDuration=" + captureDuration.isSelected()
                + "\ncaptureThread=" + captureThread.isSelected()
                + "\ncaptureStack=" + captureStack.isSelected()
                + "\ncaptureIdentity=" + captureIdentity.isSelected()
                + "\nsampleEvery=" + sampleEvery.getValue()
                + "\nrateLimit=" + rateLimit.getValue()
                + "\nmaxEvents=" + maxEvents.getValue()
                + "\nstopAfterMillis=" + (((Number) stopAfterSeconds.getValue()).longValue() * 1000L)
                + "\nmaxValueLength=" + maxValueLength.getValue()
                + "\nmaxArrayElements=" + maxArrayElements.getValue()
                + "\nmaxStackDepth=" + maxStackDepth.getValue()
                + "\ncondition=" + encodedCondition;
    }

    private void stopSelected() {
        int row = probes.getSelectedRow();
        if (row < 0 || session == null) return;
        String probeId = String.valueOf(probeModel.getValueAt(row, 0));
        stopProbe(probeId);
    }

    private void stopAll() {
        if (session == null) return;
        stopProbe("");
    }

    private void stopProbe(String probeId) {
        InspectorSession current = session;
        stop.setEnabled(false);
        stopAll.setEnabled(false);
        Async.run(() -> current.requestText(Operation.TRACE_STOP, probeId), response -> {
            if (session != current) return;
            status.setForeground(Ui.MUTED);
            status.setText(response);
            stopAll.setEnabled(true);
            poll();
        }, error -> {
            stopAll.setEnabled(true);
            Ui.error(this, error);
        });
    }

    private void poll() {
        InspectorSession current = session;
        if (current == null || polling) return;
        polling = true;
        Async.run(() -> current.requestText(Operation.TRACE_EVENTS, ""), raw -> {
            polling = false;
            if (session == current) render(raw);
        }, error -> {
            polling = false;
            if (session == current) {
                status.setForeground(Ui.WARNING);
                status.setText("Trace polling failed: " + error.getMessage());
            }
        });
    }

    private void render(String raw) {
        String selectedProbe = probes.getSelectedRow() < 0 ? null
                : String.valueOf(probeModel.getValueAt(probes.getSelectedRow(), 0));
        List<ProbeStatus> statuses = new ArrayList<>();
        List<TraceEvent> newEvents = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] values = line.split("\t", -1);
            if ("S".equals(values[0])) {
                ProbeStatus probe = ProbeStatus.parse(values);
                if (probe != null) statuses.add(probe);
            } else if ("E".equals(values[0])) {
                TraceEvent event = TraceEvent.parse(values);
                if (event != null && !captured.containsKey(event.sequence) && captured.size() < MAX_LOCAL_EVENTS) {
                    captured.put(event.sequence, event);
                    newEvents.add(event);
                    publishTimeline(event);
                    eventModel.addRow(new Object[]{event.sequence, TIME.format(Instant.ofEpochMilli(event.timestamp)),
                            event.thread, displayMethod(event.className, event.methodName, event.descriptor),
                            event.outcome, duration(event.duration)});
                }
            }
        }

        probeModel.setRowCount(0);
        long calls = 0;
        long dropped = 0;
        for (ProbeStatus probe : statuses) {
            probeModel.addRow(new Object[]{probe.id,
                    displayMethod(probe.className, probe.methodName, probe.descriptor),
                    probe.calls, probe.captured, probe.dropped,
                    TIME.format(Instant.ofEpochMilli(probe.expiresAt))});
            calls += probe.calls;
            dropped += probe.dropped;
            if (probe.id.equals(selectedProbe)) probes.setRowSelectionInterval(probeModel.getRowCount() - 1,
                    probeModel.getRowCount() - 1);
        }
        stopAll.setEnabled(session != null && !statuses.isEmpty());
        if (dropped > 100 || calls > 100_000) {
            status.setForeground(new Color(255, 95, 86));
            status.setText("High-frequency trace detected: " + calls + " calls, " + dropped
                    + " dropped by safety limits");
        }
        if (!newEvents.isEmpty()) {
            for (TraceEvent event : newEvents) addCallNode(event);
            callTreeModel.reload(callRoot);
            callTree.expandRow(0);
        }
    }

    private void publishTimeline(TraceEvent event) {
        String callId = Long.toString(event.sequence);
        String parent = event.parent == 0L ? "" : Long.toString(event.parent);
        String method = displayMethod(event.className, event.methodName, event.descriptor);
        String common = "Probe: " + event.probeId + "\nTarget: " + event.targetIdentifier
                + "\nReceiver: " + empty(event.receiver) + "\nArguments: " + empty(event.arguments)
                + "\nCaller:\n" + empty(event.stack);
        timeline.publish(new TimelineEvent("trace:0:start:" + callId, event.timestamp, TimelineSource.TRACE,
                event.thread, callId, parent, method + " entered", common,
                event.className + "." + event.methodName + event.descriptor, "ENTER", ""));
        long finished = event.duration < 0L ? event.timestamp
                : event.timestamp + Math.max(1L, (event.duration + 999_999L) / 1_000_000L);
        String result = event.exception.isEmpty() ? compact(event.result) : compact(event.exception);
        String summary = method + " -> " + event.outcome + (result.isEmpty() ? "" : "  " + result);
        String completion = "Duration: " + duration(event.duration) + "\nReturn: " + empty(event.result)
                + "\nException: " + empty(event.exception);
        timeline.publish(new TimelineEvent("trace:1:end:" + callId, finished, TimelineSource.TRACE,
                event.thread, callId, parent, summary, completion,
                event.className + "." + event.methodName + event.descriptor,
                event.exception.isEmpty() ? "RETURN" : "THROW",
                event.exception.isEmpty() ? event.result : event.exception));
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) return "";
        String compact = value.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.length() <= 120 ? compact : compact.substring(0, 117) + "...";
    }

    private void addCallNode(TraceEvent event) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(event);
        callNodes.put(event.sequence, node);
        DefaultMutableTreeNode parent = callNodes.get(event.parent);
        if (parent == null) {
            callRoot.add(node);
            if (event.parent != 0L) waitingChildren.computeIfAbsent(event.parent,
                    ignored -> new ArrayList<>()).add(node);
        } else {
            parent.add(node);
        }
        List<DefaultMutableTreeNode> waiting = waitingChildren.remove(event.sequence);
        if (waiting != null) {
            for (DefaultMutableTreeNode child : waiting) {
                if (child.getParent() != null) ((DefaultMutableTreeNode) child.getParent()).remove(child);
                node.add(child);
            }
        }
    }

    private void showSelectedEvent() {
        int row = events.getSelectedRow();
        if (row < 0) return;
        Object id = eventModel.getValueAt(row, 0);
        if (id instanceof Number) showEvent(captured.get(((Number) id).longValue()));
    }

    private void showEvent(TraceEvent event) {
        if (event == null) return;
        StringBuilder output = new StringBuilder();
        output.append('#').append(event.sequence).append("  ")
                .append(TIME.format(Instant.ofEpochMilli(event.timestamp))).append('\n')
                .append("Probe: ").append(event.probeId).append('\n')
                .append("Method: ").append(event.className).append('.').append(event.methodName)
                .append(event.descriptor).append('\n')
                .append("Thread: ").append(empty(event.thread)).append('\n')
                .append("Outcome: ").append(event.outcome).append('\n')
                .append("Duration: ").append(duration(event.duration)).append('\n')
                .append("Parent call: ").append(event.parent == 0L ? "none" : "#" + event.parent).append("\n\n")
                .append("Object:\n").append(empty(event.receiver)).append("\n\n")
                .append("Arguments:\n").append(empty(event.arguments)).append("\n\n")
                .append("Return:\n").append(empty(event.result)).append("\n\n")
                .append("Exception:\n").append(empty(event.exception)).append("\n\n")
                .append("Caller:\n").append(empty(event.stack));
        details.setText(output.toString());
        details.setCaretPosition(0);
    }

    private void useSelectedAsProbe() {
        int row = events.getSelectedRow();
        if (row < 0) return;
        Object id = eventModel.getValueAt(row, 0);
        if (id instanceof Number) useAsProbe(captured.get(((Number) id).longValue()));
    }

    private void useAsProbe(TraceEvent event) {
        if (event == null) return;
        selectTarget(event.targetIdentifier, event.className, event.methodName, event.descriptor);
        status.setText("Event #" + event.sequence + " prepared as the next trace target");
    }

    private Object selectedTreeValue() {
        TreePath path = callTree.getSelectionPath();
        if (path == null) return null;
        return ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    private void copyEvent() {
        String value = details.getText();
        if (value.isBlank()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
    }

    private void clearTunnel() {
        captured.clear();
        eventModel.setRowCount(0);
        callNodes.clear();
        waitingChildren.clear();
        callRoot.removeAllChildren();
        callTreeModel.reload();
        details.setText("Select a captured invocation to inspect it.");
    }

    private static String duration(long nanos) {
        return nanos < 0 ? "disabled" : String.format("%.3f ms", nanos / 1_000_000d);
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() ? "<not captured>" : value;
    }

    private static String decoded(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            return "<invalid trace data>";
        }
    }

    private String displayMethod(String owner, String name, String descriptor) {
        return workspace.classAlias(owner) + "." + workspace.methodAlias(owner, name, descriptor) + descriptor;
    }

    private static JSpinner spinner(int value, int minimum, int maximum, int step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
        spinner.setPreferredSize(new Dimension(76, 30));
        return spinner;
    }

    private static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static final class TraceMethod {
        final String name;
        final String displayName;
        final String descriptor;
        final String modifiers;
        final boolean patchable;

        TraceMethod(String name, String displayName, String descriptor, String modifiers, boolean patchable) {
            this.name = name;
            this.displayName = displayName;
            this.descriptor = descriptor;
            this.modifiers = modifiers;
            this.patchable = patchable;
        }

        boolean traceable() {
            return patchable && !"<init>".equals(name) && !"<clinit>".equals(name);
        }

        static TraceMethod parse(String line, DeobfuscationWorkspace workspace, String owner) {
            String[] values = line.split("\t", -1);
            if (values.length != 4) return null;
            return new TraceMethod(values[0], workspace.methodAlias(owner, values[0], values[1]),
                    values[1], values[2], Boolean.parseBoolean(values[3]));
        }

        @Override public String toString() {
            String visible = displayName.equals(name) ? name : displayName + " [" + name + "]";
            return modifiers + " " + visible + descriptor;
        }
    }

    private static final class ProbeStatus {
        final String id;
        final long calls;
        final long captured;
        final long dropped;
        final long expiresAt;
        final String className;
        final String methodName;
        final String descriptor;

        ProbeStatus(String id, long calls, long captured, long dropped, long expiresAt,
                    String className, String methodName, String descriptor) {
            this.id = id;
            this.calls = calls;
            this.captured = captured;
            this.dropped = dropped;
            this.expiresAt = expiresAt;
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
        }

        static ProbeStatus parse(String[] values) {
            if (values.length != 10) return null;
            try {
                return new ProbeStatus(values[1], Long.parseLong(values[2]), Long.parseLong(values[3]),
                        Long.parseLong(values[4]), Long.parseLong(values[5]), decoded(values[7]),
                        decoded(values[8]), decoded(values[9]));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    private static final class TraceEvent {
        final long sequence;
        final long parent;
        final long timestamp;
        final String probeId;
        final long duration;
        final String outcome;
        final String targetIdentifier;
        final String className;
        final String methodName;
        final String descriptor;
        final String thread;
        final String receiver;
        final String arguments;
        final String result;
        final String exception;
        final String stack;

        TraceEvent(long sequence, long parent, long timestamp, String probeId, long duration,
                   String outcome, String targetIdentifier, String className, String methodName,
                   String descriptor, String thread, String receiver, String arguments,
                   String result, String exception, String stack) {
            this.sequence = sequence;
            this.parent = parent;
            this.timestamp = timestamp;
            this.probeId = probeId;
            this.duration = duration;
            this.outcome = outcome;
            this.targetIdentifier = targetIdentifier;
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.thread = thread;
            this.receiver = receiver;
            this.arguments = arguments;
            this.result = result;
            this.exception = exception;
            this.stack = stack;
        }

        static TraceEvent parse(String[] values) {
            if (values.length != 17) return null;
            try {
                return new TraceEvent(Long.parseLong(values[1]), Long.parseLong(values[2]),
                        Long.parseLong(values[3]), values[4], Long.parseLong(values[5]), values[6],
                        decoded(values[7]), decoded(values[8]), decoded(values[9]), decoded(values[10]),
                        decoded(values[11]), decoded(values[12]), decoded(values[13]), decoded(values[14]),
                        decoded(values[15]), decoded(values[16]));
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        @Override public String toString() {
            return "#" + sequence + "  " + className + "." + methodName + "  "
                    + outcome + "  " + duration(duration);
        }
    }
}

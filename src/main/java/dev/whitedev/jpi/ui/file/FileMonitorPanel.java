package dev.whitedev.jpi.ui.file;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.file.FileDecision;
import dev.whitedev.jpi.file.FileEvent;
import dev.whitedev.jpi.file.FileOperation;
import dev.whitedev.jpi.file.FileProtocolCodec;
import dev.whitedev.jpi.file.FileRule;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class FileMonitorPanel extends JPanel implements SessionAware {
    private static final int CONTROLLER_LIMIT = 20000;
    private final RuntimeTimelineStore timeline;
    private final List<FileEvent> events = new ArrayList<>();
    private final DefaultTableModel eventModel = readOnly("Time", "Operation", "Path", "Caller", "Thread", "Decision", "Bytes");
    private final JTable eventTable = new JTable(eventModel);
    private final DefaultTableModel ruleModel = readOnly("ID", "Operation", "Path", "Caller", "Decision", "Redirect root");
    private final JTable ruleTable = new JTable(ruleModel);
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea details = Ui.outputArea();
    private final JLabel status = new JLabel("Not attached");
    private final JButton start = Ui.primaryButton("Start monitor");
    private final JButton stop = Ui.secondaryButton("Stop and restore");
    private final JCheckBox capture = new JCheckBox("Capture bounded content", false);
    private final JSpinner previewBytes = new JSpinner(new SpinnerNumberModel(4096, 256, 16384, 256));
    private final JSpinner eventLimit = new JSpinner(new SpinnerNumberModel(5000, 100, 50000, 100));
    private final JComboBox<FileOperation> operationFilter = new JComboBox<>(FileOperation.values());
    private final JComboBox<FileDecision> decisionFilter = new JComboBox<>(FileDecision.values());
    private final JTextField pathFilter = new JTextField();
    private final JTextField callerFilter = new JTextField();
    private final JTextField threadFilter = new JTextField();
    private final JCheckBox blockedOnly = new JCheckBox("Blocked only");
    private final JCheckBox redirectedOnly = new JCheckBox("Redirected only");
    private final JComboBox<FileOperation> ruleOperation = new JComboBox<>(FileOperation.values());
    private final JComboBox<FileDecision> ruleDecision = new JComboBox<>(new FileDecision[]{
            FileDecision.ALLOW, FileDecision.BLOCK, FileDecision.REDIRECT});
    private final JTextField rulePath = new JTextField("**");
    private final JTextField ruleCaller = new JTextField("*");
    private final JTextField redirectRoot = new JTextField();
    private final JComboBox<FileDecision> globalPolicy = new JComboBox<>(new FileDecision[]{FileDecision.ALLOW, FileDecision.BLOCK});
    private final javax.swing.Timer timer = new javax.swing.Timer(850, event -> poll());
    private InspectorSession session;
    private boolean active;
    private boolean polling;
    private long eventGeneration;
    private String editingRuleId;
    private Navigation navigation;

    public FileMonitorPanel(RuntimeTimelineStore timeline) {
        super(new BorderLayout(0, 12));
        this.timeline = timeline;
        setOpaque(false);
        setBorder(new EmptyBorder(4, 0, 0, 0));
        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerActions.setOpaque(false);
        headerActions.add(new JLabel("Events"));
        headerActions.add(eventLimit);
        headerActions.add(capture);
        headerActions.add(new JLabel("Preview bytes"));
        headerActions.add(previewBytes);
        headerActions.add(stop);
        headerActions.add(start);
        add(Ui.sectionHeader("File Monitor", "Java-level file interception with local rules and sandbox redirection",
                headerActions), BorderLayout.NORTH);

        tabs.addTab("Events", eventPanel());
        tabs.addTab("Rules", rulesPanel());
        add(tabs, BorderLayout.CENTER);

        start.addActionListener(event -> start());
        stop.addActionListener(event -> stop());
        eventTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showDetails();
        });
        ruleTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) loadSelectedRule();
            }
        });
        operationFilter.addActionListener(event -> filter());
        decisionFilter.addActionListener(event -> filter());
        blockedOnly.addActionListener(event -> filter());
        redirectedOnly.addActionListener(event -> filter());
        watch(pathFilter);
        watch(callerFilter);
        watch(threadFilter);
        timer.setRepeats(true);
        updateControls();
    }

    public void setNavigation(Navigation navigation) {
        this.navigation = navigation;
    }

    @Override public void setSession(InspectorSession value) {
        timer.stop();
        session = value;
        active = false;
        polling = false;
        eventGeneration++;
        editingRuleId = null;
        events.clear();
        eventModel.setRowCount(0);
        ruleModel.setRowCount(0);
        details.setText("Select an event to inspect its paths, caller, stack, and bounded payload preview.");
        status.setText(value == null ? "Not attached" : "Ready. Interception is limited to Java call sites.");
        updateControls();
        if (value != null) refreshRules();
    }

    private JPanel eventPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        JPanel filters = new JPanel(new GridLayout(2, 1, 0, 5));
        filters.setOpaque(false);
        JPanel first = new JPanel(new GridLayout(1, 6, 6, 0));
        first.setOpaque(false);
        pathFilter.putClientProperty("JTextField.placeholderText", "Path contains");
        callerFilter.putClientProperty("JTextField.placeholderText", "Caller contains");
        threadFilter.putClientProperty("JTextField.placeholderText", "Thread contains");
        first.add(operationFilter);
        first.add(decisionFilter);
        first.add(pathFilter);
        first.add(callerFilter);
        first.add(threadFilter);
        JPanel flags = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        flags.setOpaque(false);
        flags.add(blockedOnly);
        flags.add(redirectedOnly);
        first.add(flags);
        filters.add(first);
        filters.add(status);
        panel.add(filters, BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(eventTable), new JScrollPane(details));
        split.setResizeWeight(.7);
        split.setDividerLocation(780);
        split.setBorder(null);
        panel.add(split, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton caller = Ui.secondaryButton("Open caller");
        JButton xrefs = Ui.secondaryButton("Open Xrefs");
        JButton cfg = Ui.secondaryButton("Open CFG");
        JButton createRule = Ui.secondaryButton("Create rule");
        JButton clear = Ui.secondaryButton("Clear events");
        JButton investigate = Ui.secondaryButton("Investigate path");
        JButton timelineButton = Ui.secondaryButton("Show in Timeline");
        caller.addActionListener(event -> navigate(NavigationTarget.CALLER));
        xrefs.addActionListener(event -> navigate(NavigationTarget.XREFS));
        cfg.addActionListener(event -> navigate(NavigationTarget.CFG));
        createRule.addActionListener(event -> createRuleFromEvent());
        clear.addActionListener(event -> clearEvents());
        investigate.addActionListener(event -> navigate(NavigationTarget.INVESTIGATION));
        timelineButton.addActionListener(event -> navigate(NavigationTarget.TIMELINE));
        actions.add(caller);
        actions.add(xrefs);
        actions.add(cfg);
        actions.add(createRule);
        actions.add(clear);
        actions.add(investigate);
        actions.add(timelineButton);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel rulesPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        panel.add(new JScrollPane(ruleTable), BorderLayout.CENTER);
        JPanel editor = Ui.card(new GridLayout(3, 1, 0, 6));
        JPanel patterns = new JPanel(new GridLayout(1, 6, 6, 0));
        patterns.setOpaque(false);
        patterns.add(new JLabel("Operation"));
        patterns.add(ruleOperation);
        patterns.add(new JLabel("Path glob"));
        patterns.add(rulePath);
        patterns.add(new JLabel("Caller glob"));
        patterns.add(ruleCaller);
        JPanel decision = new JPanel(new GridLayout(1, 5, 6, 0));
        decision.setOpaque(false);
        decision.add(new JLabel("Decision"));
        decision.add(ruleDecision);
        decision.add(new JLabel("Redirect root"));
        decision.add(redirectRoot);
        JButton browse = Ui.secondaryButton("Browse...");
        browse.addActionListener(event -> chooseRedirectRoot());
        decision.add(browse);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton add = Ui.primaryButton("Save rule");
        JButton remove = Ui.secondaryButton("Remove selected");
        JButton applyPolicy = Ui.secondaryButton("Apply global policy");
        add.addActionListener(event -> addRule());
        remove.addActionListener(event -> removeRule());
        applyPolicy.addActionListener(event -> setPolicy());
        actions.add(new JLabel("Default"));
        actions.add(globalPolicy);
        actions.add(applyPolicy);
        actions.add(remove);
        actions.add(add);
        editor.add(patterns);
        editor.add(decision);
        editor.add(actions);
        panel.add(editor, BorderLayout.SOUTH);
        return panel;
    }

    private void start() {
        InspectorSession current = session;
        if (current == null) return;
        String settings = "maxEvents=" + eventLimit.getValue() + ";captureContent=" + capture.isSelected()
                + ";previewBytes=" + previewBytes.getValue();
        start.setEnabled(false);
        status.setText("Scanning and instrumenting application call sites...");
        Async.run(() -> current.requestText(Operation.FILE_MONITOR_START, settings), result -> {
            if (session != current) return;
            active = true;
            status.setText(result);
            timer.start();
            updateControls();
            poll();
        }, error -> {
            active = false;
            status.setText("File Monitor could not start");
            updateControls();
            Ui.error(this, error);
        });
    }

    private void stop() {
        InspectorSession current = session;
        if (current == null) return;
        timer.stop();
        stop.setEnabled(false);
        Async.run(() -> current.requestText(Operation.FILE_MONITOR_STOP, ""), result -> {
            active = false;
            status.setText(result);
            updateControls();
        }, error -> {
            status.setText("Restore failed: " + message(error));
            updateControls();
            Ui.error(this, error);
        });
    }

    private void poll() {
        InspectorSession current = session;
        if (!active || current == null || polling) return;
        polling = true;
        long generation = eventGeneration;
        Async.run(() -> FileProtocolCodec.parseEvents(current.requestText(Operation.FILE_EVENT_BATCH, "")), batch -> {
            polling = false;
            if (session != current || generation != eventGeneration) return;
            status.setText(batch.summary() + (batch.dropped() == 0 ? "" : " | dropped " + batch.dropped()));
            for (FileEvent event : batch.events()) addEvent(event);
            filter();
        }, error -> {
            polling = false;
            status.setText("Event polling failed: " + message(error));
        });
    }

    private void addEvent(FileEvent event) {
        events.add(event);
        while (events.size() > CONTROLLER_LIMIT) events.removeFirst();
        String callId = event.callId() <= 0 ? "" : Long.toString(event.callId());
        timeline.publish(new TimelineEvent("file:" + event.id(), event.timestamp(), TimelineSource.FILE,
                event.threadName(), callId, "", event.operation() + " " + event.path(), details(event)));
    }

    private void filter() {
        eventModel.setRowCount(0);
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS");
        for (FileEvent event : events) {
            if (!matches(event)) continue;
            eventModel.addRow(new Object[]{time.format(new Date(event.timestamp())), event.operation(), event.path(),
                    event.caller(), event.threadName(), event.decision(),
                    event.requestedBytes() < 0 ? "" : event.requestedBytes()});
        }
    }

    private boolean matches(FileEvent event) {
        FileOperation operation = (FileOperation) operationFilter.getSelectedItem();
        FileDecision decision = (FileDecision) decisionFilter.getSelectedItem();
        if (operation != FileOperation.ALL && event.operation() != operation) return false;
        if (decision != FileDecision.ALL && event.decision() != decision) return false;
        if (blockedOnly.isSelected() && event.decision() != FileDecision.BLOCK) return false;
        if (redirectedOnly.isSelected() && event.decision() != FileDecision.REDIRECT) return false;
        return contains(event.path(), pathFilter.getText()) && contains(event.caller(), callerFilter.getText())
                && contains(event.threadName(), threadFilter.getText());
    }

    private void showDetails() {
        FileEvent event = selectedEvent();
        details.setText(event == null ? "Select an event." : details(event));
        details.setCaretPosition(0);
    }

    private static String details(FileEvent event) {
        return "Operation: " + event.operation() + "\nDecision: " + event.decision() + "\nOriginal path: "
                + event.path() + "\nNormalized path: " + event.normalizedPath() + "\nRedirected path: "
                + empty(event.redirectedPath()) + "\nCaller: " + event.caller() + "\nThread: " + event.threadName()
                + "\nRequested bytes: " + (event.requestedBytes() < 0 ? "unknown" : event.requestedBytes())
                + "\nCall ID: " + (event.callId() <= 0 ? "none" : event.callId()) + "\nError: " + empty(event.error())
                + "\n\nStack\n" + empty(event.stackTrace()) + "\n\nPayload UTF-8\n"
                + previewText(event.payloadPreview()) + "\n\nPayload HEX\n" + hex(event.payloadPreview());
    }

    private FileEvent selectedEvent() {
        int row = eventTable.getSelectedRow();
        if (row < 0) return null;
        int visible = -1;
        for (FileEvent event : events) {
            if (!matches(event)) continue;
            if (++visible == eventTable.convertRowIndexToModel(row)) return event;
        }
        return null;
    }

    private void navigate(NavigationTarget target) {
        FileEvent event = selectedEvent();
        if (event != null && navigation != null) navigation.open(target, event);
    }

    private void addRule() {
        InspectorSession current = session;
        if (current == null) return;
        FileDecision decision = (FileDecision) ruleDecision.getSelectedItem();
        if (decision == FileDecision.REDIRECT && redirectRoot.getText().isBlank()) {
            Ui.error(this, new IllegalArgumentException("Select a redirect root for REDIRECT"));
            return;
        }
        FileRule rule = new FileRule("file-" + UUID.randomUUID(), (FileOperation) ruleOperation.getSelectedItem(),
                defaultPattern(rulePath.getText(), "**"), defaultPattern(ruleCaller.getText(), "*"), decision,
                redirectRoot.getText().trim());
        if (editingRuleId != null) {
            rule = new FileRule(editingRuleId, rule.operation(), rule.pathPattern(), rule.callerPattern(),
                    rule.decision(), rule.redirectRoot());
        }
        FileRule saved = rule;
        Operation operation = editingRuleId == null ? Operation.FILE_RULE_ADD : Operation.FILE_RULE_UPDATE;
        Async.run(() -> current.requestText(operation, FileProtocolCodec.encodeRule(saved)), ignored -> {
            editingRuleId = null;
            refreshRules();
        }, error -> Ui.error(this, error));
    }

    private void createRuleFromEvent() {
        FileEvent event = selectedEvent();
        if (event == null) return;
        editingRuleId = null;
        ruleOperation.setSelectedItem(event.operation());
        rulePath.setText(event.normalizedPath());
        ruleCaller.setText(event.caller());
        ruleDecision.setSelectedItem(FileDecision.BLOCK);
        tabs.setSelectedIndex(1);
    }

    private void loadSelectedRule() {
        int row = ruleTable.getSelectedRow();
        if (row < 0) return;
        int modelRow = ruleTable.convertRowIndexToModel(row);
        editingRuleId = String.valueOf(ruleModel.getValueAt(modelRow, 0));
        ruleOperation.setSelectedItem(ruleModel.getValueAt(modelRow, 1));
        rulePath.setText(String.valueOf(ruleModel.getValueAt(modelRow, 2)));
        ruleCaller.setText(String.valueOf(ruleModel.getValueAt(modelRow, 3)));
        ruleDecision.setSelectedItem(ruleModel.getValueAt(modelRow, 4));
        redirectRoot.setText(String.valueOf(ruleModel.getValueAt(modelRow, 5)));
    }

    private void clearEvents() {
        InspectorSession current = session;
        eventGeneration++;
        events.clear();
        eventModel.setRowCount(0);
        details.setText("Select an event to inspect its paths, caller, stack, and bounded payload preview.");
        if (current == null) return;
        Async.run(() -> current.requestText(Operation.FILE_EVENTS_CLEAR, ""), status::setText,
                error -> Ui.error(this, error));
    }

    private void removeRule() {
        InspectorSession current = session;
        int row = ruleTable.getSelectedRow();
        if (current == null || row < 0) return;
        String id = String.valueOf(ruleModel.getValueAt(ruleTable.convertRowIndexToModel(row), 0));
        Async.run(() -> current.requestText(Operation.FILE_RULE_REMOVE, id), ignored -> {
                    if (id.equals(editingRuleId)) editingRuleId = null;
                    refreshRules();
                },
                error -> Ui.error(this, error));
    }

    private void refreshRules() {
        InspectorSession current = session;
        if (current == null) return;
        Async.run(() -> FileProtocolCodec.parseRules(current.requestText(Operation.FILE_RULE_LIST, "")), rules -> {
            if (session != current) return;
            ruleModel.setRowCount(0);
            for (FileRule rule : rules) ruleModel.addRow(new Object[]{rule.id(), rule.operation(), rule.pathPattern(),
                    rule.callerPattern(), rule.decision(), rule.redirectRoot()});
        }, error -> status.setText("Rules unavailable: " + message(error)));
        Async.run(() -> current.requestText(Operation.FILE_POLICY_GET, ""), policy -> {
            try {
                globalPolicy.setSelectedItem(FileDecision.valueOf(policy.trim()));
            } catch (RuntimeException ignored) {
            }
        }, error -> { });
    }

    private void setPolicy() {
        InspectorSession current = session;
        if (current == null) return;
        FileDecision selected = (FileDecision) globalPolicy.getSelectedItem();
        Async.run(() -> current.requestText(Operation.FILE_POLICY_SET, selected.name()),
                result -> status.setText("Default file decision: " + result), error -> Ui.error(this, error));
    }

    private void chooseRedirectRoot() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (!redirectRoot.getText().isBlank()) chooser.setCurrentDirectory(new File(redirectRoot.getText()));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            redirectRoot.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void updateControls() {
        boolean attached = session != null;
        start.setEnabled(attached && !active);
        stop.setEnabled(attached && active);
        capture.setEnabled(attached && !active);
        previewBytes.setEnabled(attached && !active);
        eventLimit.setEnabled(attached && !active);
    }

    private void watch(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { filter(); }
            @Override public void removeUpdate(DocumentEvent event) { filter(); }
            @Override public void changedUpdate(DocumentEvent event) { filter(); }
        });
    }

    private static boolean contains(String value, String query) {
        return query == null || query.isBlank() || value.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }

    private static String previewText(byte[] value) {
        return value.length == 0 ? "<not captured>" : new String(value, StandardCharsets.UTF_8);
    }

    private static String hex(byte[] value) {
        if (value.length == 0) return "<not captured>";
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < value.length; index++) {
            if (index > 0) output.append(index % 24 == 0 ? '\n' : ' ');
            output.append(String.format("%02X", value[index] & 0xff));
        }
        return output.toString();
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() ? "<none>" : value;
    }

    private static String defaultPattern(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static DefaultTableModel readOnly(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    public enum NavigationTarget { CALLER, XREFS, CFG, INVESTIGATION, TIMELINE }

    @FunctionalInterface
    public interface Navigation {
        void open(NavigationTarget target, FileEvent event);
    }
}

package dev.whitedev.jpi.ui.inspection;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FieldWritesPanel extends JPanel implements SessionAware {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private static final int MAX_LOCAL_EVENTS = 10_000;
    private final DeobfuscationWorkspace workspace;
    private final RuntimeTimelineStore timeline;
    private final JTextField owner = new JTextField();
    private final JTextField field = new JTextField();
    private final JTextField descriptor = new JTextField();
    private final JSpinner maxEvents = spinner(500, 1, 10_000, 100);
    private final JSpinner rateLimit = spinner(100, 1, 100_000, 25);
    private final JSpinner stopAfter = spinner(60, 1, 3_600, 30);
    private final JSpinner maxValueLength = spinner(2_048, 64, 65_536, 256);
    private final JSpinner maxStackDepth = spinner(24, 1, 256, 8);
    private final JSpinner maxClasses = spinner(500, 1, 2_000, 100);
    private final JButton scan = Ui.secondaryButton("Find write sites");
    private final JButton start = Ui.primaryButton("Trace writes");
    private final JButton stop = Ui.secondaryButton("Stop trace");
    private final JLabel status = new JLabel("Select a field from Static fields or enter its owner and name");
    private final DefaultTableModel sitesModel = model("Class", "Method", "Descriptor", "Line", "Opcode", "Sites");
    private final DefaultTableModel eventsModel = model("#", "Time", "Thread", "Value change", "Written by", "Line", "Call");
    private final JTable sites = new JTable(sitesModel);
    private final JTable events = new JTable(eventsModel);
    private final JTextArea details = Ui.outputArea();
    private final Map<Long, WriteEvent> captured = new LinkedHashMap<>();
    private final Timer pollTimer = new Timer(750, event -> poll());
    private InspectorSession session;
    private boolean polling;
    private boolean loading;

    public FieldWritesPanel(DeobfuscationWorkspace workspace, RuntimeTimelineStore timeline) {
        super(new BorderLayout(0, 16));
        this.workspace = workspace;
        this.timeline = timeline;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton copy = Ui.secondaryButton("Copy event");
        JButton clear = Ui.secondaryButton("Clear events");
        copy.addActionListener(event -> copyEvent());
        clear.addActionListener(event -> clearEvents());
        stop.addActionListener(event -> stop());
        scan.addActionListener(event -> scan());
        start.addActionListener(event -> start());
        actions.add(copy);
        actions.add(clear);
        actions.add(stop);
        actions.add(scan);
        actions.add(start);
        add(Ui.sectionHeader("Why is this value this?",
                "Find every PUTFIELD or PUTSTATIC site and capture the exact runtime writer, values, and stack",
                actions), BorderLayout.NORTH);

        JPanel target = Ui.card(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(0, 0, 8, 8);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;
        addField(target, constraints, 0, "Field owner", owner);
        addField(target, constraints, 1, "Field name", field);
        addField(target, constraints, 2, "Descriptor", descriptor);
        descriptor.setEditable(false);
        owner.putClientProperty("JTextField.placeholderText", "Class name or exact class ID");
        field.putClientProperty("JTextField.placeholderText", "licensed");

        JPanel limits = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        limits.setOpaque(false);
        limits.add(new JLabel("Events"));
        limits.add(maxEvents);
        limits.add(new JLabel("Rate / sec"));
        limits.add(rateLimit);
        limits.add(new JLabel("Stop after sec"));
        limits.add(stopAfter);
        limits.add(new JLabel("Value chars"));
        limits.add(maxValueLength);
        limits.add(new JLabel("Stack depth"));
        limits.add(maxStackDepth);
        limits.add(new JLabel("Writer classes"));
        limits.add(maxClasses);
        constraints.gridx = 0;
        constraints.gridy = 3;
        constraints.gridwidth = 2;
        target.add(limits, constraints);
        constraints.gridy = 4;
        status.setForeground(Ui.MUTED);
        target.add(status, constraints);

        sites.setFillsViewportHeight(true);
        events.setFillsViewportHeight(true);
        events.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        events.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelected();
        });
        JTabbedPane evidence = new JTabbedPane();
        evidence.addTab("Static write sites", Ui.scroll(sites));
        evidence.addTab("Runtime writes", Ui.scroll(events));
        JSplitPane results = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, evidence, Ui.scroll(details));
        results.setResizeWeight(.68);
        results.setDividerLocation(780);
        results.setBorder(null);
        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, target, results);
        main.setResizeWeight(.28);
        main.setDividerLocation(225);
        main.setBorder(null);
        add(main, BorderLayout.CENTER);
        details.setText("Select a captured field write to inspect its values, writer, and caller stack.");
        pollTimer.start();
        setSession(null);
    }

    public void selectField(String owner, String fieldName) {
        this.owner.setText(owner);
        field.setText(fieldName);
        descriptor.setText("");
        status.setText("Ready to analyze " + owner + "." + fieldName);
        scan();
    }

    @Override public void setSession(InspectorSession session) {
        this.session = session;
        polling = false;
        loading = false;
        boolean attached = session != null;
        scan.setEnabled(attached);
        start.setEnabled(attached);
        stop.setEnabled(attached);
        if (!attached) {
            sitesModel.setRowCount(0);
            clearEvents();
            status.setText("Attach to a JVM to analyze field writes");
        }
    }

    private void scan() {
        InspectorSession current = session;
        if (current == null || loading || !validTarget()) return;
        loading = true;
        scan.setEnabled(false);
        sitesModel.setRowCount(0);
        status.setText("Scanning loaded bytecode for PUTFIELD and PUTSTATIC...");
        Async.run(() -> current.requestText(Operation.FIELD_WRITE_SITES, targetPayload()), raw -> {
            if (session != current) return;
            int count = renderSites(raw);
            loading = false;
            scan.setEnabled(true);
            status.setText(count + " static write locations found");
        }, error -> {
            if (session != current) return;
            loading = false;
            scan.setEnabled(true);
            status.setText("Field write analysis failed");
            Ui.error(this, error);
        });
    }

    private int renderSites(String raw) {
        int count = 0;
        for (String line : raw.split("\\n")) {
            String[] values = line.split("\\t", -1);
            if (values.length == 6 && "F".equals(values[0])) {
                descriptor.setText(decoded(values[4]));
            } else if (values.length == 8 && "W".equals(values[0])) {
                String className = decoded(values[2]);
                String methodName = decoded(values[3]);
                String methodDescriptor = decoded(values[4]);
                sitesModel.addRow(new Object[]{workspace.classAlias(className),
                        workspace.methodAlias(className, methodName, methodDescriptor), methodDescriptor,
                        "-1".equals(values[5]) ? "unknown" : values[5], values[6], values[7]});
                count++;
            }
        }
        return count;
    }

    private void start() {
        InspectorSession current = session;
        if (current == null || loading || !validTarget()) return;
        loading = true;
        start.setEnabled(false);
        status.setText("Instrumenting exact field write instructions...");
        String payload = targetPayload() + "\nmaxEvents=" + maxEvents.getValue()
                + ";rateLimit=" + rateLimit.getValue() + ";stopAfterSeconds=" + stopAfter.getValue()
                + ";maxValueLength=" + maxValueLength.getValue() + ";maxStackDepth=" + maxStackDepth.getValue()
                + ";maxClasses=" + maxClasses.getValue();
        Async.run(() -> current.requestText(Operation.FIELD_TRACE_START, payload), response -> {
            if (session != current) return;
            loading = false;
            start.setEnabled(true);
            status.setForeground(Ui.SUCCESS);
            status.setText(response.contains("\t") ? response.substring(response.indexOf('\t') + 1) : response);
            poll();
        }, error -> {
            if (session != current) return;
            loading = false;
            start.setEnabled(true);
            status.setForeground(Ui.WARNING);
            status.setText("Field write probe failed");
            Ui.error(this, error);
        });
    }

    private void stop() {
        InspectorSession current = session;
        if (current == null) return;
        stop.setEnabled(false);
        Async.run(() -> current.requestText(Operation.FIELD_TRACE_STOP, ""), response -> {
            if (session != current) return;
            stop.setEnabled(true);
            status.setForeground(Ui.MUTED);
            status.setText(response);
            poll();
        }, error -> {
            if (session != current) return;
            stop.setEnabled(true);
            Ui.error(this, error);
        });
    }

    private void poll() {
        InspectorSession current = session;
        if (current == null || polling) return;
        polling = true;
        Async.run(() -> current.requestText(Operation.FIELD_TRACE_EVENTS, ""), raw -> {
            polling = false;
            if (session == current) renderEvents(raw);
        }, error -> {
            polling = false;
            if (session == current) status.setText("Field trace polling failed: " + error.getMessage());
        });
    }

    private void renderEvents(String raw) {
        for (String line : raw.split("\\n")) {
            if (line.isEmpty()) continue;
            String[] values = line.split("\\t", -1);
            if (values.length == 13 && "S".equals(values[0])) {
                status.setText(values[4] + " captured / " + values[3] + " writes, " + values[5]
                        + " dropped, " + values[7] + " classes, " + values[8] + " sites");
            } else if (values.length == 18 && "E".equals(values[0])) {
                WriteEvent event = WriteEvent.parse(values);
                if (event == null || captured.containsKey(event.sequence) || captured.size() >= MAX_LOCAL_EVENTS) continue;
                captured.put(event.sequence, event);
                String call = event.callId == 0L ? "-" : "#" + event.callId;
                eventsModel.addRow(new Object[]{event.sequence, TIME.format(Instant.ofEpochMilli(event.timestamp)),
                        event.thread, compact(event.previous) + " -> " + compact(event.current),
                        displayWriter(event), event.line < 0 ? "unknown" : event.line, call});
                publishTimeline(event);
            }
        }
    }

    private void publishTimeline(WriteEvent event) {
        String callId = event.callId == 0L ? "" : Long.toString(event.callId);
        String fieldName = workspace.classAlias(event.owner) + "."
                + workspace.fieldAlias(event.owner, event.fieldName, event.descriptor);
        timeline.publish(new TimelineEvent("field-write:" + event.sequence, event.timestamp,
                TimelineSource.FIELD_WRITE, event.thread, callId, "",
                fieldName + "  " + compact(event.previous) + " -> " + compact(event.current),
                event.details()));
    }

    private void showSelected() {
        int row = events.getSelectedRow();
        if (row < 0) return;
        Object sequence = eventsModel.getValueAt(row, 0);
        if (sequence instanceof Number) {
            WriteEvent event = captured.get(((Number) sequence).longValue());
            if (event != null) {
                details.setText(event.details());
                details.setCaretPosition(0);
            }
        }
    }

    private void clearEvents() {
        captured.clear();
        eventsModel.setRowCount(0);
        details.setText("Select a captured field write to inspect its values, writer, and caller stack.");
    }

    private void copyEvent() {
        if (details.getText().isBlank()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(details.getText()), null);
    }

    private boolean validTarget() {
        if (!owner.getText().isBlank() && !field.getText().isBlank()) return true;
        status.setForeground(Ui.WARNING);
        status.setText("Enter a field owner and field name");
        return false;
    }

    private String targetPayload() {
        return workspace.translateSource(owner.getText().trim()).source() + "\n" + field.getText().trim();
    }

    private String displayWriter(WriteEvent event) {
        return workspace.classAlias(event.writerClass) + "."
                + workspace.methodAlias(event.writerClass, event.writerMethod, event.writerDescriptor);
    }

    private static void addField(JPanel panel, GridBagConstraints constraints, int row,
                                 String label, JComponent component) {
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        panel.add(component, constraints);
    }

    private static String decoded(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            return "<invalid field trace data>";
        }
    }

    private static String compact(String value) {
        String compact = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.length() <= 90 ? compact : compact.substring(0, 87) + "...";
    }

    private static JSpinner spinner(int value, int minimum, int maximum, int step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
        spinner.setPreferredSize(new Dimension(82, 30));
        return spinner;
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static final class WriteEvent {
        final long sequence;
        final long timestamp;
        final String probeId;
        final String thread;
        final String owner;
        final String fieldName;
        final String descriptor;
        final String writerClass;
        final String writerMethod;
        final String writerDescriptor;
        final int line;
        final String previous;
        final String current;
        final String receiver;
        final String stack;
        final long callId;
        final String opcode;

        WriteEvent(long sequence, long timestamp, String probeId, String thread, String owner,
                   String fieldName, String descriptor, String writerClass, String writerMethod,
                   String writerDescriptor, int line, String previous, String current, String receiver,
                   String stack, long callId, String opcode) {
            this.sequence = sequence;
            this.timestamp = timestamp;
            this.probeId = probeId;
            this.thread = thread;
            this.owner = owner;
            this.fieldName = fieldName;
            this.descriptor = descriptor;
            this.writerClass = writerClass;
            this.writerMethod = writerMethod;
            this.writerDescriptor = writerDescriptor;
            this.line = line;
            this.previous = previous;
            this.current = current;
            this.receiver = receiver;
            this.stack = stack;
            this.callId = callId;
            this.opcode = opcode;
        }

        static WriteEvent parse(String[] values) {
            try {
                return new WriteEvent(Long.parseLong(values[1]), Long.parseLong(values[2]), values[3],
                        decoded(values[4]), decoded(values[5]), decoded(values[6]), decoded(values[7]),
                        decoded(values[8]), decoded(values[9]), decoded(values[10]), Integer.parseInt(values[11]),
                        decoded(values[12]), decoded(values[13]), decoded(values[14]), decoded(values[15]),
                        Long.parseLong(values[16]), values[17]);
            } catch (RuntimeException error) {
                return null;
            }
        }

        String details() {
            return "Time: " + TIME.format(Instant.ofEpochMilli(timestamp)) + "\nProbe: " + probeId
                    + "\nField: " + owner + "." + fieldName + " " + descriptor
                    + "\nWrite: " + opcode + "\nThread: " + thread
                    + "\nCall ID: " + (callId == 0L ? "none" : "#" + callId)
                    + "\nObject: " + receiver + "\n\nPrevious:\n" + previous + "\n\nCurrent:\n" + current
                    + "\n\nWritten by:\n" + writerClass + "." + writerMethod + writerDescriptor
                    + (line < 0 ? "" : "\nSource line: " + line) + "\n\nCaller stack:\n" + stack;
        }
    }
}

package dev.whitedev.jpi.ui.tracing;

import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.plugin.api.hook.HookProfile;
import dev.whitedev.jpi.plugin.api.hook.HookTarget;
import dev.whitedev.jpi.plugin.runtime.ExtensionRegistry;
import dev.whitedev.jpi.plugin.runtime.RegisteredExtension;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.Timer;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ApiHooksPanel extends JPanel implements SessionAware {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private static final int MAX_LOCAL_EVENTS = 10000;

    private final DeobfuscationWorkspace workspace;
    private final RuntimeTimelineStore timeline;
    private final XrefsPanel xrefs;
    private final Runnable openXrefs;
    private final Map<String, JCheckBox> profiles = new LinkedHashMap<>();
    private final Map<String, HookProfile> pluginProfiles = new LinkedHashMap<>();
    private final JPanel profileRows = new JPanel();
    private final ExtensionRegistry extensions;
    private final JSpinner maxEvents = spinner(1000, 1, 10000, 100);
    private final JSpinner rateLimit = spinner(200, 1, 100000, 25);
    private final JSpinner stopAfter = spinner(120, 1, 3600, 30);
    private final JSpinner maxClasses = spinner(1500, 1, 10000, 100);
    private final JButton start = Ui.primaryButton("Start selected profiles");
    private final JButton stop = Ui.secondaryButton("Stop all hooks");
    private final JButton clear = Ui.secondaryButton("Clear events");
    private final JLabel status = new JLabel("Attach to a JVM to use automatic API hooks");
    private final DefaultTableModel profileModel = model(
            "Profile", "Active", "Calls", "Captured", "Dropped", "Classes", "Sites", "Expires");
    private final DefaultTableModel eventModel = model("#", "Time", "Profile", "Thread", "Caller", "Observed API");
    private final JTable profileTable = new JTable(profileModel);
    private final JTable eventTable = new JTable(eventModel);
    private final JTextArea details = Ui.outputArea();
    private final Map<Long, ApiEvent> events = new LinkedHashMap<>();
    private final Timer pollTimer = new Timer(750, event -> poll());
    private InspectorSession session;
    private boolean polling;

    public ApiHooksPanel(DeobfuscationWorkspace workspace, XrefsPanel xrefs, Runnable openXrefs) {
        this(workspace, xrefs, openXrefs, new ExtensionRegistry(), new RuntimeTimelineStore());
    }

    public ApiHooksPanel(DeobfuscationWorkspace workspace, XrefsPanel xrefs, Runnable openXrefs,
                         ExtensionRegistry extensions) {
        this(workspace, xrefs, openXrefs, extensions, new RuntimeTimelineStore());
    }

    public ApiHooksPanel(DeobfuscationWorkspace workspace, XrefsPanel xrefs, Runnable openXrefs,
                         ExtensionRegistry extensions, RuntimeTimelineStore timeline) {
        super(new BorderLayout(0, 16));
        this.workspace = workspace;
        this.xrefs = xrefs;
        this.openXrefs = openXrefs;
        this.extensions = extensions;
        this.timeline = timeline;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(clear);
        actions.add(stop);
        actions.add(start);
        add(Ui.sectionHeader("Automatic API Hooks",
                "Apply bounded call-site probes for common network, crypto, file, reflection, and loading APIs",
                actions), BorderLayout.NORTH);

        JPanel setup = new JPanel(new GridLayout(1, 2, 12, 0));
        setup.setOpaque(false);
        setup.add(profilesCard());
        setup.add(limitsCard());

        profileTable.setFillsViewportHeight(true);
        eventTable.setFillsViewportHeight(true);
        eventTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        eventTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelected();
        });
        eventTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) openCallerXrefs();
            }
        });
        JSplitPane eventSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.scroll(eventTable), Ui.scroll(details));
        eventSplit.setResizeWeight(.7);
        eventSplit.setDividerLocation(790);
        eventSplit.setBorder(null);

        JPanel activity = Ui.card(new BorderLayout(0, 10));
        JPanel activityHeader = new JPanel(new BorderLayout());
        activityHeader.setOpaque(false);
        JLabel title = new JLabel("Observed application call sites");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        status.setForeground(Ui.MUTED);
        activityHeader.add(title, BorderLayout.WEST);
        activityHeader.add(status, BorderLayout.EAST);
        activity.add(activityHeader, BorderLayout.NORTH);
        JSplitPane activitySplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(profileTable), eventSplit);
        activitySplit.setResizeWeight(.24);
        activitySplit.setDividerLocation(145);
        activitySplit.setBorder(null);
        activity.add(activitySplit, BorderLayout.CENTER);

        JSplitPane main = new JSplitPane(JSplitPane.VERTICAL_SPLIT, setup, activity);
        main.setResizeWeight(.31);
        main.setDividerLocation(225);
        main.setBorder(null);
        add(main, BorderLayout.CENTER);

        start.addActionListener(event -> start());
        stop.addActionListener(event -> stop());
        clear.addActionListener(event -> clear());
        details.setText("Select an event to inspect its caller and exact JVM descriptors. Double-click it to open Xrefs.");
        extensions.addListener(() -> SwingUtilities.invokeLater(this::refreshProfiles));
        refreshProfiles();
        setSession(null);
        pollTimer.start();
    }

    @Override public void setSession(InspectorSession session) {
        this.session = session;
        polling = false;
        boolean connected = session != null;
        start.setEnabled(connected);
        stop.setEnabled(connected);
        if (connected) {
            status.setText("Select one or more profiles");
            poll();
        } else {
            profileModel.setRowCount(0);
            clear();
            status.setText("Attach to a JVM to use automatic API hooks");
        }
    }

    private JPanel profilesCard() {
        JPanel card = Ui.card(new BorderLayout(0, 10));
        JLabel title = new JLabel("Observation profiles");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        card.add(title, BorderLayout.NORTH);
        profileRows.setOpaque(false);
        profileRows.setLayout(new BoxLayout(profileRows, BoxLayout.Y_AXIS));
        card.add(profileRows, BorderLayout.CENTER);
        return card;
    }

    private JPanel limitsCard() {
        JPanel card = Ui.card(new BorderLayout(0, 12));
        JLabel title = new JLabel("Safety limits");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        card.add(title, BorderLayout.NORTH);
        JPanel grid = new JPanel(new GridLayout(4, 2, 10, 9));
        grid.setOpaque(false);
        grid.add(new JLabel("Events per profile"));
        grid.add(maxEvents);
        grid.add(new JLabel("Rate per second"));
        grid.add(rateLimit);
        grid.add(new JLabel("Stop after seconds"));
        grid.add(stopAfter);
        grid.add(new JLabel("Maximum hooked classes"));
        grid.add(maxClasses);
        card.add(grid, BorderLayout.CENTER);
        JLabel note = new JLabel("No application objects are captured. Every class is restored when hooks stop.");
        note.setForeground(Ui.MUTED);
        note.setBorder(new EmptyBorder(4, 0, 0, 0));
        card.add(note, BorderLayout.SOUTH);
        return card;
    }

    private void addProfile(JPanel parent, String id, String title, String api, boolean selected) {
        JCheckBox checkBox = new JCheckBox(title, selected);
        profiles.put(id, checkBox);
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(2, 0, 4, 0));
        JLabel description = new JLabel(api);
        description.setForeground(Ui.MUTED);
        row.add(checkBox, BorderLayout.WEST);
        row.add(description, BorderLayout.CENTER);
        parent.add(row);
    }

    private void refreshProfiles() {
        Map<String, Boolean> selected = new LinkedHashMap<>();
        for (Map.Entry<String, JCheckBox> entry : profiles.entrySet()) selected.put(entry.getKey(), entry.getValue().isSelected());
        profiles.clear();
        pluginProfiles.clear();
        profileRows.removeAll();
        addProfile(profileRows, "NETWORK", "Network", "Socket.connect, HttpClient.send, URL.openConnection",
                selected.getOrDefault("NETWORK", false));
        addProfile(profileRows, "CRYPTO", "Crypto", "Cipher operations and MessageDigest.digest",
                selected.getOrDefault("CRYPTO", selected.isEmpty()));
        addProfile(profileRows, "FILES", "Files", "File streams and java.nio.file.Files reads or writes",
                selected.getOrDefault("FILES", false));
        addProfile(profileRows, "REFLECTION", "Reflection", "Class.forName, Method.invoke, Constructor.newInstance",
                selected.getOrDefault("REFLECTION", false));
        addProfile(profileRows, "CLASS_LOADING", "Class loading", "ClassLoader and MethodHandles class definition",
                selected.getOrDefault("CLASS_LOADING", false));
        for (RegisteredExtension<HookProfile> registered : extensions.hookProfiles()) {
            HookProfile profile = registered.extension();
            pluginProfiles.put(profile.id(), profile);
            addProfile(profileRows, profile.id(), profile.name(), profile.description(),
                    selected.getOrDefault(profile.id(), false));
        }
        profileRows.revalidate();
        profileRows.repaint();
    }

    private void start() {
        InspectorSession current = session;
        if (current == null) return;
        List<String> selected = new ArrayList<>();
        for (Map.Entry<String, JCheckBox> profile : profiles.entrySet()) {
            if (profile.getValue().isSelected()) selected.add(profile.getKey());
        }
        if (selected.isEmpty()) {
            status.setForeground(Ui.WARNING);
            status.setText("Select at least one profile");
            return;
        }
        String payload = String.join(",", selected) + "\nmaxEvents=" + maxEvents.getValue()
                + ";rateLimit=" + rateLimit.getValue() + ";stopAfterSeconds=" + stopAfter.getValue()
                + ";maxClasses=" + maxClasses.getValue() + hookDefinitions(selected);
        start.setEnabled(false);
        status.setForeground(Ui.MUTED);
        status.setText("Scanning application call sites and applying probes...");
        Async.run(() -> current.requestText(Operation.API_HOOK_START, payload), response -> {
            start.setEnabled(session == current);
            status.setForeground(Ui.SUCCESS);
            status.setText(response);
            poll();
        }, error -> {
            start.setEnabled(session == current);
            status.setForeground(Ui.WARNING);
            status.setText("API hook setup failed");
            Ui.error(this, error);
        });
    }

    private String hookDefinitions(List<String> selected) {
        StringBuilder output = new StringBuilder();
        for (String id : selected) {
            HookProfile profile = pluginProfiles.get(id);
            if (profile == null) continue;
            for (HookTarget target : profile.targets()) {
                output.append('\n').append('P').append('\t').append(encoded(profile.id())).append('\t')
                        .append(encoded(profile.name())).append('\t').append(encoded(target.owner())).append('\t')
                        .append(encoded(target.methodName()));
            }
        }
        return output.toString();
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private void stop() {
        InspectorSession current = session;
        if (current == null) return;
        stop.setEnabled(false);
        Async.run(() -> current.requestText(Operation.API_HOOK_STOP, ""), response -> {
            stop.setEnabled(session == current);
            profileModel.setRowCount(0);
            status.setForeground(Ui.MUTED);
            status.setText(response);
        }, error -> {
            stop.setEnabled(session == current);
            Ui.error(this, error);
        });
    }

    private void poll() {
        InspectorSession current = session;
        if (current == null || polling) return;
        polling = true;
        Async.run(() -> current.requestText(Operation.API_HOOK_EVENTS, ""), raw -> {
            polling = false;
            if (session == current) render(raw);
        }, error -> {
            polling = false;
            if (session == current) {
                status.setForeground(Ui.WARNING);
                status.setText("API hook polling failed: " + error.getMessage());
            }
        });
    }

    private void render(String raw) {
        List<ProfileStatus> statuses = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] values = line.split("\t", -1);
            if ("M".equals(values[0]) && values.length > 1 && statuses.isEmpty()) {
                status.setText(values[1]);
            } else if ("S".equals(values[0])) {
                ProfileStatus value = ProfileStatus.parse(values);
                if (value != null) statuses.add(value);
            } else if ("E".equals(values[0])) {
                ApiEvent value = ApiEvent.parse(values);
                if (value != null && !events.containsKey(value.sequence) && events.size() < MAX_LOCAL_EVENTS) {
                    events.put(value.sequence, value);
                    publishTimeline(value);
                    eventModel.addRow(new Object[]{value.sequence, TIME.format(Instant.ofEpochMilli(value.timestamp)),
                            displayProfile(value.profile), value.thread, displayCaller(value), displayApi(value)});
                }
            }
        }
        profileModel.setRowCount(0);
        long dropped = 0;
        for (ProfileStatus value : statuses) {
            profileModel.addRow(new Object[]{displayProfile(value.profile), value.active, value.calls,
                    value.captured, value.dropped, value.classes, value.sites,
                    TIME.format(Instant.ofEpochMilli(value.expiresAt))});
            dropped += value.dropped;
        }
        if (dropped > 100) {
            status.setForeground(new Color(255, 95, 86));
            status.setText("High-frequency API activity detected. " + dropped + " events were dropped by safety limits.");
        }
    }

    private void publishTimeline(ApiEvent event) {
        String caller = displayCaller(event) + event.callerDescriptor;
        String api = displayApi(event) + event.apiDescriptor;
        timeline.publish(new TimelineEvent("api-hook:" + event.sequence, event.timestamp, TimelineSource.API_HOOK,
                event.thread, "", "", api,
                "Profile: " + displayProfile(event.profile) + "\nApplication caller: " + caller
                        + "\nObserved API: " + api + "\nInvocation: " + event.invocationKind));
    }

    private void showSelected() {
        ApiEvent event = selectedEvent();
        if (event == null) return;
        details.setText("Event #" + event.sequence + "\nTime: "
                + TIME.format(Instant.ofEpochMilli(event.timestamp)) + "\nProfile: " + displayProfile(event.profile)
                + "\nThread: " + event.thread + "\nInvocation: " + event.invocationKind
                + "\n\nApplication caller:\n" + event.callerClass + "." + event.callerMethod
                + event.callerDescriptor + "\n\nObserved API:\n" + event.apiClass + "." + event.apiMethod
                + event.apiDescriptor + "\n\nDouble-click this event to inspect static and dynamic Xrefs for its caller.");
        details.setCaretPosition(0);
    }

    private void openCallerXrefs() {
        ApiEvent event = selectedEvent();
        if (event == null) return;
        xrefs.selectTarget(event.callerClass, event.callerClass, event.callerMethod, event.callerDescriptor);
        openXrefs.run();
    }

    private ApiEvent selectedEvent() {
        int row = eventTable.getSelectedRow();
        if (row < 0) return null;
        Object value = eventModel.getValueAt(row, 0);
        return value instanceof Number ? events.get(((Number) value).longValue()) : null;
    }

    private void clear() {
        events.clear();
        eventModel.setRowCount(0);
        details.setText("Select an event to inspect its caller and exact JVM descriptors. Double-click it to open Xrefs.");
    }

    private String displayCaller(ApiEvent event) {
        return workspace.classAlias(event.callerClass) + "."
                + workspace.methodAlias(event.callerClass, event.callerMethod, event.callerDescriptor);
    }

    private static String displayApi(ApiEvent event) {
        return event.apiClass + "." + event.apiMethod;
    }

    private static String displayProfile(String profile) {
        return profile.replace('_', ' ');
    }

    private static String decoded(String value) {
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            return "<invalid event data>";
        }
    }

    private static JSpinner spinner(int value, int minimum, int maximum, int step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
        spinner.setPreferredSize(new Dimension(100, 30));
        return spinner;
    }

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private record ProfileStatus(String profile, boolean active, long calls, long captured, long dropped,
                                 long expiresAt, int classes, int sites) {
        static ProfileStatus parse(String[] values) {
            if (values.length < 9) return null;
            try {
                return new ProfileStatus(values[1], Boolean.parseBoolean(values[2]), Long.parseLong(values[3]),
                        Long.parseLong(values[4]), Long.parseLong(values[5]), Long.parseLong(values[6]),
                        Integer.parseInt(values[7]), Integer.parseInt(values[8]));
            } catch (RuntimeException error) {
                return null;
            }
        }
    }

    private record ApiEvent(long sequence, long timestamp, String profile, String thread, String callerClass,
                            String callerMethod, String callerDescriptor, String apiClass, String apiMethod,
                            String apiDescriptor, String invocationKind) {
        static ApiEvent parse(String[] values) {
            if (values.length < 12) return null;
            try {
                return new ApiEvent(Long.parseLong(values[1]), Long.parseLong(values[2]), values[3], decoded(values[4]),
                        decoded(values[5]), decoded(values[6]), decoded(values[7]), decoded(values[8]),
                        decoded(values[9]), decoded(values[10]), values[11]);
            } catch (RuntimeException error) {
                return null;
            }
        }
    }
}

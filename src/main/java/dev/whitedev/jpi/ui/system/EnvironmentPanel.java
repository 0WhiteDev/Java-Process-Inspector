package dev.whitedev.jpi.ui.system;

import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.export.SessionSnapshotExporter;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.Locale;

public final class EnvironmentPanel extends JPanel implements SessionAware {
    private final JTextArea output = Ui.outputArea();
    private final RuntimeTimelineStore timeline;
    private final JButton refresh = Ui.primaryButton("Refresh");
    private final JButton export = Ui.secondaryButton("Export snapshot...");
    private InspectorSession session;

    public EnvironmentPanel() {
        this(new RuntimeTimelineStore());
    }

    public EnvironmentPanel(RuntimeTimelineStore timeline) {
        super(new BorderLayout(0, 16));
        this.timeline = timeline;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton copy = Ui.secondaryButton("Copy report");
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(output.getText()), null));
        refresh.addActionListener(event -> refresh());
        export.addActionListener(event -> exportSnapshot());
        actions.add(copy);
        actions.add(export);
        actions.add(refresh);
        add(Ui.sectionHeader("VM environment",
                "Launch arguments, system properties, and classloader inventory useful during analysis",
                actions), BorderLayout.NORTH);
        JPanel card = Ui.card(new BorderLayout());
        output.setText("Attach to a JVM to inspect its environment.");
        card.add(Ui.scroll(output), BorderLayout.CENTER);
        add(card, BorderLayout.CENTER);
    }

    @Override public void setSession(InspectorSession session) {
        this.session = session;
        refresh.setEnabled(session != null);
        export.setEnabled(session != null);
        if (session == null) {
            output.setText("Attach to a JVM to inspect its environment.");
        } else {
            refresh();
        }
    }

    private void refresh() {
        final InspectorSession current = session;
        if (current == null) return;
        refresh.setEnabled(false);
        output.setText("Collecting VM environment...");
        Async.run(() -> current.requestText(Operation.ENVIRONMENT, ""), value -> {
            output.setText(value);
            output.setCaretPosition(0);
            refresh.setEnabled(true);
        }, error -> {
            refresh.setEnabled(true);
            Ui.error(this, error);
        });
    }

    private void exportSnapshot() {
        InspectorSession current = session;
        if (current == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export JPI session snapshot");
        chooser.setSelectedFile(new File("jpi-snapshot-" + current.target().id() + ".zip"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File selected = chooser.getSelectedFile();
        if (!selected.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) selected = new File(selected.getParentFile(), selected.getName() + ".zip");
        final File destination = selected;
        refresh.setEnabled(false);
        output.setText("Collecting session snapshot...");
        Async.run(() -> SessionSnapshotExporter.export(current, destination.toPath()), path -> {
            if (session != current) return;
            refresh.setEnabled(true);
            output.setText("Snapshot exported to:\n" + path + "\n\n"
                    + "The archive contains metrics, environment, loaded classes, class events, and a thread dump.");
            long timestamp = System.currentTimeMillis();
            timeline.publish(new TimelineEvent("snapshot:" + timestamp + ":" + path, timestamp,
                    TimelineSource.SNAPSHOT, "", "", "", "Session snapshot exported",
                    "Target PID: " + current.target().id() + "\nArchive: " + path));
        }, error -> {
            if (session != current) return;
            refresh.setEnabled(true);
            Ui.error(this, error);
        });
    }
}

package dev.whitedev.jpi.ui;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.export.SessionSnapshotExporter;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.Locale;

final class EnvironmentPanel extends JPanel implements SessionAware {
    private final JTextArea output = Ui.outputArea();
    private final JButton refresh = Ui.primaryButton("Refresh");
    private final JButton export = Ui.secondaryButton("Export snapshot...");
    private InspectorSession session;

    EnvironmentPanel() {
        super(new BorderLayout(0, 16));
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
            refresh.setEnabled(true);
            output.setText("Snapshot exported to:\n" + path + "\n\n"
                    + "The archive contains metrics, environment, loaded classes, class events, and a thread dump.");
        }, error -> {
            refresh.setEnabled(true);
            Ui.error(this, error);
        });
    }
}

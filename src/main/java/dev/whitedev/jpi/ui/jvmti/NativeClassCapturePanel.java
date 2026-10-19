package dev.whitedev.jpi.ui.jvmti;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.layout.WrappingFlowLayout;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;

final class NativeClassCapturePanel extends JPanel {
    private final JButton start = Ui.secondaryButton("Start interception");
    private final JButton stop = Ui.secondaryButton("Stop interception");
    private final JButton refresh = Ui.secondaryButton("Refresh capture events");
    private final JButton save = Ui.secondaryButton("Save captured class...");
    private final JTextField sequence = new JTextField(12);
    private final JTextArea output = Ui.outputArea();
    private final JCheckBox archive = new JCheckBox("Save classes automatically on target");
    private final JTextField archiveDirectory = new JTextField(26);
    private final JSpinner archiveLimit = new JSpinner(new SpinnerNumberModel(256, 1, 16384, 64));
    private final Timer archiveRefresh = new Timer(1000, event -> request(Operation.JVMTI_CAPTURE_EVENTS));
    private InspectorSession session;
    private boolean busy;
    private long generation;

    NativeClassCapturePanel() {
        super(new BorderLayout(0, 8));
        JPanel actions = new JPanel(new WrappingFlowLayout(FlowLayout.LEFT, 8, 4));
        for (JButton button : List.of(start, stop, refresh)) actions.add(button);
        actions.add(new JLabel("Event sequence")); actions.add(sequence); actions.add(save);
        JPanel archiveOptions = new JPanel(new WrappingFlowLayout(FlowLayout.LEFT, 8, 4));
        archiveOptions.add(archive);
        archiveOptions.add(new JLabel("Target directory")); archiveOptions.add(archiveDirectory);
        archiveOptions.add(new JLabel("Max class data MiB")); archiveOptions.add(archiveLimit);
        JPanel top = new JPanel(new BorderLayout());
        top.add(actions, BorderLayout.NORTH); top.add(archiveOptions, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH); add(Ui.scroll(output), BorderLayout.CENTER);
        archive.addActionListener(event -> controls());
        start.addActionListener(event -> {
            if (!archive.isSelected()) { request(Operation.JVMTI_CAPTURE_START); return; }
            String directory = archiveDirectory.getText().trim();
            if (directory.isEmpty() || directory.indexOf('\n') >= 0 || directory.indexOf('\r') >= 0) {
                Ui.error(this, new IllegalArgumentException("Enter an absolute directory in the target filesystem")); return;
            }
            request(Operation.JVMTI_CAPTURE_ARCHIVE_START,
                    directory + "\n" + ((Number) archiveLimit.getValue()).longValue() * 1024 * 1024);
        });
        stop.addActionListener(event -> { archiveRefresh.stop(); request(Operation.JVMTI_CAPTURE_STOP); });
        refresh.addActionListener(event -> request(Operation.JVMTI_CAPTURE_EVENTS));
        save.addActionListener(event -> save());
        setSession(null);
    }
    void setSession(InspectorSession value) {
        archiveRefresh.stop();
        archive.setSelected(false);
        session = value; generation++; busy = false; sequence.setText("");
        output.setText("Bind the native backend, then start interception.\n"
                + "The buffer holds at most 256 events and 16 MiB of class bytes. Older events are evicted.\n"
                + "Automatic saving writes on the target filesystem in a new session directory.\n"
                + "Its bounded queue can drop classes during bursts. Check archive_dropped and archive_errors.\n"
                + "Stop retains buffered events. Disconnect stops capture and clears the buffer.\n"
                + "Captured bytes are the input seen by this JVMTI hook, not necessarily the final bytes after other agents.\n");
        controls();
    }
    private void request(Operation operation) {
        request(operation, "");
    }
    private void request(Operation operation, String payload) {
        if (busy || session == null) return;
        InspectorSession current = session; long epoch = generation;
        busy = true; controls();
        Async.run(() -> current.requestText(operation, payload), result -> {
            if (epoch != generation) return;
            if (operation == Operation.JVMTI_CAPTURE_ARCHIVE_START) archiveRefresh.start();
            if (operation == Operation.JVMTI_CAPTURE_START || result.contains("\narchive_active=0\n")) archiveRefresh.stop();
            busy = false; output.setText(display(result)); controls();
        }, error -> failed(epoch, error));
    }
    private void save() {
        if (busy || session == null) return;
        String id = sequence.getText().trim();
        try { if (Long.parseLong(id) < 1) throw new NumberFormatException(); }
        catch (NumberFormatException error) { Ui.error(this, new IllegalArgumentException("Enter a positive capture sequence")); return; }
        JFileChooser chooser = new JFileChooser();
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File file = chooser.getSelectedFile();
        if (file.exists() && JOptionPane.showConfirmDialog(this, "Replace " + file.getName() + "?", "Save captured class", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        InspectorSession current = session; long epoch = generation;
        busy = true; controls();
        Async.run(() -> {
            byte[] bytes = current.request(Operation.JVMTI_CAPTURE_BYTES, id);
            Files.write(file.toPath(), bytes);
            return "Saved " + bytes.length + " bytes to " + file.getAbsolutePath();
        }, result -> {
            if (epoch != generation) return;
            busy = false; output.setText(result); controls();
        }, error -> failed(epoch, error));
    }
    private void failed(long epoch, Throwable error) {
        if (epoch != generation) return;
        archiveRefresh.stop();
        busy = false; output.setText("Capture operation failed: " + error.getMessage()); controls();
    }
    private void controls() {
        for (JButton button : List.of(start, stop, refresh, save)) button.setEnabled(session != null && !busy);
        sequence.setEditable(!busy);
        archive.setEnabled(session != null && !busy);
        archiveDirectory.setEnabled(session != null && !busy && archive.isSelected());
        archiveLimit.setEnabled(session != null && !busy && archive.isSelected());
    }
    private static String display(String snapshot) {
        StringBuilder result = new StringBuilder();
        for (String line : snapshot.split("\n")) {
            if (line.startsWith("archive_directory_hex=")) {
                try {
                    String directory = new String(java.util.HexFormat.of().parseHex(line.substring("archive_directory_hex=".length())), java.nio.charset.StandardCharsets.UTF_8);
                    result.append("archive_directory=").append(directory).append('\n');
                    continue;
                } catch (IllegalArgumentException ignored) { }
            }
            String[] fields = line.split("\t", -1);
            if (fields.length == 4) {
                try {
                    byte[] bytes = Base64.getDecoder().decode(fields[3]);
                    if (bytes.length > 65535) throw new IOException("Name too long");
                    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                    DataOutputStream stream = new DataOutputStream(encoded);
                    stream.writeShort(bytes.length); stream.write(bytes);
                    String name = new DataInputStream(new ByteArrayInputStream(encoded.toByteArray())).readUTF();
                    result.append(fields[0]).append('\t').append(fields[1]).append('\t').append(fields[2]).append('\t').append(name).append('\n');
                    continue;
                } catch (IOException | IllegalArgumentException ignored) { }
            }
            result.append(line).append('\n');
        }
        return result.toString();
    }
}

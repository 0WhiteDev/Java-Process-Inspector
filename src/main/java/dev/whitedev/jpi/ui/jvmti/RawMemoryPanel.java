package dev.whitedev.jpi.ui.jvmti;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.Ui;
import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

final class RawMemoryPanel extends JPanel {
    private final JTextField address = new JTextField();
    private final JSpinner length = new JSpinner(new SpinnerNumberModel(64, 1, 1048576, 16));
    private final JTextField expected = new JTextField();
    private final JTextField replacement = new JTextField();
    private final JTextArea output = Ui.outputArea();
    private final JButton regions = Ui.secondaryButton("Memory regions");
    private final JButton read = Ui.secondaryButton("Read bytes");
    private final JButton patch = Ui.secondaryButton("Apply data patch");
    private final JButton undo = Ui.secondaryButton("Undo last patch");
    private InspectorSession session;
    private long generation;
    private boolean busy;
    private String inverse;

    RawMemoryPanel() {
        super(new BorderLayout(0, 8));
        JPanel fields = new JPanel(new GridLayout(0, 2, 8, 4));
        fields.add(new JLabel("Target address (hex)")); fields.add(address);
        fields.add(new JLabel("Bytes to read (max 1 MiB)")); fields.add(length);
        fields.add(new JLabel("Expected bytes (hex)")); fields.add(expected);
        fields.add(new JLabel("Replacement bytes (hex, same length)")); fields.add(replacement);
        JPanel actions = new JPanel();
        for (JButton button : List.of(regions, read, patch, undo)) actions.add(button);
        JPanel top = new JPanel(new BorderLayout());
        top.add(fields, BorderLayout.CENTER); top.add(actions, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH); add(Ui.scroll(output), BorderLayout.CENTER);
        regions.addActionListener(event -> request(Operation.MEMORY_REGIONS, "", false, null));
        read.addActionListener(event -> request(Operation.MEMORY_READ, address.getText().trim() + "\n" + length.getValue(), true, null));
        patch.addActionListener(event -> {
            try {
                byte[] before = hex(expected.getText()), after = hex(replacement.getText());
                if (before.length == 0 || before.length > 1048576 || before.length != after.length)
                    throw new IllegalArgumentException("Provide equal nonempty byte sequences, maximum 1 MiB");
                String target = address.getText().trim();
                String nextInverse = payload(target, after, before);
                request(Operation.MEMORY_PATCH, payload(target, before, after), false, nextInverse);
            } catch (IllegalArgumentException error) { Ui.error(this, error); }
        });
        undo.addActionListener(event -> { if (inverse != null) request(Operation.MEMORY_PATCH, inverse, false, ""); });
        setSession(null);
    }

    void setSession(InspectorSession value) {
        session = value; generation++; busy = false; inverse = null;
        expected.setText(""); replacement.setText(""); address.setText("");
        output.setText("Bind the native backend first. Addresses belong to the attached JVM.\n"
                + "Patches require readable, writable, non-executable data and matching expected bytes.\n"
                + "Application threads keep running: compare/write is not atomic. Pause target activity when needed.\n"
                + "Undo checks the replacement bytes before restoring. Only the last successful patch is retained.\n");
        controls();
    }

    private void request(Operation operation, String payload, boolean binary, String nextInverse) {
        if (session == null || busy) return;
        InspectorSession current = session;
        long epoch = generation;
        busy = true; controls();
        Async.run(() -> current.request(operation, payload), bytes -> {
            if (generation != epoch) return;
            busy = false;
            if (nextInverse != null) inverse = nextInverse.isEmpty() ? null : nextInverse;
            output.setText(binary ? HexFormat.ofDelimiter(" ").formatHex(bytes)
                    : new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (binary) expected.setText(HexFormat.of().formatHex(bytes));
            controls();
        }, error -> {
            if (generation != epoch) return;
            busy = false; controls();
            output.setText("Operation failed: " + error.getMessage() + "\nRe-read memory after any write failure; writes may be partial.\n");
        });
    }

    private void controls() {
        for (JButton button : List.of(regions, read, patch)) button.setEnabled(session != null && !busy);
        undo.setEnabled(session != null && !busy && inverse != null);
        address.setEditable(!busy); expected.setEditable(!busy); replacement.setEditable(!busy); length.setEnabled(!busy);
    }

    private static byte[] hex(String value) { return HexFormat.of().parseHex(value.replaceAll("\\s+", "")); }
    private static String payload(String address, byte[] before, byte[] after) {
        return address + "\n" + Base64.getEncoder().encodeToString(before) + "\n" + Base64.getEncoder().encodeToString(after);
    }
}

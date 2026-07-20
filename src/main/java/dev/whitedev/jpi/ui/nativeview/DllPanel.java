package dev.whitedev.jpi.ui.nativeview;

import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.nativeaccess.WindowsNativeAccess;
import dev.whitedev.jpi.protocol.Operation;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.util.Locale;

public final class DllPanel extends JPanel implements SessionAware {
    private final WindowsNativeAccess windows;
    private final JTextField pid = new JTextField();
    private final JTextField path = new JTextField();
    private final JButton inject = Ui.primaryButton("Load DLL");

    public DllPanel(WindowsNativeAccess windows) {
        super(new BorderLayout(0, 16));
        this.windows = windows;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);
        add(Ui.sectionHeader("DLL loader",
                "Optional Windows compatibility tool for loading a native library into a selected process", null),
                BorderLayout.NORTH);

        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        JPanel form = Ui.card(new GridBagLayout());
        form.setPreferredSize(new Dimension(720, 330));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(9, 9, 9, 9);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;

        JLabel title = new JLabel("Native library");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        form.add(title, constraints);

        JLabel description = new JLabel("JPI validates the file, process architecture, timeout, and LoadLibrary result.");
        description.setForeground(Ui.MUTED);
        constraints.gridy = 1;
        form.add(description, constraints);

        constraints.gridwidth = 1;
        constraints.gridy = 2;
        constraints.gridx = 0;
        constraints.weightx = 0;
        form.add(new JLabel("Target PID"), constraints);
        pid.setPreferredSize(new Dimension(140, 36));
        pid.putClientProperty("JTextField.placeholderText", "PID");
        constraints.gridx = 1;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        form.add(pid, constraints);

        constraints.gridy = 3;
        constraints.gridx = 0;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        form.add(new JLabel("DLL file"), constraints);
        path.putClientProperty("JTextField.placeholderText", "Choose a .dll file");
        constraints.gridx = 1;
        constraints.weightx = 1;
        form.add(path, constraints);
        JButton browse = Ui.secondaryButton("Browse...");
        browse.addActionListener(e -> browse());
        constraints.gridx = 2;
        constraints.weightx = 0;
        form.add(browse, constraints);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        inject.addActionListener(e -> inject());
        actions.add(inject);
        constraints.gridy = 4;
        constraints.gridx = 1;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        form.add(actions, constraints);

        JPanel warning = getJPanel();
        constraints.gridy = 5;
        constraints.gridx = 0;
        constraints.gridwidth = 3;
        form.add(warning, constraints);

        body.add(form);
        add(body, BorderLayout.CENTER);
    }

    private static JPanel getJPanel() {
        JPanel warning = new JPanel(new BorderLayout(10, 0));
        warning.setOpaque(false);
        JLabel warningTitle = new JLabel("Advanced operation");
        warningTitle.setForeground(Ui.WARNING);
        warningTitle.setFont(warningTitle.getFont().deriveFont(Font.BOLD));
        JLabel warningText = new JLabel("Prefer JVM Attach for Java inspection. The DLL must be trusted and match the target architecture.");
        warningText.setForeground(Ui.MUTED);
        warning.add(warningTitle, BorderLayout.WEST);
        warning.add(warningText, BorderLayout.CENTER);
        return warning;
    }

    @Override public void setSession(final InspectorSession session) {
        if (session == null) return;
        Async.run(() -> session.requestText(Operation.METRICS, ""), raw -> {
            for (String line : raw.split("\\n")) {
                if (line.startsWith("pid=")) pid.setText(line.substring(4));
            }
        }, error -> { });
    }

    private void browse() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            path.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void inject() {
        final int processId;
        try {
            processId = Integer.parseInt(pid.getText().trim());
        } catch (NumberFormatException error) {
            Ui.error(this, new IllegalArgumentException("Enter a valid PID"));
            return;
        }
        final File library = new File(path.getText());
        if (!library.isFile() || !library.getName().toLowerCase(Locale.ROOT).endsWith(".dll")) {
            Ui.error(this, new IllegalArgumentException("Choose an existing DLL file"));
            return;
        }
        int confirmation = JOptionPane.showConfirmDialog(this,
                "Load " + library.getName() + " into PID " + processId + "?\n"
                        + "Only continue when you trust the library and own the target process.",
                "Confirm DLL load", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirmation != JOptionPane.OK_OPTION) return;
        inject.setEnabled(false);
        inject.setText("Loading...");
        Async.run(() -> {
            windows.injectDll(processId, library);
            return true;
        }, ignored -> {
            inject.setEnabled(true);
            inject.setText("Load DLL");
            JOptionPane.showMessageDialog(this, "DLL loaded successfully.");
        }, error -> {
            inject.setEnabled(true);
            inject.setText("Load DLL");
            Ui.error(this, error);
        });
    }
}

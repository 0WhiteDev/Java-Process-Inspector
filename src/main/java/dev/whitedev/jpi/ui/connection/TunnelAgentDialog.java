package dev.whitedev.jpi.ui.connection;

import dev.whitedev.jpi.attach.AttachService;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;

public final class TunnelAgentDialog extends JDialog {
    private final AttachService service;
    private final JTextField remoteJar = new JTextField("jpi.jar");
    private final JTextField remotePid = new JTextField();
    private final JTextField sshTarget = new JTextField();
    private final JTextField displayName = new JTextField("Remote JVM");
    private final JSpinner agentPort = new JSpinner(new SpinnerNumberModel(43123, 1, 65535, 1));
    private final JSpinner localPort = new JSpinner(new SpinnerNumberModel(43123, 1, 65535, 1));
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(900, 10, 3600, 30));
    private final JTextArea earlyArgument = output();
    private final JTextArea lateAttachCommand = output();
    private final JTextArea sshCommand = output();
    private AttachService.PreparedTunnelAgent prepared;
    private ConnectionRequest result;

    private TunnelAgentDialog(JFrame owner, AttachService service) {
        super(owner, "Connect through tunnel", true);
        this.service = service;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(860, 650));
        setSize(930, 720);
        setLocationRelativeTo(owner);

        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBorder(new EmptyBorder(16, 18, 16, 18));
        root.add(Ui.sectionHeader("Agent server and tunneled client",
                "Start the agent on the target, forward its loopback port, then connect JPI to the local endpoint", null),
                BorderLayout.NORTH);
        root.add(form(), BorderLayout.CENTER);
        root.add(actions(), BorderLayout.SOUTH);
        setContentPane(root);

        DocumentListener update = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refreshCommands(); }
            @Override public void removeUpdate(DocumentEvent event) { refreshCommands(); }
            @Override public void changedUpdate(DocumentEvent event) { refreshCommands(); }
        };
        remoteJar.getDocument().addDocumentListener(update);
        remotePid.getDocument().addDocumentListener(update);
        sshTarget.getDocument().addDocumentListener(update);
        agentPort.addChangeListener(event -> regenerate());
        localPort.addChangeListener(event -> refreshCommands());
        timeout.addChangeListener(event -> regenerate());
        regenerate();
    }

    public static ConnectionRequest showDialog(JFrame owner, AttachService service) {
        TunnelAgentDialog dialog = new TunnelAgentDialog(owner, service);
        dialog.setVisible(true);
        return dialog.result;
    }

    private JPanel form() {
        JPanel panel = Ui.card(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(5, 6, 5, 6);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.weightx = 0;
        add(panel, constraints, 0, "JPI JAR on target", remoteJar, "Path used by the remote JVM or remote CLI");
        remotePid.putClientProperty("JTextField.placeholderText", "Required for late attach, optional for -javaagent");
        add(panel, constraints, 1, "Target PID", remotePid, "Used by the generated agent-server CLI command");
        sshTarget.putClientProperty("JTextField.placeholderText", "user@remote-host");
        add(panel, constraints, 2, "SSH destination", sshTarget, "Leave empty when another tunnel mechanism is used");
        add(panel, constraints, 3, "Display name", displayName, "Name shown in the JPI session");

        JPanel ports = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ports.setOpaque(false);
        ports.add(new JLabel("Agent port"));
        ports.add(agentPort);
        ports.add(new JLabel("Local forwarded port"));
        ports.add(localPort);
        ports.add(new JLabel("Wait seconds"));
        ports.add(timeout);
        constraints.gridx = 0;
        constraints.gridy = 4;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        panel.add(ports, constraints);

        addCommand(panel, constraints, 5, "Option A: start target with this JVM argument", earlyArgument,
                button("Copy argument", earlyArgument));
        addCommand(panel, constraints, 6, "Option B: late attach from the target machine", lateAttachCommand,
                button("Copy remote command", lateAttachCommand));
        addCommand(panel, constraints, 7, "Run this on the desktop machine", sshCommand,
                button("Copy SSH command", sshCommand));
        JLabel warning = new JLabel("The agent binds only to target loopback. Do not replace 127.0.0.1 with a public interface.");
        warning.setForeground(Ui.WARNING);
        constraints.gridy = 8;
        panel.add(warning, constraints);
        return panel;
    }

    private JPanel actions() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        JLabel hint = new JLabel("Start the agent and tunnel first. Connect consumes the one-use token.");
        hint.setForeground(Ui.MUTED);
        panel.add(hint, BorderLayout.WEST);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JButton regenerate = Ui.secondaryButton("New token");
        JButton cancel = Ui.secondaryButton("Cancel");
        JButton connect = Ui.primaryButton("Connect");
        regenerate.addActionListener(event -> regenerate());
        cancel.addActionListener(event -> dispose());
        connect.addActionListener(event -> connect());
        buttons.add(regenerate);
        buttons.add(cancel);
        buttons.add(connect);
        panel.add(buttons, BorderLayout.EAST);
        return panel;
    }

    private void connect() {
        try {
            int port = ((Number) localPort.getValue()).intValue();
            String pid = remotePid.getText().trim();
            result = new ConnectionRequest(port, prepared.token(), pid, displayName.getText().trim());
            dispose();
        } catch (RuntimeException error) {
            Ui.error(this, error);
        }
    }

    private void regenerate() {
        prepared = service.prepareTunnelAgent(((Number) agentPort.getValue()).intValue(),
                ((Number) timeout.getValue()).intValue());
        refreshCommands();
    }

    private void refreshCommands() {
        if (prepared == null) return;
        String jar = remoteJar.getText().trim().isEmpty() ? "jpi.jar" : remoteJar.getText().trim();
        String rawArgument = "-javaagent:" + jar + "=" + prepared.options();
        earlyArgument.setText(jar.indexOf(' ') >= 0 ? '"' + rawArgument + '"' : rawArgument);
        String pid = remotePid.getText().trim().isEmpty() ? "<pid>" : remotePid.getText().trim();
        lateAttachCommand.setText("java -jar " + quoted(jar) + " agent-server --pid " + pid
                + " --port " + agentPort.getValue() + " --token " + prepared.token()
                + " --timeout " + timeout.getValue());
        String destination = sshTarget.getText().trim().isEmpty() ? "<user@remote-host>" : sshTarget.getText().trim();
        sshCommand.setText("ssh -N -L " + localPort.getValue() + ":127.0.0.1:"
                + agentPort.getValue() + " " + destination);
    }

    private static void add(JPanel panel, GridBagConstraints constraints, int row, String label,
                            JTextField field, String tooltip) {
        constraints.gridy = row;
        constraints.gridwidth = 1;
        constraints.gridx = 0;
        constraints.weightx = 0;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        field.setToolTipText(tooltip);
        panel.add(field, constraints);
        constraints.gridx = 2;
        constraints.weightx = 0;
        JLabel help = new JLabel(tooltip);
        help.setForeground(Ui.MUTED);
        panel.add(help, constraints);
    }

    private static void addCommand(JPanel panel, GridBagConstraints constraints, int row, String label,
                                   JTextArea command, JButton copy) {
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(new JLabel(label), BorderLayout.WEST);
        header.add(copy, BorderLayout.EAST);
        JPanel value = new JPanel(new BorderLayout(0, 4));
        value.setOpaque(false);
        value.add(header, BorderLayout.NORTH);
        value.add(Ui.scroll(command), BorderLayout.CENTER);
        panel.add(value, constraints);
    }

    private static JButton button(String label, JTextArea value) {
        JButton button = Ui.secondaryButton(label);
        button.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(value.getText()), null));
        return button;
    }

    private static JTextArea output() {
        JTextArea value = Ui.outputArea();
        value.setRows(2);
        value.setLineWrap(true);
        value.setWrapStyleWord(false);
        return value;
    }

    private static String quoted(String value) {
        return value.indexOf(' ') < 0 ? value : '"' + value.replace("\"", "\\\"") + '"';
    }

    public record ConnectionRequest(int localPort, String token, String pid, String displayName) {}
}

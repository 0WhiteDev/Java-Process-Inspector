package dev.whitedev.jpi.ui.commands;

import dev.whitedev.jpi.ui.Ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public final class CommandPaletteDialog extends JDialog {
    private final CommandRegistry registry;
    private final JTextField query = new JTextField();
    private final DefaultListModel<WorkspaceCommand> model = new DefaultListModel<>();
    private final JList<WorkspaceCommand> results = new JList<>(model);
    private final JLabel status = new JLabel("Type a command, then press Enter");
    private final JLabel selection = new JLabel();
    private CommandContext context;

    public CommandPaletteDialog(Window owner, CommandRegistry registry) {
        super(owner, "Command Palette", ModalityType.APPLICATION_MODAL);
        this.registry = registry;
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setMinimumSize(new Dimension(580, 340));
        setSize(820, 470);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        query.putClientProperty("JTextField.placeholderText", "> trace, breakpoint, search, or a view name");
        query.addActionListener(event -> execute());
        query.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                filter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                filter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                filter();
            }
        });
        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        results.setFixedCellHeight(40);
        results.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
                WorkspaceCommand command = (WorkspaceCommand) value;
                String reason = command.reason(context);
                JLabel label = (JLabel) super.getListCellRendererComponent(list, command.title()
                        + (command.shortcutLabel().isBlank() ? "" : "    " + command.shortcutLabel())
                        + (reason.isEmpty() ? "" : "    [" + reason + "]"), index, selected, focused);
                label.putClientProperty("html.disable", Boolean.TRUE);
                if (!reason.isEmpty()) label.setForeground(Ui.MUTED);
                return label;
            }
        });
        results.addListSelectionListener(event -> {
            WorkspaceCommand command = results.getSelectedValue();
            if (command != null)
                status.setText(command.reason(context).isEmpty() ? command.title() : command.reason(context));
        });
        results.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) execute();
            }
        });
        JPanel header = new JPanel(new BorderLayout(0, 8));
        selection.putClientProperty("html.disable", Boolean.TRUE);
        selection.setForeground(Ui.MUTED);
        header.add(query, BorderLayout.NORTH);
        header.add(selection, BorderLayout.SOUTH);
        content.add(header, BorderLayout.NORTH);
        content.add(Ui.scroll(results), BorderLayout.CENTER);
        content.add(status, BorderLayout.SOUTH);
        setContentPane(content);
        bind(query, "DOWN", () -> move(1));
        bind(query, "UP", () -> move(-1));
        bind(results, "ENTER", this::execute);
        getRootPane().registerKeyboardAction(event -> setVisible(false), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    public void open(CommandContext context) {
        this.context = context;
        String target = context.target() == null ? "No JVM symbol selected" : context.target().qualifiedJvmIdentifier();
        if (target.isBlank()) target = "No exact JVM identifier selected";
        selection.setText(context.view() + " | " + target);
        selection.setToolTipText(target);
        query.setText("");
        filter();
        setLocationRelativeTo(getOwner());
        SwingUtilities.invokeLater(query::requestFocusInWindow);
        setVisible(true);
    }

    private void filter() {
        if (context == null) return;
        model.clear();
        for (WorkspaceCommand command : registry.search(query.getText(), context)) model.addElement(command);
        if (!model.isEmpty()) results.setSelectedIndex(0);
        else status.setText("No matching command");
    }

    private void move(int amount) {
        if (model.isEmpty()) return;
        int row = Math.max(0, Math.min(model.size() - 1, results.getSelectedIndex() + amount));
        results.setSelectedIndex(row);
        results.ensureIndexIsVisible(row);
    }

    private void execute() {
        WorkspaceCommand command = results.getSelectedValue();
        if (command == null) return;
        String reason = command.reason(context);
        if (!reason.isEmpty()) {
            status.setText(reason);
            return;
        }
        CommandContext selected = context;
        setVisible(false);
        SwingUtilities.invokeLater(() -> {
            String unavailable = registry.execute(command, selected);
            if (!unavailable.isEmpty()) Ui.error(getOwner(), new IllegalStateException(unavailable));
        });
    }

    private static void bind(JComponent component, String key, Runnable action) {
        component.getInputMap().put(KeyStroke.getKeyStroke(key), "palette-" + key);
        component.getActionMap().put("palette-" + key, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }
}

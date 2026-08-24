package dev.whitedev.jpi.ui.plugins;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.plugin.api.export.ExportRequest;
import dev.whitedev.jpi.plugin.api.export.PluginExporter;
import dev.whitedev.jpi.plugin.runtime.PluginDescriptor;
import dev.whitedev.jpi.plugin.runtime.PluginManager;
import dev.whitedev.jpi.plugin.runtime.RegisteredExtension;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class PluginsPanel extends JPanel implements SessionAware {
    private final PluginManager manager;
    private final PluginTableModel model = new PluginTableModel();
    private final JTable table = new JTable(model);
    private final JTextArea details = Ui.outputArea();
    private final JButton reload = Ui.primaryButton("Reload plugins");
    private final JButton install = Ui.secondaryButton("Install JAR...");
    private final JButton directory = Ui.secondaryButton("Open directory");
    private final JButton export = Ui.secondaryButton("Run exporter...");
    private final JLabel status = new JLabel();

    public PluginsPanel(PluginManager manager) {
        super(new BorderLayout(0, 14));
        this.manager = manager;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(directory);
        actions.add(install);
        actions.add(export);
        actions.add(reload);
        add(Ui.sectionHeader("Plugin API", "Load versioned JPI extensions from isolated plugin JAR classloaders", actions),
                BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(25);
        table.setFillsViewportHeight(true);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelected();
        });
        details.setText("Select a plugin to inspect its status and registered extensions.");
        JPanel detailCard = Ui.card(new BorderLayout(0, 8));
        JLabel title = new JLabel("Plugin details");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 13f));
        detailCard.add(title, BorderLayout.NORTH);
        detailCard.add(Ui.scroll(details), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.scroll(table), detailCard);
        split.setResizeWeight(.68);
        split.setDividerLocation(780);
        split.setBorder(null);

        JPanel body = Ui.card(new BorderLayout(0, 10));
        JLabel warning = new JLabel("Plugins execute local code with the same operating-system permissions as JPI. Install only trusted JARs.");
        warning.setForeground(Ui.WARNING);
        body.add(warning, BorderLayout.NORTH);
        body.add(split, BorderLayout.CENTER);
        status.setForeground(Ui.MUTED);
        body.add(status, BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);

        reload.addActionListener(event -> reload());
        install.addActionListener(event -> install());
        directory.addActionListener(event -> openDirectory());
        export.addActionListener(event -> runExporter());
        manager.addListener(() -> SwingUtilities.invokeLater(this::refresh));
        manager.extensions().addListener(() -> SwingUtilities.invokeLater(this::refresh));
        refresh();
    }

    @Override public void setSession(InspectorSession session) {}

    private void refresh() {
        model.setValues(manager.descriptors());
        int loaded = 0;
        int failed = 0;
        for (PluginDescriptor value : manager.descriptors()) {
            if (value.status() == PluginDescriptor.Status.LOADED) loaded++;
            if (value.status() == PluginDescriptor.Status.FAILED) failed++;
        }
        export.setEnabled(!manager.extensions().exporters().isEmpty());
        status.setText(loaded + " loaded  |  " + failed + " failed  |  " + manager.pluginDirectory());
        if (table.getSelectedRow() < 0) details.setText(extensionSummary());
    }

    private void reload() {
        reload.setEnabled(false);
        status.setText("Reloading plugin JARs...");
        Async.run(() -> {
            manager.reload();
            return manager.descriptors();
        }, values -> {
            reload.setEnabled(true);
            refresh();
        }, error -> {
            reload.setEnabled(true);
            Ui.error(this, error);
        });
    }

    private void install() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JPI plugin JAR", "jar"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        int confirmed = JOptionPane.showConfirmDialog(this,
                "The selected plugin can execute arbitrary local code with JPI permissions. Install and load it?",
                "Install trusted plugin", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirmed != JOptionPane.OK_OPTION) return;
        try {
            manager.install(chooser.getSelectedFile().toPath());
            reload();
        } catch (IOException error) {
            Ui.error(this, error);
        }
    }

    private void openDirectory() {
        try {
            java.nio.file.Files.createDirectories(manager.pluginDirectory());
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new IOException("Opening directories is not supported on this system");
            }
            Desktop.getDesktop().open(manager.pluginDirectory().toFile());
        } catch (Exception error) {
            Ui.error(this, error);
        }
    }

    private void runExporter() {
        List<ExporterOption> values = manager.extensions().exporters().stream()
                .map(ExporterOption::new).toList();
        ExporterOption selected = (ExporterOption) JOptionPane.showInputDialog(this, "Choose an exporter",
                "Plugin exporters", JOptionPane.PLAIN_MESSAGE, null, values.toArray(), null);
        if (selected == null) return;
        JFileChooser chooser = new JFileChooser();
        String extension = selected.value.extension().fileExtension().replace(".", "").trim();
        chooser.setSelectedFile(new java.io.File("jpi-export" + (extension.isEmpty() ? "" : "." + extension)));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path destination = chooser.getSelectedFile().toPath();
        if (java.nio.file.Files.exists(destination) && JOptionPane.showConfirmDialog(this,
                "Replace the existing file?\n" + destination, "Confirm export",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        Optional<dev.whitedev.jpi.plugin.api.JpiSession> session = manager.context(selected.value.pluginId())
                .flatMap(dev.whitedev.jpi.plugin.api.JpiContext::session);
        export.setEnabled(false);
        Async.run(() -> {
            selected.value.extension().export(new ExportRequest(destination, session));
            return destination;
        }, output -> {
            export.setEnabled(true);
            status.setText("Exported with " + selected + " to " + output);
        }, error -> {
            export.setEnabled(true);
            Ui.error(this, error);
        });
    }

    private void showSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        PluginDescriptor value = model.value(table.convertRowIndexToModel(row));
        StringBuilder output = new StringBuilder();
        output.append("Name: ").append(value.name()).append('\n');
        output.append("ID: ").append(value.id().isBlank() ? "unavailable" : value.id()).append('\n');
        output.append("Version: ").append(value.version()).append('\n');
        output.append("Status: ").append(value.status()).append('\n');
        output.append("Extensions: ").append(value.extensions()).append('\n');
        output.append("JAR: ").append(value.source()).append("\n\n");
        output.append(value.message()).append("\n\n").append(extensionSummary(value.id()));
        details.setText(output.toString());
        details.setCaretPosition(0);
    }

    private String extensionSummary() {
        return "Registered extensions\n\n" + extensionSummary("");
    }

    private String extensionSummary(String pluginId) {
        StringBuilder output = new StringBuilder();
        append(output, "Tabs", manager.extensions().tabs(), pluginId);
        append(output, "Bytecode analyzers", manager.extensions().analyzers(), pluginId);
        append(output, "Decompilers", manager.extensions().decompilers(), pluginId);
        append(output, "Deobfuscators", manager.extensions().deobfuscators(), pluginId);
        append(output, "Exporters", manager.extensions().exporters(), pluginId);
        append(output, "Hook profiles", manager.extensions().hookProfiles(), pluginId);
        return output.length() == 0 ? "No extensions registered" : output.toString();
    }

    private static void append(StringBuilder output, String title, List<? extends RegisteredExtension<?>> values,
                               String pluginId) {
        int count = 0;
        for (RegisteredExtension<?> value : values) if (pluginId.isEmpty() || pluginId.equals(value.pluginId())) count++;
        if (count > 0) output.append(title).append(": ").append(count).append('\n');
    }

    private static final class ExporterOption {
        final RegisteredExtension<PluginExporter> value;

        ExporterOption(RegisteredExtension<PluginExporter> value) {
            this.value = value;
        }

        @Override public String toString() {
            return value.extension().name() + "  [" + value.pluginId() + "]";
        }
    }

    private static final class PluginTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Name", "Version", "Status", "Extensions", "JAR"};
        private List<PluginDescriptor> values = List.of();

        void setValues(List<PluginDescriptor> values) {
            this.values = List.copyOf(values);
            fireTableDataChanged();
        }

        PluginDescriptor value(int row) { return values.get(row); }
        @Override public int getRowCount() { return values.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public Object getValueAt(int row, int column) {
            PluginDescriptor value = values.get(row);
            return switch (column) {
                case 0 -> value.name();
                case 1 -> value.version();
                case 2 -> value.status();
                case 3 -> value.extensions();
                case 4 -> value.source().getFileName();
                default -> "";
            };
        }
    }
}

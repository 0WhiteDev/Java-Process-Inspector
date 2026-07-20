package dev.whitedev.jpi.ui.workspace;

import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.deobfuscation.MappingEntry;
import dev.whitedev.jpi.deobfuscation.io.MappingFormats;
import dev.whitedev.jpi.deobfuscation.MappingInventory;
import dev.whitedev.jpi.deobfuscation.MappingKind;
import dev.whitedev.jpi.deobfuscation.search.MappingSearchQuery;
import dev.whitedev.jpi.protocol.Operation;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class DeobfuscationWorkspacePanel extends JPanel implements SessionAware {
    private final DeobfuscationWorkspace workspace;
    private final JTextField scope = new JTextField();
    private final JTextField exclusions = new JTextField();
    private final JCheckBox defaultPackageOnly = new JCheckBox("Default package only");
    private final JTextField filter = new JTextField();
    private final JComboBox<Object> kindFilter = new JComboBox<>();
    private final JCheckBox mapClasses = new JCheckBox("Classes", true);
    private final JCheckBox mapMethods = new JCheckBox("Methods", true);
    private final JCheckBox mapFields = new JCheckBox("Fields", true);
    private final JCheckBox mapParameters = new JCheckBox("Parameters", true);
    private final JCheckBox mapPackages = new JCheckBox("Packages", false);
    private final JButton load = Ui.secondaryButton("Load scope");
    private final JButton autoMap = Ui.primaryButton("AutoMap");
    private final JLabel status = new JLabel("Enter a package prefix");
    private final MappingTableModel model = new MappingTableModel();
    private final JTable table = new JTable(model);
    private final javax.swing.Timer saveTimer = new javax.swing.Timer(600, event -> saveDefault());
    private final Path defaultFile = Path.of(System.getProperty("user.home"), ".jpi", "deobfuscation-workspace.json");
    private InspectorSession session;

    public DeobfuscationWorkspacePanel(DeobfuscationWorkspace workspace) {
        super(new BorderLayout(0, 16));
        this.workspace = workspace;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);
        saveTimer.setRepeats(false);
        workspace.addListener(() -> SwingUtilities.invokeLater(() -> {
            refreshRows();
            saveTimer.restart();
        }));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton importMapping = Ui.secondaryButton("Import JSON");
        JButton exportMapping = Ui.secondaryButton("Export...");
        importMapping.addActionListener(event -> importJson());
        exportMapping.addActionListener(event -> export());
        actions.add(importMapping);
        actions.add(exportMapping);
        add(Ui.sectionHeader("Deobfuscation workspace",
                "Build a persistent naming layer without modifying the running target", actions),
                BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);
        body.add(configuration(), BorderLayout.NORTH);
        body.add(mappingTable(), BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);
        loadDefault();
        setSession(null);
    }

    @Override public void setSession(InspectorSession value) {
        session = value;
        load.setEnabled(value != null);
        autoMap.setEnabled(value != null);
        if (value == null) status.setText("Workspace available offline, attach to load a new scope");
        else status.setText(model.getRowCount() + " workspace entries loaded");
    }

    private JPanel configuration() {
        JPanel card = Ui.card(new BorderLayout(0, 10));
        JPanel first = new JPanel(new BorderLayout(8, 0));
        first.setOpaque(false);
        scope.putClientProperty("JTextField.placeholderText", "Package or class prefix, e.g. a.b or com.game");
        exclusions.putClientProperty("JTextField.placeholderText", "Excluded packages, comma separated");
        scope.setPreferredSize(new Dimension(300, 34));
        exclusions.setPreferredSize(new Dimension(300, 34));
        first.add(labeled("Scope", scope), BorderLayout.WEST);
        first.add(labeled("Exclude", exclusions), BorderLayout.CENTER);
        JPanel scopeActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        scopeActions.setOpaque(false);
        load.addActionListener(event -> loadInventory(false));
        autoMap.addActionListener(event -> loadInventory(true));
        scopeActions.add(load);
        scopeActions.add(autoMap);
        first.add(scopeActions, BorderLayout.EAST);
        card.add(first, BorderLayout.NORTH);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        options.setOpaque(false);
        defaultPackageOnly.setToolTipText("Select only classes whose binary name contains no package separator");
        defaultPackageOnly.addActionListener(event -> {
            boolean regularScope = !defaultPackageOnly.isSelected();
            scope.setEnabled(regularScope);
            exclusions.setEnabled(regularScope);
            if (!regularScope) status.setText("Only classes from the default package will be loaded");
        });
        options.add(defaultPackageOnly);
        options.add(Box.createHorizontalStrut(8));
        options.add(new JLabel("AutoMap:"));
        options.add(mapClasses);
        options.add(mapMethods);
        options.add(mapFields);
        options.add(mapParameters);
        options.add(mapPackages);
        status.setForeground(Ui.MUTED);
        card.add(options, BorderLayout.CENTER);
        card.add(status, BorderLayout.SOUTH);
        return card;
    }

    private JPanel mappingTable() {
        JPanel card = Ui.card(new BorderLayout(0, 10));
        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setOpaque(false);
        filter.putClientProperty("JTextField.placeholderText",
                "Text or class=abc field=a,b,c method=l,p,av1");
        filter.putClientProperty("JTextField.showClearButton", true);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refreshRows(); }
            @Override public void removeUpdate(DocumentEvent event) { refreshRows(); }
            @Override public void changedUpdate(DocumentEvent event) { refreshRows(); }
        });
        kindFilter.addItem("All kinds");
        for (MappingKind kind : MappingKind.values()) kindFilter.addItem(kind);
        kindFilter.addActionListener(event -> refreshRows());
        JPanel filters = new JPanel(new BorderLayout(8, 0));
        filters.setOpaque(false);
        filters.add(filter, BorderLayout.CENTER);
        filters.add(kindFilter, BorderLayout.EAST);
        JLabel syntax = new JLabel("Structured search: class=... field=... method=... package=... parameter=...   Use * as wildcard");
        syntax.setForeground(Ui.MUTED);
        syntax.setBorder(new EmptyBorder(4, 2, 0, 0));
        filters.add(syntax, BorderLayout.SOUTH);
        toolbar.add(filters, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton addPackage = Ui.secondaryButton("Add package");
        JButton color = Ui.secondaryButton("Set color");
        JButton remove = Ui.secondaryButton("Remove");
        JButton clear = Ui.secondaryButton("Clear");
        addPackage.addActionListener(event -> addPackage());
        color.addActionListener(event -> chooseColor());
        remove.addActionListener(event -> removeSelected());
        clear.addActionListener(event -> clear());
        actions.add(addPackage);
        actions.add(color);
        actions.add(remove);
        actions.add(clear);
        toolbar.add(actions, BorderLayout.EAST);
        card.add(toolbar, BorderLayout.NORTH);

        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        table.setDefaultRenderer(Object.class, new MappingRenderer());
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(250);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.getColumnModel().getColumn(3).setPreferredWidth(250);
        table.getColumnModel().getColumn(4).setPreferredWidth(140);
        table.getColumnModel().getColumn(5).setPreferredWidth(120);
        table.getColumnModel().getColumn(6).setPreferredWidth(75);
        table.getColumnModel().getColumn(7).setPreferredWidth(280);
        card.add(Ui.scroll(table), BorderLayout.CENTER);
        return card;
    }

    private void loadInventory(boolean applyAutoMap) {
        InspectorSession current = session;
        boolean defaultOnly = defaultPackageOnly.isSelected();
        String selectedScope = defaultOnly ? "<default>" : scope.getText().trim();
        if (current == null) return;
        if (selectedScope.isEmpty()) {
            Ui.error(this, new IllegalArgumentException("Enter a package or class prefix or select Default package only"));
            return;
        }
        load.setEnabled(false);
        autoMap.setEnabled(false);
        status.setText("Reading class, method, field, and parameter inventory...");
        String payload = selectedScope + "\n" + (defaultOnly ? "" : exclusions.getText().trim());
        Async.run(() -> MappingInventory.parse(
                current.requestText(Operation.DEOBFUSCATION_INVENTORY, payload)), inventory -> {
            if (applyAutoMap) {
                workspace.autoMap(inventory, mapClasses.isSelected(), mapMethods.isSelected(),
                        mapFields.isSelected(), mapParameters.isSelected(), mapPackages.isSelected());
            } else workspace.mergeInventory(inventory);
            String label = defaultOnly ? "the default package" : selectedScope;
            status.setText(inventory.entries().size() + " definitions loaded from " + label);
            load.setEnabled(true);
            autoMap.setEnabled(true);
        }, error -> {
            load.setEnabled(true);
            autoMap.setEnabled(true);
            status.setText("Inventory failed");
            Ui.error(this, error);
        });
    }

    private void addPackage() {
        JTextField original = new JTextField();
        JTextField mapped = new JTextField();
        JPanel form = new JPanel(new GridLayout(4, 1, 0, 5));
        form.add(new JLabel("Original package"));
        form.add(original);
        form.add(new JLabel("Mapped package"));
        form.add(mapped);
        if (JOptionPane.showConfirmDialog(this, form, "Add package mapping",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (original.getText().trim().isEmpty() || mapped.getText().trim().isEmpty()) return;
        if (!validMappedName(MappingKind.PACKAGE, mapped.getText().trim())) {
            Ui.error(this, new IllegalArgumentException("Package mappings must use valid qualified Java names"));
            return;
        }
        MappingEntry entry = new MappingEntry(MappingKind.PACKAGE, "", original.getText().trim(), "", -1, 0);
        entry.setMappedName(mapped.getText().trim());
        workspace.update(entry);
    }

    private void chooseColor() {
        List<MappingEntry> selected = selectedEntries();
        if (selected.isEmpty()) return;
        Color chosen = JColorChooser.showDialog(this, "Mapping color", Ui.ACCENT);
        if (chosen == null) return;
        String value = String.format("#%02X%02X%02X", chosen.getRed(), chosen.getGreen(), chosen.getBlue());
        for (MappingEntry entry : selected) {
            entry.setColor(value);
            workspace.update(entry);
        }
    }

    private void removeSelected() {
        List<MappingEntry> selected = selectedEntries();
        if (!selected.isEmpty()) workspace.remove(selected);
    }

    private void clear() {
        if (JOptionPane.showConfirmDialog(this, "Clear every mapping, note, tag, and color?",
                "Clear deobfuscation workspace", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) workspace.clear();
    }

    private void importJson() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JPI mapping workspace (*.json)", "json"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            List<MappingEntry> imported = MappingFormats.readJson(chooser.getSelectedFile().toPath());
            Object[] options = {"Merge", "Replace", "Cancel"};
            int choice = JOptionPane.showOptionDialog(this, "Import " + imported.size() + " entries?",
                    "Import mapping workspace", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, options, options[0]);
            if (choice == 0) workspace.merge(imported);
            else if (choice == 1) workspace.replace(imported);
        } catch (Exception error) {
            Ui.error(this, error);
        }
    }

    private void export() {
        JComboBox<MappingFormats.Format> format = new JComboBox<>(MappingFormats.Format.values());
        if (JOptionPane.showConfirmDialog(this, format, "Export format",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        MappingFormats.Format selected = (MappingFormats.Format) format.getSelectedItem();
        if (selected == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("jpi-mappings." + selected.extension()));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            MappingFormats.write(chooser.getSelectedFile().toPath(), selected, workspace.entries());
            status.setText("Exported " + selected + " to " + chooser.getSelectedFile().getName());
        } catch (Exception error) {
            Ui.error(this, error);
        }
    }

    private void loadDefault() {
        if (!Files.isRegularFile(defaultFile)) {
            refreshRows();
            return;
        }
        try {
            workspace.replace(MappingFormats.readJson(defaultFile));
            status.setText("Restored workspace from " + defaultFile);
        } catch (Exception error) {
            status.setText("Could not restore saved workspace: " + error.getMessage());
        }
    }

    private void saveDefault() {
        try {
            Files.createDirectories(defaultFile.getParent());
            Path temporary = defaultFile.resolveSibling(defaultFile.getFileName() + ".tmp");
            MappingFormats.write(temporary, MappingFormats.Format.JSON, workspace.entries());
            try {
                Files.move(temporary, defaultFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, defaultFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception error) {
            status.setText("Automatic save failed: " + error.getMessage());
        }
    }

    private void refreshRows() {
        MappingSearchQuery query = MappingSearchQuery.parse(filter.getText());
        Object selectedKind = kindFilter.getSelectedItem();
        List<MappingEntry> all = workspace.entries();
        List<MappingEntry> visible = new ArrayList<>();
        for (MappingEntry entry : all) {
            if (selectedKind instanceof MappingKind && entry.kind() != selectedKind) continue;
            if (query.matches(entry, workspace)) visible.add(entry);
        }
        model.setRows(visible);
        status.setText(visible.size() + " visible  |  " + all.size() + " total"
                + (query.structured() ? "  |  structured filter" : ""));
    }

    private List<MappingEntry> selectedEntries() {
        List<MappingEntry> selected = new ArrayList<>();
        for (int view : table.getSelectedRows()) {
            int row = table.convertRowIndexToModel(view);
            selected.add(model.entry(row));
        }
        return selected;
    }

    private static JPanel labeled(String text, JComponent component) {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setOpaque(false);
        panel.add(new JLabel(text), BorderLayout.WEST);
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private final class MappingTableModel extends AbstractTableModel {
        private final String[] columns = {
                "Kind", "Location", "Original", "Descriptor", "Mapped", "Tags", "Color", "Comment", "Enabled"
        };
        private List<MappingEntry> rows = new ArrayList<>();

        void setRows(List<MappingEntry> value) {
            rows = value;
            fireTableDataChanged();
        }

        MappingEntry entry(int row) {
            return rows.get(row);
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 8 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) { return column >= 4; }

        @Override public Object getValueAt(int row, int column) {
            MappingEntry entry = rows.get(row);
            if (column == 0) return entry.kind().name();
            if (column == 1) {
                if (entry.kind() == MappingKind.CLASS || entry.kind() == MappingKind.PACKAGE) {
                    return entry.originalName();
                }
                if (entry.kind() == MappingKind.PARAMETER) return entry.owner() + "." + entry.originalName();
                return entry.owner();
            }
            if (column == 2) return entry.kind() == MappingKind.PARAMETER
                    ? "arg" + entry.parameterIndex() : entry.originalName();
            if (column == 3) return entry.descriptor();
            if (column == 4) return entry.mappedName();
            if (column == 5) return entry.tags();
            if (column == 6) return entry.color();
            if (column == 7) return entry.comment();
            return entry.enabled();
        }

        @Override public void setValueAt(Object value, int row, int column) {
            MappingEntry entry = rows.get(row);
            if (column == 4) {
                String mapped = String.valueOf(value).trim();
                if (!validMappedName(entry.kind(), mapped)) {
                    Ui.error(DeobfuscationWorkspacePanel.this,
                            new IllegalArgumentException("Enter a valid Java name"
                                    + (entry.kind() == MappingKind.CLASS || entry.kind() == MappingKind.PACKAGE
                                    ? " or qualified name" : "")));
                    fireTableCellUpdated(row, column);
                    return;
                }
                entry.setMappedName(mapped);
            } else if (column == 5) entry.setTags(String.valueOf(value));
            else if (column == 6) {
                String color = String.valueOf(value).trim();
                if (!color.isEmpty() && !color.matches("#[0-9a-fA-F]{6}")) {
                    Ui.error(DeobfuscationWorkspacePanel.this,
                            new IllegalArgumentException("Color must use #RRGGBB format"));
                    fireTableCellUpdated(row, column);
                    return;
                }
                entry.setColor(color);
            }
            else if (column == 7) entry.setComment(String.valueOf(value));
            else if (column == 8) entry.setEnabled(Boolean.TRUE.equals(value));
            workspace.update(entry);
        }
    }

    private static boolean validMappedName(MappingKind kind, String value) {
        if (value.isEmpty()) return true;
        String[] parts = kind == MappingKind.CLASS || kind == MappingKind.PACKAGE
                ? value.split("\\.", -1) : new String[]{value};
        for (String part : parts) {
            if (!javax.lang.model.SourceVersion.isIdentifier(part)
                    || javax.lang.model.SourceVersion.isKeyword(part)) return false;
        }
        return true;
    }

    private final class MappingRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable source, Object value,
                boolean selected, boolean focused, int row, int column) {
            Component component = super.getTableCellRendererComponent(source, value, selected, focused, row, column);
            if (!selected) {
                component.setBackground(Ui.SURFACE);
                String color = model.entry(source.convertRowIndexToModel(row)).color();
                component.setForeground(parseColor(color));
            }
            return component;
        }

        private Color parseColor(String value) {
            try {
                return value != null && value.matches("#[0-9a-fA-F]{6}")
                        ? Color.decode(value) : Ui.TEXT;
            } catch (RuntimeException ignored) {
                return Ui.TEXT;
            }
        }
    }
}
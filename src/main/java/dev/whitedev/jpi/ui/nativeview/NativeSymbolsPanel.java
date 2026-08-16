package dev.whitedev.jpi.ui.nativeview;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.symbols.NativeSymbolService;
import dev.whitedev.jpi.symbols.model.DebugArtifact;
import dev.whitedev.jpi.symbols.model.NativeSymbol;
import dev.whitedev.jpi.symbols.model.SymbolKind;
import dev.whitedev.jpi.symbols.model.SymbolReport;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class NativeSymbolsPanel extends JPanel implements SessionAware {
    private static final String ALL_KINDS = "All kinds";

    private final NativeSymbolService service = new NativeSymbolService();
    private final JTextField binary = new JTextField();
    private final JTextField additional = new JTextField();
    private final JTextField search = new JTextField();
    private final JComboBox<Object> kind = new JComboBox<>();
    private final JButton analyze = Ui.primaryButton("Analyze symbols");
    private final JButton export = Ui.secondaryButton("Export CSV...");
    private final JLabel state = new JLabel("Choose a native binary or symbol file");
    private final JLabel symbolCount = metric("0");
    private final JLabel sourceCount = metric("0");
    private final JLabel artifactCount = metric("0");
    private final SymbolTableModel model = new SymbolTableModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<SymbolTableModel> sorter = new TableRowSorter<>(model);
    private final JTextArea details = Ui.outputArea();
    private SymbolReport report;

    public NativeSymbolsPanel() {
        super(new BorderLayout(0, 14));
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(export);
        actions.add(analyze);
        add(Ui.sectionHeader("Native symbols",
                "Resolve PE, ELF, PDB, DWARF, MAP, C++ names, source lines, RTTI, and virtual tables",
                actions), BorderLayout.NORTH);

        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);
        body.add(inputCard(), BorderLayout.NORTH);
        body.add(results(), BorderLayout.CENTER);
        body.add(footer(), BorderLayout.SOUTH);
        add(body, BorderLayout.CENTER);

        kind.addItem(ALL_KINDS);
        for (SymbolKind value : SymbolKind.values()) kind.addItem(value);
        table.setRowSorter(sorter);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(130);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(260);
        table.getColumnModel().getColumn(3).setPreferredWidth(340);
        table.getColumnModel().getColumn(4).setPreferredWidth(300);
        table.getColumnModel().getColumn(5).setPreferredWidth(120);
        DefaultTableCellRenderer addressRenderer = new DefaultTableCellRenderer();
        addressRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(0).setCellRenderer(addressRenderer);
        details.setLineWrap(false);
        details.setText("Analysis artifacts, warnings, and the selected symbol will appear here.");

        analyze.addActionListener(event -> analyze());
        export.addActionListener(event -> export());
        export.setEnabled(false);
        search.getDocument().addDocumentListener(new DocumentChange(this::filter));
        kind.addActionListener(event -> filter());
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelection();
        });
    }

    @Override public void setSession(InspectorSession session) {}

    private JPanel inputCard() {
        JPanel card = Ui.card(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(5, 5, 5, 5);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;

        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 0;
        card.add(new JLabel("Binary or symbols"), constraints);
        binary.putClientProperty("JTextField.placeholderText", "Executable, DLL, SO, PDB, or MAP file");
        constraints.gridx = 1;
        constraints.weightx = 1;
        card.add(binary, constraints);
        JButton browseBinary = Ui.secondaryButton("Browse...");
        browseBinary.addActionListener(event -> browseBinary());
        constraints.gridx = 2;
        constraints.weightx = 0;
        card.add(browseBinary, constraints);

        constraints.gridx = 0;
        constraints.gridy = 1;
        card.add(new JLabel("Additional sources"), constraints);
        additional.putClientProperty("JTextField.placeholderText", "Optional PDB, MAP, ELF debug files separated with ;");
        constraints.gridx = 1;
        constraints.weightx = 1;
        card.add(additional, constraints);
        JButton browseAdditional = Ui.secondaryButton("Add files...");
        browseAdditional.addActionListener(event -> browseAdditional());
        constraints.gridx = 2;
        constraints.weightx = 0;
        card.add(browseAdditional, constraints);

        JPanel filters = new JPanel(new BorderLayout(8, 0));
        filters.setOpaque(false);
        search.putClientProperty("JTextField.placeholderText", "Search address, raw name, demangled name, source, or provider");
        kind.setPreferredSize(new Dimension(150, 34));
        filters.add(search, BorderLayout.CENTER);
        filters.add(kind, BorderLayout.EAST);
        constraints.gridx = 0;
        constraints.gridy = 2;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        card.add(filters, constraints);

        JPanel metrics = new JPanel(new GridLayout(1, 3, 8, 0));
        metrics.setOpaque(false);
        metrics.add(metricCard("Symbols", symbolCount));
        metrics.add(metricCard("Source files", sourceCount));
        metrics.add(metricCard("Debug artifacts", artifactCount));
        constraints.gridy = 3;
        constraints.insets = new Insets(10, 5, 5, 5);
        card.add(metrics, constraints);
        return card;
    }

    private JSplitPane results() {
        JScrollPane symbols = Ui.scroll(table);
        JPanel detailCard = Ui.card(new BorderLayout(0, 8));
        JLabel title = new JLabel("Symbol details");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        detailCard.add(title, BorderLayout.NORTH);
        detailCard.add(Ui.scroll(details), BorderLayout.CENTER);
        detailCard.setMinimumSize(new Dimension(340, 200));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, symbols, detailCard);
        split.setResizeWeight(.72);
        split.setDividerLocation(850);
        split.setDividerSize(8);
        split.setBorder(null);
        return split;
    }

    private JPanel footer() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        state.setForeground(Ui.MUTED);
        panel.add(state, BorderLayout.CENTER);
        return panel;
    }

    private void analyze() {
        Path input;
        List<Path> extras;
        try {
            input = Path.of(binary.getText().trim());
            extras = additionalPaths();
        } catch (RuntimeException error) {
            Ui.error(this, new IllegalArgumentException("Choose valid symbol input paths", error));
            return;
        }
        if (!Files.isRegularFile(input)) {
            Ui.error(this, new IllegalArgumentException("Choose an existing binary or symbol file"));
            return;
        }
        analyze.setEnabled(false);
        analyze.setText("Analyzing...");
        state.setForeground(Ui.MUTED);
        state.setText("Reading symbols and debug metadata...");
        Async.run(() -> service.analyze(input, extras), value -> {
            report = value;
            model.setSymbols(value.symbols());
            symbolCount.setText(String.valueOf(value.symbols().size()));
            sourceCount.setText(String.valueOf(value.sourceFiles().size()));
            artifactCount.setText(String.valueOf(value.artifacts().size()));
            details.setText(summary(value));
            details.setCaretPosition(0);
            table.clearSelection();
            export.setEnabled(true);
            analyze.setEnabled(true);
            analyze.setText("Analyze symbols");
            state.setForeground(Ui.SUCCESS);
            state.setText(value.format() + " | " + value.architecture() + " | " + value.symbols().size() + " symbols");
            filter();
        }, error -> {
            analyze.setEnabled(true);
            analyze.setText("Analyze symbols");
            state.setForeground(Ui.WARNING);
            state.setText("Symbol analysis failed");
            Ui.error(this, error);
        });
    }

    private void filter() {
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        Object selectedKind = kind.getSelectedItem();
        sorter.setRowFilter(new RowFilter<>() {
            @Override public boolean include(Entry<? extends SymbolTableModel, ? extends Integer> entry) {
                NativeSymbol symbol = model.symbol(entry.getIdentifier());
                if (selectedKind instanceof SymbolKind value && symbol.kind() != value) return false;
                if (query.isEmpty()) return true;
                String searchable = (address(symbol.address()) + '\n' + symbol.rawName() + '\n' + symbol.displayName()
                        + '\n' + symbol.location() + '\n' + symbol.provider() + '\n' + symbol.kind())
                        .toLowerCase(Locale.ROOT);
                return searchable.contains(query);
            }
        });
    }

    private void showSelection() {
        int row = table.getSelectedRow();
        if (row < 0 || report == null) return;
        NativeSymbol symbol = model.symbol(table.convertRowIndexToModel(row));
        StringBuilder output = new StringBuilder();
        output.append("Address: ").append(address(symbol.address())).append('\n');
        output.append("Size: ").append(symbol.size()).append('\n');
        output.append("Kind: ").append(symbol.kind()).append('\n');
        output.append("Provider: ").append(symbol.provider()).append("\n\n");
        output.append("Raw name:\n").append(symbol.rawName()).append("\n\n");
        output.append("Demangled:\n").append(symbol.displayName()).append("\n\n");
        output.append("Source:\n").append(symbol.location().isEmpty() ? "not available" : symbol.location());
        details.setText(output.toString());
        details.setCaretPosition(0);
    }

    private void browseBinary() {
        JFileChooser chooser = chooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) binary.setText(chooser.getSelectedFile().getAbsolutePath());
    }

    private void browseAdditional() {
        JFileChooser chooser = chooser();
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        StringBuilder value = new StringBuilder(additional.getText().trim());
        for (java.io.File file : chooser.getSelectedFiles()) {
            if (value.length() > 0) value.append(';');
            value.append(file.getAbsolutePath());
        }
        additional.setText(value.toString());
    }

    private void export() {
        if (report == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("native-symbols.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path output = chooser.getSelectedFile().toPath();
        try {
            Files.writeString(output, csv(model.symbols), StandardCharsets.UTF_8);
            state.setForeground(Ui.SUCCESS);
            state.setText("Exported " + model.symbols.size() + " symbols to " + output);
        } catch (IOException error) {
            Ui.error(this, error);
        }
    }

    private List<Path> additionalPaths() {
        List<Path> values = new ArrayList<>();
        for (String value : additional.getText().split(";")) {
            if (!value.isBlank()) values.add(Path.of(value.trim()));
        }
        return values;
    }

    private static JFileChooser chooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Native binaries and symbols", "exe", "dll", "sys", "so", "dylib", "elf", "pdb", "map", "debug"));
        return chooser;
    }

    private static String summary(SymbolReport report) {
        StringBuilder output = new StringBuilder();
        output.append("Input: ").append(report.input()).append('\n');
        output.append("Format: ").append(report.format()).append('\n');
        output.append("Architecture: ").append(report.architecture()).append('\n');
        output.append("Symbols: ").append(report.symbols().size()).append('\n');
        output.append("Source references: ").append(report.sourceFiles().size()).append("\n\n");
        output.append("Debug artifacts:\n");
        if (report.artifacts().isEmpty()) output.append("none\n");
        for (DebugArtifact artifact : report.artifacts()) {
            output.append(artifact.type()).append(" | ").append(artifact.available() ? "available" : "missing")
                    .append(" | ").append(artifact.path()).append(" | ").append(artifact.identifier()).append('\n');
        }
        if (!report.warnings().isEmpty()) {
            output.append("\nNotes:\n");
            for (String warning : report.warnings()) output.append(warning).append('\n');
        }
        return output.toString();
    }

    private static String csv(List<NativeSymbol> symbols) {
        StringBuilder output = new StringBuilder("address,size,kind,raw_name,demangled,source,line,provider\r\n");
        for (NativeSymbol symbol : symbols) {
            output.append(csv(address(symbol.address()))).append(',').append(symbol.size()).append(',')
                    .append(symbol.kind()).append(',').append(csv(symbol.rawName())).append(',')
                    .append(csv(symbol.displayName())).append(',').append(csv(symbol.sourceFile())).append(',')
                    .append(symbol.sourceLine() > 0 ? symbol.sourceLine() : "").append(',')
                    .append(csv(symbol.provider())).append("\r\n");
        }
        return output.toString();
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String address(long value) {
        return value < 0L ? "" : "0x" + Long.toUnsignedString(value, 16).toUpperCase(Locale.ROOT);
    }

    private static JLabel metric(String value) {
        JLabel label = new JLabel(value);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 18f));
        return label;
    }

    private static JPanel metricCard(String name, JLabel value) {
        JPanel panel = new JPanel(new BorderLayout(8, 2));
        panel.setBackground(Ui.SURFACE_LIGHT);
        panel.setBorder(new EmptyBorder(8, 10, 8, 10));
        JLabel title = new JLabel(name);
        title.setForeground(Ui.MUTED);
        panel.add(title, BorderLayout.NORTH);
        panel.add(value, BorderLayout.CENTER);
        return panel;
    }

    private static final class SymbolTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Address", "Kind", "Raw name", "Demangled", "Source", "Provider"};
        private List<NativeSymbol> symbols = List.of();

        void setSymbols(List<NativeSymbol> values) {
            symbols = List.copyOf(values);
            fireTableDataChanged();
        }

        NativeSymbol symbol(int row) {
            return symbols.get(row);
        }

        @Override public int getRowCount() { return symbols.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }
        @Override public Object getValueAt(int row, int column) {
            NativeSymbol value = symbols.get(row);
            return switch (column) {
                case 0 -> address(value.address());
                case 1 -> value.kind();
                case 2 -> value.rawName();
                case 3 -> value.displayName();
                case 4 -> value.location();
                case 5 -> value.provider();
                default -> "";
            };
        }
    }

    private static final class DocumentChange implements javax.swing.event.DocumentListener {
        private final Runnable action;

        DocumentChange(Runnable action) { this.action = action; }
        @Override public void insertUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { action.run(); }
    }
}

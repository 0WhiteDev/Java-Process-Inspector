package dev.whitedev.jpi.ui;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.protocol.Operation;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class XrefsPanel extends JPanel implements SessionAware {
    private static final Color FAILED = new Color(255, 95, 86);
    private final JTextField className = new JTextField();
    private final JTextField method = new JTextField();
    private final JTextField descriptor = new JTextField();
    private final JTextField stringQuery = new JTextField();
    private final JButton refresh = Ui.primaryButton("Refresh Xrefs");
    private final JButton search = Ui.secondaryButton("Find string users");
    private final JLabel status = new JLabel("Select a method from Loaded classes");
    private final ReferenceTable calledBy = new ReferenceTable();
    private final ReferenceTable calls = new ReferenceTable();
    private final ReferenceTable data = new ReferenceTable();
    private final ReferenceTable stringUsers = new ReferenceTable();
    private final JTabbedPane tabs = new JTabbedPane();
    private InspectorSession session;
    private String classIdentifier = "";
    private long xrefGeneration;
    private long searchGeneration;

    XrefsPanel() {
        super(new BorderLayout(0, 16));
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        stringQuery.setPreferredSize(new Dimension(250, 34));
        stringQuery.putClientProperty("JTextField.placeholderText", "String, URL, endpoint...");
        stringQuery.addActionListener(event -> searchStrings());
        search.addActionListener(event -> searchStrings());
        refresh.addActionListener(event -> refresh());
        actions.add(stringQuery);
        actions.add(search);
        actions.add(refresh);
        add(Ui.sectionHeader("Xrefs and call graph",
                "Static bytecode references combined with calls observed by Live Tracer", actions),
                BorderLayout.NORTH);

        JPanel card = Ui.card(new BorderLayout(0, 12));
        JPanel target = new JPanel(new GridLayout(2, 3, 8, 5));
        target.setOpaque(false);
        target.add(label("Class"));
        target.add(label("Method"));
        target.add(label("Descriptor"));
        className.setEditable(false);
        method.setEditable(false);
        descriptor.setEditable(false);
        target.add(className);
        target.add(method);
        target.add(descriptor);
        card.add(target, BorderLayout.NORTH);

        tabs.addTab("Called by", calledBy.scroll());
        tabs.addTab("Calls", calls.scroll());
        tabs.addTab("Fields, types, constants", data.scroll());
        tabs.addTab("String users", stringUsers.scroll());
        card.add(tabs, BorderLayout.CENTER);

        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        legend.setOpaque(false);
        legend.add(key("Static", Ui.MUTED));
        legend.add(key("Executed", Ui.SUCCESS));
        legend.add(key("Reflection", Ui.WARNING));
        legend.add(key("Exception", FAILED));
        status.setForeground(Ui.MUTED);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(status, BorderLayout.CENTER);
        footer.add(legend, BorderLayout.EAST);
        card.add(footer, BorderLayout.SOUTH);
        add(card, BorderLayout.CENTER);
        setSession(null);
    }

    void selectTarget(String identifier, String owner, String methodName, String methodDescriptor) {
        classIdentifier = identifier;
        className.setText(owner);
        method.setText(methodName);
        descriptor.setText(methodDescriptor);
        status.setText("Ready to analyze " + owner + "." + methodName);
        refresh();
    }

    @Override public void setSession(InspectorSession value) {
        session = value;
        boolean attached = value != null;
        refresh.setEnabled(attached && !classIdentifier.isEmpty());
        search.setEnabled(attached);
        stringQuery.setEnabled(attached);
        clearTables();
        xrefGeneration++;
        searchGeneration++;
        if (!attached) {
            classIdentifier = "";
            className.setText("");
            method.setText("");
            descriptor.setText("");
            status.setText("Not attached");
        }
    }

    private void refresh() {
        InspectorSession current = session;
        if (current == null || classIdentifier.isEmpty()) return;
        refresh.setEnabled(false);
        status.setText("Scanning bytecode and merging observed calls...");
        String payload = classIdentifier + "\n" + method.getText() + "\n" + descriptor.getText();
        long generation = ++xrefGeneration;
        Async.run(() -> current.requestText(Operation.METHOD_XREFS, payload), raw -> {
            if (generation != xrefGeneration) return;
            renderXrefs(raw);
            refresh.setEnabled(true);
        }, error -> {
            if (generation != xrefGeneration) return;
            refresh.setEnabled(true);
            status.setText("Xref analysis failed");
            Ui.error(this, error);
        });
    }

    private void searchStrings() {
        InspectorSession current = session;
        String query = stringQuery.getText().trim();
        if (current == null) return;
        if (query.length() < 2) {
            status.setText("Enter at least two characters");
            return;
        }
        search.setEnabled(false);
        stringUsers.clear();
        tabs.setSelectedIndex(3);
        status.setText("Finding methods that load matching strings...");
        long generation = ++searchGeneration;
        Async.run(() -> current.requestText(Operation.XREF_SEARCH, query), raw -> {
            if (generation != searchGeneration) return;
            for (XrefRow row : parse(raw)) if ("STRING_USER".equals(row.relation)) stringUsers.add(row);
            status.setText(stringUsers.size() + " methods use matching strings");
            search.setEnabled(true);
        }, error -> {
            if (generation != searchGeneration) return;
            search.setEnabled(true);
            status.setText("String Xref search failed");
            Ui.error(this, error);
        });
    }

    private void renderXrefs(String raw) {
        calledBy.clear();
        calls.clear();
        data.clear();
        int staticCount = 0;
        int dynamicCount = 0;
        for (XrefRow row : parse(raw)) {
            if ("CALLED_BY".equals(row.relation)) calledBy.add(row);
            else if ("CALLS".equals(row.relation)) calls.add(row);
            else data.add(row);
            if ("STATIC".equals(row.layer)) staticCount++;
            else dynamicCount++;
        }
        status.setText(staticCount + " static references, " + dynamicCount + " observed edges");
    }

    private List<XrefRow> parse(String raw) {
        List<XrefRow> rows = new ArrayList<>();
        for (String line : raw.split("\\n")) {
            if (line.isEmpty()) continue;
            String[] values = line.split("\\t", -1);
            if (values.length != 9 || !"R".equals(values[0])) continue;
            try {
                rows.add(new XrefRow(values[1], values[2], Long.parseLong(values[3]),
                        decoded(values[4]), decoded(values[5]), decoded(values[6]),
                        decoded(values[7]), decoded(values[8])));
            } catch (RuntimeException ignored) {
            }
        }
        return rows;
    }

    private void navigate(XrefRow row) {
        if (row.className.isEmpty() || row.member.isEmpty() || "<dynamic>".equals(row.className)) return;
        classIdentifier = row.targetIdentifier.isEmpty() ? row.className : row.targetIdentifier;
        className.setText(row.className);
        method.setText(row.member);
        if (!row.descriptor.isEmpty()) {
            descriptor.setText(row.descriptor);
            refresh();
            return;
        }
        InspectorSession current = session;
        if (current == null) return;
        status.setText("Resolving method descriptor...");
        Async.run(() -> current.requestText(Operation.CLASS_METHODS, classIdentifier), raw -> {
            for (String line : raw.split("\\n")) {
                String[] values = line.split("\\t", -1);
                if (values.length == 4 && row.member.equals(values[0])) {
                    descriptor.setText(values[1]);
                    refresh();
                    return;
                }
            }
            status.setText("The target method is no longer available");
        }, error -> Ui.error(this, error));
    }

    private void clearTables() {
        calledBy.clear();
        calls.clear();
        data.clear();
        stringUsers.clear();
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Ui.MUTED);
        return label;
    }

    private static JLabel key(String text, Color color) {
        JLabel label = new JLabel("● " + text);
        label.setForeground(color);
        return label;
    }

    private static String decoded(String value) {
        if (value.isEmpty()) return "";
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private final class ReferenceTable {
        private final DefaultTableModel model = new DefaultTableModel(
                new Object[]{"Layer", "Class", "Member", "Descriptor", "Kind or value", "Hits"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        private final JTable table = new JTable(model);
        private final List<XrefRow> rows = new ArrayList<>();

        ReferenceTable() {
            table.setFillsViewportHeight(true);
            table.setAutoCreateRowSorter(true);
            table.setDefaultRenderer(Object.class, new LayerRenderer());
            table.getColumnModel().getColumn(0).setPreferredWidth(80);
            table.getColumnModel().getColumn(1).setPreferredWidth(240);
            table.getColumnModel().getColumn(2).setPreferredWidth(150);
            table.getColumnModel().getColumn(3).setPreferredWidth(300);
            table.getColumnModel().getColumn(4).setPreferredWidth(220);
            table.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() != 2) return;
                    int view = table.getSelectedRow();
                    if (view >= 0) navigate(rows.get(table.convertRowIndexToModel(view)));
                }
            });
        }

        JScrollPane scroll() {
            return Ui.scroll(table);
        }

        void add(XrefRow row) {
            rows.add(row);
            model.addRow(new Object[]{row.layer, row.className, row.member, row.descriptor, row.detail, row.count});
        }

        void clear() {
            rows.clear();
            model.setRowCount(0);
        }

        int size() {
            return rows.size();
        }

        private final class LayerRenderer extends DefaultTableCellRenderer {
            @Override public Component getTableCellRendererComponent(JTable source, Object value,
                    boolean selected, boolean focused, int row, int column) {
                Component component = super.getTableCellRendererComponent(
                        source, value, selected, focused, row, column);
                if (!selected) {
                    XrefRow reference = rows.get(source.convertRowIndexToModel(row));
                    component.setForeground(color(reference.layer));
                    component.setBackground(Ui.SURFACE);
                }
                return component;
            }

            private Color color(String layer) {
                if ("FAILED".equals(layer)) return FAILED;
                if ("REFLECTIVE".equals(layer)) return Ui.WARNING;
                if ("DYNAMIC".equals(layer)) return Ui.SUCCESS;
                return Ui.MUTED;
            }
        }
    }

    private static final class XrefRow {
        final String layer;
        final String relation;
        final long count;
        final String targetIdentifier;
        final String className;
        final String member;
        final String descriptor;
        final String detail;

        XrefRow(String layer, String relation, long count, String targetIdentifier,
                String className, String member, String descriptor, String detail) {
            this.layer = layer;
            this.relation = relation;
            this.count = count;
            this.targetIdentifier = targetIdentifier;
            this.className = className;
            this.member = member;
            this.descriptor = descriptor;
            this.detail = detail;
        }
    }
}
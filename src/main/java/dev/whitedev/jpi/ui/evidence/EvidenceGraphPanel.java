package dev.whitedev.jpi.ui.evidence;

import dev.whitedev.jpi.evidence.EvidenceStore.Edge;
import dev.whitedev.jpi.evidence.EvidenceStore.Graph;
import dev.whitedev.jpi.evidence.EvidenceStore.Kind;
import dev.whitedev.jpi.evidence.EvidenceStore.Node;
import dev.whitedev.jpi.evidence.EvidenceStore.Symbol;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.DefaultListModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class EvidenceGraphPanel extends JPanel {
    private final DefaultListModel<NodeItem> results = new DefaultListModel<>();
    private final DefaultListModel<Link> incoming = new DefaultListModel<>();
    private final DefaultListModel<Link> outgoing = new DefaultListModel<>();
    private final JList<NodeItem> nodes = new JList<>(results);
    private final JTextArea details = Ui.outputArea();
    private final JTextField query = new JTextField(22);
    private final JComboBox<String> kind = new JComboBox<>();
    private final JLabel status = new JLabel("No evidence loaded.");
    private final JLabel focus = new JLabel("Select evidence");
    private final JButton refresh = Ui.secondaryButton("Refresh graph");
    private final JButton open = Ui.secondaryButton("Open saved definition");
    private final Map<String, Node> byId = new HashMap<>();
    private final Map<String, List<Edge>> byTarget = new HashMap<>();
    private final Map<String, List<Edge>> bySource = new HashMap<>();
    private final Consumer<Symbol> navigate;
    private Supplier<Graph> loader;
    private Graph graph;
    private Node selected;
    private long generation;

    public EvidenceGraphPanel(Consumer<Symbol> navigate) {
        super(new BorderLayout(0, 8));
        this.navigate = navigate;
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        JButton find = Ui.secondaryButton("Find");
        query.putClientProperty("JTextField.placeholderText", "Find a method, event or evidence ID");
        kind.addItem("All evidence");
        for (Kind value : Kind.values()) kind.addItem(value.name());
        kind.setToolTipText("Filter incoming and outgoing connections by evidence type");
        refresh.setToolTipText("Read available evidence without starting probes or recordings");
        controls.add(query);
        controls.add(find);
        controls.add(kind);
        controls.add(refresh);
        if (navigate != null) controls.add(open);
        add(Ui.sectionHeader("Evidence Graph", "Follow connections and inspect exactly what supports each link.", controls),
                BorderLayout.NORTH);

        prepareList(nodes);
        nodes.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && nodes.getSelectedValue() != null)
                focus(nodes.getSelectedValue().node());
        });
        JPanel neighborhood = Ui.card(new BorderLayout(0, 8));
        focus.putClientProperty("html.disable", true);
        neighborhood.add(focus, BorderLayout.NORTH);
        JPanel links = new JPanel(new GridLayout(1, 2, 8, 0));
        links.add(links("Incoming to selection", incoming));
        links.add(links("Outgoing from selection", outgoing));
        neighborhood.add(links, BorderLayout.CENTER);
        details.setRows(7);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        neighborhood.add(Ui.scroll(details), BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.scroll(nodes), neighborhood);
        split.setResizeWeight(.28);
        split.setDividerLocation(260);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        query.addActionListener(event -> filter());
        find.addActionListener(event -> filter());
        kind.addActionListener(event -> showLinks());
        refresh.addActionListener(event -> refresh());
        open.addActionListener(event -> {
            if (navigate == null) return;
            if (selected != null && selected.symbol() != null) navigate.accept(selected.symbol());
        });
        setLoader(null);
    }

    public void setLoader(Supplier<Graph> source) {
        generation++;
        loader = source;
        refresh.setEnabled(source != null);
        setGraph(null);
    }

    public void loadIfNeeded() {
        if (graph == null && refresh.isEnabled()) refresh();
    }

    private void refresh() {
        Supplier<Graph> source = loader;
        if (source == null) return;
        long request = ++generation;
        refresh.setEnabled(false);
        status.setText("Building evidence graph...");
        Async.run(source::get, next -> {
            if (request != generation) return;
            refresh.setEnabled(true);
            setGraph(next);
        }, error -> {
            if (request != generation) return;
            refresh.setEnabled(true);
            status.setText("Could not build evidence graph: " + error.getMessage());
        });
    }

    private void setGraph(Graph next) {
        String previous = selected == null ? "" : selected.id();
        graph = next;
        byId.clear();
        byTarget.clear();
        bySource.clear();
        if (next != null) {
            for (Node node : next.nodes()) byId.put(node.id(), node);
            for (Edge edge : next.edges()) {
                byTarget.computeIfAbsent(edge.to(), key -> new ArrayList<>()).add(edge);
                bySource.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edge);
            }
        }
        filter();
        if (byId.containsKey(previous)) focus(byId.get(previous));
        else if (!results.isEmpty()) nodes.setSelectedIndex(0);
        else focus(null);
    }

    private void filter() {
        results.clear();
        if (graph == null) {
            status.setText(loader == null ? "No evidence loaded." : "Refresh to read the available evidence.");
            return;
        }
        String needle = query.getText().strip().toLowerCase(Locale.ROOT);
        for (Node node : graph.nodes()) {
            String text = node.label() + " " + node.id() + " " + node.source() + " " + node.details();
            if (text.toLowerCase(Locale.ROOT).contains(needle)) results.addElement(new NodeItem(node));
        }
        status.setText(results.size() + " of " + graph.nodes().size() + " nodes | "
                + graph.edges().size() + " links" + (graph.partial() ? " | Partial evidence" : ""));
    }

    private JPanel links(String title, DefaultListModel<Link> model) {
        JList<Link> list = new JList<>(model);
        prepareList(list);
        list.setToolTipText("Select to inspect the evidence. Double-click or press Enter to follow the connection.");
        Runnable follow = () -> {
            Link value = list.getSelectedValue();
            if (value != null) {
                nodes.clearSelection();
                focus(value.node());
            }
        };
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) follow.run();
            }
        });
        list.registerKeyboardAction(event -> follow.run(), KeyStroke.getKeyStroke("ENTER"), WHEN_FOCUSED);
        list.addListSelectionListener(event -> {
            Link value = list.getSelectedValue();
            if (value != null) show(value.edge().kind() + " | " + value.edge().source() + "\n"
                    + value.edge().basis() + "\n\n" + value.node().label() + "\n" + value.node().details());
        });
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.add(new JLabel(title), BorderLayout.NORTH);
        panel.add(Ui.scroll(list), BorderLayout.CENTER);
        return panel;
    }

    private void focus(Node node) {
        selected = node;
        focus.setText(node == null ? "Select evidence" : node.label());
        open.setEnabled(node != null && node.symbol() != null && !node.symbol().definitionId().isEmpty());
        showLinks();
        show(node == null ? "" : node.source() + "\nEvidence ID: " + node.id() + "\n\n" + node.details());
    }

    private void showLinks() {
        incoming.clear();
        outgoing.clear();
        if (selected == null) return;
        for (Edge edge : byTarget.getOrDefault(selected.id(), List.of())) {
            if (matches(edge)) incoming.addElement(new Link(byId.get(edge.from()), edge));
        }
        for (Edge edge : bySource.getOrDefault(selected.id(), List.of())) {
            if (matches(edge)) outgoing.addElement(new Link(byId.get(edge.to()), edge));
        }
    }

    private boolean matches(Edge edge) {
        return kind.getSelectedIndex() == 0 || edge.kind().name().equals(kind.getSelectedItem());
    }

    private static void prepareList(JList<?> list) {
        DefaultListCellRenderer renderer = new DefaultListCellRenderer();
        renderer.putClientProperty("html.disable", true);
        list.setCellRenderer(renderer);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    }

    private void show(String text) {
        details.setText(text);
        details.setCaretPosition(0);
    }

    private record NodeItem(Node node) {
        @Override
        public String toString() {
            return node.label();
        }
    }

    private record Link(Node node, Edge edge) {
        @Override
        public String toString() {
            return edge.kind() + " | " + node.label();
        }
    }
}

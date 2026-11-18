package dev.whitedev.jpi.ui.search;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.exceptions.ExceptionSnapshot;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.search.SearchCategory;
import dev.whitedev.jpi.search.SearchEverythingService;
import dev.whitedev.jpi.search.SearchHit;
import dev.whitedev.jpi.search.SearchReport;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.context.AnalysisTarget;
import dev.whitedev.jpi.ui.context.ContextActions;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class SearchEverythingPanel extends JPanel implements SessionAware, AutoCloseable {
    private final DeobfuscationWorkspace workspace;
    private final RuntimeTimelineStore timeline;
    private final Supplier<ExceptionSnapshot> exceptionSnapshot;
    private final Consumer<SearchHit> navigation;
    private final JTextField query = new JTextField();
    private final JLabel status = new JLabel("Search loaded symbols, workspace annotations, and recorded activity");
    private final JTextArea details = Ui.outputArea();
    private final DefaultListModel<String> categories = new DefaultListModel<>();
    private final JList<String> category = new JList<>(categories);
    private final DefaultTableModel rows = new DefaultTableModel(new String[]{"Category", "Result", "Location"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(rows);
    private final JButton open = Ui.primaryButton("Open result");
    private final Timer debounce = new Timer(350, event -> startSearch());
    private volatile InspectorSession session;
    private SearchReport report = SearchReport.empty();
    private List<SearchHit> visible = List.of();
    private String searched = "";
    private boolean loading;
    private boolean pending;
    private volatile boolean closed;
    private volatile long generation;

    public SearchEverythingPanel(DeobfuscationWorkspace workspace, RuntimeTimelineStore timeline,
                                 Supplier<ExceptionSnapshot> exceptionSnapshot, Consumer<SearchHit> navigation) {
        super(new BorderLayout(0, 12));
        this.workspace = workspace;
        this.timeline = timeline;
        this.exceptionSnapshot = exceptionSnapshot;
        this.navigation = navigation;
        setOpaque(false);
        debounce.setRepeats(false);
        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.setOpaque(false);
        top.add(Ui.sectionHeader("Search Everything", "Ctrl+Shift+F | Classes, members, strings, mappings, notes, and recorded events", null), BorderLayout.NORTH);
        query.putClientProperty("JTextField.placeholderText", "Search everything, for example auth, /api/login, or Session.token");
        query.putClientProperty("JTextField.showClearButton", true);
        query.addActionListener(event -> {
            debounce.stop();
            startSearch();
        });
        query.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                schedule();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                schedule();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                schedule();
            }
        });
        JButton refresh = Ui.secondaryButton("Search / Refresh");
        refresh.addActionListener(event -> {
            generation++;
            debounce.stop();
            startSearch();
        });
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(false);
        bar.add(query, BorderLayout.CENTER);
        bar.add(refresh, BorderLayout.EAST);
        top.add(bar, BorderLayout.CENTER);
        status.setForeground(Ui.MUTED);
        top.add(status, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        category.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        category.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
                JComponent component = (JComponent) super.getListCellRendererComponent(list, value, index, selected, focused);
                component.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
                return component;
            }
        });
        category.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) renderRows();
        });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
        renderer.putClientProperty("html.disable", Boolean.TRUE);
        table.setDefaultRenderer(Object.class, renderer);
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(440);
        table.getSelectionModel().addListSelectionListener(event -> showDetails());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) openSelected();
            }
        });
        table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-search-result");
        table.getActionMap().put("open-search-result", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                openSelected();
            }
        });
        open.addActionListener(event -> openSelected());
        JPanel detailPanel = new JPanel(new BorderLayout(0, 4));
        detailPanel.add(Ui.scroll(details), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(open);
        detailPanel.add(actions, BorderLayout.SOUTH);
        JSplitPane body = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(table), detailPanel);
        body.setResizeWeight(0.7);
        body.setBorder(null);
        JScrollPane categoryScroll = Ui.scroll(category);
        categoryScroll.setMinimumSize(new Dimension(175, 80));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, categoryScroll, body);
        split.setDividerLocation(200);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        render(SearchReport.empty(), "Enter at least two characters", false);
    }

    public void focusSearch() {
        query.requestFocusInWindow();
        query.selectAll();
        schedule();
    }

    @Override
    public void setSession(InspectorSession value) {
        session = value;
        generation++;
        pending = false;
        debounce.stop();
        render(SearchReport.empty(), value == null ? "Workspace search available. Attach for loaded symbols." : "Ready to search the current target", false);
        if (isShowing()) schedule();
    }

    private void schedule() {
        generation++;
        debounce.stop();
        if (query.getText().trim().length() < 2) {
            pending = false;
            render(SearchReport.empty(), "Enter at least two characters", false);
        } else debounce.restart();
    }

    private void startSearch() {
        if (closed) return;
        final String text;
        try {
            text = SearchEverythingService.query(query.getText());
        } catch (IllegalArgumentException error) {
            status.setText(error.getMessage());
            return;
        }
        if (loading) {
            pending = true;
            status.setText("Waiting for the current bounded target scan; the latest query will run next.");
            return;
        }
        loading = true;
        pending = false;
        long epoch = generation;
        InspectorSession current = session;
        searched = text;
        render(SearchReport.empty(), "Searching workspace and recorded activity...", current != null);
        ExceptionSnapshot cachedExceptions = exceptionSnapshot.get();
        Async.run(() -> {
            SearchReport local = SearchEverythingService.local(text, workspace.entries(), timeline.events());
            SearchReport cached = local.merge(SearchEverythingService.exceptions(text, cachedExceptions));
            SwingUtilities.invokeLater(() -> {
                if (current == session && epoch == generation && !closed)
                    render(cached, current == null ? "Workspace and recorded activity" : "Searching available target bytecode...", current != null);
            });
            if (current == null || current != session || epoch != generation || closed) return cached;
            SearchReport result = local;
            try {
                result = result.merge(SearchEverythingService.target(current, text));
            } catch (Exception error) {
                result = result.merge(new SearchReport(List.of(), Map.of(), List.of("Target symbols unavailable: " + error.getMessage())));
            }
            if (Thread.currentThread().isInterrupted() || current != session || epoch != generation || closed)
                return result;
            try {
                ExceptionSnapshot snapshot = ExceptionSnapshot.parse(current.requestText(Operation.EXCEPTION_SNAPSHOT, "30"));
                result = result.merge(SearchEverythingService.exceptions(text, snapshot));
            } catch (Exception error) {
                result = result.merge(SearchEverythingService.exceptions(text, cachedExceptions))
                        .merge(new SearchReport(List.of(), Map.of(), List.of("Exception snapshot unavailable: " + error.getMessage())));
            }
            return result;
        }, result -> finish(epoch, current, result, null), error -> finish(epoch, current, null, error));
    }

    private void finish(long epoch, InspectorSession current, SearchReport result, Throwable error) {
        loading = false;
        if (closed) return;
        if (current == session && epoch == generation) {
            if (error == null)
                render(result, result.hits().size() + " results shown. Counts with + are lower bounds or capped.", false);
            else status.setText("Search failed: " + error.getMessage());
        }
        if (pending) {
            pending = false;
            startSearch();
        }
    }

    private void render(SearchReport value, String message, boolean targetPending) {
        report = value;
        int selected = Math.max(0, category.getSelectedIndex());
        categories.clear();
        categories.addElement("All results    " + value.hits().size());
        for (SearchCategory item : SearchCategory.values()) {
            var count = value.counts().get(item);
            boolean remote = item == SearchCategory.CLASSES || item == SearchCategory.METHODS || item == SearchCategory.FIELDS
                    || item == SearchCategory.STRINGS || item == SearchCategory.EXCEPTIONS;
            String total = count == null ? targetPending && remote ? "..." : "0" : Math.min(200, count.matches()) + (count.partial() ? "+" : "");
            categories.addElement(item + "    " + total);
        }
        category.setSelectedIndex(selected);
        status.setText(message);
        status.setToolTipText(String.join(" | ", value.notices()));
        renderRows();
    }

    private void renderRows() {
        int index = category.getSelectedIndex();
        SearchCategory selected = index <= 0 ? null : SearchCategory.values()[index - 1];
        visible = SearchEverythingService.ranked(report, searched, selected);
        rows.setRowCount(0);
        for (SearchHit hit : visible)
            rows.addRow(new Object[]{hit.category(), singleLine(hit.title()), hit.location()});
        if (!visible.isEmpty()) table.setRowSelectionInterval(0, 0);
        showDetails();
    }

    private SearchHit selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= visible.size() ? null : visible.get(row);
    }

    private void showDetails() {
        SearchHit hit = selected();
        open.setEnabled(hit != null);
        details.setText(hit == null ? String.join("\n", report.notices()) : hit.details() + "\n\n" + String.join("\n", report.notices()));
        details.setCaretPosition(0);
    }

    private void openSelected() {
        SearchHit hit = selected();
        if (hit != null) navigation.accept(hit);
    }

    public void setContextActions(ContextActions actions) {
        ContextActions.bindSelection(query, () -> null);
        actions.install(table, () -> {
            SearchHit hit = selected();
            if (hit == null) return null;
            return switch (hit.category()) {
                case CLASSES -> AnalysisTarget.type(hit.classId(), hit.className());
                case METHODS -> AnalysisTarget.method(hit.classId(), hit.className(), hit.member(), hit.descriptor());
                case FIELDS -> AnalysisTarget.field(hit.classId(), hit.className(), hit.member(), hit.descriptor());
                case STRINGS -> AnalysisTarget.constant(hit.value());
                default -> null;
            };
        });
    }

    private static String singleLine(String text) {
        String value = text.replace('\n', ' ').replace('\r', ' ');
        return value.length() > 200 ? value.substring(0, 200) + "..." : value;
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        pending = false;
        debounce.stop();
    }
}

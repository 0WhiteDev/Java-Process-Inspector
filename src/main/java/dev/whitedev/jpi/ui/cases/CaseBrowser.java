package dev.whitedev.jpi.ui.cases;

import dev.whitedev.jpi.cases.CaseAnalysis;
import dev.whitedev.jpi.cases.CaseAnalysis.Method;
import dev.whitedev.jpi.cases.CaseArchive;
import dev.whitedev.jpi.cases.InvestigationCase;
import dev.whitedev.jpi.cases.InvestigationCase.Bookmark;
import dev.whitedev.jpi.cases.InvestigationCase.Definition;
import dev.whitedev.jpi.cases.InvestigationCase.Evidence;
import dev.whitedev.jpi.decompile.DecompilerService;
import dev.whitedev.jpi.evidence.EvidenceGraphBuilder;
import dev.whitedev.jpi.search.SearchHit;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.analysis.OfflineCfg;
import dev.whitedev.jpi.ui.callgraph.CallGraphHeatmapPanel;
import dev.whitedev.jpi.ui.evidence.EvidenceGraphPanel;

import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

final class CaseBrowser extends JPanel {
    private static final int MAX_NOTE_LENGTH = 16_000;
    private final DecompilerService decompiler = new DecompilerService();
    private final JTabbedPane tabs = new JTabbedPane();
    private final EvidenceGraphPanel graph = new EvidenceGraphPanel(symbol ->
            navigate(symbol.definitionId(), symbol.member(), symbol.descriptor()));
    private final DefaultListModel<Definition> classModel = new DefaultListModel<>();
    private final JList<Definition> classes = plainList(classModel, Definition::toString);
    private final JComboBox<Method> methods = new JComboBox<>();
    private final JTextArea output = Ui.outputArea();
    private final JPanel analysis = new JPanel(new BorderLayout());
    private final JTextField query = new JTextField(32);
    private final DefaultListModel<SearchHit> searchModel = new DefaultListModel<>();
    private final JList<SearchHit> results = plainList(searchModel, hit -> hit.category() + " | " + hit.title() + " | " + hit.location());
    private final JTextArea searchDetails = Ui.outputArea();
    private final DefaultListModel<Observation> observationModel = new DefaultListModel<>();
    private final JList<Observation> observations = plainList(observationModel, Observation::label);
    private final JTextArea observationDetails = Ui.outputArea();
    private final DefaultListModel<Bookmark> bookmarkModel = new DefaultListModel<>();
    private final JList<Bookmark> bookmarks = plainList(bookmarkModel, Bookmark::label);
    private final JTextArea notes = new JTextArea();
    private final JButton decompile = Ui.secondaryButton("Decompile");
    private final JButton xrefs = Ui.secondaryButton("Xrefs");
    private final JButton cfg = Ui.secondaryButton("CFG");
    private final JButton bookmark = Ui.secondaryButton("Bookmark selection");
    private final JButton pin = Ui.secondaryButton("Save analysis in case");
    private final List<Evidence> pinned = new ArrayList<>();
    private InvestigationCase data;
    private Evidence displayedAnalysis;
    private long analysisRequest;
    private long selectionRequest;
    private long searchRequest;
    private boolean searching;
    private boolean loadingClass;
    private boolean analyzing;
    private boolean dirty;
    private long revision;

    CaseBrowser() {
        super(new BorderLayout());
        JPanel toolbar = buttons(methods, decompile, xrefs, cfg, bookmark, pin);
        JPanel inspectedClass = new JPanel(new BorderLayout(0, 8));
        inspectedClass.add(toolbar, BorderLayout.NORTH);
        inspectedClass.add(analysis, BorderLayout.CENTER);
        setAnalysis(Ui.scroll(output));
        tabs.addTab("Saved classes", split(Ui.scroll(classes), inspectedClass));
        tabs.addTab("Search", searchTab());
        tabs.addTab("Timeline and traces", split(Ui.scroll(observations), Ui.scroll(observationDetails)));
        tabs.addTab("Notes and bookmarks", notesTab());
        tabs.addTab("Evidence Graph", graph);
        tabs.addChangeListener(event -> {
            if (tabs.getSelectedComponent() == graph) graph.loadIfNeeded();
        });
        add(tabs);

        classes.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) loadMethods(null);
        });
        methods.addActionListener(event -> updateActions());
        decompile.addActionListener(event -> analyze("Source"));
        xrefs.addActionListener(event -> analyze("Xrefs"));
        cfg.addActionListener(event -> analyze("CFG"));
        bookmark.addActionListener(event -> addBookmark());
        pin.addActionListener(event -> {
            if (displayedAnalysis != null) addEvidence(displayedAnalysis);
        });
        observations.addListSelectionListener(event -> {
            Observation selected = observations.getSelectedValue();
            if (selected != null) {
                observationDetails.setText(selected.body());
                observationDetails.setCaretPosition(0);
            }
        });
        updateActions();
    }

    private JPanel searchTab() {
        JButton search = Ui.secondaryButton("Search case");
        JButton open = Ui.secondaryButton("Open saved definition");
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(buttons(query, search, open), BorderLayout.NORTH);
        panel.add(split(Ui.scroll(results), Ui.scroll(searchDetails)), BorderLayout.CENTER);
        query.putClientProperty("JTextField.placeholderText", "Classes, members, strings, mappings, notes, runtime");
        query.addActionListener(event -> search());
        search.addActionListener(event -> search());
        open.addActionListener(event -> {
            SearchHit hit = results.getSelectedValue();
            if (hit != null) navigate(hit.classId(), hit.member(), hit.descriptor());
        });
        results.addListSelectionListener(event -> {
            SearchHit hit = results.getSelectedValue();
            if (hit != null) {
                searchDetails.setText(hit.title() + "\n" + hit.location() + "\n\n" + hit.details());
                searchDetails.setCaretPosition(0);
            }
            open.setEnabled(hit != null && data != null && data.bytecode().containsKey(hit.classId()));
        });
        open.setEnabled(false);
        return panel;
    }

    private JPanel notesTab() {
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        ((AbstractDocument) notes.getDocument()).setDocumentFilter(new DocumentFilter() {
            @Override
            public void insertString(FilterBypass bypass, int offset, String text, AttributeSet attributes) throws BadLocationException {
                replace(bypass, offset, 0, text, attributes);
            }

            @Override
            public void replace(FilterBypass bypass, int offset, int length, String text, AttributeSet attributes) throws BadLocationException {
                int available = MAX_NOTE_LENGTH - bypass.getDocument().getLength() + length;
                String value = text == null ? "" : text.substring(0, Math.min(available, text.length()));
                super.replace(bypass, offset, length, value, attributes);
            }
        });
        notes.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                changed();
            }
        });
        JButton open = Ui.secondaryButton("Open bookmark");
        JButton remove = Ui.secondaryButton("Remove bookmark");
        open.addActionListener(event -> {
            Bookmark saved = bookmarks.getSelectedValue();
            if (saved != null) navigate(saved.classId(), saved.method(), saved.descriptor());
        });
        remove.addActionListener(event -> {
            int selected = bookmarks.getSelectedIndex();
            if (selected >= 0) {
                bookmarkModel.remove(selected);
                changed();
            }
        });
        JPanel right = new JPanel(new BorderLayout());
        right.add(buttons(open, remove), BorderLayout.NORTH);
        right.add(Ui.scroll(bookmarks), BorderLayout.CENTER);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(split(Ui.scroll(notes), right));
        return panel;
    }

    void setData(InvestigationCase value) {
        data = value;
        analysisRequest++;
        searchRequest++;
        searching = false;
        classModel.clear();
        searchModel.clear();
        observationModel.clear();
        bookmarkModel.clear();
        pinned.clear();
        methods.setModel(new DefaultComboBoxModel<>());
        loadMethods(null);
        query.setText("");
        searchDetails.setText("Search saved classes, strings, mappings, notes and observations.");
        observationDetails.setText("Select a saved observation or report.");
        if (value == null) {
            notes.setText("");
        } else {
            for (Definition definition : value.catalog()) {
                if (value.bytecode().containsKey(definition.id())) classModel.addElement(definition);
            }
            notes.setText(value.notes());
            value.bookmarks().forEach(bookmarkModel::addElement);
            pinned.addAll(value.evidence());
            addObservations(value);
            if (!classModel.isEmpty()) classes.setSelectedIndex(0);
            else tabs.setSelectedIndex(1);
        }
        notes.setEditable(value != null);
        dirty = false;
        updateGraph();
        updateActions();
    }

    private void updateGraph() {
        InvestigationCase current = data == null ? null : snapshot();
        graph.setLoader(current == null ? null : () -> current.evidenceGraph() == null
                ? EvidenceGraphBuilder.saved(current) : current.evidenceGraph());
        if (tabs.getSelectedComponent() == graph) graph.loadIfNeeded();
    }

    private void addObservations(InvestigationCase value) {
        observationModel.addElement(new Observation("Case overview", overview(value)));
        if (!value.callGraph().isBlank()) {
            InvestigationCase expected = data;
            Async.run(() -> CallGraphHeatmapPanel.offlineSummary(value.callGraph()), text -> {
                if (expected == data) observationModel.add(1, new Observation("Saved call graph", text));
            }, error -> {
                if (expected == data)
                    observationModel.addElement(new Observation("Call graph unavailable", error.getMessage()));
            });
        }
        if (value.exceptions() != null) {
            var snapshot = value.exceptions();
            observationModel.addElement(new Observation("Exception snapshot | " + snapshot.state(),
                    "Snapshot entries: " + snapshot.entries().size() + "\nDropped: " + snapshot.dropped()));
            for (var entry : snapshot.entries()) {
                StringBuilder text = new StringBuilder(entry.type()).append("\nTotal: ").append(entry.total()).append('\n');
                for (var location : entry.locations()) {
                    text.append(location.count()).append(" x ").append(location).append('\n');
                    if (!location.stack().isBlank()) text.append(location.stack()).append('\n');
                }
                observationModel.addElement(new Observation("Exception | " + entry.type(), text.toString()));
            }
        }
        List<TimedObservation> ordered = new ArrayList<>();
        for (var event : value.timeline()) {
            String calls = event.callId().isBlank() ? "" : " | call #" + event.callId();
            ordered.add(new TimedObservation(event.timestamp(), new Observation(Instant.ofEpochMilli(event.timestamp())
                    + " | " + event.source() + calls + " | " + event.summary(), event.summary()
                    + "\nThread: " + event.thread() + "\nCall: " + event.callId() + "\nParent: " + event.parentCallId()
                    + "\n\n" + event.details() + "\n" + event.value())));
        }
        for (var trace : value.traces()) {
            ordered.add(new TimedObservation(trace.timestamp(), new Observation(Instant.ofEpochMilli(trace.timestamp())
                    + " | Trace #" + trace.sequence() + " | " + trace.symbol(), trace.symbol()
                    + "\nDefinition: " + trace.classId() + "\nParent: " + trace.parent()
                    + "\nOutcome: " + trace.outcome() + "\nDuration: " + trace.durationNanos() + " ns\n\n" + trace.details())));
        }
        ordered.sort(Comparator.comparingLong(TimedObservation::timestamp));
        ordered.forEach(item -> observationModel.addElement(item.observation()));
        for (Evidence evidence : pinned) showEvidence(evidence);
    }

    private static String overview(InvestigationCase value) {
        var manifest = value.manifest();
        StringBuilder text = new StringBuilder(manifest.title()).append("\n")
                .append(Instant.ofEpochMilli(manifest.startedAt())).append(" to ").append(Instant.ofEpochMilli(manifest.endedAt()))
                .append("\nCatalog: ").append(value.catalog().size()).append(" definitions | Saved classes: ").append(value.bytecode().size())
                .append("\nRuntime details: ").append(manifest.runtimeDetails() ? "included" : "omitted by default").append('\n');
        var fingerprint = value.fingerprint();
        if (fingerprint != null) {
            text.append("Fingerprint: ").append(fingerprint.id()).append("\nJava: ").append(fingerprint.javaVersion())
                    .append("\nMain: ").append(fingerprint.mainClass()).append('\n');
        }
        for (String notice : manifest.notices()) text.append('\n').append(notice);
        return text.toString();
    }

    private void loadMethods(Method preferred) {
        long request = ++selectionRequest;
        analysisRequest++;
        analyzing = false;
        displayedAnalysis = null;
        Definition selected = classes.getSelectedValue();
        byte[] bytes = data == null || selected == null ? null : data.bytecode().get(selected.id());
        loadingClass = bytes != null;
        methods.setModel(new DefaultComboBoxModel<>());
        showText(bytes == null ? "Select a saved class to inspect it offline." : "Reading saved methods...");
        updateActions();
        if (bytes == null) return;
        Async.run(() -> CaseAnalysis.methods(bytes), list -> {
            if (request != selectionRequest) return;
            loadingClass = false;
            methods.setModel(new DefaultComboBoxModel<>(list.toArray(Method[]::new)));
            if (preferred != null && list.contains(preferred)) methods.setSelectedItem(preferred);
            showText("Select Decompile, Xrefs or CFG.");
            updateActions();
        }, error -> {
            if (request != selectionRequest) return;
            loadingClass = false;
            showText("Could not read saved methods: " + error.getMessage());
            updateActions();
        });
    }

    private void analyze(String kind) {
        InvestigationCase snapshot = data;
        Definition definition = classes.getSelectedValue();
        Method method = (Method) methods.getSelectedItem();
        if (snapshot == null || definition == null || analyzing || !kind.equals("Source") && method == null) return;
        byte[] bytes = snapshot.bytecode().get(definition.id());
        long request = ++analysisRequest;
        analyzing = true;
        displayedAnalysis = null;
        updateActions();
        Async.run(() -> switch (kind) {
            case "Source" -> new Analysis(decompiler.decompile("SavedClass", bytes), null);
            case "Xrefs" -> new Analysis(CaseAnalysis.xrefs(snapshot, definition, method), null);
            default -> {
                OfflineCfg graph = OfflineCfg.analyze(bytes, method.name(), method.descriptor());
                yield new Analysis(graph.report(), graph);
            }
        }, result -> {
            if (request != analysisRequest || !Objects.equals(method, methods.getSelectedItem())) {
                if (request == analysisRequest) finishAnalysis();
                return;
            }
            displayedAnalysis = new Evidence(kind, definition.name() + (method == null ? "" : "." + method),
                    definition.id(), method == null ? "" : method.name(), method == null ? "" : method.descriptor(), result.text());
            if (result.cfg() == null) showText(result.text());
            else setAnalysis(result.cfg().view());
            finishAnalysis();
        }, error -> {
            if (request != analysisRequest) return;
            if (!Objects.equals(method, methods.getSelectedItem())) {
                finishAnalysis();
                return;
            }
            showText("Offline analysis failed: " + error.getMessage());
            finishAnalysis();
        });
    }

    private void finishAnalysis() {
        analyzing = false;
        updateActions();
    }

    private void search() {
        if (data == null || searching) return;
        String text = query.getText();
        long request = ++searchRequest;
        InvestigationCase current = snapshot();
        searching = true;
        searchModel.clear();
        searchDetails.setText("Searching saved data...");
        Async.run(() -> CaseAnalysis.search(current, text), result -> {
            if (request != searchRequest) return;
            searching = false;
            if (!text.equals(query.getText())) {
                search();
                return;
            }
            searchDetails.setText(result.hits().size() + " results. Bytecode results cover saved definitions only."
                    + (result.partial() ? "\nPartial results: a search limit was reached or some data could not be parsed." : ""));
            result.hits().forEach(searchModel::addElement);
        }, error -> {
            if (request != searchRequest) return;
            searching = false;
            if (!text.equals(query.getText())) search();
            else searchDetails.setText(error.getMessage());
        });
    }

    private void navigate(String id, String method, String descriptor) {
        for (int index = 0; index < classModel.size(); index++) {
            if (!classModel.get(index).id().equals(id)) continue;
            tabs.setSelectedIndex(0);
            classes.setSelectedIndex(index);
            classes.ensureIndexIsVisible(index);
            if (method != null && !method.isBlank()) loadMethods(new Method(method, descriptor));
            return;
        }
    }

    private void addBookmark() {
        Definition selected = classes.getSelectedValue();
        if (selected == null || bookmarkModel.size() >= CaseArchive.MAX_ANNOTATIONS) return;
        Method method = (Method) methods.getSelectedItem();
        String label = selected.name() + (method == null ? "" : "." + method);
        Bookmark item = new Bookmark(selected.id(), method == null ? "" : method.name(),
                method == null ? "" : method.descriptor(), label);
        if (!bookmarkModel.contains(item)) {
            bookmarkModel.addElement(item);
            changed();
        }
    }

    void addEvidence(Evidence evidence) {
        if (data == null || pinned.size() >= CaseArchive.MAX_ANNOTATIONS) return;
        String body = evidence.text().substring(0, Math.min(12_000, evidence.text().length()));
        Evidence bounded = new Evidence(evidence.kind(), evidence.title(), evidence.classId(), evidence.method(), evidence.descriptor(), body);
        if (!pinned.contains(bounded)) {
            pinned.add(bounded);
            showEvidence(bounded);
            updateGraph();
            changed();
        }
    }

    private void showEvidence(Evidence evidence) {
        observationModel.addElement(new Observation(evidence.kind() + " | " + evidence.title(), evidence.text()));
    }

    boolean isDirty() {
        return dirty;
    }

    long revision() {
        return revision;
    }

    void markSaved(long savedRevision) {
        if (revision == savedRevision) dirty = false;
    }

    private void changed() {
        dirty = true;
        revision++;
    }

    InvestigationCase snapshot() {
        List<Bookmark> saved = new ArrayList<>();
        for (int i = 0; i < bookmarkModel.size(); i++) saved.add(bookmarkModel.get(i));
        return new InvestigationCase(data.manifest(), data.fingerprint(), data.catalog(), data.bytecode(), data.timeline(),
                data.traces(), data.exceptions(), data.callGraph(), data.mappings(), notes.getText(), saved, pinned,
                pinned.equals(data.evidence()) ? data.evidenceGraph() : null);
    }

    private void updateActions() {
        boolean available = data != null && classes.getSelectedValue() != null && !loadingClass;
        decompile.setEnabled(available && !analyzing);
        xrefs.setEnabled(available && !analyzing && methods.getSelectedItem() != null);
        cfg.setEnabled(xrefs.isEnabled());
        bookmark.setEnabled(available);
        pin.setEnabled(displayedAnalysis != null && !analyzing);
    }

    private void showText(String value) {
        output.setText(value);
        output.setCaretPosition(0);
        setAnalysis(Ui.scroll(output));
    }

    private void setAnalysis(JComponent component) {
        analysis.removeAll();
        analysis.add(component, BorderLayout.CENTER);
        analysis.revalidate();
        analysis.repaint();
    }

    static JPanel buttons(Component... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (Component component : components) row.add(component);
        return row;
    }

    static JSplitPane split(JComponent left, JComponent right) {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(.35);
        split.setDividerLocation(340);
        return split;
    }

    static <T> JList<T> plainList(DefaultListModel<T> model, Function<T, String> label) {
        JList<T> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> component, Object item, int index,
                                                          boolean selected, boolean focused) {
                putClientProperty("html.disable", Boolean.TRUE);
                String text = index < 0 || index >= model.size() ? "" : label.apply(model.get(index));
                return super.getListCellRendererComponent(component, text, index, selected, focused);
            }
        });
        return list;
    }

    private record Observation(String label, String body) {
    }

    private record TimedObservation(long timestamp, Observation observation) {
    }

    private record Analysis(String text, OfflineCfg cfg) {
    }
}

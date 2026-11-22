package dev.whitedev.jpi.ui.cases;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.cases.CaseArchive;
import dev.whitedev.jpi.cases.CaseRecorder;
import dev.whitedev.jpi.cases.InvestigationCase;
import dev.whitedev.jpi.cases.InvestigationCase.Definition;
import dev.whitedev.jpi.cases.InvestigationCase.Evidence;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class InvestigationCasePanel extends JPanel implements SessionAware, AutoCloseable {
    private final DeobfuscationWorkspace workspace;
    private final RuntimeTimelineStore timeline;
    private final Supplier<String> investigation;
    private final Supplier<Evidence> currentCfg;
    private final Consumer<TimelineEvent> eventListener = this::record;
    private final JLabel status = new JLabel();
    private final JTextField title = new JTextField("Investigation", 19);
    private final JCheckBox details = new JCheckBox("Include sensitive runtime details");
    private final JTextField filter = new JTextField(24);
    private final DefaultListModel<Definition> definitions = new DefaultListModel<>();
    private final JList<Definition> selected = CaseBrowser.plainList(definitions, Definition::toString);
    private final JTextArea recordingDetails = Ui.outputArea();
    private final JButton newCase = Ui.secondaryButton("New investigation");
    private final JButton start = Ui.primaryButton("Start recording");
    private final JButton stop = Ui.secondaryButton("Stop recording");
    private final JButton capture = Ui.secondaryButton("Capture selected classes");
    private final JButton refreshCatalog = Ui.secondaryButton("Refresh catalog");
    private final JButton importReport = Ui.secondaryButton("Add current investigation report");
    private final JButton importCfg = Ui.secondaryButton("Add current CFG");
    private final JButton save = Ui.secondaryButton("Save case...");
    private final JButton open = Ui.secondaryButton("Open offline...");
    private final CardLayout layout = new CardLayout();
    private final JPanel content = new JPanel(layout);
    private final CaseBrowser browser = new CaseBrowser();
    private final Timer expiration = new Timer(15 * 60 * 1000, event -> stop());
    private volatile InspectorSession session;
    private volatile CaseRecorder recorder;
    private volatile long generation;
    private InvestigationCase offline;
    private final List<Evidence> pendingReports = new ArrayList<>();
    private boolean busy;
    private boolean starting;
    private boolean saved;

    public InvestigationCasePanel(DeobfuscationWorkspace workspace, RuntimeTimelineStore timeline, Supplier<String> investigation,
                                  Supplier<Evidence> currentCfg) {
        super(new BorderLayout(0, 10));
        this.workspace = workspace;
        this.timeline = timeline;
        this.investigation = investigation;
        this.currentCfg = currentCfg;
        timeline.addEventListener(eventListener);
        expiration.setRepeats(false);
        status.putClientProperty("html.disable", Boolean.TRUE);
        JPanel buttons = CaseBrowser.buttons(newCase, start, stop, save, open);
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.add(Ui.sectionHeader("Investigation Cases", "Record observations and reopen a portable case without a target JVM",
                buttons), BorderLayout.NORTH);
        header.add(status, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        JPanel draft = new JPanel(new BorderLayout(0, 8));
        JPanel options = new JPanel(new BorderLayout(0, 4));
        options.add(CaseBrowser.buttons(new JLabel("Case name"), title, details), BorderLayout.NORTH);
        JButton applyFilter = Ui.secondaryButton("Filter");
        JPanel recorderOptions = new JPanel(new BorderLayout());
        recorderOptions.add(CaseBrowser.buttons(filter, applyFilter, refreshCatalog, capture), BorderLayout.NORTH);
        recorderOptions.add(CaseBrowser.buttons(importReport, importCfg), BorderLayout.SOUTH);
        options.add(recorderOptions, BorderLayout.SOUTH);
        filter.putClientProperty("JTextField.placeholderText", "Class name or loader");
        draft.add(options, BorderLayout.NORTH);
        draft.add(CaseBrowser.split(Ui.scroll(selected), Ui.scroll(recordingDetails)), BorderLayout.CENTER);
        content.add(draft, "recording");
        content.add(browser, "offline");
        add(content, BorderLayout.CENTER);
        selected.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        selected.addListSelectionListener(event -> updateActions());
        details.setToolTipText("May include arguments, return values, paths, hostnames and other private data.");
        newCase.addActionListener(event -> reset());
        start.addActionListener(event -> start());
        stop.addActionListener(event -> stop());
        capture.addActionListener(event -> capture());
        refreshCatalog.addActionListener(event -> refreshCatalog());
        importReport.addActionListener(event -> {
            String report = investigation.get();
            addReport(report == null || report.isBlank() ? null :
                    new Evidence("Investigation", "Investigation Assistant report", "", "", "", report));
        });
        importCfg.addActionListener(event -> addReport(currentCfg.get()));
        save.addActionListener(event -> save());
        open.addActionListener(event -> open());
        filter.addActionListener(event -> renderCatalog());
        applyFilter.addActionListener(event -> renderCatalog());
        renderCatalog();
        updateActions();
    }

    private void record(TimelineEvent event) {
        CaseRecorder active = recorder;
        if (active != null) active.event(event);
    }

    @Override
    public void setSession(InspectorSession value) {
        if (session == value) return;
        session = value;
        CaseRecorder active = recorder;
        if (active != null) {
            long ticket = ++generation;
            active.seal();
            expiration.stop();
            recorder = null;
            busy = true;
            status.setText("Target changed. Keeping the partial recording...");
            var mappings = workspace.entries();
            Async.run(() -> active.interrupted(mappings), data -> {
                if (ticket == generation) showOffline(data, false);
            }, error -> failed(ticket, error));
        } else if (starting) {
            generation++;
            busy = false;
            starting = false;
        }
        updateActions();
    }

    private void reset() {
        if (!canDiscard()) return;
        generation++;
        offline = null;
        saved = false;
        pendingReports.clear();
        title.setText("Investigation");
        details.setSelected(false);
        browser.setData(null);
        filter.setText("");
        layout.show(content, "recording");
        renderCatalog();
        updateActions();
        title.requestFocusInWindow();
    }

    private void start() {
        InspectorSession expected = session;
        if (expected == null || busy || offline != null) return;
        if (title.getText().isBlank()) {
            status.setText("Enter a case name before recording.");
            title.requestFocusInWindow();
            return;
        }
        long ticket = ++generation;
        String name = title.getText();
        boolean withDetails = details.isSelected();
        busy = true;
        starting = true;
        status.setText("Reading the class catalog and trace baseline...");
        updateActions();
        Async.run(() -> CaseRecorder.start(expected, name, withDetails, () -> current(expected, ticket)), recording -> {
            if (!current(expected, ticket)) return;
            recorder = recording;
            busy = false;
            starting = false;
            expiration.restart();
            renderCatalog();
            updateActions();
        }, error -> failed(ticket, error));
    }

    private void stop() {
        InspectorSession expected = session;
        CaseRecorder active = recorder;
        if (expected == null || active == null || active.isSealed()) return;
        long ticket = ++generation;
        active.seal();
        expiration.stop();
        var mappings = workspace.entries();
        busy = true;
        status.setText("Saving the final in-memory runtime snapshots...");
        updateActions();
        Async.run(() -> active.finish(expected, mappings, () -> current(expected, ticket)), data -> {
            if (current(expected, ticket)) showOffline(data, false);
        }, error -> {
            if (!current(expected, ticket)) return;
            showOffline(active.interrupted(mappings), false);
            Ui.error(this, error);
        });
    }

    private void capture() {
        InspectorSession expected = session;
        CaseRecorder active = recorder;
        if (expected == null || active == null || busy) return;
        List<Definition> selection = selected.getSelectedValuesList().stream().limit(64).toList();
        long ticket = ++generation;
        busy = true;
        status.setText("Capturing selected class definitions...");
        updateActions();
        Async.run(() -> active.capture(expected, selection, () -> current(expected, ticket)), count -> {
            if (!current(expected, ticket)) return;
            busy = false;
            renderCatalog();
            updateActions();
            status.setText("Recording | " + count + " class definitions added in the last batch");
        }, error -> failed(ticket, error));
    }

    private void refreshCatalog() {
        InspectorSession expected = session;
        CaseRecorder active = recorder;
        if (expected == null || active == null || busy) return;
        long ticket = ++generation;
        busy = true;
        status.setText("Refreshing loaded class definitions...");
        updateActions();
        Async.run(() -> {
            active.refreshCatalog(expected, () -> current(expected, ticket));
            return true;
        }, ignored -> {
            if (!current(expected, ticket)) return;
            busy = false;
            renderCatalog();
            updateActions();
        }, error -> failed(ticket, error));
    }

    private void addReport(Evidence evidence) {
        if (recorder == null || busy) return;
        if (evidence == null) {
            status.setText("Analyze a CFG or run the Investigation Assistant before adding its report.");
            return;
        }
        if (pendingReports.size() >= CaseArchive.MAX_ANNOTATIONS) {
            status.setText("The case has reached its report limit.");
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Add this report? It may contain search terms and sensitive evidence.",
                "Add investigation report", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            String body = evidence.text().substring(0, Math.min(12_000, evidence.text().length()));
            pendingReports.add(new Evidence(evidence.kind(), evidence.title(), evidence.classId(),
                    evidence.method(), evidence.descriptor(), body));
            status.setText("Recording | Report will be included");
        }
    }

    private boolean current(InspectorSession expected, long ticket) {
        return expected == session && ticket == generation;
    }

    private void showOffline(InvestigationCase data, boolean fromDisk) {
        expiration.stop();
        recorder = null;
        busy = false;
        offline = data;
        saved = fromDisk;
        browser.setData(data);
        if (!fromDisk) pendingReports.forEach(browser::addEvidence);
        pendingReports.clear();
        layout.show(content, "offline");
        updateActions();
    }

    private void renderCatalog() {
        List<Definition> items = recorder == null ? List.of() : recorder.catalog();
        Set<String> savedIds = recorder == null ? Set.of() : recorder.savedDefinitions();
        definitions.clear();
        String needle = filter.getText().strip().toLowerCase(Locale.ROOT);
        int matches = 0;
        for (Definition definition : items) {
            if (!definition.toString().toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (++matches <= 1000) definitions.addElement(definition);
        }
        recordingDetails.setText("Start recording, exercise the target, then stop and save the case.\n\n"
                + "JPI records observations from existing probes and timeline producers. It does not install probes.\n"
                + "Select the definitions you want available for offline decompilation, Xrefs and CFG, then click Capture selected classes.\n"
                + "The case includes up to 64 selected classes per batch. Saved bytecode can contain private constants even with runtime details off.\n\n"
                + "Catalog: " + items.size() + " | Matches: " + matches + " | Shown: " + definitions.size() + " | Saved: " + savedIds.size());
        recordingDetails.setCaretPosition(0);
    }

    private void save() {
        if (offline == null || busy) return;
        JFileChooser chooser = chooser();
        chooser.setSelectedFile(new File("investigation.jpisession"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (!path.toString().toLowerCase(Locale.ROOT).endsWith(".jpisession")) path = Path.of(path + ".jpisession");
        Path destination = path;
        if (JOptionPane.showConfirmDialog(this, "Save this case, replacing the selected file if it exists?",
                "Save investigation case", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        InvestigationCase snapshot = browser.snapshot();
        long revision = browser.revision();
        long ticket = ++generation;
        busy = true;
        updateActions();
        Async.run(() -> {
            CaseArchive.save(destination, snapshot);
            return destination;
        }, result -> {
            if (ticket != generation) return;
            busy = false;
            saved = true;
            browser.markSaved(revision);
            updateActions();
            status.setText("Offline case saved to " + result.getFileName());
        }, error -> failed(ticket, error));
    }

    private void open() {
        if (busy || recorder != null || !canDiscard()) return;
        JFileChooser chooser = chooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        long ticket = ++generation;
        busy = true;
        updateActions();
        Async.run(() -> CaseArchive.open(path), data -> {
            if (ticket == generation) showOffline(data, true);
        }, error -> failed(ticket, error));
    }

    private static JFileChooser chooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JPI Investigation Case (*.jpisession)", "jpisession"));
        return chooser;
    }

    private boolean canDiscard() {
        return offline == null || saved && !browser.isDirty()
                || JOptionPane.showConfirmDialog(this, "Discard unsaved changes to this case?",
                "Investigation Cases", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }

    private void failed(long ticket, Throwable error) {
        if (ticket != generation) return;
        busy = false;
        starting = false;
        updateActions();
        Ui.error(this, error);
    }

    private void updateActions() {
        boolean recording = recorder != null;
        boolean draft = offline == null && !recording;
        start.setEnabled(draft && session != null && !busy);
        stop.setEnabled(recording && !recorder.isSealed());
        capture.setEnabled(recording && !busy && !selected.isSelectionEmpty());
        refreshCatalog.setEnabled(recording && !busy);
        importReport.setEnabled(recording && !busy);
        importCfg.setEnabled(recording && !busy);
        newCase.setEnabled(!recording && !busy);
        open.setEnabled(!recording && !busy);
        save.setEnabled(offline != null && !busy);
        title.setEnabled(draft && !busy);
        details.setEnabled(draft && !busy);
        if (busy) return;
        if (offline != null) status.setText("Offline case | " + offline.manifest().title() + " | No live actions");
        else if (recording)
            status.setText("Recording case | " + recorder.savedDefinitions().size() + " class definitions saved");
        else
            status.setText(session == null ? "Attach a JVM to record, or open an existing case." : "Ready to record a new case.");
    }

    @Override
    public void close() {
        expiration.stop();
        if (recorder != null) recorder.seal();
        recorder = null;
        generation++;
        browser.setData(null);
        timeline.removeEventListener(eventListener);
    }

    public boolean confirmClose() {
        if (recorder != null) {
            return JOptionPane.showConfirmDialog(this, "Discard the recording and close JPI?",
                    "Investigation Cases", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
        }
        return canDiscard();
    }
}

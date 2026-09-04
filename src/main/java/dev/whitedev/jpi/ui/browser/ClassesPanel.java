package dev.whitedev.jpi.ui.browser;

import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.CodeEditors;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.debug.DebuggerPanel;
import dev.whitedev.jpi.ui.tracing.LiveTracerPanel;
import dev.whitedev.jpi.ui.tracing.XrefsPanel;
import dev.whitedev.jpi.ui.timeline.RuntimeTimelineStore;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.decompile.DecompilerOption;
import dev.whitedev.jpi.decompile.DecompilerService;
import dev.whitedev.jpi.decompile.MethodBodyCompatibility;
import dev.whitedev.jpi.decompile.MethodSourceExtractor;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.deobfuscation.bytecode.DisplayBytecodeRemapper;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.plugin.api.decompile.DecompilerProvider;
import dev.whitedev.jpi.plugin.api.analysis.AnalysisResult;
import dev.whitedev.jpi.plugin.api.analysis.BytecodeAnalyzer;
import dev.whitedev.jpi.plugin.api.analysis.BytecodeTarget;
import dev.whitedev.jpi.plugin.api.deobfuscation.Deobfuscator;
import dev.whitedev.jpi.plugin.api.deobfuscation.MappingSuggestion;
import dev.whitedev.jpi.plugin.runtime.ExtensionRegistry;
import dev.whitedev.jpi.plugin.runtime.RegisteredExtension;
import dev.whitedev.jpi.ui.analysis.BytecodeCfgPanel;
import dev.whitedev.jpi.ui.analysis.DifferenceTracingPanel;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;

public final class ClassesPanel extends JPanel implements SessionAware {
    private static final int MAX_HEX_BYTES = 4 * 1024 * 1024;
    private static final int CLASS_PAGE_SIZE = 500;
    private final DeobfuscationWorkspace mappingWorkspace;
    private final RuntimeTimelineStore timeline;
    private final LiveTracerPanel liveTracer;
    private final XrefsPanel xrefs;
    private final BytecodeCfgPanel cfg;
    private final DifferenceTracingPanel differences;
    private final Runnable openLiveTracer;
    private final Runnable openXrefs;
    private final Runnable openCfg;
    private final Runnable openDifferences;
    private final DefaultListModel<LoadedClassInfo> model = new DefaultListModel<>();
    private final JList<LoadedClassInfo> list = new JList<>(model);
    private final JTextField search = new JTextField();
    private final RSyntaxTextArea source = CodeEditors.javaEditor(true);
    private final JTextArea events = Ui.outputArea();
    private final JTextArea bytecodeHex = Ui.outputArea();
    private final RSyntaxTextArea methodBody = CodeEditors.javaEditor(true);
    private final JComboBox<MethodInfo> methodSelector = new JComboBox<>();
    private final JButton patchMethod = Ui.primaryButton("Apply method body");
    private final JButton traceMethod = Ui.secondaryButton("Trace method");
    private final JButton showXrefs = Ui.secondaryButton("Xrefs");
    private final JButton showCfg = Ui.secondaryButton("CFG");
    private final JButton showDifferences = Ui.secondaryButton("Compare runs");
    private final JButton debugMethod = Ui.secondaryButton("Debug breakpoint");
    private final JButton applyHex = Ui.primaryButton("Apply hex bytecode");
    private final JLabel methodStatus = new JLabel("Select a class and method");
    private final JLabel classCount = new JLabel("0 classes");
    private final JLabel classPageStatus = new JLabel("Page 0 / 0");
    private final JButton previousClassPage = Ui.secondaryButton("Previous");
    private final JButton nextClassPage = Ui.secondaryButton("Next");
    private final JLabel metadata = new JLabel("No class selected");
    private final JButton reload = Ui.secondaryButton("Reload");
    private final JButton decompile = Ui.primaryButton("Decompile");
    private final JButton applySource = Ui.primaryButton("Apply source");
    private final JButton rollback = Ui.secondaryButton("Rollback original");
    private final JButton pluginTools = Ui.secondaryButton("Plugin tools...");
    private final JCheckBox live = new JCheckBox("Live tracking", true);
    private final JComboBox<DecompilerOption> decompilerSelector = new JComboBox<>();
    private final JComboBox<SourceMode> sourceMode = new JComboBox<>(SourceMode.values());
    private final DecompilerService decompilerService = new DecompilerService();
    private final ExtensionRegistry extensions;
    private final javax.swing.Timer classFilterTimer = new javax.swing.Timer(220, event -> filter(false, null));
    private final javax.swing.Timer mappedSourceTimer = new javax.swing.Timer(350, event -> refreshMappedSource());
    private List<LoadedClassInfo> allClasses = new ArrayList<>();
    private List<LoadedClassInfo> filteredClasses = Collections.emptyList();
    private InspectorSession session;
    private boolean reloading;
    private boolean updatingClassList;
    private int classPage;
    private long filterGeneration;
    private long capturedClassCount;
    private String methodClassId;
    private boolean updatingMethodSelector;
    private long methodLoadGeneration;
    private long decompileGeneration;
    private boolean methodBodyLoading;
    private boolean methodBodyReady;
    private boolean redefinitionControlsEnabled = true;
    private DebuggerPanel debugger;
    private Runnable openDebugger;

    public ClassesPanel(DeobfuscationWorkspace mappingWorkspace, LiveTracerPanel liveTracer, XrefsPanel xrefs,
                 BytecodeCfgPanel cfg, Runnable openLiveTracer, Runnable openXrefs, Runnable openCfg) {
        this(mappingWorkspace, liveTracer, xrefs, cfg, openLiveTracer, openXrefs, openCfg, new ExtensionRegistry(),
                new RuntimeTimelineStore());
    }

    public ClassesPanel(DeobfuscationWorkspace mappingWorkspace, LiveTracerPanel liveTracer, XrefsPanel xrefs,
                 BytecodeCfgPanel cfg, Runnable openLiveTracer, Runnable openXrefs, Runnable openCfg,
                 ExtensionRegistry extensions) {
        this(mappingWorkspace, liveTracer, xrefs, cfg, openLiveTracer, openXrefs, openCfg, extensions,
                new RuntimeTimelineStore());
    }

    public ClassesPanel(DeobfuscationWorkspace mappingWorkspace, LiveTracerPanel liveTracer, XrefsPanel xrefs,
                 BytecodeCfgPanel cfg, Runnable openLiveTracer, Runnable openXrefs, Runnable openCfg,
                 ExtensionRegistry extensions, RuntimeTimelineStore timeline) {
        this(mappingWorkspace, liveTracer, xrefs, cfg, null, openLiveTracer, openXrefs, openCfg, null,
                extensions, timeline);
    }

    public ClassesPanel(DeobfuscationWorkspace mappingWorkspace, LiveTracerPanel liveTracer, XrefsPanel xrefs,
                 BytecodeCfgPanel cfg, DifferenceTracingPanel differences, Runnable openLiveTracer,
                 Runnable openXrefs, Runnable openCfg, Runnable openDifferences,
                 ExtensionRegistry extensions, RuntimeTimelineStore timeline) {
        super(new BorderLayout(0, 16));
        this.mappingWorkspace = mappingWorkspace;
        this.timeline = timeline;
        this.liveTracer = liveTracer;
        this.xrefs = xrefs;
        this.cfg = cfg;
        this.differences = differences;
        this.openLiveTracer = openLiveTracer;
        this.openXrefs = openXrefs;
        this.openCfg = openCfg;
        this.openDifferences = openDifferences;
        this.extensions = extensions;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);
        mappingWorkspace.addListener(() -> SwingUtilities.invokeLater(() -> {
            filter(true, null);
            list.repaint();
            methodSelector.repaint();
            if (isMappedSource()) mappedSourceTimer.restart();
        }));
        add(Ui.sectionHeader("Loaded classes",
                "Track definitions, duplicate names, classloaders, modules, and captured bytecode", null),
                BorderLayout.NORTH);

        JPanel browser = Ui.card(new BorderLayout(0, 10));
        search.putClientProperty("JTextField.placeholderText", "Class, loader, module, or kind");
        search.putClientProperty("JTextField.showClearButton", true);
        search.setPreferredSize(new Dimension(280, 36));
        browser.add(search, BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(28);
        list.setCellRenderer(new ClassRenderer());
        browser.add(Ui.scroll(list), BorderLayout.CENTER);
        classCount.setForeground(Ui.MUTED);
        classPageStatus.setForeground(Ui.MUTED);
        JPanel classFooter = new JPanel(new BorderLayout(8, 0));
        classFooter.setOpaque(false);
        JPanel classPaging = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        classPaging.setOpaque(false);
        classPaging.add(previousClassPage);
        classPaging.add(classPageStatus);
        classPaging.add(nextClassPage);
        classFooter.add(classCount, BorderLayout.CENTER);
        classFooter.add(classPaging, BorderLayout.EAST);
        browser.add(classFooter, BorderLayout.SOUTH);

        JPanel detail = Ui.card(new BorderLayout(0, 10));
        JPanel tools = new JPanel(new GridLayout(2, 1, 0, 6));
        tools.setOpaque(false);
        metadata.setForeground(Ui.MUTED);
        JPanel generalRow = new JPanel(new BorderLayout(8, 0));
        generalRow.setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton dump = Ui.secondaryButton("Dump selected");
        JButton dumpAll = Ui.secondaryButton("Dump all");
        JButton refreshEvents = Ui.secondaryButton("Refresh events");
        decompilerSelector.setPreferredSize(new Dimension(150, 34));
        sourceMode.setPreferredSize(new Dimension(150, 34));
        sourceMode.setToolTipText("Original source can be edited; mapped source is a read-only analysis view");
        reload.addActionListener(e -> reload(true));
        decompile.addActionListener(e -> decompile());
        applySource.addActionListener(e -> applySource());
        rollback.addActionListener(e -> rollback());
        dump.addActionListener(e -> dumpSelected());
        dumpAll.addActionListener(e -> dumpAll());
        refreshEvents.addActionListener(e -> refreshEvents());
        pluginTools.addActionListener(event -> runPluginTool());
        actions.add(live);
        actions.add(refreshEvents);
        actions.add(reload);
        actions.add(dump);
        actions.add(dumpAll);
        actions.add(pluginTools);
        generalRow.add(metadata, BorderLayout.CENTER);
        generalRow.add(actions, BorderLayout.EAST);

        JPanel sourceActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        sourceActions.setOpaque(false);
        JLabel sourceHint = new JLabel("Mapped source is display-only and never changes the target");
        sourceHint.setForeground(Ui.MUTED);
        sourceActions.add(sourceHint);
        sourceActions.add(new JLabel("Decompiler"));
        sourceActions.add(decompilerSelector);
        sourceActions.add(new JLabel("Source"));
        sourceActions.add(sourceMode);
        sourceActions.add(decompile);
        sourceActions.add(rollback);
        sourceActions.add(applySource);
        tools.add(generalRow);
        tools.add(sourceActions);
        detail.add(tools, BorderLayout.NORTH);
        source.setText("Select a class and click Decompile.");
        events.setText("Class definition events will appear here.");
        bytecodeHex.setEditable(true);
        bytecodeHex.setText("Decompile a selected class to load its bytecode.");
        methodBody.setText("Select a method to create a replacement body.");
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Decompiled source", CodeEditors.scrollPane(source));
        tabs.addTab("Method patch", methodPatchPanel());
        tabs.addTab("Raw bytecode", rawBytecodePanel());
        tabs.addTab("Dynamic load events", Ui.scroll(events));
        detail.add(tabs, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, browser, detail);
        split.setResizeWeight(.28);
        split.setDividerLocation(340);
        split.setDividerSize(8);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        classFilterTimer.setRepeats(false);
        mappedSourceTimer.setRepeats(false);
        previousClassPage.addActionListener(event -> changeClassPage(-1));
        nextClassPage.addActionListener(event -> changeClassPage(1));
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { scheduleFilter(); }
            public void removeUpdate(DocumentEvent event) { scheduleFilter(); }
            public void changedUpdate(DocumentEvent event) { scheduleFilter(); }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) decompile();
            }
        });
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) updateSelectionMetadata();
        });
        methodSelector.addActionListener(event -> methodChanged());
        decompilerSelector.addActionListener(event -> decompilerChanged());
        extensions.addListener(() -> SwingUtilities.invokeLater(() -> {
            refreshDecompilers();
            refreshPluginTools();
        }));
        refreshDecompilers();
        refreshPluginTools();
        sourceMode.addActionListener(event -> sourceModeChanged());
        methodBody.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { refreshMethodPatchState(); }
            public void removeUpdate(DocumentEvent event) { refreshMethodPatchState(); }
            public void changedUpdate(DocumentEvent event) { refreshMethodPatchState(); }
        });
        new javax.swing.Timer(3000, event -> {
            if (live.isSelected() && session != null) {
                reload(false);
                refreshEvents();
            }
        }).start();
    }

    @Override public void setSession(InspectorSession session) {
        this.session = session;
        methodClassId = null;
        methodBodyLoading = false;
        methodBodyReady = false;
        redefinitionControlsEnabled = true;
        methodLoadGeneration++;
        boolean connected = session != null;
        reload.setEnabled(connected);
        decompile.setEnabled(connected);
        applySource.setEnabled(false);
        rollback.setEnabled(false);
        patchMethod.setEnabled(false);
        traceMethod.setEnabled(false);
        showXrefs.setEnabled(false);
        showCfg.setEnabled(false);
        showDifferences.setEnabled(false);
        debugMethod.setEnabled(false);
        applyHex.setEnabled(false);
        refreshPluginTools();
        live.setEnabled(connected);
        if (connected) {
            reload(true);
            refreshEvents();
        } else {
            filterGeneration++;
            classFilterTimer.stop();
            allClasses.clear();
            filteredClasses = Collections.emptyList();
            classPage = 0;
            capturedClassCount = 0;
            model.clear();
            classCount.setText("0 classes");
            classPageStatus.setText("Page 0 / 0");
            previousClassPage.setEnabled(false);
            nextClassPage.setEnabled(false);
            metadata.setText("Attach required");
            events.setText("Class definition events will appear here.");
            methodSelector.removeAllItems();
            methodBody.setText("Select a method to create a replacement body.");
            bytecodeHex.setText("Decompile a selected class to load its bytecode.");
        }
    }

    private void reload(boolean showLoading) {
        final InspectorSession current = session;
        if (current == null || reloading) return;
        reloading = true;
        reload.setEnabled(false);
        if (showLoading && allClasses.isEmpty()) classCount.setText("Loading...");
        Async.run(() -> parseClassInventory(current.requestText(Operation.CLASSES, "")), inventory -> {
            allClasses = inventory.classes;
            capturedClassCount = inventory.captured;
            filter(true, () -> {
                reloading = false;
                if (list.getSelectedValue() == null) updateSelectionMetadata();
                reload.setEnabled(true);
            });
        }, error -> {
            reloading = false;
            reload.setEnabled(true);
            if (showLoading) Ui.error(this, error);
        });
    }

    private static ClassInventory parseClassInventory(String value) {
        List<LoadedClassInfo> parsed = new ArrayList<>();
        long captured = 0;
        int start = 0;
        while (start < value.length()) {
            int end = value.indexOf(10, start);
            if (end < 0) end = value.length();
            if (end > start) {
                LoadedClassInfo info = LoadedClassInfo.parse(value.substring(start, end));
                if (info != null) {
                    parsed.add(info);
                    if (info.captured) captured++;
                }
            }
            start = end + 1;
        }
        return new ClassInventory(parsed, captured);
    }

    private void scheduleFilter() {
        classFilterTimer.restart();
    }

    private void filter(boolean preservePage, Runnable completion) {
        final long generation = ++filterGeneration;
        final String query = search.getText().trim().toLowerCase(Locale.ROOT);
        final List<LoadedClassInfo> snapshot = new ArrayList<>(allClasses);
        final LoadedClassInfo selected = list.getSelectedValue();
        final String selectedId = selected == null ? null : selected.id;
        final int requestedPage = preservePage ? classPage : 0;
        Async.run(() -> {
            if (query.isEmpty()) return snapshot;
            List<LoadedClassInfo> matches = new ArrayList<>();
            for (LoadedClassInfo info : snapshot) {
                if (info.matches(query) || mappingWorkspace.classAlias(info.name)
                        .toLowerCase(Locale.ROOT).contains(query)) matches.add(info);
            }
            return matches;
        }, matches -> {
            if (generation != filterGeneration) {
                if (completion != null) completion.run();
                return;
            }
            filteredClasses = matches;
            int lastPage = Math.max(0, (matches.size() - 1) / CLASS_PAGE_SIZE);
            classPage = Math.min(requestedPage, lastPage);
            renderClassPage(selectedId);
            if (completion != null) completion.run();
        }, error -> {
            if (generation != filterGeneration) {
                if (completion != null) completion.run();
                return;
            }
            if (completion != null) completion.run();
            Ui.error(this, error);
        });
    }

    private void changeClassPage(int change) {
        int lastPage = Math.max(0, (filteredClasses.size() - 1) / CLASS_PAGE_SIZE);
        int next = Math.max(0, Math.min(lastPage, classPage + change));
        if (next == classPage) return;
        classPage = next;
        renderClassPage(null);
    }

    private void renderClassPage(String selectedId) {
        LoadedClassInfo previous = list.getSelectedValue();
        String previousId = previous == null ? null : previous.id;
        updatingClassList = true;
        try {
            model.clear();
            int start = Math.min(filteredClasses.size(), classPage * CLASS_PAGE_SIZE);
            int end = Math.min(filteredClasses.size(), start + CLASS_PAGE_SIZE);
            for (int index = start; index < end; index++) model.addElement(filteredClasses.get(index));
            restoreSelection(selectedId);
        } finally {
            updatingClassList = false;
        }
        int pages = filteredClasses.isEmpty() ? 0 : (filteredClasses.size() + CLASS_PAGE_SIZE - 1) / CLASS_PAGE_SIZE;
        classPageStatus.setText("Page " + (pages == 0 ? 0 : classPage + 1) + " / " + pages);
        previousClassPage.setEnabled(classPage > 0);
        nextClassPage.setEnabled(classPage + 1 < pages);
        classCount.setText(model.size() + " on page  |  " + filteredClasses.size()
                + " matches  |  " + allClasses.size() + " loaded  |  "
                + capturedClassCount + " bytecode captures");
        LoadedClassInfo current = list.getSelectedValue();
        String currentId = current == null ? null : current.id;
        if (!Objects.equals(previousId, currentId)) updateSelectionMetadata();
    }

    private void restoreSelection(String id) {
        if (id == null) return;
        for (int index = 0; index < model.size(); index++) {
            if (id.equals(model.get(index).id)) {
                list.setSelectedIndex(index);
                return;
            }
        }
    }
    private void updateSelectionMetadata() {
        if (updatingClassList) return;
        LoadedClassInfo selected = list.getSelectedValue();
        refreshPluginTools();
        if (selected == null && reloading) return;
        decompileGeneration++;
        decompile.setEnabled(session != null);
        if (selected == null) {
            metadata.setText("No class selected");
            applySource.setEnabled(false);
            rollback.setEnabled(false);
            methodBodyLoading = false;
            methodBodyReady = false;
            methodLoadGeneration++;
            refreshMethodPatchState();
            applyHex.setEnabled(false);
            return;
        }
        boolean canRedefine = session != null && selected.modifiable && !"captured-only".equals(selected.kind);
        applySource.setEnabled(canRedefine && !isMappedSource());
        rollback.setEnabled(canRedefine);
        applyHex.setEnabled(canRedefine);
        metadata.setText(selected.kind + "  |  " + selected.module + "  |  "
                + (selected.modifiable ? "modifiable" : "read-only") + "  |  "
                + (selected.captured ? "bytecode captured" : "bytecode on demand"));
        loadMethods();
    }

    private boolean isMappedSource() {
        return sourceMode.getSelectedItem() == SourceMode.MAPPED_READ_ONLY;
    }

    private void sourceModeChanged() {
        source.setEditable(!isMappedSource());
        decompileGeneration++;
        setRedefinitionControls(true);
        if (session != null && list.getSelectedValue() != null) decompile();
    }

    private void refreshMappedSource() {
        if (isMappedSource() && session != null && list.getSelectedValue() != null) decompile();
    }

    private DecompilerOption selectedDecompiler() {
        DecompilerOption selected = (DecompilerOption) decompilerSelector.getSelectedItem();
        return selected == null ? DecompilerOption.builtIns().getFirst() : selected;
    }

    private void refreshDecompilers() {
        DecompilerOption selected = (DecompilerOption) decompilerSelector.getSelectedItem();
        decompilerSelector.removeAllItems();
        for (DecompilerOption option : DecompilerOption.builtIns()) decompilerSelector.addItem(option);
        for (RegisteredExtension<DecompilerProvider> registered : extensions.decompilers()) {
            decompilerSelector.addItem(DecompilerOption.plugin(registered.pluginId(), registered.extension()));
        }
        if (selected != null) decompilerSelector.setSelectedItem(selected);
        if (decompilerSelector.getSelectedIndex() < 0 && decompilerSelector.getItemCount() > 0) {
            decompilerSelector.setSelectedIndex(0);
        }
    }

    private void refreshPluginTools() {
        boolean available = !extensions.analyzers().isEmpty() || !extensions.deobfuscators().isEmpty();
        pluginTools.setEnabled(available && session != null && list.getSelectedValue() != null);
        pluginTools.setToolTipText(available ? "Run a plugin analyzer or deobfuscator on the selected definition"
                : "No plugin bytecode tools are registered");
    }

    private void runPluginTool() {
        LoadedClassInfo selected = list.getSelectedValue();
        InspectorSession current = session;
        if (selected == null || current == null) return;
        List<PluginTool> tools = new ArrayList<>();
        for (RegisteredExtension<BytecodeAnalyzer> value : extensions.analyzers()) {
            tools.add(new PluginTool(value.pluginId(), value.extension().name(), value.extension(), null));
        }
        for (RegisteredExtension<Deobfuscator> value : extensions.deobfuscators()) {
            tools.add(new PluginTool(value.pluginId(), value.extension().name(), null, value.extension()));
        }
        PluginTool tool = (PluginTool) JOptionPane.showInputDialog(this, "Choose an extension to run",
                "Plugin bytecode tools", JOptionPane.PLAIN_MESSAGE, null, tools.toArray(), null);
        if (tool == null) return;
        MethodInfo method = (MethodInfo) methodSelector.getSelectedItem();
        String methodName = method == null ? "" : method.name;
        String descriptor = method == null ? "" : method.descriptor;
        pluginTools.setEnabled(false);
        Async.run(() -> {
            byte[] bytecode = current.request(Operation.CLASS_BYTES, selected.id);
            BytecodeTarget target = new BytecodeTarget(selected.id, selected.name, bytecode, methodName, descriptor);
            if (tool.analyzer != null) return PluginToolResult.analysis(tool.analyzer.analyze(target));
            return PluginToolResult.suggestions(tool.deobfuscator.suggest(target));
        }, result -> {
            refreshPluginTools();
            if (result.analysis != null) showPluginAnalysis(result.analysis);
            else applyPluginSuggestions(tool, result.suggestions);
        }, error -> {
            refreshPluginTools();
            Ui.error(this, error);
        });
    }

    private void showPluginAnalysis(AnalysisResult result) {
        JTextArea output = Ui.outputArea();
        output.setText(result.content());
        output.setCaretPosition(0);
        JScrollPane scroll = Ui.scroll(output);
        scroll.setPreferredSize(new Dimension(900, 620));
        JOptionPane.showMessageDialog(this, scroll, result.title(), JOptionPane.PLAIN_MESSAGE);
    }

    private void applyPluginSuggestions(PluginTool tool, List<MappingSuggestion> suggestions) {
        List<MappingSuggestion> values = suggestions == null ? List.of() : suggestions.stream().limit(10_000).toList();
        if (values.isEmpty()) {
            JOptionPane.showMessageDialog(this, "The deobfuscator returned no mapping suggestions.",
                    tool.name, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        StringBuilder preview = new StringBuilder();
        for (int index = 0; index < Math.min(values.size(), 200); index++) {
            MappingSuggestion value = values.get(index);
            preview.append(value.type()).append("  ").append(value.owner()).append('.')
                    .append(value.originalName()).append(value.descriptor()).append("  ->  ")
                    .append(value.suggestedName()).append("  ")
                    .append(String.format(Locale.ROOT, "%.0f%%", value.confidence() * 100.0)).append('\n');
        }
        if (values.size() > 200) preview.append("\n...").append(values.size() - 200).append(" more suggestions");
        JTextArea output = Ui.outputArea();
        output.setText(preview.toString());
        output.setCaretPosition(0);
        JScrollPane scroll = Ui.scroll(output);
        scroll.setPreferredSize(new Dimension(900, 560));
        int choice = JOptionPane.showConfirmDialog(this, scroll,
                "Apply " + values.size() + " suggestions from " + tool.name,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;
        Map<String, dev.whitedev.jpi.deobfuscation.MappingEntry> current = new HashMap<>();
        for (dev.whitedev.jpi.deobfuscation.MappingEntry entry : mappingWorkspace.entries()) current.put(entry.key(), entry);
        List<dev.whitedev.jpi.deobfuscation.MappingEntry> accepted = new ArrayList<>();
        int preserved = 0;
        for (MappingSuggestion suggestion : values) {
            dev.whitedev.jpi.deobfuscation.MappingEntry candidate = new dev.whitedev.jpi.deobfuscation.MappingEntry(
                    dev.whitedev.jpi.deobfuscation.MappingKind.valueOf(suggestion.type().name()),
                    suggestion.owner(), suggestion.originalName(), suggestion.descriptor(),
                    suggestion.parameterIndex(), 0);
            dev.whitedev.jpi.deobfuscation.MappingEntry existing = current.get(candidate.key());
            if (existing != null && !existing.mappedName().isBlank()) {
                preserved++;
                continue;
            }
            dev.whitedev.jpi.deobfuscation.MappingEntry result = existing == null ? candidate : existing.copy();
            result.setMappedName(suggestion.suggestedName());
            result.setComment(suggestion.reason());
            result.setTags("plugin," + tool.pluginId);
            accepted.add(result);
        }
        mappingWorkspace.merge(accepted);
        JOptionPane.showMessageDialog(this, "Applied " + accepted.size() + " suggestions. Preserved "
                + preserved + " existing manual mappings.", tool.name, JOptionPane.INFORMATION_MESSAGE);
    }

    private void decompilerChanged() {
        methodClassId = null;
        methodLoadGeneration++;
        decompileGeneration++;
        methodBodyLoading = false;
        methodBodyReady = false;
        refreshMethodPatchState();
        if (session != null && list.getSelectedValue() != null) decompile();
    }

    private void decompile() {
        final LoadedClassInfo selected = list.getSelectedValue();
        final InspectorSession current = session;
        final DecompilerOption engine = selectedDecompiler();
        final SourceMode mode = (SourceMode) sourceMode.getSelectedItem();
        final long generation = ++decompileGeneration;
        if (selected == null || current == null || mode == null) return;
        decompile.setEnabled(false);
        source.setEditable(mode != SourceMode.MAPPED_READ_ONLY);
        String view = mode == SourceMode.MAPPED_READ_ONLY ? "mapped read-only" : "original editable";
        source.setText("Reading bytecode and decompiling " + selected.name + " as " + view + " with " + engine + "...");
        Async.run(() -> {
            byte[] bytecode = current.request(Operation.CLASS_BYTES, selected.id);
            byte[] displayBytecode = mode == SourceMode.MAPPED_READ_ONLY
                    ? DisplayBytecodeRemapper.remap(bytecode, mappingWorkspace) : bytecode;
            String displayName = mode == SourceMode.MAPPED_READ_ONLY
                    ? mappingWorkspace.classAlias(selected.name) : selected.name;
            String decompiled;
            try {
                decompiled = decompilerService.decompile(engine, displayName, displayBytecode);
            } catch (Exception error) {
                decompiled = engine + " could not produce source for this class.\n"
                        + "Method patch and raw bytecode remain available.\n\n" + error.getMessage();
            }
            return new DecompiledResult(bytecode, decompiled);
        }, result -> {
            if (!decompileMatches(generation, selected.id, engine, mode)) return;
            source.setText(result.source);
            source.setCaretPosition(0);
            renderHex(result.bytecode);
            loadMethods();
            metadata.setText(selected.kind + "  |  " + view + "  |  " + result.bytecode.length
                    + " bytes  |  SHA-256 " + sha256(result.bytecode));
            setRedefinitionControls(true);
            decompile.setEnabled(true);
        }, error -> {
            if (!decompileMatches(generation, selected.id, engine, mode)) return;
            source.setText("Decompilation failed.\n" + error.getMessage());
            decompile.setEnabled(true);
            Ui.error(this, error);
        });
    }

    private boolean decompileMatches(long generation, String classId, DecompilerOption engine,
                                     SourceMode mode) {
        LoadedClassInfo selected = list.getSelectedValue();
        return generation == decompileGeneration && selected != null && classId.equals(selected.id)
                && engine.equals(selectedDecompiler()) && mode == sourceMode.getSelectedItem();
    }
    private JPanel methodPatchPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);
        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setOpaque(false);
        methodSelector.setPreferredSize(new Dimension(460, 34));
        JLabel help = new JLabel("Java body: { return value; }   Parameters: $1, $2   Instance: this or $0   Lambdas: Java compiler backend");
        help.setForeground(Ui.MUTED);
        methodStatus.setForeground(Ui.MUTED);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        controls.setOpaque(false);
        JButton refresh = Ui.secondaryButton("Refresh methods");
        refresh.addActionListener(event -> loadMethods(true));
        patchMethod.addActionListener(event -> patchSelectedMethod());
        traceMethod.addActionListener(event -> traceSelectedMethod());
        showXrefs.addActionListener(event -> showSelectedXrefs());
        showCfg.addActionListener(event -> showSelectedCfg());
        showDifferences.addActionListener(event -> showSelectedDifferences());
        debugMethod.addActionListener(event -> debugSelectedMethod());
        controls.add(refresh);
        controls.add(showCfg);
        controls.add(showDifferences);
        controls.add(showXrefs);
        controls.add(debugMethod);
        controls.add(traceMethod);
        controls.add(patchMethod);
        header.add(methodSelector, BorderLayout.CENTER);
        header.add(controls, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);
        panel.add(CodeEditors.scrollPane(methodBody), BorderLayout.CENTER);
        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.add(methodStatus);
        footer.add(help);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel rawBytecodePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JLabel help = new JLabel("Hex editor for the complete class file. Schema validation and JVM verification run before activation.");
        help.setForeground(Ui.MUTED);
        JButton loadClass = Ui.secondaryButton("Apply .class file...");
        loadClass.addActionListener(event -> applyClassFile());
        applyHex.addActionListener(event -> applyHexBytecode());
        actions.add(loadClass);
        actions.add(applyHex);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(help, BorderLayout.CENTER);
        header.add(actions, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);
        panel.add(Ui.scroll(bytecodeHex), BorderLayout.CENTER);
        return panel;
    }

    private void loadMethods() {
        loadMethods(false);
    }

    private void loadMethods(boolean force) {
        final LoadedClassInfo selected = list.getSelectedValue();
        final InspectorSession current = session;
        if (selected == null || current == null || "captured-only".equals(selected.kind)) return;
        final String selectedId = selected.id;
        if (!force && selectedId.equals(methodClassId) && methodSelector.getItemCount() > 0) return;
        methodBodyLoading = true;
        methodBodyReady = false;
        methodLoadGeneration++;
        methodStatus.setText("Loading methods...");
        refreshMethodPatchState();
        Async.run(() -> current.requestText(Operation.CLASS_METHODS, selectedId), raw -> {
            LoadedClassInfo active = list.getSelectedValue();
            if (active == null || !selectedId.equals(active.id)) return;
            updatingMethodSelector = true;
            methodSelector.removeAllItems();
            for (String line : raw.split("\\n")) {
                MethodInfo method = parseMethodInfo(line, selected.name);
                if (method != null) methodSelector.addItem(method);
            }
            updatingMethodSelector = false;
            methodClassId = selectedId;
            methodChanged();
        }, error -> {
            methodClassId = null;
            updatingMethodSelector = false;
            methodBodyLoading = false;
            methodBodyReady = false;
            methodSelector.removeAllItems();
            methodBody.setText("Method inventory unavailable.\n" + error.getMessage());
            methodStatus.setText("Method inventory failed: " + error.getMessage());
            refreshMethodPatchState();
        });
    }

    private void methodChanged() {
        if (updatingMethodSelector) return;
        MethodInfo method = (MethodInfo) methodSelector.getSelectedItem();
        LoadedClassInfo selected = list.getSelectedValue();
        String unavailable = methodUnavailableReason(selected, method);
        patchMethod.setToolTipText(unavailable);
        if (unavailable != null) {
            methodLoadGeneration++;
            methodBodyLoading = false;
            methodBodyReady = false;
            if (method != null) methodBody.setText(method.template());
            refreshMethodPatchState();
            return;
        }
        loadMethodImplementation(selected, method);
    }

    private String methodUnavailableReason(LoadedClassInfo selected, MethodInfo method) {
        if (session == null) return "Attach to a JVM first";
        if (selected == null) return "Select a class first";
        if ("captured-only".equals(selected.kind)) return "The class is no longer loaded and can only be dumped";
        if (!selected.modifiable) return "The target JVM marks this class as read-only";
        if (method == null) return "Select a method";
        if (!method.patchable) return "Constructors, class initializers, abstract methods, and native methods are read-only";
        return null;
    }

    private void loadMethodImplementation(LoadedClassInfo selected, MethodInfo method) {
        final InspectorSession current = session;
        final long generation = ++methodLoadGeneration;
        final String selectedId = selected.id;
        final String methodKey = method.name + method.descriptor;
        final DecompilerOption engine = selectedDecompiler();
        methodBodyLoading = true;
        methodBodyReady = false;
        methodStatus.setText("Loading the current implementation of " + methodKey + "...");
        refreshMethodPatchState();
        methodBody.setText("Loading current method implementation...");
        Async.run(() -> {
            byte[] bytecode = current.request(Operation.CLASS_BYTES, selectedId);
            String decompiled = decompilerService.decompileMethod(
                    engine, selected.name, method.name, method.descriptor, bytecode);
            return MethodSourceExtractor.extract(decompiled, method.name, method.descriptor);
        }, body -> {
            if (!methodSelectionMatches(generation, selectedId, methodKey)) return;
            methodBody.setText(body);
            methodBody.setCaretPosition(0);
            methodBodyLoading = false;
            methodBodyReady = true;
            refreshMethodPatchState();
        }, error -> {
            if (!methodSelectionMatches(generation, selectedId, methodKey)) return;
            methodBody.setText(method.template());
            methodBodyLoading = false;
            methodBodyReady = true;
            methodStatus.setText("Current implementation could not be extracted. Editable template loaded: " + error.getMessage());
            refreshMethodPatchState();
        });
    }

    private boolean methodSelectionMatches(long generation, String classId, String methodKey) {
        LoadedClassInfo selected = list.getSelectedValue();
        MethodInfo method = (MethodInfo) methodSelector.getSelectedItem();
        return generation == methodLoadGeneration && selected != null && classId.equals(selected.id)
                && method != null && methodKey.equals(method.name + method.descriptor);
    }

    private void refreshMethodPatchState() {
        LoadedClassInfo selected = list.getSelectedValue();
        MethodInfo method = (MethodInfo) methodSelector.getSelectedItem();
        String reason = methodPatchUnavailableReason(selected, method);
        boolean available = reason == null;
        boolean methodAvailable = methodUnavailableReason(selected, method) == null;
        traceMethod.setEnabled(methodAvailable);
        showXrefs.setEnabled(methodAvailable);
        String cfgUnavailable = cfgUnavailableReason(selected, method);
        showCfg.setEnabled(cfgUnavailable == null);
        showCfg.setToolTipText(cfgUnavailable);
        showDifferences.setEnabled(differences != null && cfgUnavailable == null);
        showDifferences.setToolTipText(differences == null ? "Difference tracing is unavailable" : cfgUnavailable);
        debugMethod.setEnabled(debugger != null && methodAvailable);
        patchMethod.setEnabled(available);
        if (available) {
            String warning = MethodBodyCompatibility.unsupportedReason(methodBody.getText());
            patchMethod.setToolTipText(warning);
            methodStatus.setText(warning == null
                    ? "Ready to replace the selected implementation. Parameters use $1, $2, and following values."
                    : "Ready to try. " + warning + ", so the target compiler may reject this body.");
        } else {
            patchMethod.setToolTipText(reason);
            methodStatus.setText(reason);
        }
    }

    private String methodPatchUnavailableReason(LoadedClassInfo selected, MethodInfo method) {
        String unavailable = methodUnavailableReason(selected, method);
        if (unavailable != null) return unavailable;
        if (!redefinitionControlsEnabled) return "A class redefinition is already in progress";
        if (methodBodyLoading) return "Loading the selected method implementation";
        if (!methodBodyReady) return "The selected method implementation is not ready";
        return null;
    }

    private String cfgUnavailableReason(LoadedClassInfo selected, MethodInfo method) {
        if (session == null) return "Attach to a JVM first";
        if (selected == null) return "Select a class first";
        if ("captured-only".equals(selected.kind)) return "The class is no longer loaded";
        if (method == null) return "Select a method";
        return null;
    }

    private void showSelectedCfg() {
        LoadedClassInfo selected = list.getSelectedValue();
        MethodInfo method = (MethodInfo) methodSelector.getSelectedItem();
        String unavailable = cfgUnavailableReason(selected, method);
        if (unavailable != null) {
            Ui.error(this, new IllegalStateException(unavailable));
            return;
        }
        cfg.selectTarget(selected.id, selected.name, method.name, method.descriptor);
        openCfg.run();
    }

    public void setDebuggerIntegration(DebuggerPanel debugger, Runnable openDebugger) {
        this.debugger = debugger;
        this.openDebugger = openDebugger;
        refreshMethodPatchState();
    }

    public void selectClass(String className) {
        if (className == null || className.isBlank()) return;
        search.setText(className);
        filter(false, () -> {
            for (int index = 0; index < model.size(); index++) {
                if (!className.equals(model.get(index).name)) continue;
                list.setSelectedIndex(index);
                list.ensureIndexIsVisible(index);
                decompile();
                return;
            }
        });
    }

    private void debugSelectedMethod() {
        LoadedClassInfo selected = list.getSelectedValue();
        MethodInfo method = (MethodInfo) methodSelector.getSelectedItem();
        String unavailable = methodUnavailableReason(selected, method);
        if (unavailable != null || debugger == null || openDebugger == null) {
            Ui.error(this, new IllegalStateException(unavailable == null ? "Debugger is unavailable" : unavailable));
            return;
        }
        debugger.selectTarget(selected.name, method.name, method.descriptor);
        openDebugger.run();
    }

    private void showSelectedDifferences() {
        LoadedClassInfo selected = list.getSelectedValue();
        MethodInfo method = (MethodInfo) methodSelector.getSelectedItem();
        String unavailable = cfgUnavailableReason(selected, method);
        if (unavailable != null || differences == null || openDifferences == null) {
            Ui.error(this, new IllegalStateException(unavailable == null
                    ? "Difference tracing is unavailable" : unavailable));
            return;
        }
        differences.selectTarget(selected.id, selected.name, method.name, method.descriptor);
        openDifferences.run();
    }

    private void showSelectedXrefs() {
        LoadedClassInfo selected = list.getSelectedValue();
        MethodInfo method = (MethodInfo) methodSelector.getSelectedItem();
        String unavailable = methodUnavailableReason(selected, method);
        if (unavailable != null) {
            Ui.error(this, new IllegalStateException(unavailable));
            return;
        }
        xrefs.selectTarget(selected.id, selected.name, method.name, method.descriptor);
        openXrefs.run();
    }

    private void traceSelectedMethod() {
        LoadedClassInfo selected = list.getSelectedValue();
        MethodInfo method = (MethodInfo) methodSelector.getSelectedItem();
        String unavailable = methodUnavailableReason(selected, method);
        if (unavailable != null) {
            Ui.error(this, new IllegalStateException(unavailable));
            return;
        }
        liveTracer.selectTarget(selected.id, selected.name, method.name, method.descriptor);
        openLiveTracer.run();
    }

    private void patchSelectedMethod() {
        final LoadedClassInfo selected = list.getSelectedValue();
        final MethodInfo method = (MethodInfo) methodSelector.getSelectedItem();
        final InspectorSession current = session;
        String unavailable = methodPatchUnavailableReason(selected, method);
        if (unavailable != null) {
            refreshMethodPatchState();
            Ui.error(this, new IllegalStateException(unavailable));
            return;
        }
        String body = methodBody.getText().trim();
        String message = "Replace only " + selected.name + "." + method.name + method.descriptor + "?\n\n"
                + "This avoids compiling the remaining decompiled source. Existing calls already on the stack may finish with the old body.";
        if (JOptionPane.showConfirmDialog(this, message, "Apply method patch",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        setRedefinitionControls(false);
        String payload = selected.id + "\n" + method.name + "\n" + method.descriptor + "\n" + body;
        Async.run(() -> current.requestText(Operation.PATCH_METHOD, payload), result -> {
            metadata.setText(result);
            setRedefinitionControls(true);
            refreshEvents();
            decompile();
        }, error -> {
            setRedefinitionControls(true);
            Ui.error(this, error);
        });
    }

    private void renderHex(byte[] bytecode) {
        if (bytecode.length > MAX_HEX_BYTES) {
            bytecodeHex.setText("Class is too large for the interactive hex editor: " + bytecode.length
                    + " bytes. Use Dump selected and Apply .class file instead.");
            applyHex.setEnabled(false);
            return;
        }
        StringBuilder output = new StringBuilder(bytecode.length * 2 + bytecode.length / 32);
        for (int index = 0; index < bytecode.length; index++) {
            if (index > 0) output.append(index % 32 == 0 ? '\n' : ' ');
            output.append(String.format("%02x", bytecode[index] & 0xff));
        }
        bytecodeHex.setText(output.toString());
        bytecodeHex.setCaretPosition(0);
        LoadedClassInfo selected = list.getSelectedValue();
        applyHex.setEnabled(session != null && selected != null && selected.modifiable);
    }

    private void applyHexBytecode() {
        try {
            applyBytecode(parseHex(bytecodeHex.getText()), "edited hexadecimal bytecode");
        } catch (Exception error) {
            Ui.error(this, error);
        }
    }

    private void applyClassFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select replacement class file");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            applyBytecode(Files.readAllBytes(chooser.getSelectedFile().toPath()), chooser.getSelectedFile().getName());
        } catch (Exception error) {
            Ui.error(this, error);
        }
    }

    private void applyBytecode(byte[] bytecode, String sourceName) {
        final LoadedClassInfo selected = list.getSelectedValue();
        final InspectorSession current = session;
        if (selected == null || current == null || !selected.modifiable) return;
        if (bytecode.length < 16 || bytecode.length > 24 * 1024 * 1024) {
            Ui.error(this, new IllegalArgumentException("Class bytecode size is outside the supported range"));
            return;
        }
        String message = "Apply " + sourceName + " to " + selected.name + "?\n\n"
                + "JPI validates the class schema before asking the JVM to activate it.";
        if (JOptionPane.showConfirmDialog(this, message, "Apply raw class bytecode",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        setRedefinitionControls(false);
        String payload = selected.id + "\n" + Base64.getEncoder().encodeToString(bytecode);
        Async.run(() -> current.requestText(Operation.APPLY_CLASS_BYTES, payload), result -> {
            metadata.setText(result);
            setRedefinitionControls(true);
            refreshEvents();
            methodClassId = null;
            decompile();
        }, error -> {
            setRedefinitionControls(true);
            Ui.error(this, error);
        });
    }

    private static byte[] parseHex(String text) {
        String compact = text.replaceAll("\\s+", "");
        if ((compact.length() & 1) != 0) throw new IllegalArgumentException("Hex bytecode must contain complete byte pairs");
        byte[] output = new byte[compact.length() / 2];
        for (int index = 0; index < output.length; index++) {
            int high = Character.digit(compact.charAt(index * 2), 16);
            int low = Character.digit(compact.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Hex bytecode contains an invalid character");
            output[index] = (byte) ((high << 4) | low);
        }
        return output;
    }

    private void applySource() {
        final LoadedClassInfo selected = list.getSelectedValue();
        final InspectorSession current = session;
        if (selected == null || current == null || !selected.modifiable || isMappedSource()) return;
        String javaSource = source.getText();
        if (javaSource.isBlank()) return;
        String message = "Compile and redefine " + selected.name + " in the running JVM?\n\n"
                + "Only existing method bodies may change. The JVM rejects field, method signature, hierarchy, and modifier changes.";
        if (JOptionPane.showConfirmDialog(this, message, "Apply runtime class redefinition",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        setRedefinitionControls(false);
        metadata.setText("Compiling inside target JVM...");
        Async.run(() -> current.requestText(Operation.REDEFINE_SOURCE, selected.id + "\n" + javaSource), result -> {
            metadata.setText(result);
            setRedefinitionControls(true);
            refreshEvents();
            methodClassId = null;
            loadMethods(true);
        }, error -> {
            setRedefinitionControls(true);
            Ui.error(this, error);
        });
    }

    private void rollback() {
        final LoadedClassInfo selected = list.getSelectedValue();
        final InspectorSession current = session;
        if (selected == null || current == null || !selected.modifiable) return;
        String message = "Restore the first bytecode captured for " + selected.name + "?";
        if (JOptionPane.showConfirmDialog(this, message, "Rollback runtime class",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        setRedefinitionControls(false);
        Async.run(() -> current.requestText(Operation.ROLLBACK_CLASS, selected.id), result -> {
            metadata.setText(result);
            setRedefinitionControls(true);
            refreshEvents();
            methodClassId = null;
            decompile();
        }, error -> {
            setRedefinitionControls(true);
            Ui.error(this, error);
        });
    }

    private void setRedefinitionControls(boolean enabled) {
        redefinitionControlsEnabled = enabled;
        LoadedClassInfo selected = list.getSelectedValue();
        boolean available = enabled && session != null && selected != null && selected.modifiable
                && !"captured-only".equals(selected.kind);
        applySource.setEnabled(available && !isMappedSource());
        rollback.setEnabled(available);
        applyHex.setEnabled(available && bytecodeHex.getText().matches("(?s)[0-9a-fA-F\\s]+"));
        refreshMethodPatchState();
        decompile.setEnabled(enabled && session != null);
    }

    private void refreshEvents() {
        final InspectorSession current = session;
        if (current == null) return;
        Async.run(() -> current.requestText(Operation.CLASS_EVENTS, ""), raw -> {
            if (session != current) return;
            StringBuilder formatted = new StringBuilder();
            SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS");
            for (String line : raw.split("\n")) {
                String[] columns = line.split("\t", -1);
                if (columns.length != 5) continue;
                try {
                    long timestamp = Long.parseLong(columns[0]);
                    formatted.append(time.format(new Date(timestamp))).append("  ")
                            .append(String.format("%-11s", columns[4])).append("  ")
                            .append(String.format("%8s bytes", columns[3])).append("  ")
                            .append(columns[1]).append("  [").append(columns[2]).append("]\n");
                    String key = "class:" + timestamp + ":" + columns[1] + ":" + columns[2] + ":" + columns[4];
                    timeline.publish(new TimelineEvent(key, timestamp, TimelineSource.CLASS_LOAD, "", "", "",
                            columns[4] + " " + mappingWorkspace.classAlias(columns[1]),
                            "Class: " + columns[1] + "\nClassloader: " + columns[2]
                                    + "\nBytecode size: " + columns[3] + " bytes"));
                } catch (NumberFormatException ignored) { }
            }
            events.setText(formatted.length() == 0 ? "No class definitions captured yet." : formatted.toString());
            events.setCaretPosition(Math.max(0, events.getDocument().getLength()));
        }, error -> { });
    }

    private void dumpSelected() {
        final LoadedClassInfo selected = list.getSelectedValue();
        final InspectorSession current = session;
        if (selected == null || current == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(selected.name.replace('.', '_') + ".class"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        final File destination = chooser.getSelectedFile();
        Async.run(() -> {
            Files.write(destination.toPath(), current.request(Operation.CLASS_BYTES, selected.id));
            return destination;
        }, file -> JOptionPane.showMessageDialog(this, "Saved " + file), error -> Ui.error(this, error));
    }

    private void dumpAll() {
        final InspectorSession current = session;
        if (current == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        final File directory = chooser.getSelectedFile();
        final List<LoadedClassInfo> classes = new ArrayList<>(allClasses);
        final boolean resumeLive = live.isSelected();
        live.setSelected(false);
        source.setText("Dumping " + classes.size() + " class definitions...");
        Async.run(() -> {
            int saved = 0;
            int failed = 0;
            Map<String, Integer> duplicates = new HashMap<>();
            for (LoadedClassInfo info : classes) {
                if (info.name.startsWith("[")) continue;
                try {
                    int duplicate = duplicates.containsKey(info.name) ? duplicates.get(info.name) + 1 : 0;
                    duplicates.put(info.name, duplicate);
                    String suffix = duplicate == 0 ? "" : "__loader_" + duplicate;
                    String safeName = info.name.replace('/', '_').replace('\\', '_')
                            .replace('[', '_').replace(']', '_');
                    File file = new File(directory,
                            safeName.replace('.', File.separatorChar) + suffix + ".class");
                    File parent = file.getParentFile();
                    if (parent != null) Files.createDirectories(parent.toPath());
                    Files.write(file.toPath(), current.request(Operation.CLASS_BYTES, info.id));
                    saved++;
                } catch (Exception ignored) {
                    failed++;
                }
            }
            return "Class dump complete.\nSaved: " + saved + "\nUnavailable: " + failed
                    + "\nDuplicate class names preserve a loader suffix.\nDirectory: " + directory + "\n";
        }, value -> {
            source.setText(value);
            live.setSelected(resumeLive);
        }, error -> {
            live.setSelected(resumeLive);
            Ui.error(this, error);
        });
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder output = new StringBuilder();
            for (byte item : digest) output.append(String.format("%02x", item & 0xff));
            return output.toString();
        } catch (Exception impossible) {
            return "<unavailable>";
        }
    }

    private enum SourceMode {
        ORIGINAL_EDITABLE("Original, editable"),
        MAPPED_READ_ONLY("Mapped, read-only");

        private final String label;

        SourceMode(String label) {
            this.label = label;
        }

        @Override public String toString() {
            return label;
        }
    }
    private static final class DecompiledResult {
        final byte[] bytecode;
        final String source;

        DecompiledResult(byte[] bytecode, String source) {
            this.bytecode = bytecode;
            this.source = source;
        }
    }

    private MethodInfo parseMethodInfo(String line, String owner) {
        String[] values = line.split("\\t", -1);
        if (values.length != 4) return null;
        return new MethodInfo(owner, values[0], values[1], values[2], Boolean.parseBoolean(values[3]));
    }

    private final class MethodInfo {
        final String owner;
        final String name;
        final String descriptor;
        final String modifiers;
        final boolean patchable;

        MethodInfo(String owner, String name, String descriptor, String modifiers, boolean patchable) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.modifiers = modifiers;
            this.patchable = patchable;
        }

        String template() {
            int closing = descriptor.lastIndexOf(')');
            char result = closing < 0 || closing + 1 >= descriptor.length() ? 'V' : descriptor.charAt(closing + 1);
            if (result == 'V') return "{\n    \n}";
            if (result == 'Z') return "{\n    return false;\n}";
            if (result == 'J') return "{\n    return 0L;\n}";
            if (result == 'F') return "{\n    return 0.0f;\n}";
            if (result == 'D') return "{\n    return 0.0d;\n}";
            if (result == 'L' || result == '[') return "{\n    return null;\n}";
            return "{\n    return 0;\n}";
        }

        @Override public String toString() {
            String alias = mappingWorkspace.methodAlias(owner, name, descriptor);
            String visible = alias.equals(name) ? name : alias + " [" + name + "]";
            return (patchable ? "" : "[read-only] ") + visible + descriptor + "  " + modifiers;
        }
    }

    private static final class ClassInventory {
        final List<LoadedClassInfo> classes;
        final long captured;

        ClassInventory(List<LoadedClassInfo> classes, long captured) {
            this.classes = classes;
            this.captured = captured;
        }
    }

    private static final class PluginTool {
        final String pluginId;
        final String name;
        final BytecodeAnalyzer analyzer;
        final Deobfuscator deobfuscator;

        PluginTool(String pluginId, String name, BytecodeAnalyzer analyzer, Deobfuscator deobfuscator) {
            this.pluginId = pluginId;
            this.name = name;
            this.analyzer = analyzer;
            this.deobfuscator = deobfuscator;
        }

        @Override public String toString() {
            return name + "  [" + pluginId + "]";
        }
    }

    private static final class PluginToolResult {
        final AnalysisResult analysis;
        final List<MappingSuggestion> suggestions;

        private PluginToolResult(AnalysisResult analysis, List<MappingSuggestion> suggestions) {
            this.analysis = analysis;
            this.suggestions = suggestions;
        }

        static PluginToolResult analysis(AnalysisResult value) {
            return new PluginToolResult(value, List.of());
        }

        static PluginToolResult suggestions(List<MappingSuggestion> values) {
            return new PluginToolResult(null, values == null ? List.of() : List.copyOf(values));
        }
    }

    private final class ClassRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                 boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            LoadedClassInfo info = value instanceof LoadedClassInfo ? (LoadedClassInfo) value : null;
            if (info != null) {
                label.setText(info.display(mappingWorkspace.classAlias(info.name)));
                if (!selected) {
                    Color mapped = color(mappingWorkspace.classColor(info.name));
                    if (mapped != null) label.setForeground(mapped);
                    else if ("captured-only".equals(info.kind)) label.setForeground(Ui.WARNING);
                }
            }
            label.setBorder(new EmptyBorder(0, 6, 0, 6));
            return label;
        }

        private Color color(String value) {
            try {
                return value != null && value.matches("#[0-9a-fA-F]{6}") ? Color.decode(value) : null;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}

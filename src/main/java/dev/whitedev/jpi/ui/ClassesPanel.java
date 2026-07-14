package dev.whitedev.jpi.ui;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.decompile.DecompilerEngine;
import dev.whitedev.jpi.decompile.DecompilerService;
import dev.whitedev.jpi.decompile.MethodBodyCompatibility;
import dev.whitedev.jpi.decompile.MethodSourceExtractor;
import dev.whitedev.jpi.protocol.Operation;
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

final class ClassesPanel extends JPanel implements SessionAware {
    private static final int MAX_HEX_BYTES = 4 * 1024 * 1024;
    private static final int CLASS_PAGE_SIZE = 500;
    private final LiveTracerPanel liveTracer;
    private final Runnable openLiveTracer;
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
    private final JCheckBox live = new JCheckBox("Live tracking", true);
    private final JComboBox<DecompilerEngine> decompilerSelector = new JComboBox<>(DecompilerEngine.values());
    private final DecompilerService decompilerService = new DecompilerService();
    private final javax.swing.Timer classFilterTimer = new javax.swing.Timer(220, event -> filter(false, null));
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

    ClassesPanel(LiveTracerPanel liveTracer, Runnable openLiveTracer) {
        super(new BorderLayout(0, 16));
        this.liveTracer = liveTracer;
        this.openLiveTracer = openLiveTracer;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);
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
        JPanel tools = new JPanel(new BorderLayout(8, 0));
        tools.setOpaque(false);
        metadata.setForeground(Ui.MUTED);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton dump = Ui.secondaryButton("Dump selected");
        JButton dumpAll = Ui.secondaryButton("Dump all");
        JButton refreshEvents = Ui.secondaryButton("Refresh events");
        decompilerSelector.setPreferredSize(new Dimension(170, 34));
        reload.addActionListener(e -> reload(true));
        decompile.addActionListener(e -> decompile());
        applySource.addActionListener(e -> applySource());
        rollback.addActionListener(e -> rollback());
        dump.addActionListener(e -> dumpSelected());
        dumpAll.addActionListener(e -> dumpAll());
        refreshEvents.addActionListener(e -> refreshEvents());
        actions.add(live);
        actions.add(refreshEvents);
        actions.add(reload);
        actions.add(dump);
        actions.add(dumpAll);
        actions.add(new JLabel("Decompiler"));
        actions.add(decompilerSelector);
        actions.add(decompile);
        actions.add(rollback);
        actions.add(applySource);
        tools.add(metadata, BorderLayout.CENTER);
        tools.add(actions, BorderLayout.EAST);
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
        applyHex.setEnabled(false);
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
                if (info.matches(query)) matches.add(info);
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
        applySource.setEnabled(canRedefine);
        rollback.setEnabled(canRedefine);
        applyHex.setEnabled(canRedefine);
        metadata.setText(selected.kind + "  |  " + selected.module + "  |  "
                + (selected.modifiable ? "modifiable" : "read-only") + "  |  "
                + (selected.captured ? "bytecode captured" : "bytecode on demand"));
        loadMethods();
    }

    private DecompilerEngine selectedDecompiler() {
        DecompilerEngine selected = (DecompilerEngine) decompilerSelector.getSelectedItem();
        return selected == null ? DecompilerEngine.CFR : selected;
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
        final DecompilerEngine engine = selectedDecompiler();
        final long generation = ++decompileGeneration;
        if (selected == null || current == null) return;
        decompile.setEnabled(false);
        source.setText("Reading bytecode and decompiling " + selected.name + " with " + engine + "...");
        Async.run(() -> {
            byte[] bytecode = current.request(Operation.CLASS_BYTES, selected.id);
            String decompiled;
            try {
                decompiled = decompilerService.decompile(engine, selected.name, bytecode);
            } catch (Exception error) {
                decompiled = engine + " could not produce compilable source for this class.\n"
                        + "Method patch and raw bytecode remain available.\n\n" + error.getMessage();
            }
            return new DecompiledResult(bytecode, decompiled);
        }, result -> {
            if (!decompileMatches(generation, selected.id, engine)) return;
            source.setText(result.source);
            source.setCaretPosition(0);
            renderHex(result.bytecode);
            loadMethods();
            metadata.setText(selected.kind + "  |  " + result.bytecode.length + " bytes  |  SHA-256 "
                    + sha256(result.bytecode));
            decompile.setEnabled(true);
        }, error -> {
            if (!decompileMatches(generation, selected.id, engine)) return;
            source.setText("Decompilation failed.\n" + error.getMessage());
            decompile.setEnabled(true);
            Ui.error(this, error);
        });
    }

    private boolean decompileMatches(long generation, String classId, DecompilerEngine engine) {
        LoadedClassInfo selected = list.getSelectedValue();
        return generation == decompileGeneration && selected != null && classId.equals(selected.id)
                && engine == selectedDecompiler();
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
        controls.add(refresh);
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
                MethodInfo method = MethodInfo.parse(line);
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
        final DecompilerEngine engine = selectedDecompiler();
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
        traceMethod.setEnabled(methodUnavailableReason(selected, method) == null);
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
        if (selected == null || current == null || !selected.modifiable) return;
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
        applySource.setEnabled(available);
        rollback.setEnabled(available);
        applyHex.setEnabled(available && bytecodeHex.getText().matches("(?s)[0-9a-fA-F\\s]+"));
        refreshMethodPatchState();
        decompile.setEnabled(enabled && session != null);
    }

    private void refreshEvents() {
        final InspectorSession current = session;
        if (current == null) return;
        Async.run(() -> current.requestText(Operation.CLASS_EVENTS, ""), raw -> {
            StringBuilder formatted = new StringBuilder();
            SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS");
            for (String line : raw.split("\n")) {
                String[] columns = line.split("\t", -1);
                if (columns.length != 5) continue;
                try {
                    formatted.append(time.format(new Date(Long.parseLong(columns[0])))).append("  ")
                            .append(String.format("%-11s", columns[4])).append("  ")
                            .append(String.format("%8s bytes", columns[3])).append("  ")
                            .append(columns[1]).append("  [").append(columns[2]).append("]\n");
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

    private static final class DecompiledResult {
        final byte[] bytecode;
        final String source;

        DecompiledResult(byte[] bytecode, String source) {
            this.bytecode = bytecode;
            this.source = source;
        }
    }

    private static final class MethodInfo {
        final String name;
        final String descriptor;
        final String modifiers;
        final boolean patchable;

        MethodInfo(String name, String descriptor, String modifiers, boolean patchable) {
            this.name = name;
            this.descriptor = descriptor;
            this.modifiers = modifiers;
            this.patchable = patchable;
        }

        static MethodInfo parse(String line) {
            String[] values = line.split("\\t", -1);
            if (values.length != 4) return null;
            return new MethodInfo(values[0], values[1], values[2], Boolean.parseBoolean(values[3]));
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
            return (patchable ? "" : "[read-only] ") + name + descriptor + "  " + modifiers;
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

    private static final class ClassRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                 boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            LoadedClassInfo info = value instanceof LoadedClassInfo ? (LoadedClassInfo) value : null;
            if (!selected && info != null && "captured-only".equals(info.kind)) label.setForeground(Ui.WARNING);
            label.setBorder(new EmptyBorder(0, 6, 0, 6));
            return label;
        }
    }
}

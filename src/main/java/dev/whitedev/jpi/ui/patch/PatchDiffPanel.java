package dev.whitedev.jpi.ui.patch;

import dev.whitedev.jpi.agent.patch.PatchHistory;
import dev.whitedev.jpi.analysis.patch.ClassDisassembly;
import dev.whitedev.jpi.analysis.patch.LineDiff;
import dev.whitedev.jpi.analysis.patch.PatchEntry;
import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.decompile.DecompilerOption;
import dev.whitedev.jpi.decompile.DecompilerService;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.CodeEditors;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.layout.WrappingFlowLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

public final class PatchDiffPanel extends JPanel {
    private final InspectorSession session;
    private final String identifier;
    private final String className;
    private final DecompilerOption decompiler;
    private final BooleanSupplier connected;
    private final Runnable onRollback;
    private final String hexDraft;
    private final String methodDraft;
    private final DecompilerService service = new DecompilerService();
    private final Map<String, String> sources = new ConcurrentHashMap<>();
    private final JComboBox<String> pair = new JComboBox<>(new String[]{"Original / Current", "Current / Modified", "Original / Modified", "Patch before / after"});
    private final JCheckBox hideCommon = new JCheckBox("Hide unchanged lines", true);
    private final JButton refresh = Ui.secondaryButton("Refresh");
    private final JButton update = Ui.secondaryButton("Update diff");
    private final JButton compile = Ui.secondaryButton("Preview Java source");
    private final JButton method = Ui.secondaryButton("Preview method body");
    private final JButton hex = Ui.secondaryButton("Preview hex draft");
    private final JButton rollback = Ui.primaryButton("Rollback selected patch");
    private final JButton copy = Ui.secondaryButton("Copy diff");
    private final JLabel status = new JLabel("Loading versions...");
    private final RSyntaxTextArea draft = CodeEditors.javaEditor(true);
    private final RSyntaxTextArea sourceDiff = diffEditor();
    private final RSyntaxTextArea bytecodeDiff = diffEditor();
    private final JTabbedPane tabs = new JTabbedPane();
    private final DefaultTableModel historyModel = new DefaultTableModel(new String[]{"Patch", "Time", "Class", "Change", "Before bytes", "After bytes"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Long.class : column >= 4 ? Integer.class : String.class; }
    };
    private final JTable history = new JTable(historyModel);
    private List<PatchEntry> entries = List.of();
    private byte[] original;
    private byte[] current;
    private byte[] modified;
    private byte[] rollbackCurrent;
    private long rollbackEntry = -1;
    private boolean busy;
    private boolean closed;
    private long generation;

    public PatchDiffPanel(InspectorSession session, String identifier, String className, DecompilerOption decompiler,
                          String sourceDraft, String hexDraft, String methodDraft, BooleanSupplier connected, Runnable onRollback) {
        super(new BorderLayout(0, 10));
        this.session = session;
        this.identifier = identifier;
        this.className = className;
        this.decompiler = decompiler;
        this.hexDraft = hexDraft;
        this.methodDraft = methodDraft;
        this.connected = connected;
        this.onRollback = onRollback;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel controls = new JPanel(new WrappingFlowLayout(FlowLayout.LEFT, 6, 3));
        for (JComponent control : new JComponent[]{pair, hideCommon, refresh, update, compile, method, hex, copy}) controls.add(control);
        add(controls, BorderLayout.NORTH);
        draft.setText(sourceDraft);
        tabs.addTab("Source diff", CodeEditors.scrollPane(sourceDiff));
        tabs.addTab("Bytecode diff", CodeEditors.scrollPane(bytecodeDiff));
        tabs.addTab("Modified source", CodeEditors.scrollPane(draft));
        history.setAutoCreateRowSorter(true);
        history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        history.setFillsViewportHeight(true);
        JPanel historyPanel = new JPanel(new BorderLayout(0, 6));
        JPanel historyActions = new JPanel(new BorderLayout());
        historyActions.add(new JLabel("Session patch history (latest 100 patches / 64 MiB) | Class: " + identifier), BorderLayout.CENTER);
        historyActions.add(rollback, BorderLayout.EAST);
        historyPanel.add(historyActions, BorderLayout.NORTH);
        historyPanel.add(new JScrollPane(history), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, historyPanel);
        split.setResizeWeight(.75);
        split.setDividerLocation(440);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        refresh.addActionListener(event -> reload());
        update.addActionListener(event -> render());
        pair.addActionListener(event -> render());
        hideCommon.addActionListener(event -> render());
        history.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !busy) {
                pair.setSelectedIndex(3);
                render();
            }
        });
        compile.addActionListener(event -> preview(Operation.PREVIEW_SOURCE, identifier + "\n" + draft.getText(), false));
        method.addActionListener(event -> preview(Operation.PREVIEW_METHOD, identifier + "\n" + methodDraft, true));
        hex.addActionListener(event -> previewHex());
        rollback.addActionListener(event -> rollback());
        copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(tabs.getSelectedIndex() == 1 ? bytecodeDiff.getText() : sourceDiff.getText()), null));
        draft.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { changed(); }
            @Override public void removeUpdate(DocumentEvent event) { changed(); }
            @Override public void changedUpdate(DocumentEvent event) { changed(); }
            private void changed() {
                modified = null;
                bytecodeDiff.setText("Modified source changed. Compile a new preview before comparing its bytecode.");
                controls();
            }
        });
        controls();
        reload();
    }

    public void close() {
        closed = true;
        generation++;
        sources.clear();
        controls();
    }

    private boolean valid(long request) {
        return !closed && connected.getAsBoolean() && request == generation;
    }

    private void reload() {
        if (closed || !connected.getAsBoolean()) return;
        long request = ++generation;
        busy = true;
        controls();
        status.setText("Reading original, current, and patch history...");
        Async.run(() -> new Versions(session.request(Operation.CLASS_VERSION, identifier + "\noriginal"),
                session.request(Operation.CLASS_VERSION, identifier + "\ncurrent"),
                PatchEntry.parse(session.requestText(Operation.PATCH_HISTORY, ""))), result -> {
            if (!valid(request)) return;
            original = result.original;
            current = result.current;
            modified = null;
            rollbackCurrent = null;
            rollbackEntry = -1;
            entries = result.entries;
            sources.clear();
            historyModel.setRowCount(0);
            DateTimeFormatter format = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
            for (PatchEntry entry : entries) historyModel.addRow(new Object[]{entry.id(), format.format(Instant.ofEpochMilli(entry.timestamp())),
                    entry.name(), entry.description(), entry.beforeSize(), entry.afterSize()});
            if (!entries.isEmpty()) history.setRowSelectionInterval(entries.size() - 1, entries.size() - 1);
            busy = false;
            render();
        }, error -> fail(request, error));
    }

    private void render() {
        if (busy || current == null || closed || !connected.getAsBoolean()) return;
        int selection = pair.getSelectedIndex();
        PatchEntry entry = selected();
        if (selection == 3 && entry == null) {
            sourceDiff.setText("Select a patch in history to compare its before and after definitions.");
            bytecodeDiff.setText(sourceDiff.getText());
            return;
        }
        long request = ++generation;
        byte[] a = selection == 0 || selection == 2 ? original : current;
        byte[] b = selection == 0 ? current : modified;
        String leftName = selection == 0 || selection == 2 ? "Original" : "Current";
        String rightName = selection == 0 ? "Current" : "Modified";
        String edited = draft.getText();
        boolean hide = hideCommon.isSelected();
        busy = true;
        controls();
        status.setText("Computing source and bytecode differences...");
        Async.run(() -> {
            byte[] left = a;
            byte[] right = b;
            String labelA = leftName;
            String labelB = rightName;
            String owner = className;
            byte[] rollbackSnapshot = null;
            if (selection == 3) {
                left = session.request(Operation.CLASS_VERSION, entry.identifier() + "\nbefore:" + entry.id());
                right = session.request(Operation.CLASS_VERSION, entry.identifier() + "\nafter:" + entry.id());
                try {
                    rollbackSnapshot = session.request(Operation.CLASS_VERSION, entry.identifier() + "\ncurrent");
                } catch (java.io.IOException unavailable) {
                    rollbackSnapshot = null;
                }
                owner = entry.name();
                labelA = "Before #" + entry.id();
                labelB = "After #" + entry.id();
            }
            String javaDiff;
            try {
                javaDiff = LineDiff.unified(source(owner, left), right == null ? edited : source(owner, right), labelA, labelB, hide);
            } catch (Exception error) {
                javaDiff = "Source diff unavailable: " + error.getMessage() + "\nUse Bytecode diff to inspect these definitions.";
            }
            String asmDiff;
            try {
                asmDiff = right == null ? "No compiled Modified version. Use Preview Java source, method body, or hex draft."
                        : LineDiff.unified(ClassDisassembly.render(left), ClassDisassembly.render(right), labelA, labelB, hide);
            } catch (Exception error) {
                asmDiff = "Bytecode diff unavailable: " + error.getMessage();
            }
            return new Comparison(javaDiff, asmDiff, labelA + " / " + labelB, rollbackSnapshot, selection == 3 ? entry.id() : -1);
        }, result -> {
            if (!valid(request)) return;
            display(sourceDiff, result.source);
            display(bytecodeDiff, result.bytecode);
            sourceDiff.setCaretPosition(0);
            bytecodeDiff.setCaretPosition(0);
            if (result.patchId >= 0) {
                rollbackCurrent = result.rollbackCurrent;
                rollbackEntry = result.patchId;
            }
            status.setText(result.label + " | Preview only. No patch applied. Original means first captured bytecode."
                    + (result.patchId >= 0 && result.rollbackCurrent == null ? " Current definition unavailable; rollback disabled." : ""));
            busy = false;
            controls();
        }, error -> fail(request, error));
    }

    private String source(String owner, byte[] bytes) throws Exception {
        String hash = PatchHistory.sha256(bytes);
        String cached = sources.get(hash);
        if (cached != null) return cached;
        String text = service.decompile(decompiler, owner, bytes);
        if (text.length() > 2_000_000) throw new IllegalArgumentException("Decompiled source exceeds the 2 million character diff limit");
        if (sources.size() < 8) sources.put(hash, text);
        return text;
    }

    private void preview(Operation operation, String payload, boolean replaceDraft) {
        if (busy || closed || !connected.getAsBoolean()) return;
        long request = ++generation;
        busy = true;
        controls();
        status.setText("Compiling a preview without redefining the target...");
        Async.run(() -> {
            byte[] bytes = session.request(operation, payload);
            return new Preview(bytes, replaceDraft ? source(className, bytes) : null);
        }, result -> {
            if (!valid(request)) return;
            if (result.source != null) draft.setText(result.source);
            modified = result.bytes;
            busy = false;
            pair.setSelectedIndex(1);
            render();
        }, error -> fail(request, error));
    }

    private void previewHex() {
        if (busy) return;
        try {
            String text = hexDraft.replaceAll("\\s", "");
            if (text.isEmpty() || (text.length() & 1) != 0 || !text.matches("[0-9a-fA-F]+")) throw new IllegalArgumentException("Invalid hex draft");
            byte[] bytes = new byte[text.length() / 2];
            for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
            new org.objectweb.asm.ClassReader(bytes);
            modified = bytes;
            pair.setSelectedIndex(1);
            render();
        } catch (Exception error) {
            Ui.error(this, error);
        }
    }

    private void rollback() {
        PatchEntry entry = selected();
        if (busy || entry == null || rollbackCurrent == null || rollbackEntry != entry.id() || !connected.getAsBoolean()) return;
        String message = "Restore " + entry.identifier() + " to the state BEFORE patch #" + entry.id()
                + "?\nLater changes to this class will be replaced. Active probes on this class will stop.";
        if (JOptionPane.showConfirmDialog(this, message, "Rollback patch", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        long request = ++generation;
        busy = true;
        controls();
        String payload = entry.identifier() + "\n" + entry.id() + "\n" + PatchHistory.sha256(rollbackCurrent);
        Async.run(() -> session.requestText(Operation.ROLLBACK_PATCH, payload), result -> {
            if (!valid(request)) return;
            busy = false;
            status.setText(result);
            onRollback.run();
            pair.setSelectedIndex(0);
            reload();
        }, error -> fail(request, error));
    }

    private PatchEntry selected() {
        int row = history.getSelectedRow();
        return row < 0 ? null : entries.get(history.convertRowIndexToModel(row));
    }

    private void fail(long request, Throwable error) {
        if (!valid(request)) return;
        busy = false;
        status.setText(error.getMessage());
        controls();
        Ui.error(this, error);
    }

    private void controls() {
        boolean ready = !busy && !closed && connected.getAsBoolean();
        for (JComponent control : new JComponent[]{pair, hideCommon, refresh, update, compile, hex, history, copy}) control.setEnabled(ready);
        method.setEnabled(ready && !methodDraft.isEmpty());
        rollback.setEnabled(ready && rollbackCurrent != null && selected() != null && rollbackEntry == selected().id());
        draft.setEditable(ready);
    }

    private static RSyntaxTextArea diffEditor() {
        RSyntaxTextArea editor = CodeEditors.javaEditor(false);
        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        editor.setCodeFoldingEnabled(false);
        return editor;
    }

    private static void display(RSyntaxTextArea editor, String text) {
        editor.setText(text);
        editor.getHighlighter().removeAllHighlights();
        DefaultHighlighter.DefaultHighlightPainter added = new DefaultHighlighter.DefaultHighlightPainter(new Color(38, 76, 49));
        DefaultHighlighter.DefaultHighlightPainter removed = new DefaultHighlighter.DefaultHighlightPainter(new Color(85, 42, 42));
        try {
            for (int line = 0; line < Math.min(editor.getLineCount(), 20_000); line++) {
                int start = editor.getLineStartOffset(line);
                int end = Math.min(editor.getDocument().getLength(), editor.getLineEndOffset(line));
                if (start >= end) continue;
                char prefix = text.charAt(start);
                if (prefix == '+' || prefix == '-') editor.getHighlighter().addHighlight(start, end, prefix == '+' ? added : removed);
            }
        } catch (BadLocationException error) {
            throw new IllegalStateException(error);
        }
    }

    private record Versions(byte[] original, byte[] current, List<PatchEntry> entries) {}
    private record Comparison(String source, String bytecode, String label, byte[] rollbackCurrent, long patchId) {}
    private record Preview(byte[] bytes, String source) {}
}

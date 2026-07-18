package dev.whitedev.jpi.ui;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.protocol.Operation;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

final class ExecutorPanel extends JPanel implements SessionAware {
    private final DeobfuscationWorkspace workspace;
    private final RSyntaxTextArea code = CodeEditors.javaEditor(true);
    private final JTextArea output = Ui.outputArea();
    private final JButton run = Ui.primaryButton("Run in target JVM");
    private final JLabel state = new JLabel("Ready");
    private final JCheckBox resolveMappings = new JCheckBox("Resolve mapped names", true);
    private InspectorSession session;

    ExecutorPanel(DeobfuscationWorkspace workspace) {
        super(new BorderLayout(0, 16));
        this.workspace = workspace;
        setBorder(new EmptyBorder(4, 0, 0, 0));
        setOpaque(false);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton reset = Ui.secondaryButton("Reset example");
        reset.addActionListener(e -> setTemplate());
        run.addActionListener(e -> execute());
        resolveMappings.setToolTipText("Translate enabled class, method, field, and package aliases before compilation");
        actions.add(resolveMappings);
        actions.add(reset);
        actions.add(run);
        add(Ui.sectionHeader("Code executor",
                "Compile an isolated Java class and invoke execute(PrintStream) inside the target", actions),
                BorderLayout.NORTH);

        JPanel editorCard = Ui.card(new BorderLayout(0, 10));
        JPanel editorMeta = new JPanel(new BorderLayout());
        editorMeta.setOpaque(false);
        JLabel editorTitle = new JLabel("Java source");
        editorTitle.setFont(editorTitle.getFont().deriveFont(Font.BOLD, 15f));
        JLabel shortcut = new JLabel("Ctrl+Enter to run");
        shortcut.setForeground(Ui.MUTED);
        editorMeta.add(editorTitle, BorderLayout.WEST);
        editorMeta.add(shortcut, BorderLayout.EAST);
        editorCard.add(editorMeta, BorderLayout.NORTH);
        editorCard.add(CodeEditors.scrollPane(code), BorderLayout.CENTER);

        JPanel outputCard = Ui.card(new BorderLayout(0, 10));
        JPanel outputMeta = new JPanel(new BorderLayout());
        outputMeta.setOpaque(false);
        JLabel outputTitle = new JLabel("Execution output");
        outputTitle.setFont(outputTitle.getFont().deriveFont(Font.BOLD, 15f));
        state.setForeground(Ui.MUTED);
        outputMeta.add(outputTitle, BorderLayout.WEST);
        outputMeta.add(state, BorderLayout.EAST);
        outputCard.add(outputMeta, BorderLayout.NORTH);
        output.setText("Output and compilation diagnostics will appear here.");
        outputCard.add(Ui.scroll(output), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorCard, outputCard);
        split.setResizeWeight(.67);
        split.setDividerLocation(470);
        split.setDividerSize(8);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);

        code.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "jpi-run");
        code.getActionMap().put("jpi-run", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { execute(); }
        });
        setTemplate();
    }

    private void setTemplate() {
        code.setText("import java.io.PrintStream;\n\n"
                + "public class JpiSnippet {\n"
                + "    public static void execute(PrintStream out) {\n"
                + "        out.println(\"Hello from Java \" + System.getProperty(\"java.version\"));\n"
                + "    }\n"
                + "}\n");
        code.setCaretPosition(0);
    }

    @Override public void setSession(InspectorSession session) {
        this.session = session;
        run.setEnabled(session != null);
        state.setText(session == null ? "Attach required" : "Ready");
        state.setForeground(session == null ? Ui.MUTED : Ui.SUCCESS);
    }

    private void execute() {
        final InspectorSession current = session;
        if (current == null || !run.isEnabled()) return;
        DeobfuscationWorkspace.TranslationResult translation = resolveMappings.isSelected()
                ? workspace.translateSource(code.getText())
                : new DeobfuscationWorkspace.TranslationResult(code.getText(), 0, java.util.Collections.emptyList());
        if (!translation.ambiguousAliases().isEmpty()) {
            Ui.error(this, new IllegalStateException("Mapped aliases are ambiguous: "
                    + String.join(", ", translation.ambiguousAliases())));
            return;
        }
        final String source = translation.source();
        final int replacements = translation.replacements();
        run.setEnabled(false);
        state.setText(replacements == 0 ? "Running..." : "Running with " + replacements + " mapped names...");
        state.setForeground(Ui.WARNING);
        output.setText("");
        Async.run(() -> current.requestText(Operation.EXECUTE, source), value -> {
            output.setText(value.isEmpty() ? "Execution completed without output." : value);
            output.setCaretPosition(0);
            state.setText(replacements == 0 ? "Completed" : "Completed  |  " + replacements + " aliases resolved");
            state.setForeground(Ui.SUCCESS);
            run.setEnabled(true);
        }, error -> {
            state.setText("Failed");
            state.setForeground(Ui.WARNING);
            run.setEnabled(true);
            Ui.error(this, error);
        });
    }
}

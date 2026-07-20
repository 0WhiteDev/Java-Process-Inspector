package dev.whitedev.jpi.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import java.awt.*;
import java.io.InputStream;

public final class CodeEditors {
    private CodeEditors() {}

    public static RSyntaxTextArea javaEditor(boolean editable) {
        RSyntaxTextArea editor = new RSyntaxTextArea();
        editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        editor.setEditable(editable);
        editor.setCodeFoldingEnabled(true);
        editor.setAntiAliasingEnabled(true);
        editor.setAutoIndentEnabled(true);
        editor.setBracketMatchingEnabled(true);
        editor.setAnimateBracketMatching(false);
        editor.setMarkOccurrences(true);
        editor.setTabsEmulated(true);
        editor.setTabSize(4);
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        editor.setMargin(new Insets(12, 14, 12, 14));
        applyTheme(editor);
        return editor;
    }

    public static RTextScrollPane scrollPane(RSyntaxTextArea editor) {
        RTextScrollPane scroll = new RTextScrollPane(editor);
        scroll.setLineNumbersEnabled(true);
        scroll.setFoldIndicatorEnabled(true);
        scroll.setBorder(BorderFactory.createLineBorder(Ui.BORDER));
        return scroll;
    }

    private static void applyTheme(RSyntaxTextArea editor) {
        InputStream input = CodeEditors.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/dark.xml");
        if (input == null) return;
        try {
            Theme.load(input).apply(editor);
        } catch (Exception ignored) {
            editor.setBackground(Ui.SURFACE);
            editor.setForeground(Ui.TEXT);
        } finally {
            try { input.close(); } catch (Exception ignored) {}
        }
    }
}

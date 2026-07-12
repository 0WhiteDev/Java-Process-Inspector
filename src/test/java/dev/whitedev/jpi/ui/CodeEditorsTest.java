package dev.whitedev.jpi.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodeEditorsTest {
    @Test void configuresAJavaEditorWithNavigationFeatures() {
        RSyntaxTextArea editor = CodeEditors.javaEditor(true);
        RTextScrollPane scrollPane = CodeEditors.scrollPane(editor);

        assertEquals(SyntaxConstants.SYNTAX_STYLE_JAVA, editor.getSyntaxEditingStyle());
        assertTrue(editor.isCodeFoldingEnabled());
        assertTrue(editor.getMarkOccurrences());
        assertTrue(editor.isBracketMatchingEnabled());
        assertTrue(scrollPane.getLineNumbersEnabled());
        assertTrue(scrollPane.isFoldIndicatorEnabled());
    }
}

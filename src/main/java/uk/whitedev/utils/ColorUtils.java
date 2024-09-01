package uk.whitedev.utils;

import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;

public class ColorUtils {
    public static void addStylesToDocument(StyledDocument doc) {
        Style style = doc.addStyle("Keyword", null);
        StyleConstants.setForeground(style, new Color(249, 95, 119));

        StyleConstants.setBold(style, true);
        style = doc.addStyle("Comment", null);
        StyleConstants.setForeground(style, new Color(121, 199, 101));

        style = doc.addStyle("String", null);
        StyleConstants.setForeground(style, new Color(250, 227, 101));
    }

    public static void applySyntaxHighlighting(String text, StyledDocument doc) {
        try {
            doc.remove(0, doc.getLength());
            String[] keywords = {"abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
                    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
                    "finally", "float", "for", "if", "goto", "implements", "import", "instanceof", "int",
                    "interface", "long", "native", "new", "null", "package", "private", "protected",
                    "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
                    "this", "throw", "throws", "transient", "try", "void", "volatile", "while"};
            for (String keyword : keywords) {
                text = text.replaceAll("\\b" + keyword + "\\b", "<Keyword>" + keyword + "</Keyword>");
            }
            text = text.replaceAll("//.*", "<Comment>$0</Comment>");
            text = text.replaceAll("\".*?\"", "<String>$0</String>");

            int pos = 0;
            while (pos < text.length()) {
                if (text.startsWith("<Keyword>", pos)) {
                    pos = insertStyledText(doc, text, pos, "Keyword");
                } else if (text.startsWith("<Comment>", pos)) {
                    pos = insertStyledText(doc, text, pos, "Comment");
                } else if (text.startsWith("<String>", pos)) {
                    pos = insertStyledText(doc, text, pos, "String");
                } else {
                    doc.insertString(doc.getLength(), String.valueOf(text.charAt(pos)), null);
                    pos++;
                }
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    public static int insertStyledText(StyledDocument doc, String text, int pos, String styleName) throws BadLocationException {
        int endPos = text.indexOf("</" + styleName + ">", pos);
        String content = text.substring(pos + styleName.length() + 2, endPos);
        doc.insertString(doc.getLength(), content, doc.getStyle(styleName));
        return endPos + styleName.length() + 3;
    }
}

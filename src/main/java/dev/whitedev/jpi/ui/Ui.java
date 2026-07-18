package dev.whitedev.jpi.ui;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public final class Ui {
    public static final Color BACKGROUND = new Color(28, 28, 30);
    public static final Color SIDEBAR = new Color(34, 34, 37);
    public static final Color SURFACE = new Color(42, 42, 45);
    public static final Color SURFACE_LIGHT = new Color(57, 57, 62);
    public static final Color BORDER = new Color(76, 76, 82);
    public static final Color ACCENT = new Color(113, 113, 122);
    public static final Color SUCCESS = new Color(52, 199, 137);
    public static final Color WARNING = new Color(245, 174, 66);
    public static final Color TEXT = new Color(235, 240, 248);
    public static final Color MUTED = new Color(144, 158, 180);

    private Ui() {}

    public static void install() {
        FlatDarkLaf.setup();
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 9);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.focusColor", new Color(161, 161, 170));
        UIManager.put("Component.focusedBorderColor", new Color(161, 161, 170));
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("TabbedPane.showTabSeparators", false);
        UIManager.put("TabbedPane.tabHeight", 38);
        UIManager.put("Table.rowHeight", 29);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.gridColor", BORDER);
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("TextArea.background", SURFACE);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("Table.background", SURFACE);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("List.background", SURFACE);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("List.selectionBackground", SURFACE_LIGHT);
        UIManager.put("Table.selectionBackground", SURFACE_LIGHT);
    }

    public static JButton primaryButton(String text) {
        JButton button = button(text);
        button.setBackground(ACCENT);
        button.setForeground(Color.WHITE);
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    public static JButton secondaryButton(String text) {
        JButton button = button(text);
        button.setBackground(SURFACE_LIGHT);
        button.putClientProperty("JButton.buttonType", "roundRect");
        return button;
    }

    public static JButton navigationButton(String text) {
        JButton button = button(text);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        button.setPreferredSize(new Dimension(176, 36));
        button.setBackground(SIDEBAR);
        button.setBorder(new EmptyBorder(0, 9, 0, 9));
        return button;
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(new EmptyBorder(8, 15, 8, 15));
        return button;
    }

    public static JPanel sectionHeader(String title, String subtitle, JComponent actions) {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setOpaque(false);
        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 22f));
        JLabel description = new JLabel(subtitle);
        description.setForeground(MUTED);
        description.setBorder(new EmptyBorder(4, 0, 0, 0));
        labels.add(heading);
        labels.add(description);
        header.add(labels, BorderLayout.CENTER);
        if (actions != null) header.add(actions, BorderLayout.EAST);
        return header;
    }

    public static JPanel card(LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(14, 14, 14, 14)));
        return panel;
    }

    public static JTextArea outputArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setMargin(new Insets(12, 14, 12, 14));
        area.setCaretColor(TEXT);
        return area;
    }

    public static JScrollPane scroll(Component view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        return scroll;
    }

    public static void error(Component parent, Throwable error) {
        String message = error.getMessage() == null ? error.toString() : error.getMessage();
        JOptionPane.showMessageDialog(parent, message, "Java Process Inspector", JOptionPane.ERROR_MESSAGE);
    }
}

package dev.whitedev.jpi.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import dev.whitedev.jpi.ui.layout.WrappingFlowLayout;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public final class Ui {
    public static final Color BACKGROUND = new Color(0x202020);
    public static final Color SIDEBAR = new Color(0x161616);
    public static final Color SURFACE = new Color(0x252525);
    public static final Color SURFACE_LIGHT = new Color(0x393939);
    public static final Color BORDER = new Color(0x454545);
    public static final Color ACCENT = new Color(0xc4c4c4);
    public static final Color SUCCESS = new Color(52, 199, 137);
    public static final Color WARNING = new Color(245, 174, 66);
    public static final Color TEXT = new Color(0xf4f4f4);
    public static final Color MUTED = new Color(0xa9a9a9);

    private Ui() {
    }

    public static void install() {
        FlatLaf.registerCustomDefaultsSource("dev.whitedev.jpi.ui");
        FlatLaf.setUseNativeWindowDecorations(true);
        FlatDarkLaf.setup();
        UIManager.put("TitlePane.closeIcon", new WindowControl(WindowControl.Action.CLOSE));
        UIManager.put("TitlePane.iconifyIcon", new WindowControl(WindowControl.Action.MINIMIZE));
        UIManager.put("TitlePane.maximizeIcon", new WindowControl(WindowControl.Action.MAXIMIZE));
        UIManager.put("TitlePane.restoreIcon", new WindowControl(WindowControl.Action.RESTORE));
        UIManager.put("Component.arc", 6);
        UIManager.put("Button.arc", 6);
        UIManager.put("TextComponent.arc", 6);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
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
        button.setBackground(TEXT);
        button.setForeground(SIDEBAR);
        button.putClientProperty("FlatLaf.style", "hoverBackground: #dddddd; pressedBackground: #bdbdbd");
        return button;
    }

    public static JButton secondaryButton(String text) {
        JButton button = button(text);
        button.setBackground(SURFACE_LIGHT);
        return button;
    }

    public static JButton navigationButton(String text) {
        JButton button = button(text);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        button.setPreferredSize(new Dimension(176, 34));
        button.setBackground(SIDEBAR);
        button.setMargin(new Insets(2, 10, 2, 10));
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.setToolTipText(switch (text) {
            case "Command Palette" -> "Find a command by name (Ctrl+P)";
            case "Search Everything" -> "Search across the workspace (Ctrl+Shift+F)";
            case "Investigation Cases" -> "Record evidence or open a saved case offline";
            default -> "Open " + text;
        });
        return button;
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setMargin(new Insets(5, 10, 5, 10));
        return button;
    }

    public static JPanel sectionHeader(String title, String subtitle, JComponent actions) {
        JPanel header = new JPanel(new BorderLayout(0, 6));
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
        description.setToolTipText(subtitle);
        header.add(labels, BorderLayout.NORTH);
        if (actions != null) {
            if (actions instanceof JPanel && actions.getLayout() instanceof FlowLayout) {
                actions.setLayout(new WrappingFlowLayout(FlowLayout.LEADING, 4, 2));
            }
            header.add(actions, BorderLayout.CENTER);
        }
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

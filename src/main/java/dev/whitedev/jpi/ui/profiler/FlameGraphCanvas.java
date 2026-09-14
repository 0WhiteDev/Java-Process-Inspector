package dev.whitedev.jpi.ui.profiler;

import dev.whitedev.jpi.profiler.ProfilerReport;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class FlameGraphCanvas extends JComponent {
    private static final int ROW_HEIGHT = 30;
    private static final int GAP = 2;
    private static final int WIDTH = 1200;
    private final Map<Node, Rectangle> bounds = new LinkedHashMap<>();
    private Node root = new Node("All samples");
    private Node selected;
    private Consumer<Node> listener = ignored -> {};

    FlameGraphCanvas() {
        setOpaque(true);
        setBackground(Ui.BACKGROUND);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                setCursor(nodeAt(event.getPoint()) == null ? Cursor.getDefaultCursor()
                        : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override public void mouseClicked(MouseEvent event) {
                selected = nodeAt(event.getPoint());
                listener.accept(selected);
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    void setSelectionListener(Consumer<Node> value) {
        listener = value == null ? ignored -> {} : value;
    }

    void setMetrics(List<ProfilerReport.Metric> metrics) {
        root = new Node("All samples");
        for (ProfilerReport.Metric metric : metrics) {
            Node current = root;
            current.value += metric.value();
            current.count += metric.count();
            for (String frame : metric.path()) {
                current = current.children.computeIfAbsent(frame, Node::new);
                current.value += metric.value();
                current.count += metric.count();
            }
        }
        selected = null;
        layoutGraph();
        repaint();
    }

    @Override public String getToolTipText(MouseEvent event) {
        Node node = nodeAt(event.getPoint());
        return node == null ? null : node.name + " | " + node.value + " | " + node.count + " events";
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        FontMetrics metrics = g.getFontMetrics();
        for (Map.Entry<Node, Rectangle> entry : bounds.entrySet()) {
            Node node = entry.getKey();
            Rectangle area = entry.getValue();
            float ratio = root.value == 0L ? 0f : (float) node.value / root.value;
            g.setColor(heat(ratio, node.depth));
            g.fillRoundRect(area.x, area.y, area.width, area.height, 7, 7);
            g.setColor(node == selected ? Color.WHITE : Ui.BORDER);
            g.drawRoundRect(area.x, area.y, area.width, area.height, 7, 7);
            if (area.width > 38) {
                g.setClip(area.x + 5, area.y, area.width - 10, area.height);
                g.setColor(Color.WHITE);
                String label = node.name + "  " + percent(node.value, root.value);
                g.drawString(trim(metrics, label, area.width - 10), area.x + 5, area.y + 20);
                g.setClip(null);
            }
        }
        g.dispose();
    }

    private void layoutGraph() {
        bounds.clear();
        int depth = depth(root, 0);
        setPreferredSize(new Dimension(WIDTH + 24, Math.max(220, (depth + 1) * (ROW_HEIGHT + GAP) + 24)));
        if (root.value > 0L) layoutChildren(root, 12, 12, WIDTH, 0);
        revalidate();
    }

    private void layoutChildren(Node parent, int x, int y, int width, int depth) {
        int offset = x;
        int remainingWidth = width;
        long remainingValue = parent.value;
        List<Node> children = new ArrayList<>(parent.children.values());
        children.sort((left, right) -> Long.compare(right.value, left.value));
        for (int index = 0; index < children.size(); index++) {
            if (remainingWidth <= 0) break;
            Node child = children.get(index);
            child.depth = depth;
            int childWidth = index == children.size() - 1 ? remainingWidth
                    : Math.max(1, (int) Math.round(remainingWidth * (double) child.value / Math.max(1L, remainingValue)));
            childWidth = Math.min(remainingWidth, childWidth);
            bounds.put(child, new Rectangle(offset, y, childWidth, ROW_HEIGHT));
            if (!child.children.isEmpty()) layoutChildren(child, offset, y + ROW_HEIGHT + GAP, childWidth, depth + 1);
            offset += childWidth;
            remainingWidth -= childWidth;
            remainingValue -= child.value;
        }
    }

    private static int depth(Node node, int current) {
        int result = current;
        for (Node child : node.children.values()) result = Math.max(result, depth(child, current + 1));
        return result;
    }

    private Node nodeAt(Point point) {
        for (Map.Entry<Node, Rectangle> entry : bounds.entrySet()) {
            if (entry.getValue().contains(point)) return entry.getKey();
        }
        return null;
    }

    private static Color heat(float ratio, int depth) {
        float bounded = Math.max(0f, Math.min(1f, ratio));
        int red = Math.min(225, 92 + (int) (110 * bounded) + depth * 3);
        int green = Math.max(62, 142 - depth * 5);
        int blue = Math.max(48, 104 - depth * 4);
        return new Color(red, green, blue);
    }

    private static String percent(long value, long total) {
        return total == 0L ? "0%" : String.format("%.1f%%", value * 100d / total);
    }

    private static String trim(FontMetrics metrics, String value, int width) {
        if (metrics.stringWidth(value) <= width) return value;
        int end = value.length();
        while (end > 0 && metrics.stringWidth(value.substring(0, end) + "...") > width) end--;
        return value.substring(0, end) + "...";
    }

    static final class Node {
        final String name;
        final Map<String, Node> children = new LinkedHashMap<>();
        long value;
        long count;
        int depth;

        Node(String name) {
            this.name = name;
        }
    }
}

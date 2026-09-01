package dev.whitedev.jpi.ui.callgraph;

import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Path2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class CallGraphCanvas extends JComponent {
    private static final int NODE_WIDTH = 250;
    private static final int NODE_HEIGHT = 78;
    private static final int COLUMN_GAP = 110;
    private static final int ROW_GAP = 28;
    private static final int MARGIN = 36;
    private final DeobfuscationWorkspace workspace;
    private final Map<String, Rectangle> bounds = new LinkedHashMap<>();
    private CallGraphModel.View view = new CallGraphModel.View(List.of(), List.of(),
            CallGraphModel.HeatMetric.CALLS);
    private CallGraphModel.Node selected;
    private Consumer<CallGraphModel.Node> selectionListener = ignored -> {};
    private long maximumHeat;

    CallGraphCanvas(DeobfuscationWorkspace workspace) {
        this.workspace = workspace;
        setOpaque(true);
        setBackground(Ui.BACKGROUND);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                setCursor(nodeAt(event.getPoint()) == null
                        ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override public void mouseClicked(MouseEvent event) {
                selected = nodeAt(event.getPoint());
                selectionListener.accept(selected);
                repaint();
            }
        };
        addMouseMotionListener(mouse);
        addMouseListener(mouse);
    }

    void setSelectionListener(Consumer<CallGraphModel.Node> listener) {
        selectionListener = listener == null ? ignored -> {} : listener;
    }

    void setView(CallGraphModel.View value) {
        view = value == null ? new CallGraphModel.View(List.of(), List.of(),
                CallGraphModel.HeatMetric.CALLS) : value;
        if (selected != null) {
            String selectedId = selected.id();
            selected = view.nodes().stream().filter(node -> node.id().equals(selectedId)).findFirst().orElse(null);
            selectionListener.accept(selected);
        }
        layoutGraph();
        repaint();
    }

    @Override public String getToolTipText(MouseEvent event) {
        CallGraphModel.Node node = nodeAt(event.getPoint());
        if (node == null) return null;
        return node.id() + " | " + node.calls + " calls | " + formatNanos(node.totalNanos);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        paintEdges(g);
        paintNodes(g);
        g.dispose();
    }

    private void paintEdges(Graphics2D g) {
        List<CallGraphModel.Edge> edges = new ArrayList<>(view.edges());
        edges.sort(Comparator.comparingLong(edge -> edge.calls));
        for (CallGraphModel.Edge edge : edges) {
            Rectangle from = bounds.get(edge.callerId());
            Rectangle to = bounds.get(edge.calleeId());
            if (from == null || to == null) continue;
            float thickness = (float) Math.min(9d, 1d + Math.log10(edge.calls + 1d) * 1.65d);
            Color color = edge.failures > 0L ? new Color(220, 88, 96, 190)
                    : edge.reflective ? new Color(224, 168, 72, 180) : new Color(94, 166, 132, 155);
            g.setColor(color);
            g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double x1 = from.getMaxX();
            double y1 = from.getCenterY();
            double x2 = to.getMinX();
            double y2 = to.getCenterY();
            double bend = Math.max(38d, Math.abs(x2 - x1) * .42d);
            CubicCurve2D curve = new CubicCurve2D.Double(x1, y1, x1 + bend, y1,
                    x2 - bend, y2, x2, y2);
            g.draw(curve);
            arrow(g, x2, y2, color);
            if (thickness >= 4f) {
                String label = compact(edge.calls) + (edge.failures > 0L ? " / " + edge.failures + " failed" : "");
                FontMetrics metrics = g.getFontMetrics();
                int x = (int) ((x1 + x2) / 2d - metrics.stringWidth(label) / 2d);
                int y = (int) ((y1 + y2) / 2d) - 4;
                g.setColor(Ui.BACKGROUND);
                g.fillRoundRect(x - 4, y - metrics.getAscent(), metrics.stringWidth(label) + 8,
                        metrics.getHeight(), 6, 6);
                g.setColor(color.brighter());
                g.drawString(label, x, y);
            }
        }
    }

    private void paintNodes(Graphics2D g) {
        Font normal = getFont() == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 12) : getFont();
        Font bold = normal.deriveFont(Font.BOLD, 12f);
        for (CallGraphModel.Node node : view.nodes()) {
            Rectangle area = bounds.get(node.id());
            if (area == null) continue;
            double ratio = maximumHeat == 0L ? 0d
                    : Math.log1p(view.metric().value(node)) / Math.log1p(maximumHeat);
            Color fill = heat(ratio);
            g.setColor(fill);
            g.fillRoundRect(area.x, area.y, area.width, area.height, 14, 14);
            g.setStroke(new BasicStroke(node == selected ? 3f : node.active ? 2f : 1f));
            g.setColor(node == selected ? Color.WHITE : node.active ? Ui.SUCCESS : Ui.BORDER);
            g.drawRoundRect(area.x, area.y, area.width, area.height, 14, 14);
            g.setClip(area.x + 10, area.y + 6, area.width - 20, area.height - 12);
            g.setFont(bold);
            g.setColor(Color.WHITE);
            g.drawString(ellipsize(g, display(node), area.width - 20), area.x + 10, area.y + 20);
            g.setFont(normal);
            g.setColor(new Color(222, 225, 228));
            g.drawString(compact(node.calls) + " calls   " + formatNanos(node.totalNanos),
                    area.x + 10, area.y + 42);
            String detail = node.measured() ? "avg " + formatNanos(node.averageNanos()) : "time not measured";
            if (node.exceptions > 0L) detail += "   " + compact(node.exceptions) + " exceptions";
            g.drawString(detail, area.x + 10, area.y + 62);
            g.setClip(null);
        }
    }

    private void layoutGraph() {
        bounds.clear();
        maximumHeat = 0L;
        Map<String, CallGraphModel.Node> indexed = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        for (CallGraphModel.Node node : view.nodes()) {
            indexed.put(node.id(), node);
            incoming.put(node.id(), 0);
            maximumHeat = Math.max(maximumHeat, view.metric().value(node));
        }
        for (CallGraphModel.Edge edge : view.edges()) {
            incoming.computeIfPresent(edge.calleeId(), (key, value) -> value + 1);
        }
        Map<String, Integer> depth = new HashMap<>();
        ArrayDeque<CallGraphModel.Node> queue = new ArrayDeque<>();
        for (CallGraphModel.Node node : view.nodes()) {
            if (incoming.getOrDefault(node.id(), 0) == 0) {
                depth.put(node.id(), 0);
                queue.add(node);
            }
        }
        if (queue.isEmpty() && !view.nodes().isEmpty()) {
            CallGraphModel.Node hottest = view.nodes().stream()
                    .max(Comparator.comparingLong(node -> view.metric().value(node))).orElseThrow();
            depth.put(hottest.id(), 0);
            queue.add(hottest);
        }
        while (!queue.isEmpty()) {
            CallGraphModel.Node node = queue.removeFirst();
            int nextDepth = Math.min(8, depth.get(node.id()) + 1);
            for (CallGraphModel.Edge edge : node.outgoing) {
                CallGraphModel.Node callee = indexed.get(edge.calleeId());
                if (callee == null || depth.containsKey(callee.id())) continue;
                depth.put(callee.id(), nextDepth);
                queue.addLast(callee);
            }
        }
        int fallback = depth.values().stream().max(Integer::compareTo).orElse(0) + 1;
        Map<Integer, List<CallGraphModel.Node>> columns = new LinkedHashMap<>();
        for (CallGraphModel.Node node : view.nodes()) {
            columns.computeIfAbsent(depth.getOrDefault(node.id(), fallback), ignored -> new ArrayList<>()).add(node);
        }
        int maximumRows = 1;
        for (Map.Entry<Integer, List<CallGraphModel.Node>> entry : columns.entrySet()) {
            List<CallGraphModel.Node> nodes = entry.getValue();
            nodes.sort(Comparator.comparingLong((CallGraphModel.Node node) -> view.metric().value(node)).reversed());
            maximumRows = Math.max(maximumRows, nodes.size());
            for (int row = 0; row < nodes.size(); row++) {
                bounds.put(nodes.get(row).id(), new Rectangle(
                        MARGIN + entry.getKey() * (NODE_WIDTH + COLUMN_GAP),
                        MARGIN + row * (NODE_HEIGHT + ROW_GAP), NODE_WIDTH, NODE_HEIGHT));
            }
        }
        int columnsCount = columns.keySet().stream().max(Integer::compareTo).orElse(0) + 1;
        setPreferredSize(new Dimension(MARGIN * 2 + columnsCount * NODE_WIDTH
                + Math.max(0, columnsCount - 1) * COLUMN_GAP,
                MARGIN * 2 + maximumRows * NODE_HEIGHT + Math.max(0, maximumRows - 1) * ROW_GAP));
        revalidate();
    }

    private CallGraphModel.Node nodeAt(Point point) {
        for (CallGraphModel.Node node : view.nodes()) {
            Rectangle area = bounds.get(node.id());
            if (area != null && area.contains(point)) return node;
        }
        return null;
    }

    private String display(CallGraphModel.Node node) {
        String owner = workspace.classAlias(node.className);
        String method = workspace.methodAlias(node.className, node.methodName, node.descriptor);
        return owner + "." + method + node.descriptor;
    }

    private static void arrow(Graphics2D g, double x, double y, Color color) {
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(x, y);
        arrow.lineTo(x - 10, y - 6);
        arrow.lineTo(x - 10, y + 6);
        arrow.closePath();
        g.setColor(color);
        g.fill(arrow);
    }

    private static Color heat(double ratio) {
        double bounded = Math.max(0d, Math.min(1d, ratio));
        int red = (int) (54 + 112 * bounded);
        int green = (int) (57 + 36 * bounded);
        int blue = (int) (61 - 18 * bounded);
        return new Color(red, green, blue);
    }

    private static String formatNanos(long nanos) {
        if (nanos < 0L) return "n/a";
        if (nanos >= 1_000_000_000L) return String.format("%.2f s", nanos / 1_000_000_000d);
        if (nanos >= 1_000_000L) return String.format("%.2f ms", nanos / 1_000_000d);
        if (nanos >= 1_000L) return String.format("%.2f us", nanos / 1_000d);
        return nanos + " ns";
    }

    private static String compact(long value) {
        if (value >= 1_000_000_000L) return String.format("%.1fB", value / 1_000_000_000d);
        if (value >= 1_000_000L) return String.format("%.1fM", value / 1_000_000d);
        if (value >= 1_000L) return String.format("%.1fK", value / 1_000d);
        return Long.toString(value);
    }

    private static String ellipsize(Graphics2D g, String value, int width) {
        if (g.getFontMetrics().stringWidth(value) <= width) return value;
        String suffix = "...";
        int end = value.length();
        while (end > 0 && g.getFontMetrics().stringWidth(value.substring(0, end) + suffix) > width) end--;
        return value.substring(0, end) + suffix;
    }
}

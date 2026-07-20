package dev.whitedev.jpi.ui.analysis;

import dev.whitedev.jpi.ui.Ui;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

final class CfgGraphCanvas extends JComponent {
    private static final int NODE_WIDTH = 260;
    private static final int NODE_HEIGHT = 108;
    private static final int HORIZONTAL_GAP = 54;
    private static final int VERTICAL_GAP = 70;
    private static final int MARGIN = 45;
    private static final int MAX_COLUMNS = 5;
    private static final Color DEAD = new Color(112, 52, 58);
    private static final Color NEVER_EXECUTED = new Color(125, 91, 38);
    private static final Color EXECUTED = new Color(38, 124, 88);
    private static final Color EDGE = new Color(105, 110, 120);

    private final Map<String, Rectangle> bounds = new LinkedHashMap<String, Rectangle>();
    private CfgGraphModel model;
    private CfgGraphModel.Block selected;
    private Consumer<CfgGraphModel.Block> selectionListener = block -> {};

    CfgGraphCanvas() {
        setOpaque(true);
        setBackground(Ui.BACKGROUND);
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                CfgGraphModel.Block block = blockAt(event.getX(), event.getY());
                if (block == null) return;
                selected = block;
                selectionListener.accept(block);
                repaint();
            }
        });
    }

    void setSelectionListener(Consumer<CfgGraphModel.Block> listener) {
        selectionListener = listener == null ? block -> {} : listener;
    }

    void setModel(CfgGraphModel value) {
        model = value;
        selected = value == null || value.blocks.isEmpty() ? null : value.blocks.get(0);
        rebuildLayout();
        selectionListener.accept(selected);
        repaint();
    }

    void refreshCounts() {
        repaint();
    }

    CfgGraphModel.Block selectedBlock() {
        return selected;
    }

    @Override public String getToolTipText(MouseEvent event) {
        CfgGraphModel.Block block = blockAt(event.getX(), event.getY());
        if (block == null) return null;
        return block.id + " | lines " + block.lineRange() + " | " + block.executions + " executions";
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (model == null) return;
        Graphics2D output = (Graphics2D) graphics.create();
        output.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        output.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        paintEdges(output);
        paintNodes(output);
        output.dispose();
    }

    private void paintEdges(Graphics2D output) {
        output.setStroke(new BasicStroke(1.6f));
        int routed = 0;
        for (CfgGraphModel.Edge edge : model.edges) {
            Rectangle from = bounds.get(edge.from);
            Rectangle to = bounds.get(edge.to);
            if (from == null || to == null) continue;
            Color color = edgeColor(edge);
            output.setColor(color);
            double startX = from.getCenterX();
            double startY = from.getMaxY();
            double endX = to.getCenterX();
            double endY = to.getMinY();
            Path2D path = new Path2D.Double();
            path.moveTo(startX, startY);
            if (endY > startY) {
                double middle = startY + (endY - startY) / 2.0;
                path.curveTo(startX, middle, endX, middle, endX, endY);
            } else {
                double side = Math.max(from.getMaxX(), to.getMaxX()) + 24 + (routed++ % 8) * 9;
                path.curveTo(side, startY + 18, side, endY - 18, endX, endY);
            }
            output.draw(path);
            arrow(output, endX, endY, color);
            String label = edge.label.isEmpty() ? edge.kind.toLowerCase() : edge.label;
            if (!label.isEmpty() && !"flow".equals(label)) {
                int labelX = (int) ((startX + endX) / 2.0);
                int labelY = (int) ((startY + endY) / 2.0) - 4;
                output.setFont(getFont().deriveFont(Font.PLAIN, 10f));
                output.drawString(label, labelX + 5, labelY);
            }
        }
    }

    private Color edgeColor(CfgGraphModel.Edge edge) {
        CfgGraphModel.Block from = model.byId.get(edge.from);
        CfgGraphModel.Block to = model.byId.get(edge.to);
        if (from != null && !from.reachable || to != null && !to.reachable) return DEAD;
        if (model.totalExecutions > 0L) {
            boolean sourceExecuted = from == null || from.executions > 0L;
            boolean targetExecuted = to == null || to.executions > 0L;
            if (sourceExecuted && targetExecuted) return EXECUTED;
            return NEVER_EXECUTED;
        }
        if ("EXCEPTION".equals(edge.kind)) return new Color(166, 92, 102);
        if ("BRANCH".equals(edge.kind) || "SWITCH".equals(edge.kind)) return new Color(141, 127, 82);
        return EDGE;
    }

    private void paintNodes(Graphics2D output) {
        Font title = getFont().deriveFont(Font.BOLD, 13f);
        Font text = getFont().deriveFont(Font.PLAIN, 11f);
        for (Map.Entry<String, Rectangle> entry : bounds.entrySet()) {
            String id = entry.getKey();
            Rectangle rectangle = entry.getValue();
            CfgGraphModel.Block block = model.byId.get(id);
            Color border = nodeColor(block);
            output.setColor(fill(border));
            output.fillRoundRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height, 16, 16);
            output.setColor(block != null && block == selected ? Ui.TEXT : border);
            output.setStroke(new BasicStroke(block != null && block == selected ? 2.8f : 1.8f));
            output.drawRoundRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height, 16, 16);

            output.setColor(Ui.TEXT);
            output.setFont(title);
            String heading = block == null ? id : block.id + "   " + executions(block.executions);
            output.drawString(heading, rectangle.x + 12, rectangle.y + 21);
            output.setFont(text);
            output.setColor(Ui.MUTED);
            if (block == null) {
                output.drawString("Synthetic control-flow node", rectangle.x + 12, rectangle.y + 44);
                continue;
            }
            output.drawString("instructions " + block.startInstruction + ".." + block.endInstruction
                    + "   lines " + block.lineRange(), rectangle.x + 12, rectangle.y + 43);
            output.drawString("idom " + (block.immediateDominator.isEmpty() ? "none" : block.immediateDominator)
                    + "   in " + block.incoming.size() + "   out " + block.outgoing.size(),
                    rectangle.x + 12, rectangle.y + 62);
            output.setColor(Ui.TEXT);
            output.drawString(clipped(block.firstInstruction(), output.getFontMetrics(text), rectangle.width - 24),
                    rectangle.x + 12, rectangle.y + 85);
        }
    }

    private Color nodeColor(CfgGraphModel.Block block) {
        if (block == null) return Ui.ACCENT;
        if (!block.reachable) return DEAD;
        if (block.executions > 0L) return EXECUTED;
        if (model.totalExecutions > 0L) return NEVER_EXECUTED;
        return Ui.ACCENT;
    }

    private static Color fill(Color color) {
        return new Color(Math.max(24, color.getRed() / 3), Math.max(24, color.getGreen() / 3),
                Math.max(26, color.getBlue() / 3));
    }

    private void rebuildLayout() {
        bounds.clear();
        if (model == null) {
            setPreferredSize(new Dimension(600, 500));
            revalidate();
            return;
        }

        Map<String, Integer> ranks = new HashMap<String, Integer>();
        ranks.put("ENTRY", Integer.valueOf(0));
        ArrayDeque<String> queue = new ArrayDeque<String>();
        queue.add("ENTRY");
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            int nextRank = ranks.get(current).intValue() + 1;
            for (CfgGraphModel.Edge edge : model.edges) {
                if (!edge.from.equals(current) || "EXIT".equals(edge.to)) continue;
                if (!ranks.containsKey(edge.to)) {
                    ranks.put(edge.to, Integer.valueOf(nextRank));
                    queue.addLast(edge.to);
                }
            }
        }

        int maximumRank = 0;
        for (Integer rank : ranks.values()) maximumRank = Math.max(maximumRank, rank.intValue());
        for (CfgGraphModel.Block block : model.blocks) {
            if (!ranks.containsKey(block.id)) ranks.put(block.id, Integer.valueOf(++maximumRank));
        }
        int exitRank = maximumRank + 1;
        ranks.put("EXIT", Integer.valueOf(exitRank));

        Map<Integer, List<String>> rows = new LinkedHashMap<Integer, List<String>>();
        List<String> identifiers = new ArrayList<String>();
        identifiers.add("ENTRY");
        for (CfgGraphModel.Block block : model.blocks) identifiers.add(block.id);
        identifiers.add("EXIT");
        Collections.sort(identifiers, new Comparator<String>() {
            @Override public int compare(String left, String right) {
                int byRank = Integer.compare(ranks.get(left).intValue(), ranks.get(right).intValue());
                if (byRank != 0) return byRank;
                return Integer.compare(order(left), order(right));
            }
        });
        for (String id : identifiers) {
            Integer rank = ranks.get(id);
            List<String> values = rows.get(rank);
            if (values == null) {
                values = new ArrayList<String>();
                rows.put(rank, values);
            }
            values.add(id);
        }

        int layoutColumns = 1;
        for (List<String> values : rows.values()) {
            layoutColumns = Math.max(layoutColumns, Math.min(MAX_COLUMNS, values.size()));
        }
        int visualRow = 0;
        for (List<String> values : rows.values()) {
            for (int offset = 0; offset < values.size(); offset += MAX_COLUMNS) {
                int size = Math.min(MAX_COLUMNS, values.size() - offset);
                int rowWidth = size * NODE_WIDTH + (size - 1) * HORIZONTAL_GAP;
                int fullWidth = layoutColumns * NODE_WIDTH + (layoutColumns - 1) * HORIZONTAL_GAP;
                int startX = MARGIN + Math.max(0, (fullWidth - rowWidth) / 2);
                for (int column = 0; column < size; column++) {
                    String id = values.get(offset + column);
                    int x = startX + column * (NODE_WIDTH + HORIZONTAL_GAP);
                    int y = MARGIN + visualRow * (NODE_HEIGHT + VERTICAL_GAP);
                    bounds.put(id, new Rectangle(x, y, NODE_WIDTH, NODE_HEIGHT));
                }
                visualRow++;
            }
        }
        int width = MARGIN * 2 + layoutColumns * NODE_WIDTH + (layoutColumns - 1) * HORIZONTAL_GAP + 110;
        int height = MARGIN * 2 + visualRow * (NODE_HEIGHT + VERTICAL_GAP);
        setPreferredSize(new Dimension(Math.max(720, width), Math.max(520, height)));
        revalidate();
    }

    private CfgGraphModel.Block blockAt(int x, int y) {
        if (model == null) return null;
        for (Map.Entry<String, Rectangle> entry : bounds.entrySet()) {
            if (entry.getValue().contains(x, y)) return model.byId.get(entry.getKey());
        }
        return null;
    }

    private static int order(String id) {
        if ("ENTRY".equals(id)) return -1;
        if ("EXIT".equals(id)) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(id.substring(1));
        } catch (RuntimeException error) {
            return Integer.MAX_VALUE - 1;
        }
    }

    private static String executions(long value) {
        if (value < 1000L) return value + " hits";
        if (value < 1000000L) return String.format("%.1fk hits", Double.valueOf(value / 1000.0));
        return String.format("%.1fM hits", Double.valueOf(value / 1000000.0));
    }


    private static String clipped(String value, FontMetrics metrics, int width) {
        if (metrics.stringWidth(value) <= width) return value;
        String suffix = "...";
        int end = value.length();
        while (end > 0 && metrics.stringWidth(value.substring(0, end) + suffix) > width) end--;
        return value.substring(0, end) + suffix;
    }

    private static void arrow(Graphics2D output, double x, double y, Color color) {
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(x, y);
        arrow.lineTo(x - 5, y - 9);
        arrow.lineTo(x + 5, y - 9);
        arrow.closePath();
        output.setColor(color);
        output.fill(arrow);
    }
}


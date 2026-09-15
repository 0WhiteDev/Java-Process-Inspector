package dev.whitedev.jpi.ui.threads;

import dev.whitedev.jpi.threads.ThreadAnalysisSnapshot;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ThreadLockGraphCanvas extends JPanel {
    private static final int NODE_WIDTH = 310;
    private static final int NODE_HEIGHT = 54;
    private static final int THREAD_X = 30;
    private static final int LOCK_X = 580;
    private static final int TOP = 34;
    private static final int GAP = 76;
    private ThreadAnalysisSnapshot snapshot;

    ThreadLockGraphCanvas() {
        setBackground(Ui.SURFACE);
        setOpaque(true);
        setPreferredSize(new Dimension(940, 380));
    }

    void setSnapshot(ThreadAnalysisSnapshot value) {
        snapshot = value;
        int rows = value == null ? 4 : Math.max(4, Math.max(graphThreads(value).size(), lockCount(value)));
        setPreferredSize(new Dimension(940, TOP * 2 + rows * GAP));
        revalidate();
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ThreadAnalysisSnapshot value = snapshot;
        if (value == null || graphThreads(value).isEmpty()) {
            g.setColor(Ui.MUTED);
            g.drawString("No lock dependencies in the current snapshot", 32, 42);
            g.dispose();
            return;
        }
        List<ThreadAnalysisSnapshot.ThreadEntry> threads = graphThreads(value);
        Map<Long, Integer> threadY = new LinkedHashMap<>();
        Map<String, Integer> lockY = new LinkedHashMap<>();
        int index = 0;
        for (ThreadAnalysisSnapshot.ThreadEntry thread : threads) {
            threadY.put(thread.id(), TOP + index++ * GAP);
            if (!thread.lock().isBlank()) lockY.putIfAbsent(thread.lock(), TOP + (lockY.size() * GAP));
            for (ThreadAnalysisSnapshot.HeldLock lock : thread.heldLocks()) {
                lockY.putIfAbsent(lock.identity(), TOP + (lockY.size() * GAP));
            }
        }
        for (ThreadAnalysisSnapshot.ThreadEntry thread : threads) {
            Integer fromY = threadY.get(thread.id());
            if (!thread.lock().isBlank() && lockY.containsKey(thread.lock())) {
                arrow(g, THREAD_X + NODE_WIDTH, fromY + NODE_HEIGHT / 2, LOCK_X,
                        lockY.get(thread.lock()) + NODE_HEIGHT / 2,
                        thread.deadlocked() ? new Color(232, 84, 84) : Ui.WARNING, "waits");
            }
            for (ThreadAnalysisSnapshot.HeldLock lock : thread.heldLocks()) {
                Integer y = lockY.get(lock.identity());
                if (y != null) arrow(g, LOCK_X, y + NODE_HEIGHT / 2, THREAD_X + NODE_WIDTH,
                        fromY + NODE_HEIGHT / 2, Ui.SUCCESS, "held by");
            }
        }
        for (Map.Entry<String, Integer> lock : lockY.entrySet()) drawLock(g, lock.getKey(), lock.getValue());
        for (ThreadAnalysisSnapshot.ThreadEntry thread : threads) drawThread(g, thread, threadY.get(thread.id()));
        g.dispose();
    }

    private List<ThreadAnalysisSnapshot.ThreadEntry> graphThreads(ThreadAnalysisSnapshot value) {
        Set<Long> included = new LinkedHashSet<>();
        for (ThreadAnalysisSnapshot.ThreadEntry thread : value.threads()) {
            if (!thread.lock().isBlank() || !thread.heldLocks().isEmpty() || thread.deadlocked()) {
                included.add(thread.id());
                if (thread.ownerId() >= 0L) included.add(thread.ownerId());
            }
        }
        List<ThreadAnalysisSnapshot.ThreadEntry> result = new ArrayList<>();
        for (ThreadAnalysisSnapshot.ThreadEntry thread : value.threads()) {
            if (included.contains(thread.id())) result.add(thread);
        }
        return result;
    }

    private int lockCount(ThreadAnalysisSnapshot value) {
        Set<String> locks = new LinkedHashSet<>();
        for (ThreadAnalysisSnapshot.ThreadEntry thread : graphThreads(value)) {
            if (!thread.lock().isBlank()) locks.add(thread.lock());
            for (ThreadAnalysisSnapshot.HeldLock lock : thread.heldLocks()) locks.add(lock.identity());
        }
        return locks.size();
    }

    private void drawThread(Graphics2D g, ThreadAnalysisSnapshot.ThreadEntry thread, int y) {
        Color border = thread.deadlocked() ? new Color(232, 84, 84)
                : "BLOCKED".equals(thread.state()) ? Ui.WARNING : Ui.BORDER;
        g.setColor(Ui.SURFACE_LIGHT);
        g.fillRoundRect(THREAD_X, y, NODE_WIDTH, NODE_HEIGHT, 12, 12);
        g.setColor(border);
        g.setStroke(new BasicStroke(thread.deadlocked() ? 2.5f : 1.5f));
        g.drawRoundRect(THREAD_X, y, NODE_WIDTH, NODE_HEIGHT, 12, 12);
        g.setFont(getFont().deriveFont(Font.BOLD, 13f));
        g.setColor(Ui.TEXT);
        g.drawString(shorten(thread.name(), 36), THREAD_X + 12, y + 21);
        g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g.setColor(Ui.MUTED);
        g.drawString("#" + thread.id() + "  " + thread.state()
                + (thread.deadlocked() ? "  DEADLOCKED" : ""), THREAD_X + 12, y + 41);
    }

    private void drawLock(Graphics2D g, String lock, int y) {
        g.setColor(new Color(38, 43, 47));
        g.fillRoundRect(LOCK_X, y, NODE_WIDTH, NODE_HEIGHT, 12, 12);
        g.setColor(Ui.BORDER);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(LOCK_X, y, NODE_WIDTH, NODE_HEIGHT, 12, 12);
        g.setColor(Ui.TEXT);
        g.setFont(getFont().deriveFont(Font.BOLD, 12f));
        g.drawString("Lock", LOCK_X + 12, y + 20);
        g.setColor(Ui.MUTED);
        g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g.drawString(shorten(lock, 43), LOCK_X + 12, y + 40);
    }

    private void arrow(Graphics2D g, int x1, int y1, int x2, int y2, Color color, String label) {
        g.setColor(color);
        g.setStroke(new BasicStroke(1.7f));
        int bend = (x1 + x2) / 2;
        Path2D path = new Path2D.Double();
        path.moveTo(x1, y1);
        path.curveTo(bend, y1, bend, y2, x2, y2);
        g.draw(path);
        int direction = x2 > x1 ? 1 : -1;
        Path2D head = new Path2D.Double();
        head.moveTo(x2, y2);
        head.lineTo(x2 - direction * 8, y2 - 5);
        head.lineTo(x2 - direction * 8, y2 + 5);
        head.closePath();
        g.fill(head);
        g.setFont(getFont().deriveFont(Font.PLAIN, 10f));
        g.drawString(label, bend - 16, (y1 + y2) / 2 - 4);
    }

    private String shorten(String value, int limit) {
        if (value == null || value.length() <= limit) return value == null ? "" : value;
        return value.substring(0, limit - 3) + "...";
    }
}

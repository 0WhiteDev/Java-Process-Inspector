package dev.whitedev.jpi.ui.layout;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

public final class WrappingFlowLayout extends FlowLayout {
    public WrappingFlowLayout(int alignment, int horizontalGap, int verticalGap) {
        super(alignment, horizontalGap, verticalGap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return size(target, false);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        return size(target, true);
    }

    private Dimension size(Container target, boolean minimum) {
        synchronized (target.getTreeLock()) {
            Container parent = target.getParent();
            int width = parent != null && parent.getWidth() > 0 ? parent.getWidth() : target.getWidth();
            if (parent != null && parent.getWidth() > 0) {
                Insets parentInsets = parent.getInsets();
                width -= parentInsets.left + parentInsets.right;
            }
            Insets insets = target.getInsets();
            int padding = insets.left + insets.right + 2 * getHgap();
            int available = width > 0 ? Math.max(1, width - padding) : Integer.MAX_VALUE;
            int rowWidth = 0;
            int rowHeight = 0;
            int totalWidth = 0;
            int totalHeight = 0;
            for (Component component : target.getComponents()) {
                if (!component.isVisible()) continue;
                Dimension item = minimum ? component.getMinimumSize() : component.getPreferredSize();
                int gap = rowWidth == 0 ? 0 : getHgap();
                if (rowWidth > 0 && rowWidth + gap + item.width > available) {
                    totalWidth = Math.max(totalWidth, rowWidth);
                    totalHeight += rowHeight + getVgap();
                    rowWidth = 0;
                    rowHeight = 0;
                    gap = 0;
                }
                rowWidth += gap + item.width;
                rowHeight = Math.max(rowHeight, item.height);
            }
            totalWidth = Math.max(totalWidth, rowWidth);
            totalHeight += rowHeight;
            return new Dimension(totalWidth + padding,
                    totalHeight + insets.top + insets.bottom + 2 * getVgap());
        }
    }
}

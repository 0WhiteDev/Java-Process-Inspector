package dev.whitedev.jpi.ui;

import com.formdev.flatlaf.icons.FlatAbstractIcon;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics2D;

final class SectionIcon extends FlatAbstractIcon {
    enum Kind { WORKSPACE, RUNTIME, CODE, ADVANCED }

    private final Kind kind;

    SectionIcon(Kind kind) {
        super(16, 16, Ui.MUTED);
        this.kind = kind;
    }

    @Override
    protected void paintIcon(Component component, Graphics2D g) {
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case WORKSPACE -> {
                g.drawRoundRect(2, 3, 12, 10, 2, 2);
                g.drawLine(6, 4, 6, 12);
            }
            case RUNTIME -> g.drawPolyline(new int[]{1, 4, 6, 9, 11, 15},
                    new int[]{9, 9, 3, 13, 7, 7}, 6);
            case CODE -> {
                g.drawPolyline(new int[]{5, 2, 5}, new int[]{5, 8, 11}, 3);
                g.drawPolyline(new int[]{11, 14, 11}, new int[]{5, 8, 11}, 3);
                g.drawLine(9, 3, 7, 13);
            }
            case ADVANCED -> {
                g.drawLine(2, 5, 14, 5);
                g.drawLine(2, 11, 14, 11);
                g.fillRoundRect(5, 2, 3, 6, 2, 2);
                g.fillRoundRect(10, 8, 3, 6, 2, 2);
            }
        }
    }
}

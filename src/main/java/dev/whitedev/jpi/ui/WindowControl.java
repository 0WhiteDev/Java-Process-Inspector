package dev.whitedev.jpi.ui;

import com.formdev.flatlaf.icons.FlatAbstractIcon;

import javax.swing.AbstractButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;

final class WindowControl extends FlatAbstractIcon {
    enum Action { CLOSE, MINIMIZE, MAXIMIZE, RESTORE }

    private final Action action;

    WindowControl(Action action) {
        super(14, 14, switch (action) {
            case CLOSE -> new Color(0xff5f73);
            case MINIMIZE -> new Color(0xffc740);
            default -> new Color(0x3ed897);
        });
        this.action = action;
    }

    @Override
    protected void paintIcon(Component component, Graphics2D graphics) {
        graphics.setColor(component.isEnabled() ? color : Ui.BORDER);
        graphics.fillOval(1, 1, 12, 12);
        if (!(component instanceof AbstractButton button) || !button.isEnabled()
                || !(button.getModel().isRollover() || button.isFocusOwner() || button.getModel().isPressed())) return;
        graphics.setColor(Ui.SIDEBAR);
        graphics.setStroke(new BasicStroke(1.2f));
        switch (action) {
            case CLOSE -> {
                graphics.drawLine(5, 5, 9, 9);
                graphics.drawLine(5, 9, 9, 5);
            }
            case MINIMIZE -> graphics.drawLine(4, 7, 10, 7);
            case MAXIMIZE -> graphics.drawRect(5, 5, 4, 4);
            case RESTORE -> {
                graphics.drawRect(4, 6, 4, 4);
                graphics.drawLine(6, 4, 10, 4);
                graphics.drawLine(10, 4, 10, 8);
            }
        }
    }
}

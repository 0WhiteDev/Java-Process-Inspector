package dev.whitedev.jpi;

import dev.whitedev.jpi.ui.MainFrame;
import dev.whitedev.jpi.ui.Ui;
import javax.swing.SwingUtilities;

public final class JpiApplication {
    private JpiApplication() {}
    public static void main(String[] args) {
        Ui.install();
        SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true));
    }
}

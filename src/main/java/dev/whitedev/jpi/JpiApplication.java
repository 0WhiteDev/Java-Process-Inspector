package dev.whitedev.jpi;

import dev.whitedev.jpi.ui.MainFrame;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.plugin.runtime.PluginManager;
import javax.swing.SwingUtilities;

public final class JpiApplication {
    private JpiApplication() {}
    public static void main(String[] args) {
        Ui.install();
        PluginManager plugins = new PluginManager();
        try {
            plugins.reload();
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> new MainFrame(plugins).setVisible(true));
    }
}

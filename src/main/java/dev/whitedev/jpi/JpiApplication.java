package dev.whitedev.jpi;

import java.awt.GraphicsEnvironment;
import java.io.OutputStream;

import dev.whitedev.jpi.mcp.JpiMcpRuntime;
import dev.whitedev.jpi.ui.MainFrame;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.plugin.runtime.PluginManager;
import dev.whitedev.jpi.attach.AttachService;
import dev.whitedev.jpi.attach.TunnelAgentCommand;
import javax.swing.SwingUtilities;

public final class JpiApplication {
    private JpiApplication() {}
    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--mcp")) {
            startMcp();
            return;
        }
        if (TunnelAgentCommand.matches(args)) {
            System.exit(TunnelAgentCommand.run(args, new AttachService(), System.out, System.err));
            return;
        }
        Ui.install();
        PluginManager plugins = new PluginManager();
        try {
            plugins.reload();
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> new MainFrame(plugins).setVisible(true));
    }

    private static void startMcp() {
        OutputStream protocol = System.out;
        System.setOut(System.err);
        var runtime = new JpiMcpRuntime();
        if (GraphicsEnvironment.isHeadless()) {
            runtime.serve(System.in, protocol);
            return;
        }
        Ui.install();
        PluginManager plugins = new PluginManager();
        try { plugins.reload(); }
        catch (Exception error) { System.err.println("JPI plugins could not be loaded: " + error.getMessage()); }
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame(plugins, runtime);
            frame.setVisible(true);
            Thread transport = new Thread(() -> {
                runtime.serve(System.in, protocol);
                SwingUtilities.invokeLater(frame::dispose);
            }, "jpi-mcp-stdio");
            transport.setDaemon(true);
            transport.start();
        });
    }
}

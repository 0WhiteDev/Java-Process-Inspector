package dev.whitedev.jpi.ui.mcp;

import dev.whitedev.jpi.mcp.JpiMcpRuntime;
import dev.whitedev.jpi.ui.Ui;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;

public final class McpPanel extends JPanel {
    private final JpiMcpRuntime runtime;
    private final JLabel status = new JLabel();
    private final DefaultTableModel calls = new DefaultTableModel(new String[]{"Time", "Client", "Tool", "PID", "Duration (ms)", "Status", "Results", "Error"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final Timer refresh = new Timer(1000, event -> refresh());
    private Object lastEntries;

    public McpPanel(JpiMcpRuntime runtime) {
        super(new BorderLayout(0, 12));
        this.runtime = runtime;
        setOpaque(false);
        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.setOpaque(false);
        top.add(Ui.sectionHeader("AI / MCP", "Read-only JVM analysis over STDIO. 14 tools and jpi://session.", null), BorderLayout.NORTH);
        top.add(status, BorderLayout.CENTER);
        var help = Ui.outputArea();
        help.setText("Configure your MCP client to launch: java -jar /absolute/path/jpi.jar --mcp\n"
                + "Attach a target manually in the JPI window opened by that client.\n"
                + "An independently started JPI window has its own session and is not shared.\n"
                + "No tools attach, execute code, load agents, patch classes, or modify fields.\n"
                + "Audit log: " + runtime.audit().path());
        top.add(help, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(Ui.scroll(new JTable(calls)), BorderLayout.CENTER);
        refresh();
    }

    private void refresh() {
        var snapshot = runtime.sessions().snapshot();
        status.setText("MCP Server: " + (runtime.running() ? "Running" : "Stopped") + " | STDIO | Target: "
                + (snapshot.session() == null ? "None" : snapshot.session().target().id() + " " + snapshot.session().target().displayName()));
        var entries = runtime.audit().recent();
        if (entries.equals(lastEntries)) return;
        lastEntries = entries;
        calls.setRowCount(0);
        for (var entry : entries) calls.addRow(new Object[]{entry.timestamp(), entry.client(), entry.tool(), entry.targetPid(),
                entry.durationMillis(), entry.status(), entry.resultCount(), entry.errorCode()});
    }

    @Override public void addNotify() { super.addNotify(); refresh.start(); }
    @Override public void removeNotify() { refresh.stop(); super.removeNotify(); }
}

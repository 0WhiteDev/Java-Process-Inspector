package dev.whitedev.jpi.ui.analysis;

import dev.whitedev.jpi.agent.cfg.BytecodeCfgAnalyzer;
import dev.whitedev.jpi.ui.Ui;

import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import java.io.IOException;

public final class OfflineCfg {
    private final CfgGraphModel graph;
    private final String report;

    private OfflineCfg(CfgGraphModel graph, String report) {
        this.graph = graph;
        this.report = report;
    }

    public static OfflineCfg analyze(byte[] bytes, String method, String descriptor) throws IOException {
        CfgGraphModel model = CfgGraphModel.parse(BytecodeCfgAnalyzer.analyze(bytes, method, descriptor));
        StringBuilder report = new StringBuilder().append(model.className).append('.').append(method).append(descriptor)
                .append("\nBlocks: ").append(model.blocks.size()).append(" | Edges: ").append(model.edges.size())
                .append(" | Complexity: ").append(model.complexity).append("\nStatic analysis of saved bytecode.\n\n");
        for (var block : model.blocks) {
            if (report.length() > 48 * 1024) {
                report.append("\nAdditional blocks omitted from this report.");
                break;
            }
            report.append(model.details(block)).append("\n\n");
        }
        return new OfflineCfg(model, report.toString());
    }

    public String report() {
        return report;
    }

    public JComponent view() {
        JTextArea details = Ui.outputArea();
        if (graph.blocks.size() > 400) {
            details.setText(report);
            details.setCaretPosition(0);
            return Ui.scroll(details);
        }
        CfgGraphCanvas canvas = new CfgGraphCanvas();
        canvas.setModel(graph);
        details.setText(graph.details(graph.blocks.getFirst()));
        canvas.setSelectionListener(block -> {
            details.setText(graph.details(block));
            details.setCaretPosition(0);
        });
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.scroll(canvas), Ui.scroll(details));
        split.setResizeWeight(.65);
        return split;
    }
}

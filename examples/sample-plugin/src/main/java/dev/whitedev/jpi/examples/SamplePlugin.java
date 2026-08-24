package dev.whitedev.jpi.examples;

import dev.whitedev.jpi.plugin.api.JpiContext;
import dev.whitedev.jpi.plugin.api.JpiPlugin;
import dev.whitedev.jpi.plugin.api.analysis.AnalysisResult;
import dev.whitedev.jpi.plugin.api.analysis.BytecodeAnalyzer;
import dev.whitedev.jpi.plugin.api.analysis.BytecodeTarget;
import dev.whitedev.jpi.plugin.api.export.ExportRequest;
import dev.whitedev.jpi.plugin.api.export.PluginExporter;
import dev.whitedev.jpi.plugin.api.hook.HookProfile;
import dev.whitedev.jpi.plugin.api.hook.HookTarget;
import dev.whitedev.jpi.plugin.api.ui.JpiTab;
import dev.whitedev.jpi.plugin.api.ui.TabGroup;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class SamplePlugin implements JpiPlugin {
    @Override
    public String id() {
        return "dev.whitedev.jpi.sample";
    }

    @Override
    public String name() {
        return "JPI Sample Plugin";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void initialize(JpiContext context) {
        context.registerTab(new StatusTab());
        context.registerBytecodeAnalyzer(new SizeAnalyzer());
        context.registerExporter(new TargetExporter());
        context.registerHookProfile(new HookProfile("NETTY_WRITES", "Netty writes",
                "Observe application call sites that write through a Netty channel",
                List.of(new HookTarget("io.netty.channel.Channel", "write"),
                        new HookTarget("io.netty.channel.Channel", "writeAndFlush"))));
    }

    private static final class StatusTab implements JpiTab {
        @Override
        public String id() {
            return "sample.status";
        }

        @Override
        public String title() {
            return "Sample plugin";
        }

        @Override
        public TabGroup group() {
            return TabGroup.ADVANCED;
        }

        @Override
        public JComponent createComponent(JpiContext context) {
            JPanel panel = new JPanel();
            panel.setBorder(new EmptyBorder(24, 24, 24, 24));
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            JLabel title = new JLabel("JPI Sample Plugin");
            JLabel state = new JLabel();
            panel.add(title);
            panel.add(state);
            context.onSessionChanged(value -> SwingUtilities.invokeLater(() -> state.setText(value
                    .map(session -> "Attached to " + session.targetDisplayName() + " | PID " + session.targetId())
                    .orElse("Not attached"))));
            state.setText(context.session().map(session -> "Attached to " + session.targetDisplayName()
                    + " | PID " + session.targetId()).orElse("Not attached"));
            return panel;
        }
    }

    private static final class SizeAnalyzer implements BytecodeAnalyzer {
        @Override
        public String id() {
            return "sample.class-size";
        }

        @Override
        public String name() {
            return "Class size summary";
        }

        @Override
        public AnalysisResult analyze(BytecodeTarget target) {
            String method = target.methodName().isEmpty() ? "none" : target.methodName() + target.methodDescriptor();
            String result = "Class: " + target.className() + "\nDefinition: " + target.classId()
                    + "\nBytecode size: " + target.bytecode().length + " bytes\nSelected method: " + method;
            return new AnalysisResult("Class size summary", result);
        }
    }

    private static final class TargetExporter implements PluginExporter {
        @Override
        public String id() {
            return "sample.target";
        }

        @Override
        public String name() {
            return "Target summary";
        }

        @Override
        public String fileExtension() {
            return "txt";
        }

        @Override
        public void export(ExportRequest request) throws Exception {
            String output = request.session()
                    .map(session -> "Target: " + session.targetDisplayName() + "\nPID: " + session.targetId() + "\n")
                    .orElse("JPI is not attached to a target.\n");
            Files.writeString(request.destination(), output, StandardCharsets.UTF_8);
        }
    }
}

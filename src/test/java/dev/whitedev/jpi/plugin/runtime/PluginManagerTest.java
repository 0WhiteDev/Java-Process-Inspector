package dev.whitedev.jpi.plugin.runtime;

import dev.whitedev.jpi.plugin.api.JpiContext;
import dev.whitedev.jpi.plugin.api.JpiPlugin;
import dev.whitedev.jpi.plugin.api.analysis.AnalysisResult;
import dev.whitedev.jpi.plugin.api.analysis.BytecodeAnalyzer;
import dev.whitedev.jpi.plugin.api.analysis.BytecodeTarget;
import dev.whitedev.jpi.plugin.api.hook.HookProfile;
import dev.whitedev.jpi.plugin.api.hook.HookTarget;
import dev.whitedev.jpi.plugin.api.ui.JpiTab;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JLabel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManagerTest {
    @TempDir Path directory;

    @Test void loadsServicePluginAndRemovesEveryContributionOnClose() throws Exception {
        Path plugins = directory.resolve("plugins");
        Files.createDirectories(plugins);
        serviceJar(plugins.resolve("sample.jar"), SamplePlugin.class.getName());
        PluginManager manager = new PluginManager(plugins);

        manager.reload();

        assertEquals(1, manager.descriptors().size());
        assertEquals(PluginDescriptor.Status.LOADED, manager.descriptors().getFirst().status());
        assertEquals(3, manager.descriptors().getFirst().extensions());
        assertEquals(1, manager.extensions().tabs().size());
        assertEquals(1, manager.extensions().analyzers().size());
        assertEquals(1, manager.extensions().hookProfiles().size());
        assertTrue(Files.isDirectory(manager.context("test.sample").orElseThrow().dataDirectory()));

        manager.close();

        assertTrue(manager.extensions().tabs().isEmpty());
        assertTrue(manager.extensions().analyzers().isEmpty());
        assertTrue(manager.extensions().hookProfiles().isEmpty());
    }

    private static void serviceJar(Path path, String provider) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("META-INF/services/" + JpiPlugin.class.getName()));
            output.write((provider + "\n").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    public static final class SamplePlugin implements JpiPlugin {
        @Override public String id() { return "test.sample"; }
        @Override public String name() { return "Sample plugin"; }

        @Override public void initialize(JpiContext context) {
            context.registerTab(new JpiTab() {
                @Override public String id() { return "sample.tab"; }
                @Override public String title() { return "Sample"; }
                @Override public javax.swing.JComponent createComponent(JpiContext value) { return new JLabel("Sample"); }
            });
            context.registerBytecodeAnalyzer(new BytecodeAnalyzer() {
                @Override public String id() { return "sample.analyzer"; }
                @Override public String name() { return "Sample analyzer"; }
                @Override public AnalysisResult analyze(BytecodeTarget target) {
                    return new AnalysisResult("Bytes", String.valueOf(target.bytecode().length));
                }
            });
            context.registerHookProfile(new HookProfile("SAMPLE_CALLS", "Sample calls", "Test profile",
                    List.of(new HookTarget("java.lang.String", "valueOf"))));
        }
    }
}

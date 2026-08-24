package dev.whitedev.jpi.plugin.runtime;

import dev.whitedev.jpi.plugin.api.JpiContext;
import dev.whitedev.jpi.plugin.api.JpiSession;
import dev.whitedev.jpi.plugin.api.Registration;
import dev.whitedev.jpi.plugin.api.analysis.BytecodeAnalyzer;
import dev.whitedev.jpi.plugin.api.decompile.DecompilerProvider;
import dev.whitedev.jpi.plugin.api.deobfuscation.Deobfuscator;
import dev.whitedev.jpi.plugin.api.export.PluginExporter;
import dev.whitedev.jpi.plugin.api.hook.HookProfile;
import dev.whitedev.jpi.plugin.api.ui.JpiTab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

final class PluginContext implements JpiContext, AutoCloseable {
    private final String pluginId;
    private final Path dataDirectory;
    private final ExtensionRegistry registry;
    private final List<Registration> registrations = new ArrayList<>();
    private final List<Consumer<Optional<JpiSession>>> sessionListeners = new CopyOnWriteArrayList<>();
    private volatile JpiSession session;
    private int extensionCount;

    PluginContext(String pluginId, Path dataRoot, ExtensionRegistry registry) throws IOException {
        this.pluginId = pluginId;
        this.registry = registry;
        this.dataDirectory = dataRoot.resolve(safeName(pluginId)).normalize();
        if (!dataDirectory.startsWith(dataRoot.normalize())) throw new IOException("Invalid plugin data directory");
        Files.createDirectories(dataDirectory);
    }

    @Override public int apiVersion() { return PluginManager.API_VERSION; }
    @Override public Path dataDirectory() { return dataDirectory; }
    @Override public Optional<JpiSession> session() { return Optional.ofNullable(session); }

    @Override public Registration onSessionChanged(Consumer<Optional<JpiSession>> listener) {
        if (listener == null) throw new IllegalArgumentException("Session listener is required");
        sessionListeners.add(listener);
        Registration registration = () -> sessionListeners.remove(listener);
        return track(registration);
    }

    @Override public Registration registerTab(JpiTab tab) {
        return trackExtension(registry.registerTab(pluginId, tab));
    }

    @Override public Registration registerBytecodeAnalyzer(BytecodeAnalyzer analyzer) {
        return trackExtension(registry.registerAnalyzer(pluginId, analyzer));
    }

    @Override public Registration registerDecompiler(DecompilerProvider decompiler) {
        return trackExtension(registry.registerDecompiler(pluginId, decompiler));
    }

    @Override public Registration registerDeobfuscator(Deobfuscator deobfuscator) {
        return trackExtension(registry.registerDeobfuscator(pluginId, deobfuscator));
    }

    @Override public Registration registerExporter(PluginExporter exporter) {
        return trackExtension(registry.registerExporter(pluginId, exporter));
    }

    @Override public Registration registerHookProfile(HookProfile profile) {
        return trackExtension(registry.registerHookProfile(pluginId, profile));
    }

    void setSession(JpiSession value) {
        session = value;
        Optional<JpiSession> current = Optional.ofNullable(value);
        for (Consumer<Optional<JpiSession>> listener : sessionListeners) {
            try {
                listener.accept(current);
            } catch (RuntimeException ignored) {
            }
        }
    }

    int extensionCount() {
        return extensionCount;
    }

    private synchronized Registration track(Registration registration) {
        registrations.add(registration);
        return registration;
    }

    private synchronized Registration trackExtension(Registration registration) {
        extensionCount++;
        registrations.add(registration);
        return registration;
    }

    @Override public synchronized void close() {
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException ignored) {
            }
        }
        registrations.clear();
        sessionListeners.clear();
        extensionCount = 0;
        session = null;
    }

    private static String safeName(String value) {
        String normalized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return normalized.length() > 100 ? normalized.substring(0, 100) : normalized;
    }
}

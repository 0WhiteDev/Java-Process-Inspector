package dev.whitedev.jpi.plugin.runtime;

import dev.whitedev.jpi.plugin.api.Registration;
import dev.whitedev.jpi.plugin.api.analysis.BytecodeAnalyzer;
import dev.whitedev.jpi.plugin.api.decompile.DecompilerProvider;
import dev.whitedev.jpi.plugin.api.deobfuscation.Deobfuscator;
import dev.whitedev.jpi.plugin.api.export.PluginExporter;
import dev.whitedev.jpi.plugin.api.hook.HookProfile;
import dev.whitedev.jpi.plugin.api.ui.JpiTab;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public final class ExtensionRegistry {
    private final List<RegisteredExtension<JpiTab>> tabs = new CopyOnWriteArrayList<>();
    private final List<RegisteredExtension<BytecodeAnalyzer>> analyzers = new CopyOnWriteArrayList<>();
    private final List<RegisteredExtension<DecompilerProvider>> decompilers = new CopyOnWriteArrayList<>();
    private final List<RegisteredExtension<Deobfuscator>> deobfuscators = new CopyOnWriteArrayList<>();
    private final List<RegisteredExtension<PluginExporter>> exporters = new CopyOnWriteArrayList<>();
    private final List<RegisteredExtension<HookProfile>> hookProfiles = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public List<RegisteredExtension<JpiTab>> tabs() { return List.copyOf(tabs); }
    public List<RegisteredExtension<BytecodeAnalyzer>> analyzers() { return List.copyOf(analyzers); }
    public List<RegisteredExtension<DecompilerProvider>> decompilers() { return List.copyOf(decompilers); }
    public List<RegisteredExtension<Deobfuscator>> deobfuscators() { return List.copyOf(deobfuscators); }
    public List<RegisteredExtension<PluginExporter>> exporters() { return List.copyOf(exporters); }
    public List<RegisteredExtension<HookProfile>> hookProfiles() { return List.copyOf(hookProfiles); }

    public Registration addListener(Runnable listener) {
        if (listener == null) throw new IllegalArgumentException("Listener is required");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    Registration registerTab(String pluginId, JpiTab value) {
        return register(tabs, pluginId, value, JpiTab::id, "tab");
    }

    Registration registerAnalyzer(String pluginId, BytecodeAnalyzer value) {
        return register(analyzers, pluginId, value, BytecodeAnalyzer::id, "bytecode analyzer");
    }

    Registration registerDecompiler(String pluginId, DecompilerProvider value) {
        return register(decompilers, pluginId, value, DecompilerProvider::id, "decompiler");
    }

    Registration registerDeobfuscator(String pluginId, Deobfuscator value) {
        return register(deobfuscators, pluginId, value, Deobfuscator::id, "deobfuscator");
    }

    Registration registerExporter(String pluginId, PluginExporter value) {
        return register(exporters, pluginId, value, PluginExporter::id, "exporter");
    }

    Registration registerHookProfile(String pluginId, HookProfile value) {
        if (value != null && List.of("NETWORK", "CRYPTO", "FILES", "REFLECTION", "CLASS_LOADING").contains(value.id())) {
            throw new IllegalArgumentException("Plugin hook profile uses a reserved id: " + value.id());
        }
        return register(hookProfiles, pluginId, value, HookProfile::id, "hook profile");
    }

    private <T> Registration register(List<RegisteredExtension<T>> target, String pluginId, T value,
                                      Function<T, String> identifier, String type) {
        if (value == null) throw new IllegalArgumentException("Plugin " + type + " is required");
        String id = normalize(identifier.apply(value));
        for (RegisteredExtension<T> existing : target) {
            if (normalize(identifier.apply(existing.extension())).equals(id)) {
                throw new IllegalArgumentException("Duplicate " + type + " id: " + id);
            }
        }
        RegisteredExtension<T> registered = new RegisteredExtension<>(pluginId, value);
        target.add(registered);
        changed();
        return new Registration() {
            private boolean closed;

            @Override public synchronized void close() {
                if (closed) return;
                closed = true;
                if (target.remove(registered)) changed();
            }
        };
    }

    private void changed() {
        for (Runnable listener : listeners) listener.run();
    }

    private static String normalize(String value) {
        String id = value == null ? "" : value.trim().toLowerCase();
        if (!id.matches("[a-z0-9][a-z0-9._-]{1,127}")) throw new IllegalArgumentException("Invalid extension id: " + value);
        return id;
    }
}

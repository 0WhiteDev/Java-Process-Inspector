package dev.whitedev.jpi.plugin.api;

import dev.whitedev.jpi.plugin.api.analysis.BytecodeAnalyzer;
import dev.whitedev.jpi.plugin.api.decompile.DecompilerProvider;
import dev.whitedev.jpi.plugin.api.deobfuscation.Deobfuscator;
import dev.whitedev.jpi.plugin.api.export.PluginExporter;
import dev.whitedev.jpi.plugin.api.hook.HookProfile;
import dev.whitedev.jpi.plugin.api.ui.JpiTab;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

public interface JpiContext {
    int apiVersion();
    Path dataDirectory();
    Optional<JpiSession> session();
    Registration onSessionChanged(Consumer<Optional<JpiSession>> listener);
    Registration registerTab(JpiTab tab);
    Registration registerBytecodeAnalyzer(BytecodeAnalyzer analyzer);
    Registration registerDecompiler(DecompilerProvider decompiler);
    Registration registerDeobfuscator(Deobfuscator deobfuscator);
    Registration registerExporter(PluginExporter exporter);
    Registration registerHookProfile(HookProfile profile);
}

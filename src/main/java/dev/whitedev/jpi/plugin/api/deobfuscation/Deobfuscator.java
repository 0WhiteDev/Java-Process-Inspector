package dev.whitedev.jpi.plugin.api.deobfuscation;

import dev.whitedev.jpi.plugin.api.analysis.BytecodeTarget;

import java.util.List;

public interface Deobfuscator {
    String id();
    String name();
    List<MappingSuggestion> suggest(BytecodeTarget target) throws Exception;
}

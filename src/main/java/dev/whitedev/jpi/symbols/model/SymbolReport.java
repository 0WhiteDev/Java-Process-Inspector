package dev.whitedev.jpi.symbols.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public record SymbolReport(Path input, String format, String architecture, List<NativeSymbol> symbols,
                           List<DebugArtifact> artifacts, Set<String> sourceFiles, List<String> warnings) {
    public SymbolReport {
        symbols = List.copyOf(symbols);
        artifacts = List.copyOf(artifacts);
        sourceFiles = Set.copyOf(sourceFiles);
        warnings = List.copyOf(warnings);
    }
}

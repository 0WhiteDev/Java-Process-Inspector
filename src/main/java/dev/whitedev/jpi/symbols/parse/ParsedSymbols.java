package dev.whitedev.jpi.symbols.parse;

import dev.whitedev.jpi.symbols.model.DebugArtifact;
import dev.whitedev.jpi.symbols.model.NativeSymbol;

import java.util.List;
import java.util.Set;

public record ParsedSymbols(String format, String architecture, List<NativeSymbol> symbols,
                            List<DebugArtifact> artifacts, Set<String> sourceFiles, List<String> warnings) {
    public ParsedSymbols {
        symbols = List.copyOf(symbols);
        artifacts = List.copyOf(artifacts);
        sourceFiles = Set.copyOf(sourceFiles);
        warnings = List.copyOf(warnings);
    }
}

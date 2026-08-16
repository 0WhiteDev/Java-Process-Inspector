package dev.whitedev.jpi.symbols.model;

public record NativeSymbol(long address, long size, String rawName, String displayName, SymbolKind kind,
                           String sourceFile, int sourceLine, String provider) {
    public NativeSymbol {
        rawName = value(rawName);
        displayName = value(displayName);
        kind = kind == null ? SymbolKind.UNKNOWN : kind;
        sourceFile = value(sourceFile);
        provider = value(provider);
    }

    public String location() {
        if (sourceFile.isEmpty()) return "";
        return sourceLine > 0 ? sourceFile + ":" + sourceLine : sourceFile;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}

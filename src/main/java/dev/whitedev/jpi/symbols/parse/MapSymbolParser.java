package dev.whitedev.jpi.symbols.parse;

import dev.whitedev.jpi.symbols.CppDemangler;
import dev.whitedev.jpi.symbols.model.DebugArtifact;
import dev.whitedev.jpi.symbols.model.NativeSymbol;
import dev.whitedev.jpi.symbols.model.SymbolKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MapSymbolParser {
    private static final Pattern MSVC_SYMBOL = Pattern.compile(
            "^\\s*[0-9A-Fa-f]{1,8}:([0-9A-Fa-f]{1,16})\\s+(\\S+)(?:\\s+([0-9A-Fa-f]{6,16}))?(?:\\s+.*)?$");
    private static final Pattern GNU_SYMBOL = Pattern.compile(
            "^\\s*(?:0x)?([0-9A-Fa-f]{6,16})\\s+([_?$A-Za-z][^\\s=]*(?:::[^\\s=]+)?)\\s*$");
    private static final Pattern LINE_HEADER = Pattern.compile(
            "(?i)^\\s*Line numbers for .*\\(([^)]+\\.(?:c|cc|cpp|cxx|h|hpp|inl|rs))\\).*$");
    private static final Pattern LINE_ENTRY = Pattern.compile(
            "(\\d+)\\s+[0-9A-Fa-f]{1,8}:([0-9A-Fa-f]{1,16})");

    public ParsedSymbols parse(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.ISO_8859_1);
        List<NativeSymbol> symbols = new ArrayList<>();
        Set<String> sourceFiles = new LinkedHashSet<>();
        String currentSource = "";
        for (String line : lines) {
            Matcher header = LINE_HEADER.matcher(line);
            if (header.matches()) {
                currentSource = header.group(1);
                sourceFiles.add(currentSource);
                continue;
            }
            if (!currentSource.isEmpty()) {
                Matcher entries = LINE_ENTRY.matcher(line);
                boolean found = false;
                while (entries.find()) {
                    found = true;
                    int sourceLine = integer(entries.group(1));
                    long address = hex(entries.group(2));
                    symbols.add(new NativeSymbol(address, 0L, currentSource + ":" + sourceLine,
                            currentSource + ":" + sourceLine, SymbolKind.LINE,
                            currentSource, sourceLine, "MAP line"));
                }
                if (found) continue;
            }
            Matcher msvc = MSVC_SYMBOL.matcher(line);
            if (msvc.matches()) {
                String raw = msvc.group(2);
                if (!symbolName(raw)) continue;
                long address = msvc.group(3) == null ? hex(msvc.group(1)) : hex(msvc.group(3));
                symbols.add(symbol(address, raw, "MAP"));
                continue;
            }
            Matcher gnu = GNU_SYMBOL.matcher(line);
            if (gnu.matches()) {
                String raw = gnu.group(2);
                if (symbolName(raw)) symbols.add(symbol(hex(gnu.group(1)), raw, "MAP"));
            }
        }
        List<String> warnings = symbols.isEmpty()
                ? List.of("No supported symbol or line entries were recognized in the MAP file") : List.of();
        return new ParsedSymbols("MAP", "unknown", symbols,
                List.of(new DebugArtifact("MAP", path, path.getFileName().toString(), true)), sourceFiles, warnings);
    }

    private static NativeSymbol symbol(long address, String raw, String provider) {
        String demangled = CppDemangler.demangle(raw);
        SymbolKind kind = CppDemangler.kind(raw, demangled, SymbolKind.FUNCTION);
        return new NativeSymbol(address, 0L, raw, demangled, kind, "", -1, provider);
    }

    private static boolean symbolName(String value) {
        if (value.isEmpty() || value.startsWith("Address") || value.startsWith("entry")) return false;
        return value.indexOf('=') < 0 && value.indexOf(' ') < 0;
    }

    private static long hex(String value) {
        return Long.parseUnsignedLong(value, 16);
    }

    private static int integer(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return -1;
        }
    }
}

package dev.whitedev.jpi.symbols.parse;

import dev.whitedev.jpi.symbols.CppDemangler;
import dev.whitedev.jpi.symbols.model.DebugArtifact;
import dev.whitedev.jpi.symbols.model.NativeSymbol;
import dev.whitedev.jpi.symbols.model.SymbolKind;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PdbSymbolParser {
    private static final int MAX_STRINGS = 250_000;

    public ParsedSymbols parse(Path path) throws IOException {
        BinaryData data = BinaryData.read(path, ByteOrder.LITTLE_ENDIAN);
        if (!data.startsWith(0L, "Microsoft C/C++ MSF".getBytes())) {
            throw new IOException("Unsupported or invalid PDB/MSF file: " + path);
        }
        List<NativeSymbol> symbols = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        int strings = 0;
        int offset = 0;
        while (offset < data.value.length && strings < MAX_STRINGS) {
            while (offset < data.value.length && !printable(data.value[offset])) offset++;
            int start = offset;
            while (offset < data.value.length && printable(data.value[offset]) && offset - start < 4096) offset++;
            if (offset - start < 4) continue;
            strings++;
            String value = new String(data.value, start, offset - start, StandardCharsets.ISO_8859_1).trim();
            if (sourceFile(value)) sources.add(value);
            if (decorated(value)) {
                String demangled = CppDemangler.demangle(value);
                SymbolKind kind = CppDemangler.kind(value, demangled, SymbolKind.UNKNOWN);
                symbols.add(new NativeSymbol(-1L, 0L, value, demangled, kind, "", -1, "PDB strings"));
            }
        }
        List<String> warnings = List.of("PDB support extracts embedded names and source references; addresses and lines require MAP or PE/COFF data");
        return new ParsedSymbols("PDB 7.0", "unknown", symbols,
                List.of(new DebugArtifact("PDB", path, path.getFileName() + ":" + data.value.length, true)),
                sources, warnings);
    }

    private static boolean printable(byte value) {
        int character = value & 0xff;
        return character >= 32 && character < 127;
    }

    private static boolean decorated(String value) {
        if (value.length() > 1024 || value.indexOf(' ') >= 0) return false;
        return value.startsWith("?") && value.contains("@@") || value.startsWith("_Z");
    }

    private static boolean sourceFile(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(c|cc|cpp|cxx|h|hh|hpp|hxx|inl|ixx|m|mm|rs)")
                && value.length() < 2048;
    }
}

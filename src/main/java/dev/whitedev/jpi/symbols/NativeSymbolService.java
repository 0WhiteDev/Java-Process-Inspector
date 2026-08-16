package dev.whitedev.jpi.symbols;

import dev.whitedev.jpi.symbols.model.DebugArtifact;
import dev.whitedev.jpi.symbols.model.NativeSymbol;
import dev.whitedev.jpi.symbols.model.SymbolKind;
import dev.whitedev.jpi.symbols.model.SymbolReport;
import dev.whitedev.jpi.symbols.parse.ElfSymbolParser;
import dev.whitedev.jpi.symbols.parse.MapSymbolParser;
import dev.whitedev.jpi.symbols.parse.ParsedSymbols;
import dev.whitedev.jpi.symbols.parse.PdbSymbolParser;
import dev.whitedev.jpi.symbols.parse.PeSymbolParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class NativeSymbolService {
    private static final int MAX_MERGED_SYMBOLS = 2_000_000;

    public SymbolReport analyze(Path input, List<Path> additional) throws IOException {
        Path primary = requireFile(input);
        List<Path> inputs = new ArrayList<>();
        inputs.add(primary);
        if (additional != null) for (Path path : additional) add(inputs, path);
        for (Path candidate : adjacent(primary)) add(inputs, candidate);

        List<ParsedSymbols> parsed = new ArrayList<>();
        Set<Path> consumed = new LinkedHashSet<>();
        for (int index = 0; index < inputs.size(); index++) {
            Path path = requireFile(inputs.get(index));
            Path canonical = path.toAbsolutePath().normalize();
            if (!consumed.add(canonical)) continue;
            ParsedSymbols result = parse(canonical);
            parsed.add(result);
            for (DebugArtifact artifact : result.artifacts()) {
                if (artifact.available() && artifact.path() != null) add(inputs, artifact.path());
            }
        }

        List<NativeSymbol> merged = merge(parsed);
        List<DebugArtifact> artifacts = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        List<String> formats = new ArrayList<>();
        String architecture = "unknown";
        for (ParsedSymbols result : parsed) {
            if (!formats.contains(result.format())) formats.add(result.format());
            if ("unknown".equals(architecture) && !"unknown".equals(result.architecture())) architecture = result.architecture();
            artifacts.addAll(result.artifacts());
            sources.addAll(result.sourceFiles());
            warnings.addAll(result.warnings());
        }
        return new SymbolReport(primary, String.join(" + ", formats), architecture,
                attachLines(merged), uniqueArtifacts(artifacts), sources, warnings.stream().distinct().toList());
    }

    private static ParsedSymbols parse(Path path) throws IOException {
        String extension = extension(path);
        if ("map".equals(extension)) return new MapSymbolParser().parse(path);
        if ("pdb".equals(extension)) return new PdbSymbolParser().parse(path);
        byte[] signature = signature(path);
        if (signature.length >= 2 && signature[0] == 'M' && signature[1] == 'Z') return new PeSymbolParser().parse(path);
        if (signature.length >= 4 && signature[0] == 0x7f && signature[1] == 'E'
                && signature[2] == 'L' && signature[3] == 'F') return new ElfSymbolParser().parse(path);
        throw new IOException("Unsupported symbol input. Choose PE, ELF, PDB, or MAP: " + path);
    }

    private static List<NativeSymbol> merge(List<ParsedSymbols> parsed) throws IOException {
        Map<String, NativeSymbol> values = new LinkedHashMap<>();
        Map<String, NativeSymbol> addressedByName = new HashMap<>();
        for (ParsedSymbols result : parsed) for (NativeSymbol symbol : result.symbols()) {
            if (symbol.address() >= 0L && symbol.kind() != SymbolKind.LINE) addressedByName.putIfAbsent(symbol.rawName(), symbol);
        }
        for (ParsedSymbols result : parsed) {
            for (NativeSymbol symbol : result.symbols()) {
                if (values.size() >= MAX_MERGED_SYMBOLS) throw new IOException("Merged symbols exceed the "
                        + MAX_MERGED_SYMBOLS + " entry safety limit");
                if (symbol.address() < 0L && addressedByName.containsKey(symbol.rawName())) continue;
                String key = symbol.address() + "\u0000" + symbol.rawName() + "\u0000"
                        + symbol.sourceFile() + "\u0000" + symbol.sourceLine();
                values.putIfAbsent(key, symbol);
            }
        }
        List<NativeSymbol> result = new ArrayList<>(values.values());
        result.sort(Comparator.comparingLong((NativeSymbol value) -> value.address() < 0L ? Long.MAX_VALUE : value.address())
                .thenComparing(NativeSymbol::displayName));
        return result;
    }

    private static List<NativeSymbol> attachLines(List<NativeSymbol> symbols) {
        List<NativeSymbol> lines = symbols.stream().filter(value -> value.kind() == SymbolKind.LINE && value.address() >= 0L)
                .sorted(Comparator.comparingLong(NativeSymbol::address)).toList();
        if (lines.isEmpty()) return symbols;
        List<NativeSymbol> result = new ArrayList<>(symbols.size());
        for (NativeSymbol symbol : symbols) {
            if (symbol.kind() == SymbolKind.LINE || symbol.address() < 0L || !symbol.sourceFile().isEmpty()) {
                result.add(symbol);
                continue;
            }
            NativeSymbol line = floor(lines, symbol.address());
            if (line == null || Long.compareUnsigned(symbol.address() - line.address(), 0x10000L) > 0) {
                result.add(symbol);
                continue;
            }
            result.add(new NativeSymbol(symbol.address(), symbol.size(), symbol.rawName(), symbol.displayName(),
                    symbol.kind(), line.sourceFile(), line.sourceLine(), symbol.provider()));
        }
        return result;
    }

    private static NativeSymbol floor(List<NativeSymbol> lines, long address) {
        int low = 0;
        int high = lines.size() - 1;
        NativeSymbol result = null;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            NativeSymbol value = lines.get(middle);
            if (Long.compareUnsigned(value.address(), address) <= 0) {
                result = value;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result;
    }

    private static List<DebugArtifact> uniqueArtifacts(List<DebugArtifact> values) {
        Map<String, DebugArtifact> result = new LinkedHashMap<>();
        for (DebugArtifact value : values) {
            String key = value.type() + '\u0000' + value.path() + '\u0000' + value.identifier();
            result.putIfAbsent(key, value);
        }
        return List.copyOf(result.values());
    }

    private static List<Path> adjacent(Path input) {
        String file = input.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String base = dot < 0 ? file : file.substring(0, dot);
        Path parent = input.getParent();
        if (parent == null) return List.of();
        return List.of(parent.resolve(base + ".pdb"), parent.resolve(base + ".map"),
                parent.resolve(base + ".debug"), parent.resolve(file + ".debug"));
    }

    private static void add(List<Path> values, Path value) {
        if (value != null && Files.isRegularFile(value)) values.add(value);
    }

    private static Path requireFile(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) throw new IOException("Symbol input does not exist: " + path);
        return path;
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static byte[] signature(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(4);
        }
    }
}

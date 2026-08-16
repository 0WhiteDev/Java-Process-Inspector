package dev.whitedev.jpi.symbols.parse;

import dev.whitedev.jpi.symbols.CppDemangler;
import dev.whitedev.jpi.symbols.model.DebugArtifact;
import dev.whitedev.jpi.symbols.model.NativeSymbol;
import dev.whitedev.jpi.symbols.model.SymbolKind;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PeSymbolParser {
    private static final byte[] PE_SIGNATURE = {'P', 'E', 0, 0};
    private static final byte[] RSDS_SIGNATURE = {'R', 'S', 'D', 'S'};
    private static final int MAX_SYMBOLS = 1_000_000;

    public ParsedSymbols parse(Path path) throws IOException {
        BinaryData data = BinaryData.read(path, ByteOrder.LITTLE_ENDIAN);
        if (!data.startsWith(0L, new byte[]{'M', 'Z'})) throw new IOException("Not a PE image: " + path);
        long pe = data.u32(0x3c);
        if (!data.startsWith(pe, PE_SIGNATURE)) throw new IOException("Invalid PE signature: " + path);
        int machine = data.u16(pe + 4);
        int sectionCount = data.u16(pe + 6);
        long symbolTable = data.u32(pe + 12);
        long symbolCount = data.u32(pe + 16);
        int optionalSize = data.u16(pe + 20);
        long optional = pe + 24;
        int magic = data.u16(optional);
        boolean pe64 = magic == 0x20b;
        if (!pe64 && magic != 0x10b) throw new IOException("Unsupported PE optional-header format");
        long imageBase = pe64 ? data.u64(optional + 24) : data.u32(optional + 28);
        long directories = optional + (pe64 ? 112 : 96);
        List<Section> sections = sections(data, optional + optionalSize, sectionCount);
        List<NativeSymbol> symbols = new ArrayList<>();
        List<DebugArtifact> artifacts = new ArrayList<>();
        Set<String> sourceFiles = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        parseExports(data, directories, imageBase, sections, symbols, warnings);
        parseCoff(data, symbolTable, symbolCount, imageBase, sections, symbols, warnings);
        parseDebugDirectory(path, data, directories, sections, artifacts, warnings);
        if (symbols.isEmpty()) warnings.add("No PE exports or COFF symbols were found");
        return new ParsedSymbols("PE/COFF", architecture(machine), symbols, artifacts, sourceFiles, warnings);
    }

    private static List<Section> sections(BinaryData data, long offset, int count) throws IOException {
        if (count < 0 || count > 4096) throw new IOException("Invalid PE section count: " + count);
        List<Section> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            long entry = offset + index * 40L;
            result.add(new Section(data.ascii(entry, 8), data.u32(entry + 8), data.u32(entry + 12),
                    data.u32(entry + 16), data.u32(entry + 20)));
        }
        return result;
    }

    private static void parseExports(BinaryData data, long directories, long imageBase, List<Section> sections,
                                     List<NativeSymbol> symbols, List<String> warnings) throws IOException {
        if (!data.contains(directories, 8)) return;
        long exportRva = data.u32(directories);
        long exportSize = data.u32(directories + 4);
        if (exportRva == 0L || exportSize == 0L) return;
        long exportOffset = fileOffset(exportRva, sections);
        if (exportOffset < 0L || !data.contains(exportOffset, 40)) {
            warnings.add("PE export directory points outside available section data");
            return;
        }
        long functionCount = Math.min(data.u32(exportOffset + 20), MAX_SYMBOLS);
        long nameCount = Math.min(data.u32(exportOffset + 24), MAX_SYMBOLS);
        long functions = fileOffset(data.u32(exportOffset + 28), sections);
        long names = fileOffset(data.u32(exportOffset + 32), sections);
        long ordinals = fileOffset(data.u32(exportOffset + 36), sections);
        if (functions < 0 || names < 0 || ordinals < 0) return;
        for (long index = 0; index < nameCount; index++) {
            if (!data.contains(names + index * 4, 4) || !data.contains(ordinals + index * 2, 2)) break;
            long nameOffset = fileOffset(data.u32(names + index * 4), sections);
            int ordinal = data.u16(ordinals + index * 2);
            if (nameOffset < 0 || ordinal >= functionCount || !data.contains(functions + ordinal * 4L, 4)) continue;
            String raw = data.asciiZ(nameOffset, 4096);
            long functionRva = data.u32(functions + ordinal * 4L);
            String provider = functionRva >= exportRva && functionRva < exportRva + exportSize
                    ? "PE forwarded export" : "PE export";
            symbols.add(symbol(imageBase + functionRva, 0L, raw, SymbolKind.FUNCTION, provider));
        }
    }

    private static void parseCoff(BinaryData data, long table, long count, long imageBase,
                                  List<Section> sections, List<NativeSymbol> symbols,
                                  List<String> warnings) throws IOException {
        if (table == 0L || count == 0L) return;
        count = Math.min(count, MAX_SYMBOLS);
        long strings = table + count * 18L;
        if (!data.contains(table, count * 18L) || !data.contains(strings, 4)) {
            warnings.add("COFF symbol table is truncated");
            return;
        }
        long stringSize = data.u32(strings);
        for (long index = 0; index < count; index++) {
            long entry = table + index * 18L;
            String raw;
            if (data.u32(entry) == 0L) {
                long nameOffset = data.u32(entry + 4);
                raw = nameOffset >= 4L && nameOffset < stringSize
                        ? data.asciiZ(strings + nameOffset, (int) Math.min(4096L, stringSize - nameOffset)) : "";
            } else {
                raw = data.ascii(entry, 8);
            }
            long value = data.u32(entry + 8);
            int sectionNumber = data.i16(entry + 12);
            int type = data.u16(entry + 14);
            int storageClass = data.u8(entry + 16);
            int auxiliary = data.u8(entry + 17);
            if (!raw.isEmpty() && sectionNumber > 0 && sectionNumber <= sections.size()
                    && storageClass != 103 && storageClass != 104) {
                Section section = sections.get(sectionNumber - 1);
                SymbolKind kind = (type & 0x20) != 0 ? SymbolKind.FUNCTION : SymbolKind.DATA;
                symbols.add(symbol(imageBase + section.virtualAddress + value, 0L, raw, kind, "COFF"));
            }
            index += auxiliary;
        }
    }

    private static void parseDebugDirectory(Path image, BinaryData data, long directories, List<Section> sections,
                                            List<DebugArtifact> artifacts, List<String> warnings) throws IOException {
        long debugDirectory = directories + 6L * 8L;
        if (!data.contains(debugDirectory, 8)) return;
        long debugRva = data.u32(debugDirectory);
        long debugSize = data.u32(debugDirectory + 4);
        long offset = fileOffset(debugRva, sections);
        if (offset < 0L || debugSize < 28L) return;
        int entries = (int) Math.min(debugSize / 28L, 4096L);
        for (int index = 0; index < entries; index++) {
            long entry = offset + index * 28L;
            if (!data.contains(entry, 28)) break;
            if (data.u32(entry + 12) != 2L) continue;
            long size = data.u32(entry + 16);
            long raw = data.u32(entry + 24);
            if (size < 24L || !data.contains(raw, size) || !data.startsWith(raw, RSDS_SIGNATURE)) continue;
            String identifier = guid(data, raw + 4) + '-' + data.u32(raw + 20);
            String pdbName = data.asciiZ(raw + 24, (int) Math.min(16_384L, size - 24L));
            Path declared = path(pdbName);
            Path adjacent = image.getParent() == null || declared == null ? declared
                    : image.getParent().resolve(declared.getFileName()).normalize();
            Path resolved = declared != null && Files.isRegularFile(declared) ? declared : adjacent;
            boolean available = resolved != null && Files.isRegularFile(resolved);
            artifacts.add(new DebugArtifact("PDB", resolved == null ? declared : resolved, identifier, available));
            if (!available) warnings.add("Referenced PDB is unavailable: " + pdbName);
        }
    }

    private static NativeSymbol symbol(long address, long size, String raw, SymbolKind fallback, String provider) {
        String display = CppDemangler.demangle(raw);
        return new NativeSymbol(address, size, raw, display, CppDemangler.kind(raw, display, fallback),
                "", -1, provider);
    }

    private static long fileOffset(long rva, List<Section> sections) {
        for (Section section : sections) {
            long extent = Math.max(section.virtualSize, section.rawSize);
            if (rva >= section.virtualAddress && rva < section.virtualAddress + extent) {
                long relative = rva - section.virtualAddress;
                return relative < section.rawSize ? section.rawOffset + relative : -1L;
            }
        }
        return -1L;
    }

    private static String guid(BinaryData data, long offset) throws IOException {
        return String.format(Locale.ROOT, "%08X-%04X-%04X-%02X%02X-%02X%02X%02X%02X%02X%02X",
                data.u32(offset), data.u16(offset + 4), data.u16(offset + 6),
                data.u8(offset + 8), data.u8(offset + 9), data.u8(offset + 10), data.u8(offset + 11),
                data.u8(offset + 12), data.u8(offset + 13), data.u8(offset + 14), data.u8(offset + 15));
    }

    private static Path path(String value) {
        try {
            return value == null || value.isBlank() ? null : Path.of(value);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String architecture(int machine) {
        return switch (machine) {
            case 0x14c -> "x86";
            case 0x8664 -> "x86-64";
            case 0x1c0, 0x1c4 -> "ARM";
            case 0xaa64 -> "ARM64";
            default -> "machine-0x" + Integer.toHexString(machine);
        };
    }

    private record Section(String name, long virtualSize, long virtualAddress, long rawSize, long rawOffset) {}
}

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

public final class ElfSymbolParser {
    private static final byte[] ELF_SIGNATURE = {0x7f, 'E', 'L', 'F'};
    private static final int MAX_SECTIONS = 65_536;
    private static final int MAX_SYMBOLS = 1_000_000;

    public ParsedSymbols parse(Path path) throws IOException {
        BinaryData source = BinaryData.read(path, ByteOrder.LITTLE_ENDIAN);
        if (!source.startsWith(0L, ELF_SIGNATURE)) throw new IOException("Not an ELF image: " + path);
        int elfClass = source.u8(4);
        boolean elf64 = elfClass == 2;
        if (!elf64 && elfClass != 1) throw new IOException("Unsupported ELF class: " + elfClass);
        int encoding = source.u8(5);
        BinaryData data = source.order(encoding == 2 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        int machine = data.u16(18);
        long sectionOffset = elf64 ? data.u64(40) : data.u32(32);
        int entrySize = data.u16(elf64 ? 58 : 46);
        int sectionCount = data.u16(elf64 ? 60 : 48);
        int namesIndex = data.u16(elf64 ? 62 : 50);
        if (sectionCount < 1 || sectionCount > MAX_SECTIONS || namesIndex >= sectionCount) {
            throw new IOException("Invalid ELF section table");
        }
        List<Section> sections = sections(data, sectionOffset, entrySize, sectionCount, elf64);
        Section nameTable = sections.get(namesIndex);
        for (Section section : sections) section.name = string(data, nameTable, section.nameOffset);
        List<NativeSymbol> symbols = new ArrayList<>();
        List<DebugArtifact> artifacts = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        for (Section section : sections) {
            if (section.type == 2L || section.type == 11L) parseSymbols(data, sections, section, elf64, symbols, warnings);
            if (".debug_str".equals(section.name) || ".debug_line_str".equals(section.name)) {
                extractSourceStrings(data, section, sources);
            }
        }
        Section debugLine = named(sections, ".debug_line");
        if (debugLine != null) {
            DwarfLineParser.Result lines = new DwarfLineParser().parse(data, debugLine.offset, debugLine.size, elf64 ? 8 : 4);
            symbols.addAll(lines.symbols());
            sources.addAll(lines.sourceFiles());
            warnings.addAll(lines.warnings());
        }
        List<String> dwarfSections = new ArrayList<>();
        for (Section section : sections) if (section.name.startsWith(".debug_") || section.name.startsWith(".zdebug_")) dwarfSections.add(section.name);
        if (!dwarfSections.isEmpty()) artifacts.add(new DebugArtifact("DWARF", path, String.join(",", dwarfSections), true));
        if (symbols.isEmpty()) warnings.add("No ELF symbol table or supported DWARF line rows were found");
        return new ParsedSymbols("ELF", architecture(machine), symbols, artifacts, sources, warnings);
    }

    private static List<Section> sections(BinaryData data, long offset, int entrySize, int count,
                                          boolean elf64) throws IOException {
        int minimum = elf64 ? 64 : 40;
        if (entrySize < minimum || !data.contains(offset, (long) entrySize * count)) throw new IOException("Truncated ELF section table");
        List<Section> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            long entry = offset + (long) index * entrySize;
            if (elf64) {
                result.add(new Section(data.u32(entry), data.u32(entry + 4), data.u64(entry + 16),
                        data.u64(entry + 24), data.u64(entry + 32), data.u32(entry + 40), data.u64(entry + 56)));
            } else {
                result.add(new Section(data.u32(entry), data.u32(entry + 4), data.u32(entry + 12),
                        data.u32(entry + 16), data.u32(entry + 20), data.u32(entry + 24), data.u32(entry + 36)));
            }
        }
        return result;
    }

    private static void parseSymbols(BinaryData data, List<Section> sections, Section table, boolean elf64,
                                     List<NativeSymbol> symbols, List<String> warnings) throws IOException {
        if (table.link < 0 || table.link >= sections.size()) return;
        Section strings = sections.get((int) table.link);
        long entrySize = table.entrySize > 0 ? table.entrySize : elf64 ? 24 : 16;
        long count = Math.min(table.size / entrySize, MAX_SYMBOLS);
        if (!data.contains(table.offset, table.size)) {
            warnings.add("An ELF symbol table is truncated: " + table.name);
            return;
        }
        for (long index = 0; index < count; index++) {
            long entry = table.offset + index * entrySize;
            long nameOffset = data.u32(entry);
            int info = data.u8(entry + (elf64 ? 4 : 12));
            int sectionIndex = data.u16(entry + (elf64 ? 6 : 14));
            long address = elf64 ? data.u64(entry + 8) : data.u32(entry + 4);
            long size = elf64 ? data.u64(entry + 16) : data.u32(entry + 8);
            if (nameOffset == 0L || sectionIndex == 0) continue;
            String raw = string(data, strings, nameOffset);
            if (raw.isEmpty()) continue;
            SymbolKind fallback = switch (info & 0x0f) {
                case 2 -> SymbolKind.FUNCTION;
                case 1, 5, 6 -> SymbolKind.DATA;
                default -> SymbolKind.UNKNOWN;
            };
            String display = CppDemangler.demangle(raw);
            symbols.add(new NativeSymbol(address, size, raw, display,
                    CppDemangler.kind(raw, display, fallback), "", -1,
                    table.type == 11L ? "ELF dynamic" : "ELF symbol table"));
        }
    }

    private static void extractSourceStrings(BinaryData data, Section section, Set<String> sources) throws IOException {
        if (!data.contains(section.offset, section.size)) return;
        long cursor = section.offset;
        long end = section.offset + section.size;
        while (cursor < end) {
            String value = data.asciiZ(cursor, (int) Math.min(16_384L, end - cursor));
            cursor += value.getBytes(StandardCharsets.UTF_8).length + 1L;
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.matches(".*\\.(c|cc|cpp|cxx|h|hh|hpp|hxx|inl|ixx|m|mm|rs)")) sources.add(value);
        }
    }

    private static String string(BinaryData data, Section strings, long offset) throws IOException {
        if (offset < 0L || offset >= strings.size) return "";
        return data.asciiZ(strings.offset + offset, (int) Math.min(16_384L, strings.size - offset));
    }

    private static Section named(List<Section> sections, String name) {
        for (Section section : sections) if (name.equals(section.name)) return section;
        return null;
    }

    private static String architecture(int machine) {
        return switch (machine) {
            case 3 -> "x86";
            case 40 -> "ARM";
            case 62 -> "x86-64";
            case 183 -> "ARM64";
            case 243 -> "RISC-V";
            default -> "machine-" + machine;
        };
    }

    private static final class Section {
        final long nameOffset;
        final long type;
        final long address;
        final long offset;
        final long size;
        final long link;
        final long entrySize;
        String name = "";

        Section(long nameOffset, long type, long address, long offset, long size, long link, long entrySize) {
            this.nameOffset = nameOffset;
            this.type = type;
            this.address = address;
            this.offset = offset;
            this.size = size;
            this.link = link;
            this.entrySize = entrySize;
        }
    }
}

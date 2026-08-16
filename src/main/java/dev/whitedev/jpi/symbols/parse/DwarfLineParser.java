package dev.whitedev.jpi.symbols.parse;

import dev.whitedev.jpi.symbols.model.NativeSymbol;
import dev.whitedev.jpi.symbols.model.SymbolKind;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DwarfLineParser {
    private static final int MAX_ROWS = 1_000_000;

    Result parse(BinaryData data, long offset, long size, int addressSize) throws IOException {
        List<NativeSymbol> symbols = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        long sectionEnd = offset + size;
        long unit = offset;
        while (unit + 10 < sectionEnd && symbols.size() < MAX_ROWS) {
            long length32 = data.u32(unit);
            boolean dwarf64 = length32 == 0xffff_ffffL;
            long length = dwarf64 ? data.u64(unit + 4) : length32;
            long content = unit + (dwarf64 ? 12 : 4);
            long unitEnd = content + length;
            if (length == 0L) {
                unit = content;
                continue;
            }
            if (unitEnd > sectionEnd || unitEnd <= content) {
                warnings.add("A DWARF line unit is truncated");
                break;
            }
            int version = data.u16(content);
            if (version < 2 || version > 4) {
                warnings.add("DWARF line version " + version + " is detected but only versions 2-4 are decoded");
                unit = unitEnd;
                continue;
            }
            long headerLength = dwarf64 ? data.u64(content + 2) : data.u32(content + 2);
            long cursor = content + (dwarf64 ? 10 : 6);
            long headerEnd = cursor + headerLength;
            if (headerEnd > unitEnd) {
                warnings.add("A DWARF line header is truncated");
                unit = unitEnd;
                continue;
            }
            int minimumInstruction = data.u8(cursor++);
            if (version >= 4) cursor++;
            boolean defaultStatement = data.u8(cursor++) != 0;
            int lineBase = (byte) data.u8(cursor++);
            int lineRange = data.u8(cursor++);
            int opcodeBase = data.u8(cursor++);
            int[] operandCounts = new int[Math.max(0, opcodeBase - 1)];
            for (int index = 0; index < operandCounts.length; index++) operandCounts[index] = data.u8(cursor++);
            List<String> directories = new ArrayList<>();
            directories.add("");
            while (cursor < headerEnd) {
                String directory = data.asciiZ(cursor, (int) Math.min(16_384L, headerEnd - cursor));
                cursor += directory.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
                if (directory.isEmpty()) break;
                directories.add(directory);
            }
            List<FileEntry> files = new ArrayList<>();
            files.add(new FileEntry("", 0));
            while (cursor < headerEnd) {
                String name = data.asciiZ(cursor, (int) Math.min(16_384L, headerEnd - cursor));
                cursor += name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
                if (name.isEmpty()) break;
                long[] position = {cursor};
                int directory = (int) unsignedLeb(data, position, headerEnd);
                unsignedLeb(data, position, headerEnd);
                unsignedLeb(data, position, headerEnd);
                cursor = position[0];
                files.add(new FileEntry(name, directory));
                sources.add(resolve(name, directory, directories));
            }
            cursor = headerEnd;
            State state = new State(defaultStatement);
            while (cursor < unitEnd && symbols.size() < MAX_ROWS) {
                int opcode = data.u8(cursor++);
                if (opcode == 0) {
                    long[] position = {cursor};
                    long extendedLength = unsignedLeb(data, position, unitEnd);
                    long extendedEnd = position[0] + extendedLength;
                    if (extendedLength < 1L || extendedEnd > unitEnd) break;
                    int extended = data.u8(position[0]++);
                    if (extended == 1) {
                        state = new State(defaultStatement);
                    } else if (extended == 2) {
                        int bytes = (int) Math.min(addressSize, extendedEnd - position[0]);
                        state.address = address(data, position[0], bytes);
                    } else if (extended == 3) {
                        String name = data.asciiZ(position[0], (int) Math.min(16_384L, extendedEnd - position[0]));
                        position[0] += name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
                        int directory = (int) unsignedLeb(data, position, extendedEnd);
                        unsignedLeb(data, position, extendedEnd);
                        unsignedLeb(data, position, extendedEnd);
                        files.add(new FileEntry(name, directory));
                        sources.add(resolve(name, directory, directories));
                    }
                    cursor = extendedEnd;
                    continue;
                }
                if (opcode < opcodeBase) {
                    long[] position = {cursor};
                    switch (opcode) {
                        case 1 -> emit(state, files, directories, symbols, sources);
                        case 2 -> state.address += unsignedLeb(data, position, unitEnd) * minimumInstruction;
                        case 3 -> state.line += signedLeb(data, position, unitEnd);
                        case 4 -> state.file = (int) unsignedLeb(data, position, unitEnd);
                        case 5 -> unsignedLeb(data, position, unitEnd);
                        case 6 -> state.statement = !state.statement;
                        case 8 -> state.address += (long) ((255 - opcodeBase) / Math.max(1, lineRange)) * minimumInstruction;
                        case 9 -> {
                            state.address += data.u16(position[0]);
                            position[0] += 2;
                        }
                        default -> {
                            int count = opcode > 0 && opcode - 1 < operandCounts.length ? operandCounts[opcode - 1] : 0;
                            for (int index = 0; index < count; index++) unsignedLeb(data, position, unitEnd);
                        }
                    }
                    cursor = position[0];
                } else {
                    int adjusted = opcode - opcodeBase;
                    state.address += (long) (adjusted / Math.max(1, lineRange)) * minimumInstruction;
                    state.line += lineBase + adjusted % Math.max(1, lineRange);
                    emit(state, files, directories, symbols, sources);
                }
            }
            unit = unitEnd;
        }
        if (symbols.size() >= MAX_ROWS) warnings.add("DWARF line rows were capped at " + MAX_ROWS);
        return new Result(symbols, sources, warnings);
    }

    private static void emit(State state, List<FileEntry> files, List<String> directories,
                             List<NativeSymbol> symbols, Set<String> sources) {
        if (state.file < 1 || state.file >= files.size() || state.line < 1) return;
        FileEntry entry = files.get(state.file);
        String file = resolve(entry.name, entry.directory, directories);
        sources.add(file);
        String name = file + ':' + state.line;
        symbols.add(new NativeSymbol(state.address, 0L, name, name, SymbolKind.LINE,
                file, (int) Math.min(Integer.MAX_VALUE, state.line), "DWARF line"));
    }

    private static String resolve(String file, int directory, List<String> directories) {
        if (directory < 1 || directory >= directories.size() || directories.get(directory).isEmpty()) return file;
        String separator = directories.get(directory).endsWith("/") || directories.get(directory).endsWith("\\") ? "" : "/";
        return directories.get(directory) + separator + file;
    }

    private static long address(BinaryData data, long offset, int size) throws IOException {
        return switch (size) {
            case 1 -> data.u8(offset);
            case 2 -> data.u16(offset);
            case 4 -> data.u32(offset);
            case 8 -> data.u64(offset);
            default -> 0L;
        };
    }

    private static long unsignedLeb(BinaryData data, long[] offset, long end) throws IOException {
        long result = 0L;
        int shift = 0;
        while (offset[0] < end && shift < 64) {
            int value = data.u8(offset[0]++);
            result |= (long) (value & 0x7f) << shift;
            if ((value & 0x80) == 0) return result;
            shift += 7;
        }
        return result;
    }

    private static long signedLeb(BinaryData data, long[] offset, long end) throws IOException {
        long result = 0L;
        int shift = 0;
        int value = 0;
        while (offset[0] < end && shift < 64) {
            value = data.u8(offset[0]++);
            result |= (long) (value & 0x7f) << shift;
            shift += 7;
            if ((value & 0x80) == 0) break;
        }
        if (shift < 64 && (value & 0x40) != 0) result |= -1L << shift;
        return result;
    }

    record Result(List<NativeSymbol> symbols, Set<String> sourceFiles, List<String> warnings) {}

    private record FileEntry(String name, int directory) {}

    private static final class State {
        long address;
        long line = 1L;
        int file = 1;
        boolean statement;

        State(boolean statement) {
            this.statement = statement;
        }
    }
}

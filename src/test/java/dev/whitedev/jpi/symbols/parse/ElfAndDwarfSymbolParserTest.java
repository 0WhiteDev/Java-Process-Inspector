package dev.whitedev.jpi.symbols.parse;

import dev.whitedev.jpi.symbols.model.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElfAndDwarfSymbolParserTest {
    @TempDir Path directory;

    @Test void readsElfSymbolsAndDebugSourceStrings() throws Exception {
        byte[] bytes = new byte[0x500];
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        bytes[0] = 0x7f;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;
        bytes[5] = 1;
        data.putShort(16, (short) 2);
        data.putShort(18, (short) 62);
        data.putLong(40, 0x300);
        data.putShort(52, (short) 64);
        data.putShort(58, (short) 64);
        data.putShort(60, (short) 5);
        data.putShort(62, (short) 1);
        String sectionNames = "\0.shstrtab\0.strtab\0.symtab\0.debug_str\0";
        put(bytes, 0x100, sectionNames);
        put(bytes, 0x140, "\0_Z3foov\0");
        put(bytes, 0x1c0, "\0/src/demo.cpp\0");
        data.putInt(0x180 + 24, 1);
        bytes[0x180 + 28] = 2;
        data.putShort(0x180 + 30, (short) 1);
        data.putLong(0x180 + 32, 0x401000);
        data.putLong(0x180 + 40, 12);
        section(data, 1, 1, 3, 0, 0x100, sectionNames.length(), 0, 0);
        section(data, 2, 11, 3, 0, 0x140, 10, 0, 0);
        section(data, 3, 19, 2, 0, 0x180, 48, 2, 24);
        section(data, 4, 27, 1, 0, 0x1c0, 16, 0, 0);
        Path elf = directory.resolve("demo.so");
        Files.write(elf, bytes);

        ParsedSymbols parsed = new ElfSymbolParser().parse(elf);

        assertEquals("x86-64", parsed.architecture());
        assertTrue(parsed.symbols().stream().anyMatch(value -> "foo()".equals(value.displayName())
                && value.address() == 0x401000));
        assertTrue(parsed.sourceFiles().contains("/src/demo.cpp"));
    }

    @Test void decodesDwarfFourLineProgram() throws Exception {
        byte[] bytes = dwarfLine();
        Path file = directory.resolve("debug-line.bin");
        Files.write(file, bytes);
        BinaryData data = BinaryData.read(file, ByteOrder.LITTLE_ENDIAN);

        DwarfLineParser.Result result = new DwarfLineParser().parse(data, 0, bytes.length, 8);

        assertTrue(result.symbols().stream().anyMatch(value -> value.kind() == SymbolKind.LINE
                && value.address() == 0x401004L && value.sourceLine() == 10));
        assertTrue(result.sourceFiles().contains("/src/demo.cpp"));
    }

    private static byte[] dwarfLine() {
        ByteBuffer header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 1).put((byte) 1).put((byte) 1).put((byte) -5).put((byte) 14).put((byte) 13);
        header.put(new byte[]{0, 1, 1, 1, 1, 0, 0, 0, 1, 0, 0, 1});
        header.put("/src\0\0demo.cpp\0".getBytes(StandardCharsets.ISO_8859_1));
        header.put((byte) 1).put((byte) 0).put((byte) 0).put((byte) 0);
        int headerLength = header.position();
        ByteBuffer program = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        program.put((byte) 0).put((byte) 9).put((byte) 2).putLong(0x401000L);
        program.put((byte) 1);
        program.put((byte) 3).put((byte) 9);
        program.put((byte) 2).put((byte) 4);
        program.put((byte) 1);
        program.put((byte) 0).put((byte) 1).put((byte) 1);
        int programLength = program.position();
        ByteBuffer unit = ByteBuffer.allocate(4 + 2 + 4 + headerLength + programLength).order(ByteOrder.LITTLE_ENDIAN);
        unit.putInt(2 + 4 + headerLength + programLength);
        unit.putShort((short) 4);
        unit.putInt(headerLength);
        unit.put(header.array(), 0, headerLength);
        unit.put(program.array(), 0, programLength);
        return unit.array();
    }

    private static void section(ByteBuffer data, int index, int name, int type, long address,
                                long offset, long size, int link, long entrySize) {
        int base = 0x300 + index * 64;
        data.putInt(base, name);
        data.putInt(base + 4, type);
        data.putLong(base + 16, address);
        data.putLong(base + 24, offset);
        data.putLong(base + 32, size);
        data.putInt(base + 40, link);
        data.putLong(base + 56, entrySize);
    }

    private static void put(byte[] target, int offset, String value) {
        byte[] source = value.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(source, 0, target, offset, source.length);
    }
}

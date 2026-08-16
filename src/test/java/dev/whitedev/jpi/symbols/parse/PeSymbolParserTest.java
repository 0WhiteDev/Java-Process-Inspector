package dev.whitedev.jpi.symbols.parse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeSymbolParserTest {
    @TempDir Path directory;

    @Test void readsExportsAndCodeViewPdbReference() throws Exception {
        byte[] bytes = new byte[0x800];
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        bytes[0] = 'M';
        bytes[1] = 'Z';
        data.putInt(0x3c, 0x80);
        put(bytes, 0x80, "PE\0\0");
        data.putShort(0x84, (short) 0x8664);
        data.putShort(0x86, (short) 1);
        data.putShort(0x94, (short) 0xf0);
        data.putShort(0x98, (short) 0x20b);
        data.putLong(0xb0, 0x140000000L);
        data.putInt(0x108, 0x1000);
        data.putInt(0x10c, 0x100);
        data.putInt(0x138, 0x1090);
        data.putInt(0x13c, 28);
        put(bytes, 0x188, ".rdata\0\0");
        data.putInt(0x190, 0x400);
        data.putInt(0x194, 0x1000);
        data.putInt(0x198, 0x400);
        data.putInt(0x19c, 0x200);
        data.putInt(0x214, 1);
        data.putInt(0x218, 1);
        data.putInt(0x21c, 0x1050);
        data.putInt(0x220, 0x1060);
        data.putInt(0x224, 0x1070);
        data.putInt(0x250, 0x1200);
        data.putInt(0x260, 0x1080);
        data.putShort(0x270, (short) 0);
        put(bytes, 0x280, "?run@Demo@@YAXH@Z\0");
        data.putInt(0x29c, 2);
        data.putInt(0x2a0, 40);
        data.putInt(0x2a8, 0x300);
        put(bytes, 0x300, "RSDS");
        for (int index = 0; index < 16; index++) bytes[0x304 + index] = (byte) (index + 1);
        data.putInt(0x314, 3);
        put(bytes, 0x318, "C:\\symbols\\demo.pdb\0");
        Path image = directory.resolve("demo.dll");
        Files.write(image, bytes);

        ParsedSymbols parsed = new PeSymbolParser().parse(image);

        assertEquals("x86-64", parsed.architecture());
        assertTrue(parsed.symbols().stream().anyMatch(value -> "Demo::run".equals(value.displayName())
                && value.address() == 0x140001200L));
        assertEquals(1, parsed.artifacts().size());
        assertFalse(parsed.artifacts().get(0).available());
        assertTrue(parsed.artifacts().get(0).identifier().endsWith("-3"));
    }

    private static void put(byte[] target, int offset, String value) {
        byte[] source = value.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(source, 0, target, offset, source.length);
    }
}

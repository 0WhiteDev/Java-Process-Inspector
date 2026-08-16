package dev.whitedev.jpi.symbols.parse;

import dev.whitedev.jpi.symbols.model.SymbolKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapAndPdbSymbolParserTest {
    @TempDir Path directory;

    @Test void readsMsvcSymbolsAndSourceLinesFromMap() throws Exception {
        Path map = directory.resolve("sample.map");
        Files.writeString(map, " 0001:00000010 ?run@Demo@@YAXH@Z 0000000140001010 f demo.obj\n"
                + "Line numbers for demo.obj(C:\\src\\demo.cpp) segment .text\n"
                + "  42 0001:00000010  43 0001:00000020\n", StandardCharsets.ISO_8859_1);

        ParsedSymbols parsed = new MapSymbolParser().parse(map);

        assertTrue(parsed.symbols().stream().anyMatch(value -> "Demo::run".equals(value.displayName())));
        assertTrue(parsed.symbols().stream().anyMatch(value -> value.kind() == SymbolKind.LINE
                && value.sourceLine() == 42));
        assertTrue(parsed.sourceFiles().contains("C:\\src\\demo.cpp"));
    }

    @Test void extractsDecoratedNamesAndSourcesFromPdbStrings() throws Exception {
        Path pdb = directory.resolve("sample.pdb");
        byte[] header = "Microsoft C/C++ MSF 7.00\r\n\u001aDS\0\0\0?run@Demo@@YAXH@Z\0C:\\src\\demo.cpp\0"
                .getBytes(StandardCharsets.ISO_8859_1);
        Files.write(pdb, header);

        ParsedSymbols parsed = new PdbSymbolParser().parse(pdb);

        assertEquals("PDB 7.0", parsed.format());
        assertTrue(parsed.symbols().stream().anyMatch(value -> "Demo::run".equals(value.displayName())));
        assertTrue(parsed.sourceFiles().contains("C:\\src\\demo.cpp"));
    }
}

package dev.whitedev.jpi.analysis.patch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LineDiffTest {
    @Test
    void rendersChangesWithContextAndPreservesBlankLines() {
        String diff = LineDiff.unified("before\n\nreturn false;\n", "before\n\nreturn true;\n", "Current", "Modified", true);
        assertTrue(diff.startsWith("--- Current\n+++ Modified\n"));
        assertTrue(diff.contains("-return false;\n+return true;"));
        assertEquals("No differences.\n", LineDiff.unified("a\r\nb", "a\nb", "A", "B", true));
        assertTrue(LineDiff.unified("", "added", "A", "B", true).contains("@@ -0,0 +1,1 @@"));
    }

    @Test
    void reconstructsBothInputsWithRepeatedLinesAndLargeInsertions() {
        Random random = new Random(13);
        for (int run = 0; run < 200; run++) {
            String a = randomText(random);
            String b = randomText(random);
            List<LineDiff.Line> diff = LineDiff.compare(a, b);
            assertEquals(a, String.join("\n", diff.stream().filter(line -> line.kind() != '+').map(LineDiff.Line::text).toList()));
            assertEquals(b, String.join("\n", diff.stream().filter(line -> line.kind() != '-').map(LineDiff.Line::text).toList()));
        }
    }

    @Test
    void boundsLargeDiffInputsAndHidesDistantCommonLines() {
        String a = "start\n" + "common\n".repeat(5000) + "old\n" + "tail\n".repeat(5000);
        String b = a.replace("old\n", "new\n");
        String compact = LineDiff.unified(a, b, "A", "B", true);
        assertTrue(compact.length() < 300);
        assertTrue(LineDiff.unified(a, b, "A", "B", false).length() > 50_000);
        assertThrows(IllegalArgumentException.class, () -> LineDiff.compare("a".repeat(2_000_001), ""));
    }

    private static String randomText(Random random) {
        return String.join("\n", java.util.stream.IntStream.range(0, 200).mapToObj(index -> "line" + random.nextInt(15)).toList());
    }
}

package dev.whitedev.jpi.agent.patch;

import dev.whitedev.jpi.analysis.patch.PatchEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class PatchHistoryTest {
    @Test
    void isolatesClassLoadersAndCopiesSnapshots() throws Exception {
        PatchHistory history = new PatchHistory();
        byte[] before = {1, 2};
        byte[] after = {3, 4};
        history.record("Sample@1", "Sample", "Java\tsource\nchange", before, after);
        before[0] = 99;
        after[0] = 99;
        assertArrayEquals(new byte[]{1, 2}, history.version("Sample@1", 1, true));
        byte[] read = history.version("Sample@1", 1, false);
        read[0] = 99;
        assertArrayEquals(new byte[]{3, 4}, history.version("Sample@1", 1, false));
        assertThrows(IOException.class, () -> history.version("Sample@2", 1, true));
        assertEquals("", history.list("Sample@2"));
        PatchEntry entry = PatchEntry.parse(history.list("")).getFirst();
        assertEquals("Java\tsource\nchange", entry.description());
        assertEquals(PatchHistory.sha256(new byte[]{3, 4}), entry.afterHash());
    }

    @Test
    void evictsOldSnapshotsByEntryCountAndByteBudget() throws Exception {
        PatchHistory history = new PatchHistory(2, 6);
        history.record("A", "A", "one", new byte[2], new byte[2]);
        history.record("A", "A", "two", new byte[2], new byte[2]);
        assertThrows(IOException.class, () -> history.version("A", 1, true));
        assertEquals(2, PatchEntry.parse(history.list("")).getFirst().id());
        history.record("A", "A", "too large", new byte[7], new byte[7]);
        assertEquals(1, PatchEntry.parse(history.list("")).size());
        PatchHistory count = new PatchHistory(1, 100);
        count.record("A", "A", "one", new byte[1], new byte[1]);
        count.record("B", "B", "two", new byte[1], new byte[1]);
        assertThrows(IOException.class, () -> count.version("A", 1, true));
        assertArrayEquals(new byte[1], count.version("B", 2, false));
    }
}

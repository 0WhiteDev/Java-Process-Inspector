package dev.whitedev.jpi.debug.watch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WatchExpressionStoreTest {
    @TempDir Path directory;

    @Test void persistsOnlyExpressionsAndDeduplicates() throws Exception {
        WatchExpressionStore store = new WatchExpressionStore(directory.resolve("config/watches.txt"));
        assertEquals(List.of(), store.load());
        store.save(List.of("this.player.health", " session.token ", "session.token"));
        assertEquals(List.of("this.player.health", "session.token"), new WatchExpressionStore(store.path()).load());
        store.save(List.of("config.debug"));
        assertEquals(List.of("config.debug"), store.load());
    }

    @Test void rejectsInvalidAndOversizedFiles() throws Exception {
        WatchExpressionStore store = new WatchExpressionStore(directory.resolve("watches.txt"));
        assertThrows(java.io.IOException.class, () -> store.save(List.of("")));
        assertThrows(java.io.IOException.class, () -> store.save(List.of("x".repeat(2049))));
        Files.writeString(store.path(), "not-base64!");
        assertThrows(java.io.IOException.class, store::load);
    }
}

package dev.whitedev.jpi.agent;

import dev.whitedev.jpi.agent.patch.PatchHistory;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class PatchVersionTest {
    @Test
    void readsHistoricalVersionsWithoutResolvingAnUnloadedClass() throws Exception {
        TargetInspector inspector = new TargetInspector(null, new ClassRegistry());
        var field = TargetInspector.class.getDeclaredField("patchHistory");
        field.setAccessible(true);
        PatchHistory history = (PatchHistory) field.get(inspector);
        history.record("Unloaded@1", "Unloaded", "method", new byte[]{1}, new byte[]{2});
        assertArrayEquals(new byte[]{1}, inspector.classVersion("Unloaded@1\nbefore:1"));
        assertArrayEquals(new byte[]{2}, inspector.classVersion("Unloaded@1\nafter:1"));
        assertThrows(IOException.class, () -> inspector.classVersion("Unloaded@2\nbefore:1"));
    }
}

package dev.whitedev.jpi.attach.jvmti;

import dev.whitedev.jpi.agent.jvmti.NativeJvmtiBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class NativeAgentArtifactTest {
    @TempDir Path directory;

    @Test void verifiesExactBytesAndRejectsChangedOrEmptyLibraries() throws Exception {
        Path file = directory.resolve("backend.bin");
        byte[] bytes = {1, 2, 3};
        Files.write(file, bytes);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var artifact = NativeAgentArtifact.verify(file, sha);
        assertEquals(file.toRealPath(), artifact.library());
        assertEquals(file.toRealPath() + "\n" + sha, artifact.bindingOptions());
        Files.write(file, new byte[]{3, 2, 1});
        assertThrows(IOException.class, () -> NativeAgentArtifact.verify(file, sha));
        Files.write(file, new byte[0]);
        assertThrows(IOException.class, () -> NativeAgentArtifact.verify(file, sha));
    }

    @Test void rejectsRelativePathsMalformedHashesAndUnboundQueries() throws Exception {
        assertThrows(IOException.class, () -> NativeJvmtiBridge.verifyLibrary("relative.dll", "0".repeat(64)));
        assertThrows(IOException.class, () -> NativeJvmtiBridge.verifyLibrary(directory.resolve("backend.dll").toString(), "wrong"));
        assertTrue(NativeJvmtiBridge.status().contains("state=not-bound"));
        assertThrows(IOException.class, NativeJvmtiBridge::loadedClasses);
        assertThrows(IOException.class, () -> NativeJvmtiBridge.classBytes(String.class));
        assertThrows(IOException.class, () -> NativeJvmtiBridge.redefineClass(String.class, new byte[]{1}));
        assertThrows(IOException.class, () -> NativeJvmtiBridge.heapStats(String.class, 1));
        assertThrows(IOException.class, () -> NativeJvmtiBridge.objectSize("value"));
    }

    @Test void resolvesBundledAgentForTheCurrentPlatform() throws Exception {
        String agent = NativeAgentArtifact.resourcePath();
        assertTrue(agent.startsWith("/native/"));
        assertTrue(agent.endsWith(System.mapLibraryName("jpi_jvmti")));
    }
}

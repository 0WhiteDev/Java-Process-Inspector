package dev.whitedev.jpi.attach.jvmti;

import dev.whitedev.jpi.agent.jvmti.NativeJvmtiBridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;

public record NativeAgentArtifact(Path library, String sha256) {
    public NativeAgentArtifact {
        library = library.toAbsolutePath().normalize();
    }

    public static NativeAgentArtifact verify(Path library, String sha256) throws IOException {
        return new NativeAgentArtifact(NativeJvmtiBridge.verifyLibrary(library.toString(), sha256).toPath(), sha256.toLowerCase(Locale.ROOT));
    }

    public static String resourcePath() throws IOException {
        return resourcePath("jpi_jvmti");
    }

    static String resourcePath(String libraryName) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform = os.startsWith("windows") ? "windows" : os.equals("linux") ? "linux" : "";
        String cpu = arch.equals("amd64") || arch.equals("x86_64") ? "x86_64" : arch.equals("aarch64") || arch.equals("arm64") ? "aarch64" : "";
        if (platform.isEmpty() || cpu.isEmpty()) throw new IOException("No bundled JVMTI library for this OS and architecture");
        return "/native/" + platform + "-" + cpu + "/" + System.mapLibraryName(libraryName);
    }

    public static NativeAgentArtifact extractBundled() throws IOException {
        return extractBundled("jpi_jvmti");
    }

    public static NativeAgentArtifact extractBundled(String libraryName) throws IOException {
        if (!libraryName.matches("[a-z_]+")) throw new IOException("Invalid native library name");
        String resource = resourcePath(libraryName);
        byte[] bytes;
        String expected;
        try (InputStream input = NativeAgentArtifact.class.getResourceAsStream(resource);
             InputStream checksum = NativeAgentArtifact.class.getResourceAsStream(resource + ".sha256")) {
            if (input == null || checksum == null) throw new IOException("This JAR has no bundled native backend. Build src/native/CMakeLists.txt before Maven packaging, or select an external library.");
            bytes = input.readNBytes(64 * 1024 * 1024 + 1);
            if (bytes.length > 64 * 1024 * 1024) throw new IOException("Bundled native library exceeds 64 MiB");
            expected = new String(checksum.readNBytes(256), StandardCharsets.US_ASCII).trim();
        }
        Path directory = Files.createTempDirectory("jpi-jvmti-");
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        Path file = directory.resolve(System.mapLibraryName(libraryName));
        try {
            Files.write(file, bytes);
            NativeAgentArtifact artifact = verify(file, expected);
            directory.toFile().deleteOnExit();
            file.toFile().deleteOnExit();
            return artifact;
        } catch (IOException error) {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
            throw error;
        }
    }

    public String bindingOptions() { return library + "\n" + sha256; }

    public String startupArgument() { return "-agentpath:" + library; }
}

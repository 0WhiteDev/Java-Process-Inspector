package dev.whitedev.jpi.agent.file;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class FilePathResolver {
    private FilePathResolver() {}

    public static String normalized(Object value) {
        try {
            return path(value).toAbsolutePath().normalize().toString();
        } catch (Throwable error) {
            return String.valueOf(value);
        }
    }

    public static Path redirected(Object value, String root) throws IOException {
        Path sandbox = Paths.get(root).toAbsolutePath().normalize();
        String source = normalized(value).replace(':', '_').replace('\\', '/');
        while (source.startsWith("/")) source = source.substring(1);
        Path destination = sandbox.resolve(source).normalize();
        if (!destination.startsWith(sandbox)) throw new IOException("Redirect path escapes the sandbox root");
        return destination;
    }

    private static Path path(Object value) {
        if (value instanceof Path) return (Path) value;
        if (value instanceof File) return ((File) value).toPath();
        return Paths.get(String.valueOf(value));
    }
}

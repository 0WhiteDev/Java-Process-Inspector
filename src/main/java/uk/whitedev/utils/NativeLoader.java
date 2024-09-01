package uk.whitedev.utils;

import uk.whitedev.injector.DLLInjector;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public class NativeLoader {
    public static void loadLibraryFromJar(String path, String nativeLib) throws IOException {
        try (InputStream is = DLLInjector.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Native library not found in JAR: " + path);
            }
            File tempFile = File.createTempFile(nativeLib, ".dll");
            tempFile.deleteOnExit();
            Files.copy(is, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.load(tempFile.getAbsolutePath());
        }
    }
}

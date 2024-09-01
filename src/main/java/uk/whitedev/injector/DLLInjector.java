package uk.whitedev.injector;

import uk.whitedev.utils.NativeLoader;

import java.io.IOException;

public class DLLInjector {
    static {
        try {
            NativeLoader.loadLibraryFromJar("/natives/DLLInjector.dll", "DLLInjector");
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load native library", e);
        }
    }

    public native boolean injectDLL(long pid, String dllPath);
}

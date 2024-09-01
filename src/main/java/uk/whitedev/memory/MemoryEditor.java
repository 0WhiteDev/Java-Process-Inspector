package uk.whitedev.memory;

import uk.whitedev.utils.NativeLoader;

import java.io.IOException;
import java.util.*;

public class MemoryEditor {
    static {
        try {
            NativeLoader.loadLibraryFromJar("/natives/MemoryEditor.dll", "MemoryEditor");
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load native library", e);
        }
    }
    public native Map<Integer, String> listProcesses();
    public native WindowInfo[] getOpenWindows();
    public native MemoryLocation[] scanMemory(long pid, String searchStr, int dataType);
    public native void modifyMemory(long pid, MemoryLocation[] locations, String newValue, int type);
}

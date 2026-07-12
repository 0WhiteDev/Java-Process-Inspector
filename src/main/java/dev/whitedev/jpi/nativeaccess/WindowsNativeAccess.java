package dev.whitedev.jpi.nativeaccess;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Tlhelp32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.DWORDByReference;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class WindowsNativeAccess {
    private static final Kernel32 KERNEL = Kernel32.INSTANCE;
    private static final int MAX_RESULTS = 50_000;
    private static final int CHUNK_SIZE = 1024 * 1024;

    public boolean isSupported() { return Platform.isWindows(); }

    public List<ProcessInfo> processes() throws IOException {
        requireWindows();
        WinNT.HANDLE snapshot = KERNEL.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, new DWORD(0));
        if (WinBase.INVALID_HANDLE_VALUE.equals(snapshot)) throw error("Could not create process snapshot");
        List<ProcessInfo> result = new ArrayList<>();
        try {
            Tlhelp32.PROCESSENTRY32 entry = new Tlhelp32.PROCESSENTRY32();
            if (KERNEL.Process32First(snapshot, entry)) {
                do {
                    result.add(new ProcessInfo(entry.th32ProcessID.intValue(), trim(entry.szExeFile)));
                } while (KERNEL.Process32Next(snapshot, entry));
            }
        } finally { KERNEL.CloseHandle(snapshot); }
        result.sort(Comparator.comparingInt(ProcessInfo::pid));
        return result;
    }

    public List<ProcessInfo> visibleWindows() {
        requireWindows();
        final List<ProcessInfo> result = new ArrayList<>();
        User32.INSTANCE.EnumWindows((window, data) -> {
            if (!User32.INSTANCE.IsWindowVisible(window)) return true;
            int length = User32.INSTANCE.GetWindowTextLength(window);
            if (length <= 0) return true;
            char[] title = new char[Math.min(length + 1, 4096)];
            User32.INSTANCE.GetWindowText(window, title, title.length);
            IntByReference processId = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(window, processId);
            result.add(new ProcessInfo(processId.getValue(), trim(title)));
            return true;
        }, null);
        result.sort(Comparator.comparingInt(ProcessInfo::pid));
        return result;
    }

    public void injectDll(int pid, File library) throws IOException {
        requireWindows();
        File dll = library.getCanonicalFile();
        if (!dll.isFile() || !dll.getName().toLowerCase(Locale.ROOT).endsWith(".dll")) throw new IOException("Select an existing DLL file");
        int rights = WinNT.PROCESS_CREATE_THREAD | WinNT.PROCESS_QUERY_INFORMATION | WinNT.PROCESS_VM_OPERATION
                | WinNT.PROCESS_VM_WRITE | WinNT.PROCESS_VM_READ;
        WinNT.HANDLE process = KERNEL.OpenProcess(rights, false, pid);
        if (process == null) throw error("Could not open process " + pid);
        IntByReference currentWow64 = new IntByReference();
        IntByReference targetWow64 = new IntByReference();
        if (KERNEL.IsWow64Process(KERNEL.GetCurrentProcess(), currentWow64)
                && KERNEL.IsWow64Process(process, targetWow64)
                && currentWow64.getValue() != targetWow64.getValue()) {
            KERNEL.CloseHandle(process);
            throw new IOException("JPI and the target process must use the same architecture for DLL injection");
        }
        byte[] path = (dll.getAbsolutePath() + "\0").getBytes(StandardCharsets.UTF_16LE);
        Pointer remote = null;
        try {
            remote = KERNEL.VirtualAllocEx(process, null, new SIZE_T(path.length), WinNT.MEM_COMMIT | WinNT.MEM_RESERVE, WinNT.PAGE_READWRITE);
            if (remote == null) throw error("Could not allocate memory in target process");
            Memory local = new Memory(path.length); local.write(0, path, 0, path.length);
            if (!KERNEL.WriteProcessMemory(process, remote, local, path.length, new IntByReference())) throw error("Could not write DLL path");
            Pointer loadLibrary = NativeLibrary.getInstance("kernel32").getFunction("LoadLibraryW");
            WinNT.HANDLE thread = KERNEL.CreateRemoteThread(process, null, 0, loadLibrary, remote, 0, new DWORDByReference());
            if (thread == null) throw error("Could not start LoadLibraryW in target process");
            try {
                int wait = KERNEL.WaitForSingleObject(thread, 15_000);
                if (wait != WinBase.WAIT_OBJECT_0) throw new IOException("DLL injection timed out");
                IntByReference exitCode = new IntByReference();
                if (!KERNEL.GetExitCodeThread(thread, exitCode) || exitCode.getValue() == 0) {
                    throw error("LoadLibraryW rejected the DLL");
                }
            } finally { KERNEL.CloseHandle(thread); }
        } finally {
            if (remote != null) KERNEL.VirtualFreeEx(process, remote, new SIZE_T(0), WinNT.MEM_RELEASE);
            KERNEL.CloseHandle(process);
        }
    }

    public List<MemoryMatch> scanMemory(int pid, String value, MemoryDataType type) throws IOException {
        requireWindows();
        byte[] needle;
        try { needle = type.encode(value); } catch (RuntimeException e) { throw new IOException("Invalid " + type + " value: " + value, e); }
        if (needle.length == 0) throw new IOException("Search value cannot be empty");
        WinNT.HANDLE process = KERNEL.OpenProcess(WinNT.PROCESS_QUERY_INFORMATION | WinNT.PROCESS_VM_READ, false, pid);
        if (process == null) throw error("Could not open process " + pid);
        List<MemoryMatch> matches = new ArrayList<>();
        try {
            long address = 0x10000L;
            long maximum = Native.POINTER_SIZE == 8 ? 0x00007FFFFFFEFFFFL : 0x7FFEFFFFL;
            WinNT.MEMORY_BASIC_INFORMATION info = new WinNT.MEMORY_BASIC_INFORMATION();
            while (address < maximum && matches.size() < MAX_RESULTS) {
                SIZE_T queried = KERNEL.VirtualQueryEx(process, Pointer.createConstant(address), info, new SIZE_T(info.size()));
                if (queried.longValue() == 0) break;
                long base = Pointer.nativeValue(info.baseAddress);
                long regionSize = info.regionSize.longValue();
                if (regionSize <= 0) break;
                if (info.state.intValue() == WinNT.MEM_COMMIT && readable(info.protect.intValue())) {
                    scanRegion(process, base, regionSize, needle, value, matches);
                }
                long next = base + regionSize;
                if (next <= address) break;
                address = next;
            }
        } finally { KERNEL.CloseHandle(process); }
        return matches;
    }

    public void writeMemory(int pid, List<MemoryMatch> matches, String value, MemoryDataType type) throws IOException {
        requireWindows();
        byte[] encoded;
        try { encoded = type.encode(value); } catch (RuntimeException e) { throw new IOException("Invalid " + type + " value: " + value, e); }
        WinNT.HANDLE process = KERNEL.OpenProcess(WinNT.PROCESS_VM_OPERATION | WinNT.PROCESS_VM_WRITE, false, pid);
        if (process == null) throw error("Could not open process " + pid);
        try {
            for (MemoryMatch match : matches) {
                if (type == MemoryDataType.STRING && encoded.length > match.byteSize()) throw new IOException("Replacement string is too long");
                int length = type == MemoryDataType.STRING ? match.byteSize() : encoded.length;
                Memory buffer = new Memory(length); buffer.clear(); buffer.write(0, encoded, 0, encoded.length);
                IntByReference written = new IntByReference();
                if (!KERNEL.WriteProcessMemory(process, Pointer.createConstant(match.address()), buffer, length, written)
                        || written.getValue() != length) throw error("Could not write address 0x" + Long.toHexString(match.address()));
            }
        } finally { KERNEL.CloseHandle(process); }
    }

    private static void scanRegion(WinNT.HANDLE process, long base, long size, byte[] needle,
                                   String value, List<MemoryMatch> matches) {
        long offset = 0;
        while (offset < size && matches.size() < MAX_RESULTS) {
            int requested = (int) Math.min(CHUNK_SIZE, size - offset);
            if (requested < needle.length) break;
            Memory buffer = new Memory(requested);
            IntByReference bytesRead = new IntByReference();
            KERNEL.ReadProcessMemory(process, Pointer.createConstant(base + offset), buffer, requested, bytesRead);
            int read = bytesRead.getValue();
            if (read >= needle.length) {
                byte[] data = buffer.getByteArray(0, read);
                for (int index = 0; index <= read - needle.length && matches.size() < MAX_RESULTS; index++) {
                    if (matches(data, index, needle)) matches.add(new MemoryMatch(base + offset + index, value, needle.length));
                }
            }
            long advance = read > 0 ? read : requested;
            if (read >= needle.length) advance -= needle.length - 1;
            offset += Math.max(1, advance);
        }
    }

    private static boolean matches(byte[] data, int offset, byte[] needle) {
        for (int index = 0; index < needle.length; index++) if (data[offset + index] != needle[index]) return false;
        return true;
    }

    private static boolean readable(int protection) {
        if ((protection & WinNT.PAGE_GUARD) != 0 || (protection & WinNT.PAGE_NOACCESS) != 0) return false;
        int page = protection & 0xff;
        return page == WinNT.PAGE_READONLY || page == WinNT.PAGE_READWRITE || page == WinNT.PAGE_WRITECOPY
                || page == WinNT.PAGE_EXECUTE_READ || page == WinNT.PAGE_EXECUTE_READWRITE || page == 0x80;
    }

    private static String trim(char[] value) { int length = 0; while (length < value.length && value[length] != 0) length++; return new String(value, 0, length); }
    private static void requireWindows() { if (!Platform.isWindows()) throw new UnsupportedOperationException("This operation is available on Windows only"); }
    private static IOException error(String message) { return new IOException(message + " (Windows error " + KERNEL.GetLastError() + ")"); }
}

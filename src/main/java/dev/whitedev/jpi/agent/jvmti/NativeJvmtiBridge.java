package dev.whitedev.jpi.agent.jvmti;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;

public final class NativeJvmtiBridge {
    private static String loadedPath;
    private static String loadedDigest;

    private NativeJvmtiBridge() {}

    public static synchronized String connect(String path, String expectedDigest) throws IOException {
        File library = verifyLibrary(path, expectedDigest);
        String canonical = library.getCanonicalPath();
        if (loadedPath != null && !loadedDigest.equalsIgnoreCase(expectedDigest)) {
            throw new IOException("A different native backend is already bound. Restart the target before changing native library contents.");
        }
        try {
            if (loadedPath == null) {
                System.load(canonical);
                loadedPath = canonical;
                loadedDigest = expectedDigest.toLowerCase(java.util.Locale.ROOT);
            }
            return status0();
        } catch (LinkageError error) {
            throw new IOException("Native backend binding failed. Load this library through -agentpath or loadAgentPath first, and match the target OS and architecture. " + error.getMessage(), error);
        }
    }

    public static synchronized String status() throws IOException {
        if (loadedPath == null) return "backend=Instrumentation\nstate=not-bound\n";
        return status0() + "library_sha256=" + loadedDigest + "\n";
    }

    public static synchronized Class<?>[] loadedClasses() throws IOException {
        requireBound();
        return loadedClasses0();
    }

    public static synchronized byte[] classBytes(Class<?> type) throws IOException {
        requireBound();
        return classBytes0(type);
    }

    public static synchronized boolean isBound() { return loadedPath != null; }

    public static synchronized boolean isModifiableClass(Class<?> type) throws IOException {
        requireBound();
        return isModifiableClass0(type);
    }

    public static synchronized void startCapture() throws IOException { requireBound(); startCapture0(); }
    public static synchronized void startCaptureArchive(String parent, long limit) throws IOException {
        requireBound();
        if (parent == null || parent.indexOf('\0') >= 0 || parent.indexOf('\r') >= 0 || parent.indexOf('\n') >= 0
                || !java.nio.file.Paths.get(parent).isAbsolute() || !java.nio.file.Files.isDirectory(java.nio.file.Paths.get(parent))
                || limit < 1 || limit > 16L * 1024 * 1024 * 1024)
            throw new IOException("An absolute target archive directory and a byte limit from 1 byte to 16 GiB are required");
        String directory = java.nio.file.Paths.get(parent).resolve("jpi-capture-" + java.util.UUID.randomUUID()).toString();
        try { startCaptureArchive0(directory.getBytes(java.nio.charset.StandardCharsets.UTF_8), limit); }
        catch (LinkageError error) { throw new IOException("This native backend does not support class archives. Rebuild the native library and use a matching JPI JAR", error); }
    }
    public static synchronized void stopCapture() throws IOException { requireBound(); stopCapture0(false); }
    public static synchronized String captureEvents() throws IOException { requireBound(); return captureEvents0(); }
    public static synchronized byte[] captureBytes(long sequence) throws IOException {
        requireBound();
        if (sequence < 1) throw new IOException("A positive capture sequence is required");
        return captureBytes0(sequence);
    }

    public static synchronized void clearCaptureForSession() {
        if (loadedPath == null) return;
        try { stopCapture0(true); }
        catch (IOException | LinkageError error) {
            System.err.println("[JPI agent] Native capture cleanup failed: " + error.getMessage());
        }
    }

    public static synchronized String memoryRegions() throws IOException {
        requireBound();
        return memoryRegions0();
    }

    public static synchronized byte[] readMemory(long address, int length) throws IOException {
        requireBound();
        return readMemory0(address, length);
    }

    public static synchronized void patchMemory(long address, byte[] expected, byte[] replacement) throws IOException {
        requireBound();
        patchMemory0(address, expected, replacement);
    }

    public static synchronized void redefineClass(Class<?> type, byte[] bytecode) throws IOException {
        requireBound();
        redefineClass0(type, bytecode);
    }

    public static synchronized long[] heapStats(Class<?> type, long limit) throws IOException {
        requireBound();
        return heapStats0(type, limit);
    }

    public static synchronized long objectSize(Object value) throws IOException {
        requireBound();
        return objectSize0(value);
    }

    public static File verifyLibrary(String path, String expectedDigest) throws IOException {
        if (path == null || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0 || !new File(path).isAbsolute()) {
            throw new IOException("Native library path must be absolute and contain no line breaks");
        }
        if (expectedDigest == null || !expectedDigest.matches("[a-fA-F0-9]{64}")) throw new IOException("A SHA-256 digest is required");
        File file = new File(path);
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("Native library symbolic links are not accepted");
        file = file.getCanonicalFile();
        if (!file.isFile() || file.length() == 0 || file.length() > 64L * 1024 * 1024) throw new IOException("Native library must be a non-empty file no larger than 64 MiB");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long read = 0;
            try (InputStream input = Files.newInputStream(file.toPath())) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    read += count;
                    if (read > 64L * 1024 * 1024) throw new IOException("Native library exceeds 64 MiB");
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder actual = new StringBuilder();
            for (byte value : digest.digest()) actual.append(String.format("%02x", value & 255));
            if (!MessageDigest.isEqual(actual.toString().getBytes("US-ASCII"), expectedDigest.toLowerCase(java.util.Locale.ROOT).getBytes("US-ASCII"))) {
                throw new IOException("Native library SHA-256 mismatch");
            }
        } catch (java.security.NoSuchAlgorithmException error) { throw new IOException(error); }
        return file;
    }

    private static void requireBound() throws IOException {
        if (loadedPath == null) throw new IOException("JVMTI backend is not bound. Configure it in Native JVMTI or select Instrumentation.");
    }

    private static native String status0() throws IOException;
    private static native boolean isModifiableClass0(Class<?> type) throws IOException;
    private static native void startCapture0() throws IOException;
    private static native void stopCapture0(boolean clear) throws IOException;
    private static native String captureEvents0() throws IOException;
    private static native byte[] captureBytes0(long sequence) throws IOException;
    private static native String memoryRegions0() throws IOException;
    private static native void startCaptureArchive0(byte[] directory, long limit) throws IOException;
    private static native byte[] readMemory0(long address, int length) throws IOException;
    private static native void patchMemory0(long address, byte[] expected, byte[] replacement) throws IOException;
    private static native Class<?>[] loadedClasses0() throws IOException;
    private static native byte[] classBytes0(Class<?> type) throws IOException;
    private static native void redefineClass0(Class<?> type, byte[] bytecode) throws IOException;
    private static native long[] heapStats0(Class<?> type, long limit) throws IOException;
    private static native long objectSize0(Object value) throws IOException;
}

package dev.whitedev.jpi.heap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class HprofAnalysis implements AutoCloseable {
    private static final java.util.Set<Process> WORKERS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> WORKERS.forEach(Process::destroyForcibly), "jpi-heap-worker-cleanup"));
    }
    private final Process process;
    private final ObjectInputStream input;
    private final ObjectOutputStream output;
    private final Path file;

    private HprofAnalysis(Process process, ObjectInputStream input, ObjectOutputStream output, Path file) {
        this.process = process;
        this.input = input;
        this.output = output;
        this.file = file;
    }

    public static HprofAnalysis open(Path file) throws IOException {
        return open(file, 2048);
    }

    public static HprofAnalysis open(Path file, int memoryMiB) throws IOException {
        if (memoryMiB < 128 || memoryMiB > 32768) throw new IllegalArgumentException("Analyzer memory must be 128 to 32768 MiB");
        Path absolute = file.toRealPath();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.isRegularFile(java)) java = java.resolveSibling("java");
        Process process = new ProcessBuilder(java.toString(), "-Xmx" + memoryMiB + "m", "-XX:+ExitOnOutOfMemoryError",
                "-cp", System.getProperty("java.class.path"), HprofWorker.class.getName())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        WORKERS.add(process);
        try {
            var output = new ObjectOutputStream(new BufferedOutputStream(process.getOutputStream()));
            output.flush();
            var input = new ObjectInputStream(new BufferedInputStream(process.getInputStream()));
            HprofAnalysis analysis = new HprofAnalysis(process, input, output, absolute);
            analysis.request(new HprofWorker.Request("OPEN", absolute.toString(), 0, 0, false, true));
            return analysis;
        } catch (Exception error) {
            process.destroyForcibly();
            WORKERS.remove(process);
            throw new IOException("Cannot open heap snapshot: " + error.getMessage(), error);
        }
    }

    public Path file() {
        return file;
    }

    public synchronized List<HprofSnapshot.ObjectSummary> search(String filter, int limit) throws IOException {
        return summaries(request(new HprofWorker.Request("SEARCH", filter, limit, 0, false, true)));
    }

    public synchronized List<HprofSnapshot.ObjectSummary> biggest(int limit) throws IOException {
        return summaries(request(new HprofWorker.Request("BIGGEST", "", limit, 0, true, true)));
    }

    public synchronized HprofSnapshot.ObjectReport inspect(long id, boolean retained, boolean excludeWeak) throws IOException {
        return (HprofSnapshot.ObjectReport) request(new HprofWorker.Request("INSPECT", "", 0, id, retained, excludeWeak));
    }

    private Object request(HprofWorker.Request request) throws IOException {
        try {
            output.writeObject(request);
            output.reset();
            output.flush();
            Object response = input.readObject();
            if (response instanceof HprofWorker.Failure failure) throw new IOException(failure.message());
            return response;
        } catch (ClassNotFoundException error) {
            throw new IOException("Incompatible heap analyzer response", error);
        } catch (java.io.EOFException error) {
            throw new IOException("Heap analyzer stopped. The snapshot may need a higher analyzer memory limit.", error);
        }
    }

    private static List<HprofSnapshot.ObjectSummary> summaries(Object response) throws IOException {
        if (!(response instanceof List<?> rows)) throw new IOException("Invalid heap analyzer response");
        var result = new java.util.ArrayList<HprofSnapshot.ObjectSummary>();
        for (Object row : rows) {
            if (!(row instanceof HprofSnapshot.ObjectSummary object)) throw new IOException("Invalid object summary");
            result.add(object);
        }
        return List.copyOf(result);
    }

    @Override public void close() {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try { input.close(); } catch (IOException ignored) {}
        try { output.close(); } catch (IOException ignored) {}
        WORKERS.remove(process);
    }
}

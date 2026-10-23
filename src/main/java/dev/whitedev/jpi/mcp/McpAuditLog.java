package dev.whitedev.jpi.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;

public final class McpAuditLog {
    public record Entry(String timestamp, String client, String tool, String targetPid, String sessionId,
                        long durationMillis, String status, int resultCount, String errorCode) {}
    private final Path directory;
    private final ArrayDeque<Entry> recent = new ArrayDeque<>();

    public McpAuditLog(Path directory) { this.directory = directory; }
    public Path path() { return directory.resolve("audit.jsonl"); }
    public synchronized List<Entry> recent() { return List.copyOf(recent); }

    public synchronized void write(String client, String tool, McpSessionState.Snapshot snapshot,
                                   long duration, String status, int count, String errorCode) throws IOException {
        Files.createDirectories(directory);
        Path file = path();
        if (Files.exists(file) && Files.size(file) >= 2 * 1024 * 1024) {
            for (int i = 3; i >= 1; i--) {
                Path from = i == 1 ? file : directory.resolve("audit." + (i - 1) + ".jsonl");
                if (Files.exists(from)) Files.move(from, directory.resolve("audit." + i + ".jsonl"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Entry entry = new Entry(Instant.now().toString(), McpJson.bounded(client, 200), McpJson.bounded(tool, 200),
                snapshot.session() == null ? "" : snapshot.session().target().id(), snapshot.id(), duration, status, count, errorCode);
        Files.writeString(file, McpJson.GSON.toJson(entry) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (!status.equals("STARTED")) {
            recent.addFirst(entry);
            while (recent.size() > 100) recent.removeLast();
        }
    }
}

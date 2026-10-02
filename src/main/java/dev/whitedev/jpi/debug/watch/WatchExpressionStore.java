package dev.whitedev.jpi.debug.watch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;

public final class WatchExpressionStore {
    private final Path path;

    public WatchExpressionStore(Path path) { this.path = path.toAbsolutePath(); }

    public static WatchExpressionStore defaultStore() {
        return new WatchExpressionStore(Path.of(System.getProperty("user.home"), ".jpi", "watches.txt"));
    }

    public Path path() { return path; }

    public List<String> load() throws IOException {
        if (!Files.exists(path)) return List.of();
        if (Files.size(path) > 1024 * 1024) throw new IOException("Watch list file exceeds 1 MiB");
        List<String> values = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try { values.add(new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid watch list encoding", error); }
        }
        return validate(values);
    }

    public void save(List<String> values) throws IOException {
        List<String> validated = validate(values);
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "watches-", ".tmp");
        try {
            List<String> lines = validated.stream().map(value -> Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))).toList();
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static List<String> validate(List<String> values) throws IOException {
        if (values.size() > 100) throw new IOException("Limit of 100 watch expressions reached");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String expression = value == null ? "" : value.trim();
            if (expression.isEmpty() || expression.length() > 2048 || expression.contains("\n") || expression.contains("\r")) {
                throw new IOException("Watch expression must contain 1 to 2048 characters on one line");
            }
            result.add(expression);
        }
        return List.copyOf(result);
    }
}

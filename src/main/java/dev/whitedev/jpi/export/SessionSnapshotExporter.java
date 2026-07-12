package dev.whitedev.jpi.export;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.protocol.Operation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SessionSnapshotExporter {
    private SessionSnapshotExporter() { }

    public static Path export(InspectorSession session, Path output) throws IOException {
        if (session == null) throw new IOException("Inspector session is not attached");
        Path destination = output.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);

        Map<String, String> reports = new LinkedHashMap<>();
        reports.put("manifest.txt", "Java Process Inspector session snapshot\n"
                + "created=" + Instant.now() + "\n"
                + "target.pid=" + session.target().id() + "\n"
                + "target.name=" + session.target().displayName() + "\n");
        reports.put("metrics.txt", session.requestText(Operation.METRICS, ""));
        reports.put("environment.txt", session.requestText(Operation.ENVIRONMENT, ""));
        reports.put("loaded-classes.tsv", session.requestText(Operation.CLASSES, ""));
        reports.put("class-events.tsv", session.requestText(Operation.CLASS_EVENTS, ""));
        reports.put("thread-dump.txt", session.requestText(Operation.THREAD_DUMP, ""));

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> report : reports.entrySet()) {
                zip.putNextEntry(new ZipEntry(report.getKey()));
                zip.write(report.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return destination;
    }
}

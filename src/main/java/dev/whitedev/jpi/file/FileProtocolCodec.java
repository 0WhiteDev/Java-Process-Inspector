package dev.whitedev.jpi.file;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class FileProtocolCodec {
    private FileProtocolCodec() {}

    public static Batch parseEvents(String value) {
        List<FileEvent> events = new ArrayList<>();
        String summary = "";
        long dropped = 0L;
        for (String line : value.split("\n")) {
            String[] columns = line.split("\t", -1);
            if (columns.length == 2 && "M".equals(columns[0])) summary = decoded(columns[1]);
            else if (columns.length == 3 && "S".equals(columns[0])) dropped = Long.parseLong(columns[1]);
            else if (columns.length == 17 && "E".equals(columns[0])) {
                events.add(new FileEvent(Long.parseLong(columns[1]), Long.parseLong(columns[2]),
                        FileOperation.valueOf(columns[3]), decoded(columns[4]), decoded(columns[5]),
                        decoded(columns[6]), decoded(columns[7]), decoded(columns[8]), decoded(columns[9]),
                        FileDecision.valueOf(columns[10]), Long.parseLong(columns[11]), decoded(columns[12]),
                        decoded(columns[13]), decoded(columns[14]), bytes(columns[15]), Long.parseLong(columns[16])));
            }
        }
        return new Batch(summary, dropped, List.copyOf(events));
    }

    public static List<FileRule> parseRules(String value) {
        List<FileRule> rules = new ArrayList<>();
        for (String line : value.split("\n")) {
            String[] columns = line.split("\t", -1);
            if (columns.length != 6) continue;
            String operations = columns[1];
            FileOperation operation = operations.indexOf(',') >= 0 ? FileOperation.ALL
                    : operations.isEmpty() ? FileOperation.ALL : FileOperation.valueOf(operations);
            rules.add(new FileRule(columns[0], operation, decoded(columns[2]), decoded(columns[3]),
                    FileDecision.valueOf(columns[4]), decoded(columns[5])));
        }
        return List.copyOf(rules);
    }

    public static String encodeRule(FileRule rule) {
        return rule.id() + "\t" + (rule.operation() == FileOperation.ALL ? "ALL" : rule.operation())
                + "\t" + encoded(rule.pathPattern()) + "\t" + encoded(rule.callerPattern())
                + "\t" + rule.decision() + "\t" + encoded(rule.redirectRoot());
    }

    private static String encoded(String value) {
        String safe = value == null ? "" : value;
        return Base64.getEncoder().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String value) {
        if (value.isEmpty()) return "";
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.isEmpty() ? new byte[0] : Base64.getDecoder().decode(value);
    }

    public record Batch(String summary, long dropped, List<FileEvent> events) {}
}

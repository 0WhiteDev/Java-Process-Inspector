package dev.whitedev.jpi.analysis.patch;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public record PatchEntry(long id, long timestamp, String identifier, String name, String description,
                         int beforeSize, int afterSize, String beforeHash, String afterHash) {
    public static List<PatchEntry> parse(String wire) {
        List<PatchEntry> entries = new ArrayList<>();
        for (String line : wire.split("\n")) {
            if (line.isBlank()) continue;
            String[] values = line.split("\t", -1);
            if (values.length != 9) throw new IllegalArgumentException("Invalid patch history response");
            entries.add(new PatchEntry(Long.parseLong(values[0]), Long.parseLong(values[1]),
                    decoded(values[2]), decoded(values[3]), decoded(values[4]), Integer.parseInt(values[5]),
                    Integer.parseInt(values[6]), values[7], values[8]));
        }
        return List.copyOf(entries);
    }

    private static String decoded(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }
}

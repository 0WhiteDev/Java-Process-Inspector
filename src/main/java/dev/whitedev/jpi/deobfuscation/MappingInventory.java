package dev.whitedev.jpi.deobfuscation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

public final class MappingInventory {
    private final List<MappingEntry> entries;

    private MappingInventory(List<MappingEntry> entries) {
        this.entries = entries;
    }

    public List<MappingEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public static MappingInventory parse(String value) {
        List<MappingEntry> entries = new ArrayList<>();
        for (String line : value.split("\\n")) {
            if (line.isEmpty()) continue;
            String[] columns = line.split("\\t", -1);
            try {
                if ("C".equals(columns[0]) && columns.length == 3) {
                    entries.add(new MappingEntry(MappingKind.CLASS, "", decoded(columns[1]), "",
                            -1, Integer.parseInt(columns[2])));
                } else if ("F".equals(columns[0]) && columns.length == 5) {
                    entries.add(new MappingEntry(MappingKind.FIELD, decoded(columns[1]), decoded(columns[2]),
                            decoded(columns[3]), -1, Integer.parseInt(columns[4])));
                } else if ("M".equals(columns[0]) && columns.length == 6) {
                    String owner = decoded(columns[1]);
                    String name = decoded(columns[2]);
                    String descriptor = decoded(columns[3]);
                    int access = Integer.parseInt(columns[4]);
                    int parameters = Integer.parseInt(columns[5]);
                    entries.add(new MappingEntry(MappingKind.METHOD, owner, name, descriptor, -1, access));
                    for (int index = 0; index < parameters; index++) {
                        entries.add(new MappingEntry(MappingKind.PARAMETER, owner, name, descriptor, index, access));
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return new MappingInventory(entries);
    }

    private static String decoded(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
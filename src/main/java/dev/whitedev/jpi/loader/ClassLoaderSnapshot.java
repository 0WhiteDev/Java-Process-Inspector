package dev.whitedev.jpi.loader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record ClassLoaderSnapshot(long timestamp, List<Loader> loaders, List<Definition> definitions,
                                  Map<String, List<String>> sources) {
    public static ClassLoaderSnapshot parse(String raw) {
        long timestamp = 0;
        List<Loader> loaders = new ArrayList<>();
        List<Definition> definitions = new ArrayList<>();
        Map<String, List<String>> sources = new LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            String[] values = line.split("\t", -1);
            switch (values[0]) {
                case "S" -> timestamp = Long.parseLong(values[1]);
                case "L" -> loaders.add(new Loader(decode(values[1]), decode(values[2]), decode(values[3]),
                        decode(values[4]), values[5], Long.parseLong(values[6]), Long.parseLong(values[7]),
                        Integer.parseInt(values[8]), Long.parseLong(values[9]), decode(values[10])));
                case "C" -> definitions.add(new Definition(decode(values[1]), decode(values[2]),
                        decode(values[3]), decode(values[4]), values[5]));
                case "U" -> sources.computeIfAbsent(decode(values[1]), ignored -> new ArrayList<>()).add(decode(values[2]));
                default -> { }
            }
        }
        sources.replaceAll((key, value) -> List.copyOf(value));
        return new ClassLoaderSnapshot(timestamp, List.copyOf(loaders), List.copyOf(definitions), Map.copyOf(sources));
    }

    public Map<String, List<Definition>> duplicateDefinitions() {
        Map<String, List<Definition>> grouped = new LinkedHashMap<>();
        for (Definition definition : definitions) {
            if (!"definition".equals(definition.kind)) continue;
            grouped.computeIfAbsent(definition.name, ignored -> new ArrayList<>()).add(definition);
        }
        grouped.entrySet().removeIf(entry -> entry.getValue().stream().map(Definition::loaderId).distinct().count() < 2);
        grouped.replaceAll((key, value) -> List.copyOf(value));
        return Map.copyOf(grouped);
    }

    public Map<String, Set<String>> packageCollisions() {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (Definition definition : definitions) {
            if (!"definition".equals(definition.kind)) continue;
            int separator = definition.name.lastIndexOf('.');
            String name = separator < 0 ? "<default>" : definition.name.substring(0, separator);
            grouped.computeIfAbsent(name, ignored -> new LinkedHashSet<>()).add(definition.loaderId);
        }
        grouped.entrySet().removeIf(entry -> entry.getValue().size() < 2);
        grouped.replaceAll((key, value) -> Set.copyOf(value));
        return Map.copyOf(grouped);
    }

    public String loaderLabel(String id) {
        return loaders.stream().filter(loader -> loader.id.equals(id)).map(Loader::label).findFirst().orElse(id);
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public record Loader(String id, String parentId, String type, String label, String status,
                         long firstSeen, long observedAt, int classes, long shallowSize, String notice) {
        @Override public String toString() {
            return label + " | " + classes + " classes" + ("collected".equals(status) ? " | collected" : "");
        }
    }
    public record Definition(String id, String name, String loaderId, String source, String kind) {}
}

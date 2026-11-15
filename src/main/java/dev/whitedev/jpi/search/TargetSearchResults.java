package dev.whitedev.jpi.search;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class TargetSearchResults {
    private TargetSearchResults() {}

    public static SearchReport parse(String raw) {
        List<SearchHit> hits = new ArrayList<>();
        Map<SearchCategory, SearchReport.Count> counts = new EnumMap<>(SearchCategory.class);
        List<String> notices = new ArrayList<>();
        boolean classesPartial = false, membersPartial = false, header = false;
        if (raw.length() > 8 * 1024 * 1024) throw new IllegalArgumentException("Search response exceeds 8 MiB");
        for (String row : raw.split("\n")) {
            String[] v = row.split("\t", -1);
            if (v.length == 8 && v[0].equals("S") && v[1].equals("1")) {
                header = true;
                classesPartial = Boolean.parseBoolean(v[6]);
                membersPartial = Boolean.parseBoolean(v[7]);
                notices.add("Target: " + v[2] + " loaded definitions considered, " + v[3] + " bytecode reads, "
                        + v[4] + " unavailable or unreadable. Searches use available bytecode without retransformation.");
                if (classesPartial || membersPartial) notices.add("Target search is partial: class, time, bytecode, or availability limits were reached.");
            } else if (v.length == 4 && v[0].equals("C")) {
                SearchCategory category = SearchCategory.valueOf(v[1]);
                counts.put(category, new SearchReport.Count(Math.min(200, Integer.parseInt(v[2])), Boolean.parseBoolean(v[3])));
            } else if (v.length == 8 && v[0].equals("R")) {
                if (hits.size() >= 800) throw new IllegalArgumentException("Too many search results");
                SearchCategory category = SearchCategory.valueOf(v[1]);
                String id = decode(v[2]), owner = decode(v[3]), member = decode(v[4]), descriptor = decode(v[5]);
                String loader = decode(v[6]), value = decode(v[7]);
                String title = category == SearchCategory.CLASSES ? owner : category == SearchCategory.STRINGS ? value : member + descriptor;
                String location = owner + (member.isEmpty() ? "" : "." + member + descriptor) + " | " + loader;
                hits.add(new SearchHit(category, id + '\0' + member + '\0' + descriptor + '\0' + value, title,
                        location, location + "\nDefinition: " + id + "\n\n" + value, id, owner, member, descriptor, value));
            } else if (!row.isBlank()) throw new IllegalArgumentException("Unsupported search response");
        }
        if (!header) throw new IllegalArgumentException("The agent did not return a Search Everything snapshot. Update the target agent.");
        for (SearchCategory category : List.of(SearchCategory.CLASSES, SearchCategory.METHODS, SearchCategory.FIELDS, SearchCategory.STRINGS)) {
            var count = counts.getOrDefault(category, new SearchReport.Count(0, false));
            counts.put(category, new SearchReport.Count(count.matches(), count.partial() || (category == SearchCategory.CLASSES ? classesPartial : membersPartial)));
        }
        return new SearchReport(hits, counts, notices);
    }

    private static String decode(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
}

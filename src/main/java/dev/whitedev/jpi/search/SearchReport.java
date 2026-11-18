package dev.whitedev.jpi.search;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public record SearchReport(List<SearchHit> hits, Map<SearchCategory, Count> counts, List<String> notices) {
    public record Count(int matches, boolean partial) {
    }

    public SearchReport {
        hits = List.copyOf(hits);
        counts = Map.copyOf(counts);
        notices = List.copyOf(notices);
    }

    public static SearchReport empty() {
        return new SearchReport(List.of(), Map.of(), List.of());
    }

    public SearchReport merge(SearchReport other) {
        List<SearchHit> combined = new ArrayList<>(hits);
        combined.addAll(other.hits);
        Map<SearchCategory, Count> totals = new EnumMap<>(SearchCategory.class);
        totals.putAll(counts);
        other.counts.forEach((category, count) -> totals.merge(category, count,
                (left, right) -> new Count(left.matches + right.matches, left.partial || right.partial)));
        List<String> messages = new ArrayList<>(notices);
        messages.addAll(other.notices);
        return new SearchReport(combined, totals, messages);
    }
}

package dev.whitedev.jpi.search;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SearchResults {
    static final int LIMIT = 200;
    private final List<SearchHit> hits = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private final Map<SearchCategory, SearchReport.Count> counts = new EnumMap<>(SearchCategory.class);

    void add(SearchHit hit) {
        SearchCategory category = hit.category();
        SearchReport.Count previous = counts.getOrDefault(category, new SearchReport.Count(0, false));
        if (previous.matches() >= LIMIT) {
            counts.put(category, new SearchReport.Count(previous.matches() + 1, true));
            return;
        }
        if (!keys.add(category + ":" + hit.key())) {
            return;
        }
        counts.put(category, new SearchReport.Count(previous.matches() + 1, false));
        hits.add(hit);
    }

    SearchReport report(List<String> notices) {
        return new SearchReport(hits, counts, notices);
    }
}

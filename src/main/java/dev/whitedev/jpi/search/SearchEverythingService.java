package dev.whitedev.jpi.search;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.deobfuscation.MappingEntry;
import dev.whitedev.jpi.exceptions.ExceptionSnapshot;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class SearchEverythingService {
    private SearchEverythingService() {
    }

    public static String query(String text) {
        String value = text == null ? "" : text.trim();
        if (value.length() < 2 || value.length() > 200 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Enter 2 to 200 characters without line breaks");
        return value;
    }

    public static SearchReport local(String query, List<MappingEntry> mappings, List<TimelineEvent> events) {
        SearchResults results = new SearchResults();
        for (MappingEntry entry : mappings) {
            String identity = entry.location() + " " + entry.mappedName() + " " + entry.tags() + " " + entry.color();
            boolean customized = !entry.mappedName().isEmpty() || !entry.tags().isEmpty() || !entry.color().isEmpty() || !entry.enabled();
            if (customized && matches(query, identity)) results.add(new SearchHit(SearchCategory.MAPPINGS, entry.key(),
                    entry.mappedName().isEmpty() ? entry.originalName() : entry.mappedName(), entry.location(),
                    entry.location() + "\nMapped: " + entry.mappedName() + "\nTags: " + entry.tags() + "\nEnabled: " + entry.enabled(),
                    "", entry.owner(), entry.originalName(), entry.descriptor(), entry.key()));
            if (!entry.comment().isBlank() && matches(query, identity + " " + entry.comment()))
                results.add(new SearchHit(SearchCategory.NOTES, entry.key(), excerpt(entry.comment()), entry.location(),
                        entry.location() + "\n\n" + entry.comment(), "", entry.owner(), entry.originalName(), entry.descriptor(), entry.key()));
        }
        for (TimelineEvent event : events) {
            if (!matches(query, event.summary() + " " + event.details() + " " + event.thread() + " " + event.subject() + " " + event.value()))
                continue;
            String location = Instant.ofEpochMilli(event.timestamp()) + " | " + event.source() + " | " + event.thread();
            results.add(new SearchHit(SearchCategory.TIMELINE, event.key(), excerpt(event.summary()), location,
                    location + "\n\n" + event.details(), "", "", "", "", event.key()));
            if (event.source() == TimelineSource.NETWORK) results.add(new SearchHit(SearchCategory.NETWORK, event.key(),
                    excerpt(event.summary()), location, location + "\n\n" + event.details(), "", "", "", "", event.key()));
        }
        return results.report(List.of("Mappings and notes come from the current workspace. Timeline and network results include already recorded events only."));
    }

    public static SearchReport target(InspectorSession session, String query) throws Exception {
        return TargetSearchResults.parse(session.requestText(Operation.SEARCH_EVERYTHING, query));
    }

    public static SearchReport exceptions(String query, ExceptionSnapshot snapshot) {
        SearchResults results = new SearchResults();
        if (snapshot != null) for (var entry : snapshot.entries()) {
            StringBuilder details = new StringBuilder(entry.type()).append("\nWindow events: ").append(entry.windowCount())
                    .append("\nSession total: ").append(entry.total()).append("\nThreads: ").append(entry.threads());
            for (var location : entry.locations())
                details.append("\n").append(location).append("\n").append(location.stack());
            if (matches(query, details.toString()))
                results.add(new SearchHit(SearchCategory.EXCEPTIONS, entry.type(), entry.type(),
                        entry.windowCount() + " events in " + snapshot.windowSeconds() + "s", details.toString(), "", "", "", "", entry.type()));
        }
        return results.report(snapshot == null ? List.of() : List.of("Exception Analyzer: " + snapshot.state() + ". No recording is started by search."));
    }

    public static List<SearchHit> ranked(SearchReport report, String query, SearchCategory category) {
        String needle = query.toLowerCase(Locale.ROOT);
        List<SearchHit> hits = new ArrayList<>();
        for (SearchHit hit : report.hits()) if (category == null || hit.category() == category) hits.add(hit);
        hits.sort(Comparator.comparingInt((SearchHit hit) -> rank(hit.title(), needle))
                .thenComparing(hit -> hit.category().ordinal()).thenComparing(SearchHit::location).thenComparing(SearchHit::key));
        return hits;
    }

    private static int rank(String title, String query) {
        String text = title.toLowerCase(Locale.ROOT);
        return text.equals(query) ? 0 : text.startsWith(query) ? 1 : text.contains(query) ? 2 : 3;
    }

    private static boolean matches(String query, String text) {
        String value = text.toLowerCase(Locale.ROOT);
        for (String term : query.toLowerCase(Locale.ROOT).split("\\s+")) if (!value.contains(term)) return false;
        return true;
    }

    private static String excerpt(String value) {
        String line = value.replace('\n', ' ').replace('\r', ' ');
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }
}

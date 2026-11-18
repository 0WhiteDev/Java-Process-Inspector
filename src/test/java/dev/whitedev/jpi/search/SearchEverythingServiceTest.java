package dev.whitedev.jpi.search;

import dev.whitedev.jpi.deobfuscation.MappingEntry;
import dev.whitedev.jpi.deobfuscation.MappingKind;
import dev.whitedev.jpi.exceptions.ExceptionSnapshot;
import dev.whitedev.jpi.ui.timeline.TimelineEvent;
import dev.whitedev.jpi.ui.timeline.TimelineSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchEverythingServiceTest {
    @Test
    void combinesWorkspaceAnnotationsRecordedNetworkActivityAndExistingExceptions() {
        MappingEntry mapping = new MappingEntry(MappingKind.METHOD, "a.b", "a", "()V", -1, 0);
        mapping.setMappedName("authenticate");
        mapping.setComment("Authentication request signer");
        var event = new TimelineEvent("network-1", 10, TimelineSource.NETWORK, "Worker", "", "", "Connected api.auth.test", "TCP 443");
        var exceptions = new ExceptionSnapshot(10, "stopped", 30, 0, "", List.of(new ExceptionSnapshot.Entry(
                "demo.AuthenticationException", 3, 5, 1, 10, 2, false, List.of())));
        var report = SearchEverythingService.local("AUTH", List.of(mapping), List.of(event))
                .merge(SearchEverythingService.exceptions("AUTH", exceptions));
        for (var category : List.of(SearchCategory.MAPPINGS, SearchCategory.NOTES, SearchCategory.TIMELINE, SearchCategory.NETWORK, SearchCategory.EXCEPTIONS))
            assertEquals(1, report.counts().get(category).matches(), category.name());
        assertEquals(mapping.key(), report.hits().stream().filter(hit -> hit.category() == SearchCategory.NOTES).findFirst().orElseThrow().value());
        assertEquals("network-1", report.hits().stream().filter(hit -> hit.category() == SearchCategory.NETWORK).findFirst().orElseThrow().value());
        assertTrue(SearchEverythingService.local("auth missing", List.of(mapping), List.of(event)).hits().isEmpty());
    }

    @Test
    void boundsLargeLocalCollectionsAndKeepsNavigationKeysIntact() {
        List<TimelineEvent> events = new ArrayList<>();
        for (int i = 0; i < 1000; i++)
            events.add(new TimelineEvent("event-" + i, i, TimelineSource.NETWORK, "", "", "", "auth", "details"));
        var report = SearchEverythingService.local("auth", List.of(), events);
        assertEquals(400, report.hits().size());
        assertTrue(report.counts().get(SearchCategory.NETWORK).partial());
        assertEquals(1000, report.counts().get(SearchCategory.NETWORK).matches());
        assertEquals(200, SearchEverythingService.ranked(report, "auth", SearchCategory.NETWORK).size());
        assertThrows(IllegalArgumentException.class, () -> SearchEverythingService.query("a"));
        assertThrows(IllegalArgumentException.class, () -> SearchEverythingService.query("x".repeat(201)));
        assertEquals("auth", SearchEverythingService.query("  auth  "));
    }
}
